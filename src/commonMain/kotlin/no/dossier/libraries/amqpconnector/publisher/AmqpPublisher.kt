package no.dossier.libraries.amqpconnector.publisher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import no.dossier.libraries.amqpconnector.dsl.messageProperties
import no.dossier.libraries.amqpconnector.error.AmqpConfigurationError
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.platform.Channel
import no.dossier.libraries.amqpconnector.platform.Connection
import no.dossier.libraries.amqpconnector.platform.OutstandingConfirms
import no.dossier.libraries.amqpconnector.primitives.*
import no.dossier.libraries.amqpconnector.utils.getCurrentTimeStamp
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

    private val amqpChannel: Channel = publishingConnection.createChannel()

    init {
        runBlocking {
            withContext(threadPoolDispatcher) {
                logger.debug {
                    "Creating new AMQP publisher publishing to [${exchangeSpec.name}] " +
                            "exchange using default routing key [$routingKey]"
                }

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
            val sequenceNumber = amqpChannel.nextPublishSeqNo

            val defaultHeaders: Map<String, String> = mapOf(
                AmqpMessageProperty.PUBLISHED_TIMESTAMP.name to getCurrentTimeStamp(),
                AmqpMessageProperty.SEQUENCE_NUMBER.name to sequenceNumber.toString()
            )

            logOutboundMessage(sequenceNumber, message, defaultHeaders, actualRoutingKey)

            submitMessage(message, actualRoutingKey, defaultHeaders)
        }


    suspend operator fun <T: Any> invoke(
        message: AmqpOutboundMessage<T>
    ): Outcome<AmqpPublishingError, Unit> = withContext(threadPoolDispatcher) {
        val sequenceNumber = amqpChannel.nextPublishSeqNo
        val actualRoutingKey = getActualRoutingKey(message.routingKey)
        val defaultHeaders: Map<String, String> = mapOf(
            AmqpMessageProperty.PUBLISHED_TIMESTAMP.name to getCurrentTimeStamp(),
            AmqpMessageProperty.SEQUENCE_NUMBER.name to sequenceNumber.toString()
        )

        logOutboundMessage(sequenceNumber, message, defaultHeaders, actualRoutingKey)

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
                    val result = submitMessage(message, actualRoutingKey, defaultHeaders)

                    /* If the submission fails we want to resume right away */
                    if (result is Failure) {
                        outstandingConfirms.remove(sequenceNumber)
                        continuation.resume(result)
                    }
                }
            )
        } else {
            val result = submitMessage(message, actualRoutingKey, defaultHeaders)
            if (result is Success) {
                onMessagePublished(message, actualRoutingKey)
            }
            result
        }
    }

    private fun <T : Any> logOutboundMessage(
        sequenceNumber: Long,
        message: AmqpOutboundMessage<T>,
        defaultHeaders: Map<String, String>,
        actualRoutingKey: String
    ) {
        logger.debug {
            val sequenceNumberInfo = if (enableConfirmations) "(sequence number [$sequenceNumber]) " else ""
            val messageHeaders = message.headers.entries.joinToString() + defaultHeaders.entries.joinToString()

            "← \uD83D\uDCE8 AMQP Publisher - sending message $sequenceNumberInfo" +
                    "to [${exchangeSpec.name}] using routing key [$actualRoutingKey], headers: [$messageHeaders]"
        }
    }

    private fun <T> submitMessage(
        message: AmqpOutboundMessage<T>,
        routingKey: String,
        defaultHeaders: Map<String, String>
    ): Outcome<AmqpPublishingError, Unit> =
        serializePayload(message)
            .andThen { serializedPayload ->
                no.dossier.libraries.functional.runCatching({
                    AmqpPublishingError(
                        message = "Unable to publish message: ${it.message}",
                        amqpMessage = message
                    )
                }, {
                    val amqpMessageProperties = messageProperties {
                        deliveryMode = message.deliveryMode
                        headers = (message.headers + defaultHeaders).toMutableMap()

                        message.replyTo?.let { replyTo = it }
                        message.correlationId?.let { correlationId = it }

                    }
                    amqpChannel.basicPublish(
                        exchangeSpec.name,
                        routingKey,
                        amqpMessageProperties,
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