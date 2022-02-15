package no.dossier.libraries.amqpconnector.consumer

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import mu.KotlinLogging
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
    private val messageProcessingCoroutineScope: CoroutineScope
) {
    private val logger = KotlinLogging.logger { }

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
            exchangeDeclare(exchangeSpec.name, exchangeSpec.type.stringRepresentation)
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
                exchangeDeclare(exchangeName, deadLetterSpec.exchangeSpec.type.stringRepresentation)
                logger.debug { "Dead-letter exchange [$exchangeName] created" }
            }

            if (deadLetterSpec.implicitQueueEnabled) {
                val errorQueueName = "$actualMainQueueName-error"
                queueDeclare(
                    errorQueueName,
                    queueSpec.durable,
                    queueSpec.exclusive,
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
        val amqpChannel = connection.createChannel()
        val actualMainQueueName = createMainExchangesAndQueue(amqpChannel)
        createErrorExchangesAndQueue(amqpChannel, actualMainQueueName)

        amqpChannel.basicConsume(actualMainQueueName, false, { _, delivery ->
            /* This is executed in the AMQP client consumer thread */
            logger.debug {
                "→ \uD83D\uDCE8️ AMQP Consumer - launching message processing coroutine"
            }

            try {
                /* But the processing of the message should be dispatched to the workers thread pool */
                messageProcessingCoroutineScope.launch {
                    processMessage(AmqpInboundMessage(
                        headers = delivery.properties.headers?.mapValues { it.value.toString() } ?: emptyMap(),
                        payload = Json.decodeFromString(serializer, String(delivery.body)),
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
                        routingKey = delivery.envelope.routingKey
                    ))
                }
            } catch (e: Exception) {
                logger.error { "Unable to consume message: ${e.message}" }
            }
        }, { _ ->
            //TODO, cancelling is currently not supported
        })

        return actualMainQueueName
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
                logger.debug { "Message processing finished with Success, dispatching ACK" }
                when (replyingMode) {
                    AmqpReplyingMode.Always,
                    AmqpReplyingMode.IfRequired -> if (messageHasReplyPropertiesSet) {
                        try {
                            val payload = Json.encodeToString(replyPayloadSerializer, result.value)
                            logger.debug { "Message processing finished with Success, dispatching REPLY" }
                            val replyToExchange = message.headers[AmqpMessageProperty.REPLY_TO_EXCHANGE.name] ?: ""
                            message.reply(payload, message.replyTo!!, message.correlationId!!, replyToExchange)
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
                message.acknowledge()
            }
            is Failure -> {
                logger.debug { "Message processing finished with Failure, dispatching REJECT" }
                message.reject()
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
    ): suspend (serializedPayload: String, replyTo: String, correlationId: String, replyToExchange: String) -> Unit =
        { serializedPayload, replyTo, correlationId, replyToExchange ->
            /* Reply callbacks are dispatched back to the AMQP client consumer thread pool */
            withContext(consumerThreadPoolDispatcher) {
                logger.debug {
                    "↩️ \uD83D\uDCE8️ AMQP Consumer - sending reply to exchange: '$replyToExchange'" +
                            "with routing key: $replyTo (correlationId: $correlationId)"
                }

                try {
                    val replyProperties = AMQP.BasicProperties().builder()
                        .correlationId(correlationId)
                        .deliveryMode(2 /*persistent*/)
                        .build()

                    @Suppress("BlockingMethodInNonBlockingContext")
                    amqpChannel.basicPublish(replyToExchange, replyTo, replyProperties, serializedPayload.toByteArray())
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
                @Suppress("BlockingMethodInNonBlockingContext")
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
