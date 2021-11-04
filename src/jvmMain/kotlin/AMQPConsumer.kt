package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Result
import no.dossier.libraries.functional.Success
import java.io.IOException
import java.lang.Runnable
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class AMQPConsumer<T: Any>(
    private val topicName: String,
    private val bindingKey: String,
    private val numberOfWorkers: Int,
    private val messageHandler: (AMQPMessage<T>) -> Result<Unit, AMQPConsumingError>,
    private val serializer: KSerializer<T>
) {

    private val logger = KotlinLogging.logger {

    }

    fun startConsuming(connection: Connection) {
        val amqpChannel = connection.createChannel()
        amqpChannel.exchangeDeclare(topicName, "topic")
        val queueName: String = "queue"
        amqpChannel.queueDeclare(queueName, true, false, false, null)
        amqpChannel.queueBind(queueName, topicName, bindingKey)

        val workersChannel = KChannel<AMQPMessage<T>>(16)

        logger.debug { "Channel and exchange created, consumer queue bound" }

        repeat(numberOfWorkers) {
            GlobalScope.launch(Dispatchers.Default) {
                logger.debug { "Message processing worker started" }
                while(true) {
                    val message = workersChannel.receive()
                    logger.debug { "Processing message" }
                    when(messageHandler(message)) {
                        is Success -> {
                            logger.debug { "Message processing finished with Success, sending ACK" }
                            message.acknowledge()
                        }
                        is Failure -> {
                            logger.debug { "Message processing finished with Failure message (NACK)" }
                            message.reject()
                        }
                    }
                }
            }
        }

        amqpChannel.basicConsume(queueName, false, { _, payload ->
            val executor = Executor(Runnable::run)
            val dispatcher = executor.asCoroutineDispatcher()

            logger.debug { "AMQP Consumer worker started" }

            GlobalScope.launch(dispatcher) {
                var continuation: CancellableContinuation<Unit>? = null

                logger.debug { "AMQP Consumer - sending coroutine started" }

                launch(start = CoroutineStart.UNDISPATCHED) {
                    logger.debug { "AMQP Consumer - starting ack coroutine" }
                    suspendCancellableCoroutine<Unit> {
                        continuation = it
                        logger.debug { "AMQP Consumer - suspending ack coroutine" }
                    }

                    logger.debug { "AMQP Consumer - ack coroutine resumed, sending ACK" }

                    try {
                        amqpChannel.basicAck(payload.envelope.deliveryTag, false)
                    } catch (e: IOException) {
                        logger.debug { "AMQP Consumer - ack coroutine failed to send ACK" }
                    }
                }

                logger.debug { "AMQP Consumer - forwarding message to processing workers via coroutine channel" }
                workersChannel.send(
                    AMQPMessage(
                        payload.properties.headers.mapValues { it.value.toString() },
                        Json.decodeFromString(serializer, String(payload.body)),
                        { continuation!!.resume(Unit) },
                        { continuation!!.cancel() }
                    )
                )
            }

        }, { _ ->
            workersChannel.cancel()
        })
    }
}