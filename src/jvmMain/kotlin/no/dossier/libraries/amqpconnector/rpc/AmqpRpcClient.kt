package no.dossier.libraries.amqpconnector.rpc

import com.rabbitmq.client.Connection
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import mu.KotlinLogging
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.error.AmqpRpcError
import no.dossier.libraries.amqpconnector.consumer.AmqpConsumer
import no.dossier.libraries.amqpconnector.consumer.AmqpReplyingMode
import no.dossier.libraries.amqpconnector.primitives.*
import no.dossier.libraries.functional.*
import no.dossier.libraries.amqpconnector.publisher.AmqpPublisher
import no.dossier.libraries.amqpconnector.utils.getValidatedUUID
import no.dossier.libraries.amqpconnector.utils.suspendCancellableCoroutineWithTimeout
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class AmqpRpcClient<U: Any>(
    private val responsePayloadSerializer: KSerializer<U>,
    @PublishedApi
    internal val messageProcessingCoroutineScope: CoroutineScope,
    private val publishingExchangeSpec: AmqpExchangeSpec,
    @PublishedApi
    internal  val replyToExchangeSpec: AmqpExchangeSpec,
    @PublishedApi
    internal  val replyQueueBindingKey: AmqpBindingKey,
    private val routingKey: String,
    private val publishingConnection: Connection,
    private val consumingConnection: Connection,
    private val consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher,
    private val onReplyConsumed: (message: AmqpInboundMessage<U>) -> Unit,
    private val onReplyRejected: (message: AmqpInboundMessage<U>) -> Unit,
    private val onRequestPublished: (message: AmqpOutboundMessage<*>, actualRoutingKey: String) -> Unit
) {
    @PublishedApi
    internal val logger = KotlinLogging.logger { }

    private val consumer: AmqpConsumer<U, Unit>

    @PublishedApi
    internal val consumerQueueName: String

    @PublishedApi
    internal val publisherThreadPoolDispatcher: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    @PublishedApi
    internal val publisher: AmqpPublisher

    @PublishedApi
    internal val pendingRequestsMap: ConcurrentMap<UUID, CancellableContinuation<Outcome<AmqpRpcError, U>>> = ConcurrentHashMap()

    private val responseMessageHandler: (message: AmqpInboundMessage<U>) -> Outcome<AmqpConsumingError, Unit> = { message ->
        logger.debug { "AMQP RPC Client - Received reply message with correlation ID: [${message.correlationId}]" }

        val correlationIdResult = message.correlationId
            ?.let(::getValidatedUUID)
            ?.mapError { AmqpConsumingError("AMQP RPC Client - Invalid correlation ID") }
            ?: Failure(AmqpConsumingError("AMQP RPC Client - Missing correlationId"))

        when(correlationIdResult) {
            is Success -> {
                val correlationId = correlationIdResult.value
                val continuation = pendingRequestsMap[correlationId]

                if(continuation == null) {
                    logger.error { "AMQP RPC Client - Received message with unknown correlation ID: [$correlationId]" }
                } else {
                    if (continuation.isCancelled) {
                        logger.warn {
                            "AMQP RPC Client - Received response with correlation ID: [$correlationId], " +
                                    "but the related request has been cancelled"
                        }
                    }
                    else {
                        continuation.resume(Success(message.payload))
                    }
                }
            }
            is Failure -> {
                logger.error { correlationIdResult.error }
            }
        }

        Success(Unit) // We need to send ACK, regardless the processing status
    }

    init {
        val queueSpec = AmqpQueueSpec(
            name = "", // Empty string means that the name will be assigned by the brokerExecutorCoroutineDispatcher
            durable = false,
            exclusive = true,
            autoDelete = true
        )

        val deadLetterSpec = AmqpDeadLetterSpec(
            enabled = false, // Since the dead letter forwarding is disabled, the below arguments are not relevant
            exchangeSpec = replyToExchangeSpec,
            routingKey = DeadLetterRoutingKey.SameAsOriginalMessage,
            implicitQueueEnabled = false
        )

        consumer = AmqpConsumer(
            replyToExchangeSpec,
            replyQueueBindingKey, // Will be used only if consumingExchangeSpec.type is DIRECT or TOPIC
            responseMessageHandler,
            responsePayloadSerializer,
            serializer(),
            queueSpec,
            deadLetterSpec,
            AmqpReplyingMode.Never,
            messageProcessingCoroutineScope,
            onReplyConsumed,
            onReplyRejected,
            onMessageReplyPublished = { _, _ ->},
            false
        )

        publisher = AmqpPublisher(
            publishingExchangeSpec,
            routingKey,
            false,
            publishingConnection,
            publisherThreadPoolDispatcher,
            onRequestPublished
        )

        consumerQueueName = consumer.startConsuming(consumingConnection, consumerThreadPoolDispatcher)
    }

    //TODO: extract internal body into a separate non-inline function and remove the @PublishedApi annotations
    suspend inline operator fun <reified T: Any> invoke(
        payload: T,
        headers: Map<String, String> = mapOf(),
        timeoutMillis: Long = 10_000,
        routingKey: AmqpRoutingKey = AmqpRoutingKey.PublisherDefault,
        replyToRoutingKey: String = consumerQueueName
    ): Outcome<AmqpRpcError, U> {
        val correlationId = UUID.randomUUID()

        val refinedHeaders =
            if (replyToExchangeSpec.name != "")
                headers + (AmqpMessageProperty.REPLY_TO_EXCHANGE.name to replyToExchangeSpec.name)
            else
                headers

        val message = AmqpOutboundMessage(
            payload,
            refinedHeaders,
            replyToRoutingKey,
            correlationId.toString(),
            routingKey
        )

        return withContext(publisherThreadPoolDispatcher) {
            suspendCancellableCoroutineWithTimeout(timeoutMillis, {
                pendingRequestsMap.remove(correlationId)
                AmqpRpcError("AMQP RPC Client - Request timed out")
            }, { continuation ->
                pendingRequestsMap[correlationId] = continuation

                when (val result = publisher.invokeBlocking(message)) {
                    is Failure -> {
                        /* If the submission fails we want to resume right away */
                        pendingRequestsMap.remove(correlationId)
                        continuation.resume(
                            Failure(
                                AmqpRpcError(
                                    "AMQP RPC Client - Failed to send RPC request",
                                    mapOf("cause" to result.error)
                                )
                            )
                        )
                    }
                    is Success -> {
                        logger.debug { "AMQP RPC Client - Request sent, correlation ID: [${correlationId}]" }
                    }
                }
            })
        }
    }

}
