package no.dossier.libraries.amqpconnector.consumer

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.Delivery
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import mu.KotlinLogging
import no.dossier.libraries.amqpconnector.error.AmqpConfigurationError
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.primitives.*
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success
import java.io.IOException

class AmqpConsumer<T : Any, U : Any>(
    private val exchangeSpec: AmqpExchangeSpec,
    private val bindingKey: AmqpBindingKey,
    private val messageHandler: suspend (AmqpInboundMessage<T>) -> Outcome<AmqpConsumingError, U>,
    private val serializer: KSerializer<T>,
    private val replyPayloadSerializer: KSerializer<U>,
    private val queueSpec: AmqpQueueSpec,
    private val deadLetterSpec: AmqpDeadLetterSpec,
    private val replyingMode: AmqpReplyingMode,
    private val messageProcessingCoroutineScope: CoroutineScope,
    private val onMessageConsumed: suspend (message: AmqpInboundMessage<T>) -> Unit,
    private val onMessageRejected: suspend (message: AmqpInboundMessage<T>) -> Unit,
    private val onMessageReplyPublished: suspend (message: AmqpOutboundMessage<*>, String) -> Unit,
    private val autoAckEnabled: Boolean,
    private val prefetchCount: Int,
) {
    private val logger = KotlinLogging.logger { }

    private class AmqpConsumerState(
        var connection: Connection? = null,
        var consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher? = null,
        var tag: String? = null,
        var amqpChannel: Channel? = null,
        var actualMainQueueName: String? = null
    )

    private val state: AmqpConsumerState = AmqpConsumerState()

    private val deadLetterRoutingKey = when (deadLetterSpec.routingKey) {
        is DeadLetterRoutingKey.Custom -> deadLetterSpec.routingKey.routingKey
        DeadLetterRoutingKey.OriginalQueueName -> queueSpec.name
        DeadLetterRoutingKey.SameAsOriginalMessage -> null
    }

    private val createMainExchangesAndQueue: Channel.() -> String = {
        logger.debug { "Channel created" }

        val queueArgs = deadLetterSpec.enabled.takeIf { it }?.let {
            val args = mapOf("x-dead-letter-exchange" to deadLetterSpec.exchangeSpec.name)
            deadLetterRoutingKey
                ?.let { args + ("x-dead-letter-routing-key" to deadLetterRoutingKey) }
                ?: args
        }
        val actualQueueName = queueDeclare(
            queueSpec.name,
            queueSpec.durable,
            queueSpec.exclusive,
            queueSpec.autoDelete,
            queueArgs
        ).queue

        logger.debug {
            "Consumer queue (${getQueuePropertiesString()}) [$actualQueueName] created"
        }

        val actualBindingKey = when (bindingKey) {
            is AmqpBindingKey.QueueName -> actualQueueName
            is AmqpBindingKey.Custom -> bindingKey.key
        }

        if (exchangeSpec.type != AmqpExchangeType.DEFAULT) {
            exchangeDeclare(exchangeSpec.name, exchangeSpec.type.stringRepresentation, exchangeSpec.durable)
            logger.debug { "Exchange [${exchangeSpec.name}] created" }
            queueBind(actualQueueName, exchangeSpec.name, actualBindingKey)
            logger.debug {
                "Queue [$actualQueueName] created bound to [${exchangeSpec.name}]"
            }
        }

        actualQueueName
    }

    private val createErrorExchangesAndQueue: Channel.(actualMainQueueName: String) -> Unit = { actualMainQueueName ->
        if (deadLetterSpec.enabled) {
            val exchangeName = deadLetterSpec.exchangeSpec.name

            if (exchangeSpec.type != AmqpExchangeType.DEFAULT) {
                exchangeDeclare(
                    exchangeName,
                    deadLetterSpec.exchangeSpec.type.stringRepresentation,
                    deadLetterSpec.exchangeSpec.durable
                )
                logger.debug { "Dead-letter exchange [$exchangeName] created" }
            }

            if (deadLetterSpec.implicitQueueEnabled) {
                val errorQueueName = "$actualMainQueueName-error"
                queueDeclare(
                    errorQueueName,
                    queueSpec.durable,
                    // we don't want to make the error queues exclusive so that ops can manipulate with the messages
                    false,
                    queueSpec.autoDelete,
                    null
                )

                logger.debug {
                    "Error queue (${getQueuePropertiesString()}) [$errorQueueName] created"
                }

                if (exchangeSpec.type != AmqpExchangeType.DEFAULT) {
                    val routingKey = deadLetterRoutingKey ?: "#"
                    queueBind(errorQueueName, exchangeName, routingKey)
                    logger.debug {
                        "Error queue [$errorQueueName] bound to [$exchangeName]"
                    }
                }
            }
        }
    }

    fun startConsuming(connection: Connection, consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher): String {

        state.connection = connection
        state.consumerThreadPoolDispatcher = consumerThreadPoolDispatcher

        val amqpChannel = connection.createChannel()
        val actualMainQueueName = createMainExchangesAndQueue(amqpChannel)

        state.amqpChannel = amqpChannel
        state.actualMainQueueName = actualMainQueueName

        createErrorExchangesAndQueue(amqpChannel, actualMainQueueName)

        amqpChannel.basicQos(prefetchCount)

        state.tag = amqpChannel.basicConsume(actualMainQueueName, autoAckEnabled,
            getDeliverCallback(consumerThreadPoolDispatcher, amqpChannel)
        ) { _ -> }

        return actualMainQueueName
    }

    private fun getDeliverCallback(
        consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher,
        amqpChannel: Channel
    ): (consumerTag: String, message: Delivery) -> Unit = { _, delivery ->
        /* This is executed in the AMQP client consumer thread */
        logger.debug {
            "→ \uD83D\uDCE8️ AMQP Consumer - launching message processing coroutine"
        }

        try {
            /* But the processing of the message should be dispatched to the workers thread pool */
            messageProcessingCoroutineScope.launch {
                processMessage(AmqpInboundMessage(
                    headers = delivery.properties.headers?.mapValues { it.value.toString() } ?: emptyMap(),
                    rawPayload = delivery.body,
                    reply = getReplyCallback(consumerThreadPoolDispatcher, amqpChannel),
                    acknowledge = getAckOrRejectCallback(
                        consumerThreadPoolDispatcher,
                        amqpChannel,
                        delivery.envelope.deliveryTag,
                        true
                    ),
                    reject = getAckOrRejectCallback(
                        consumerThreadPoolDispatcher,
                        amqpChannel,
                        delivery.envelope.deliveryTag,
                        false
                    ),
                    replyTo = delivery.properties.replyTo,
                    correlationId = delivery.properties.correlationId,
                    routingKey = delivery.envelope.routingKey,
                    serializer = serializer
                ))
            }
        } catch (e: Exception) {
            logger.error { "Unable to consume message: ${e.message}" }
        }
    }

    fun pause(): Outcome<AmqpConfigurationError, Unit> = with(state) {
        if (connection != null
            && consumerThreadPoolDispatcher != null
            && tag != null
            && amqpChannel != null
            && actualMainQueueName != null
        ) {
            amqpChannel!!.basicCancel(tag)
            tag = null
            Success(Unit)
        } else {
            Failure(AmqpConfigurationError("Unable to pause consumer, it isn't running"))
        }
    }

    fun unpause(): Outcome<AmqpConfigurationError, Unit> = with(state) {
        if (connection != null
            && consumerThreadPoolDispatcher != null
            && tag == null
            && amqpChannel != null
            && actualMainQueueName != null
        ) {
            tag = amqpChannel!!.basicConsume(
                actualMainQueueName, autoAckEnabled,
                getDeliverCallback(consumerThreadPoolDispatcher!!, amqpChannel!!)
            ) { _ -> }
            Success(Unit)
        } else {
            Failure(AmqpConfigurationError("Unable to unpause consumer, it isn't paused"))
        }
    }

    private suspend fun processMessage(message: AmqpInboundMessage<T>) {
        logger.debug { "Processing message: $message" }

        val messageHasReplyPropertiesSet = message.replyTo != null && message.correlationId != null

        if (replyingMode == AmqpReplyingMode.Always && !messageHasReplyPropertiesSet) {
            logger.error {
                "Replying mode is set to Always but received message with missing " +
                        "replyTo and/or correlationId properties"
            }
            message.reject()
            return
        }

        when (val result = messageHandler(message)) {
            is Success -> {
                logger.debug { "Message processing finished with Success" }
                when (replyingMode) {
                    AmqpReplyingMode.Always,
                    AmqpReplyingMode.IfRequired -> if (messageHasReplyPropertiesSet) {
                        try {
                            logger.debug { "Sending reply message to ${message.replyTo} " +
                                    "with correlationId ${message.correlationId}" }

                            val replyToExchange = message.headers[AmqpMessageProperty.REPLY_TO_EXCHANGE.name] ?: ""
                            val reply = AmqpOutboundMessage(
                                payload = result.value,
                                headers = emptyMap(),
                                replyTo = null,
                                correlationId =  message.correlationId!!,
                                routingKey = AmqpRoutingKey.Custom(message.replyTo!!),
                                serializer = replyPayloadSerializer
                            )
                            message.reply(reply, replyToExchange)
                        } catch (e: Exception) {
                            logger.debug { "Unable to send reply message ${e.message}" }
                        }
                    }
                    AmqpReplyingMode.Never -> if (result.value !is Unit) {
                        logger.warn {
                            "Replying mode is set to Never but message handler returned non-Unit result"
                        }
                    }
                }
                if (!autoAckEnabled) {
                    logger.debug { "Sending ACK" }
                    message.acknowledge()
                }
                onMessageConsumed(message)
            }
            is Failure -> {
                if (!autoAckEnabled) {
                    logger.warn {
                        "Message processing finished with Failure, dispatching REJECT\n" +
                                result.error.toString()
                    }
                    message.reject()
                }
                else {
                    logger.warn {
                        "Message processing finished with Failure (but auto-acknowledging is enabled)\n" +
                                result.error.toString()
                    }
                }
                onMessageRejected(message)
            }
        }
    }

    private fun getQueuePropertiesString(): String = queueSpec.run {
        mutableSetOf<String>().apply {
            if (durable) add("durable")
            if (exclusive) add("exclusive")
            if (autoDelete) add("autoDelete")
        }.joinToString(" ")
    }

    private fun getReplyCallback(
        consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher,
        amqpChannel: Channel
    ): suspend (message: AmqpOutboundMessage<*>, replyToExchange: String) -> Unit =
        { message, replyToExchange ->
            /* Reply callbacks are dispatched back to the AMQP client consumer thread pool */
            withContext(consumerThreadPoolDispatcher) {
                val routingKey = (message.routingKey as AmqpRoutingKey.Custom).key

                logger.debug {
                    "↩️ \uD83D\uDCE8️ AMQP Consumer - sending reply to exchange: '$replyToExchange'" +
                            "with routing key: $routingKey (correlationId: ${message.correlationId})"
                }

                try {
                    val replyProperties = AMQP.BasicProperties().builder()
                        .correlationId(message.correlationId)
                        .deliveryMode(2 /*persistent*/)
                        .build()

                    amqpChannel.basicPublish(replyToExchange, routingKey, replyProperties, message.rawPayload)
                    onMessageReplyPublished(message, routingKey)
                } catch (e: IOException) {
                    logger.debug { "AMQP Consumer - failed to send reply" }
                }
            }
        }

    private fun getAckOrRejectCallback(
        consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher,
        amqpChannel: Channel,
        deliveryTag: Long,
        acknowledge: Boolean
    ): suspend () -> Unit = {
        /* Acknowledge and Reject callbacks are dispatched back to the AMQP client consumer thread pool */
        withContext(consumerThreadPoolDispatcher) {
            val operationName = if (acknowledge) "ACK" else "REJECT"
            logger.debug { "AMQP Consumer - sending $operationName" }

            try {
                /* We can currently only acknowledge 1 message at time because we use multiple threads.
                  From RabbitMQ doc:
                  When manual acknowledgements are used, it is important to consider what thread does the acknowledgement.
                  If it's different from the thread that received the delivery (e.g. Consumer#handleDelivery delegated delivery handling to a different thread),
                   acknowledging with the multiple parameter set to true is unsafe and will result in double-acknowledgements,
                   and therefore a channel-level protocol exception that closes the channel.
                   Acknowledging a single message at a time can be safe.
                 */
                if (acknowledge) {
                    amqpChannel.basicAck(deliveryTag, false)
                } else {
                    amqpChannel.basicReject(deliveryTag, false)
                }
            } catch (e: IOException) {
                logger.error { "AMQP Consumer - failed to send $operationName" }
            }
        }
    }
}
