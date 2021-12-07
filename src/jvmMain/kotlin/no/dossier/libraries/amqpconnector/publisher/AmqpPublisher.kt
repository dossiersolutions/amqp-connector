package no.dossier.libraries.amqpconnector.publisher

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import mu.KotlinLogging
import no.dossier.libraries.amqpconnector.error.AmqpConfigurationError
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.primitives.AmqpExchangeSpec
import no.dossier.libraries.amqpconnector.primitives.AmqpExchangeType
import no.dossier.libraries.amqpconnector.primitives.AmqpMessage
import no.dossier.libraries.functional.*
import no.dossier.libraries.stl.suspendCancellableCoroutineWithTimeout
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

typealias PublishingContinuation = CancellableContinuation<Outcome<AmqpPublishingError, Unit>>

class AmqpPublisher(
    private val exchangeSpec: AmqpExchangeSpec,
    private val routingKey: String,
    private val enableConfirmations: Boolean,
    publishingConnection: Connection
) {
    private val logger = KotlinLogging.logger { }

    private val outstandingConfirms: ConcurrentSkipListMap<Long, Pair<PublishingContinuation, AmqpMessage<*>>> =
        ConcurrentSkipListMap()

    private val amqpChannel: Channel

    private val threadPoolDispatcher: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    init {
        runBlocking {
            withContext(threadPoolDispatcher) {
                logger.debug {
                    "Creating new AMQP publisher publishing to [${exchangeSpec.name}] " +
                            "exchange using routing key [$routingKey]"
                }

                amqpChannel = publishingConnection.createChannel()

                if (exchangeSpec.type != AmqpExchangeType.DEFAULT)
                    amqpChannel.exchangeDeclare(exchangeSpec.name, exchangeSpec.type.stringRepresentation)

                if (enableConfirmations) {
                    runCatching({
                        AmqpConfigurationError("Unable to configure channel" +
                                " to use publisher confirms: ${it.message}")
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

    suspend inline operator fun <reified T: Any> invoke(
        message: AmqpMessage<T>
    ): Outcome<AmqpPublishingError, Unit> = publish(message, serializer())

    inline fun <reified T: Any> invokeBlocking(
        message: AmqpMessage<T>
    ): Outcome<AmqpPublishingError, Unit> = publishBlocking(message, serializer())

    @PublishedApi
    internal fun <T> publishBlocking(
        message: AmqpMessage<T>,
        payloadSerializer: KSerializer<T>,
    ): Outcome<AmqpPublishingError, Unit> =
        if (enableConfirmations) {
            Failure(AmqpPublishingError(
                "publishing messages in blocking way is not supported when confirmations are enabled",
                amqpMessage = message
            ))
        }
        else {
            logger.debug {
                "← \uD83D\uDCE8 AMQP Publisher - sending message " +
                        "to [${exchangeSpec.name}] using routing key [$routingKey]"
            }

            submitMessage(message, payloadSerializer)
        }


    @PublishedApi
    internal suspend fun <T> publish(
        message: AmqpMessage<T>,
        payloadSerializer: KSerializer<T>,
    ): Outcome<AmqpPublishingError, Unit> = withContext(threadPoolDispatcher) {
        val sequenceNumber = amqpChannel.nextPublishSeqNo

        logger.debug {
            val sequenceNumberInfo = if (enableConfirmations) "(sequence number [$sequenceNumber]) " else ""

            "← \uD83D\uDCE8 AMQP Publisher - sending message $sequenceNumberInfo" +
                    "to [${exchangeSpec.name}] using routing key [$routingKey]"
        }

        if (enableConfirmations) {
            suspendCancellableCoroutineWithTimeout(
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
                    val result = submitMessage(message, payloadSerializer)

                    /* If the submission fails we want to resume right away */
                    if (result is Failure) {
                        outstandingConfirms.remove(sequenceNumber)
                        continuation.resume(result)
                    }
                }
            )
        } else {
            submitMessage(message, payloadSerializer)
        }
    }

    private fun <T> submitMessage(
        message: AmqpMessage<T>,
        payloadSerializer: KSerializer<T>,
    ): Outcome<AmqpPublishingError, Unit> =
        serializePayload(message, payloadSerializer)
            .andThen { serializedPayload ->
                runCatching({
                    AmqpPublishingError(
                        message = "Unable to publish message: ${it.message}",
                        amqpMessage = message
                    )
                }, {
                    amqpChannel.basicPublish(
                        exchangeSpec.name,
                        routingKey,
                        buildProperties(message),
                        serializedPayload.toByteArray()
                    )
                })
            }

    private fun buildProperties(message: AmqpMessage<*>): AMQP.BasicProperties? {
        val amqpPropertiesBuilder = AMQP.BasicProperties().builder()
            .deliveryMode(2 /*persistent*/)
            .headers(message.headers)

        message.replyTo?.run { amqpPropertiesBuilder.replyTo(message.replyTo) }
        message.correlationId?.run { amqpPropertiesBuilder.correlationId(message.correlationId) }

        return amqpPropertiesBuilder.build()
    }

    private fun <T> serializePayload(
        message: AmqpMessage<T>,
        payloadSerializer: KSerializer<T>
    ) = runCatching({
        AmqpPublishingError(
            message = "Unable to serialize payload: ${it.message}",
            amqpMessage = message
        )
    }, {
        Json.encodeToString(payloadSerializer, message.payload)
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
                    if (positive)
                        continuation.resume(Success(Unit))
                    else
                        continuation.resume(Failure(AmqpPublishingError(
                            message = "Messages with sequence numbers [$sequenceNumbers] were rejected by the broker",
                            amqpMessage = message
                        )))
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
                    if (positive)
                        continuation.resume(Success(Unit))
                    else
                        continuation.resume(
                            Failure(AmqpPublishingError(
                                message = "Message with sequence number [$sequenceNumber] was rejected by the broker",
                                amqpMessage = message
                            ))
                        )

                    outstandingConfirms.remove(sequenceNumber)
                }
                ?: logger.error {
                    "AMQP Publisher - received $feedbackType for unknown sequenceNumber: [$sequenceNumber]"
                }
        }
    }
}