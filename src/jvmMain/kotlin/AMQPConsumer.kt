package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Channel
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
    private val deadLetterSpec: AMQPDeadLetterSpec,
    private val workersCoroutineScope: CoroutineScope
) {
    private val logger = KotlinLogging.logger { }

    private val deadLetterRoutingKey = when (deadLetterSpec.routingKey) {
        is DeadLetterRoutingKey.Custom -> deadLetterSpec.routingKey.routingKey
        DeadLetterRoutingKey.OriginalQueueName -> queueSpec.name
        DeadLetterRoutingKey.SameAsOriginalMessage -> null
    }

    private val createMainExchangesAndQueue: Channel.() -> Unit = {
        exchangeDeclare(topicName, "topic")
        val queueArgs = deadLetterSpec.exchangeEnabled.takeIf { it }?.let {
            val args = mapOf("x-dead-letter-exchange" to deadLetterSpec.exchangeName)
            deadLetterRoutingKey
                ?.let { args + ("x-dead-letter-routing-key" to deadLetterRoutingKey) }
                ?: args
        }
        queueDeclare(
            queueSpec.name,
            queueSpec.durable,
            queueSpec.exclusive,
            queueSpec.autoDelete,
            queueArgs
        )
        /* Main queue */
        queueBind(queueSpec.name, topicName, bindingKey)

        logger.debug { "Channel created. Exchange [$topicName] created" }
        logger.debug {
            "Consumer queue (${getQueuePropertiesString()}) [${queueSpec.name}] created, " +
                    "bound to [${topicName}]"
        }
    }

    private val createErrorExchangesAndQueue: Channel.() -> Unit = {
        if (deadLetterSpec.exchangeEnabled) {
            exchangeDeclare(deadLetterSpec.exchangeName, "topic")
            logger.debug { "Dead-letter exchange [$topicName] created" }

            if (deadLetterSpec.implicitQueueEnabled) {
                val errorQueueName = "${queueSpec.name}-error"
                queueDeclare(
                    errorQueueName,
                    queueSpec.durable,
                    queueSpec.exclusive,
                    queueSpec.autoDelete,
                    null
                )
                /* Error queue */
                val routingKey = deadLetterRoutingKey ?: "#"
                queueBind(errorQueueName, deadLetterSpec.exchangeName, routingKey)

                logger.debug {
                    "Error queue (${getQueuePropertiesString()}) [$errorQueueName] created, " +
                            "bound to [${deadLetterSpec.exchangeName}]"
                }
            }
        }
    }

    fun startConsuming(connection: Connection, consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher) {
        val amqpChannel = connection.createChannel()
        createMainExchangesAndQueue(amqpChannel)
        createErrorExchangesAndQueue(amqpChannel)

        val workersChannel = KChannel<AMQPMessage<T>>(workersPipeBuffer)
        launchProcessingWorkers(workersChannel)

        amqpChannel.basicConsume(queueSpec.name, false, { _, payload ->
            /* This is executed in the AMQP client consumer thread */
            runBlocking {
                logger.debug {
                    "-> \uD83D\uDCE8️ AMQP Consumer - forwarding message to processing workers via coroutine channel"
                }

                workersChannel.send(AMQPMessage(
                    headers = payload.properties.headers.mapValues { it.value.toString() },
                    payload = Json.decodeFromString(serializer, String(payload.body)),
                    acknowledge = getReplyCallback(consumerThreadPoolDispatcher) {
                        amqpChannel.basicAck(payload.envelope.deliveryTag, false)
                    },
                    reject = getReplyCallback(consumerThreadPoolDispatcher) {
                        amqpChannel.basicReject(payload.envelope.deliveryTag, false)
                    }
                ))
            }
        }, { _ ->
            workersChannel.cancel()
        })
    }

    private fun getQueuePropertiesString():String = queueSpec.run {
        val durable = if(durable) "durable " else ""
        val exclusive = if(exclusive) "exclusive " else ""
        val autoDelete = if(autoDelete) "autoDelete " else ""
        "$durable$exclusive$autoDelete".trimEnd()
    }

    private fun launchProcessingWorkers(
        workersChannel: KChannel<AMQPMessage<T>>
    ) = repeat(numberOfWorkers) { workerIndex ->
        /* Processing workers coroutines are executed on a custom specified coroutine scope */
        workersCoroutineScope.launch(Dispatchers.Default) {
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
                        message.reject()
                    }
                }
            }
        }
    }

    private fun getReplyCallback(
        consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher,
        replyOperation: () -> Unit
    ): suspend () -> Unit = {
        /* Acknowledge and Reject callbacks are dispatched back to the AMQP client consumer thread pool */
        withContext(consumerThreadPoolDispatcher) {
            logger.debug { "AMQP Consumer - sending $replyOperation" }

            try {
                @Suppress("BlockingMethodInNonBlockingContext")
                replyOperation()
            } catch (e: IOException) {
                logger.debug { "AMQP Consumer - failed to send $replyOperation" }
            }
        }
    }
}