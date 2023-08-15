package no.dossier.libraries.amqpconnector.publisher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import no.dossier.libraries.amqpconnector.dsl.replyProperties
import no.dossier.libraries.amqpconnector.error.AmqpConfigurationError
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.platform.Channel
import no.dossier.libraries.amqpconnector.platform.Connection
import no.dossier.libraries.amqpconnector.platform.OutstandingConfirms
import no.dossier.libraries.amqpconnector.primitives.AmqpExchangeSpec
import no.dossier.libraries.amqpconnector.primitives.AmqpExchangeType
import no.dossier.libraries.amqpconnector.primitives.AmqpOutboundMessage
import no.dossier.libraries.amqpconnector.primitives.AmqpRoutingKey
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success
import no.dossier.libraries.functional.andThen
import kotlin.coroutines.resume

class AmqpPublisher(
    private val exchangeSpec: AmqpExchangeSpec,
    private val routingKey: String,
    private val enableConfirmations: Boolean,
    publishingConnection: Connection,
    private val threadPoolDispatcher: CoroutineDispatcher,
    private val onMessagePublished: (message: AmqpOutboundMessage<*>, actualRoutingKey: String) -> Unit
) {
    private val logger = mu.KotlinLogging.logger { }

    private val outstandingConfirms: OutstandingConfirms = OutstandingConfirms()

    private lateinit var amqpChannel: Channel

    init {
        runBlocking {
            withContext(threadPoolDispatcher) {
                logger.debug {
                    "Creating new AMQP publisher publishing to [${exchangeSpec.name}] " +
                            "exchange using default routing key [$routingKey]"
                }

                amqpChannel = publishingConnection.createChannel()

                if (exchangeSpec.type != AmqpExchangeType.DEFAULT)
                    amqpChannel.exchangeDeclare(
                        exchangeSpec.name,
                        exchangeSpec.type.stringRepresentation,
                        exchangeSpec.durable
                    )

                if (enableConfirmations) {
                    no.dossier.libraries.functional.runCatching({
                        AmqpConfigurationError(
                            "Unable to configure channel" +
                                    " to use publisher confirms: ${it.message}"
                        )
                    }, {
                        amqpChannel.confirmSelect()
                    })

                    amqpChannel.addConfirmListener(
                        getSubmissionFeedbackHandler(true),
                        getSubmissionFeedbackHandler(false)
                    )
                }
            }
        }
    }

    fun <T: Any> invokeBlocking(
        message: AmqpOutboundMessage<T>
    ): Outcome<AmqpPublishingError, Unit> =
        if (enableConfirmations) {
            Failure(
                AmqpPublishingError(
                    "publishing messages in blocking way is not supported when confirmations are enabled",
                    amqpMessage = message
                )
            )
        }
        else {
            val actualRoutingKey = getActualRoutingKey(message.routingKey)
            val messageHeaders = message.headers.entries.joinToString()

            logger.debug {
                "← \uD83D\uDCE8 AMQP Publisher - sending message " +
                        "to [${exchangeSpec.name}] using routing key [$actualRoutingKey], headers: [$messageHeaders]"
            }

            submitMessage(message, actualRoutingKey)
        }


    suspend operator fun <T: Any> invoke(
        message: AmqpOutboundMessage<T>
    ): Outcome<AmqpPublishingError, Unit> = withContext(threadPoolDispatcher) {
        val sequenceNumber = amqpChannel.nextPublishSeqNo
        val actualRoutingKey = getActualRoutingKey(message.routingKey)

        logger.debug {
            val sequenceNumberInfo = if (enableConfirmations) "(sequence number [$sequenceNumber]) " else ""
            val messageHeaders = message.headers.entries.joinToString()

            "← \uD83D\uDCE8 AMQP Publisher - sending message $sequenceNumberInfo" +
                    "to [${exchangeSpec.name}] using routing key [$actualRoutingKey], headers: [$messageHeaders]"
        }

        if (enableConfirmations) {
            no.dossier.libraries.amqpconnector.utils.suspendCancellableCoroutineWithTimeout(
                10_000,
                {
                    outstandingConfirms.remove(sequenceNumber)
                    AmqpPublishingError(
                        message = "Unable to publish message: broker confirmation timeout",
                        amqpMessage = message
                    )
                },
                { continuation ->
                    /*
                     * The message must be submitted only once the continuation is stored to outstandingConfirms.
                     * Otherwise, if confirmation of that message arrived before the continuation is stored,
                     * we wouldn't be able to resume the execution.
                     */
                    outstandingConfirms[sequenceNumber] = continuation to message
                    val result = submitMessage(message, actualRoutingKey)

                    /* If the submission fails we want to resume right away */
                    if (result is Failure) {
                        outstandingConfirms.remove(sequenceNumber)
                        continuation.resume(result)
                    }
                }
            )
        } else {
            val result = submitMessage(message, actualRoutingKey)
            if (result is Success) {
                onMessagePublished(message, actualRoutingKey)
            }
            result
        }
    }

    private fun <T> submitMessage(
        message: AmqpOutboundMessage<T>,
        routingKey: String
    ): Outcome<AmqpPublishingError, Unit> =
        serializePayload(message)
            .andThen { serializedPayload ->
                no.dossier.libraries.functional.runCatching({
                    AmqpPublishingError(
                        message = "Unable to publish message: ${it.message}",
                        amqpMessage = message
                    )
                }, {
                    val amqpReplyProperties = replyProperties {
                        deliveryMode = message.deliveryMode
                        headers = message.headers.toMutableMap()

                        message.replyTo?.let { replyTo = it }
                        message.correlationId?.let { correlationId = it }

                    }
                    amqpChannel.basicPublish(
                        exchangeSpec.name,
                        routingKey,
                        amqpReplyProperties,
                        serializedPayload
                    )
                })
            }

    private fun <T> serializePayload(
        message: AmqpOutboundMessage<T>
    ) = no.dossier.libraries.functional.runCatching({
        AmqpPublishingError(
            message = "Unable to serialize payload: ${it.message}",
            amqpMessage = message
        )
    }, {
        message.rawPayload
    })

    private fun getSubmissionFeedbackHandler(
        positive: Boolean
    ): (sequenceNumber: Long, multiple: Boolean) -> Unit = { sequenceNumber, multiple ->

        val feedbackType = if (positive) "confirmation" else "rejection"

        logger.debug {
            "AMQP Publisher - received $feedbackType (sequence number: [$sequenceNumber], multiple: [$multiple])"
        }

        if (multiple) {
            val confirmed = outstandingConfirms.headMap(sequenceNumber, true)
            val sequenceNumbers = confirmed.keys.joinToString(",")
            if (confirmed.isNotEmpty()) {
                confirmed.values.forEach { (continuation, message) ->
                    if (positive) {
                        continuation.resume(Success(Unit))
                        onMessagePublished(message, getActualRoutingKey(message.routingKey))
                    }
                    else
                        continuation.resume(
                            Failure(
                                AmqpPublishingError(
                                    message = "Messages with sequence numbers [$sequenceNumbers] were rejected by the broker",
                                    amqpMessage = message
                                )
                            )
                        )
                }
                confirmed.clear()
            } else {
                logger.error {
                    "AMQP Publisher - received $feedbackType for unknown sequenceNumbers: [$sequenceNumbers]"
                }
            }
        } else {
            outstandingConfirms[sequenceNumber]
                ?.also { (continuation, message) ->
                    if (positive) {
                        continuation.resume(Success(Unit))
                        onMessagePublished(message, getActualRoutingKey(message.routingKey))
                    }
                    else
                        continuation.resume(
                            Failure(
                                AmqpPublishingError(
                                    message = "Message with sequence number [$sequenceNumber] was rejected by the broker",
                                    amqpMessage = message
                                )
                            )
                        )

                    outstandingConfirms.remove(sequenceNumber)
                }
                ?: logger.error {
                    "AMQP Publisher - received $feedbackType for unknown sequenceNumber: [$sequenceNumber]"
                }
        }
    }

    private fun getActualRoutingKey(amqpRoutingKey: AmqpRoutingKey) = when(amqpRoutingKey) {
        is AmqpRoutingKey.Custom -> amqpRoutingKey.key
        AmqpRoutingKey.PublisherDefault -> routingKey
    }
}