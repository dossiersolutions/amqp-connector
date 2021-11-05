package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Result
import no.dossier.libraries.functional.Success
import java.io.IOException
import kotlinx.coroutines.channels.Channel as KChannel

class AMQPConsumer<T: Any>(
    private val topicName: String,
    private val bindingKey: String,
    private val numberOfWorkers: Int,
    private val messageHandler: (AMQPMessage<T>) -> Result<Unit, AMQPConsumingError>,
    private val serializer: KSerializer<T>,
    private val workersPipeBuffer: Int = 16,
    private val queueSpec: AMQPQueueSpec,
) {
    private val logger = KotlinLogging.logger { }

    fun startConsuming(connection: Connection, consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher) {

        val amqpChannel = connection.createChannel().apply {
            exchangeDeclare(topicName, "topic")
            queueDeclare(
                queueSpec.name,
                queueSpec.durable,
                queueSpec.exclusive,
                queueSpec.autoDelete,
                null
            )
            queueBind(queueSpec.name, topicName, bindingKey)
        }

        val workersChannel = KChannel<AMQPMessage<T>>(workersPipeBuffer)

        logger.debug {
            "Channel and exchange created, consumer queue [${queueSpec.name}] bound to [$topicName] exchange"
        }

        runBlocking {

            repeat(numberOfWorkers) { workerIndex ->
                launch(Dispatchers.Default) {
                    logger.debug { "Message processing worker [$workerIndex] started for [${queueSpec.name}]" }
                    while (true) {
                        val message = workersChannel.receive()
                        logger.debug { "Processing message" }
                        when (messageHandler(message)) {
                            is Success -> {
                                logger.debug { "Message processing finished with Success, dispatching ACK" }
                                message.acknowledge()
                            }
                            is Failure -> {
                                logger.debug { "Message processing finished with Failure message" }
                            }
                        }
                    }
                }
            }

            amqpChannel.basicConsume(queueSpec.name, false, { _, payload ->
                runBlocking {
                    logger.debug {
                        "-> \uD83D\uDCE8️ AMQP Consumer - forwarding message to processing workers via coroutine channel"
                    }
                    workersChannel.send(
                        AMQPMessage(
                            payload.properties.headers.mapValues { it.value.toString() },
                            Json.decodeFromString(serializer, String(payload.body))
                        ) {
                            withContext(consumerThreadPoolDispatcher) {
                                logger.debug { "AMQP Consumer - sending ACK" }

                                try {
                                    @Suppress("BlockingMethodInNonBlockingContext")
                                    amqpChannel.basicAck(payload.envelope.deliveryTag, false)
                                } catch (e: IOException) {
                                    logger.debug { "AMQP Consumer - failed to send ACK" }
                                }
                            }
                        }
                    )
                }

            }, { _ ->
                workersChannel.cancel()
            })

        }
    }
}