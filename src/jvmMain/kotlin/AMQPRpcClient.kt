package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import mu.KotlinLogging
import no.dossier.libraries.functional.*
import no.dossier.libraries.stl.getValidatedUUID
import java.util.*
import kotlin.coroutines.resume

class AMQPRpcClient<U: Any>(
    private val responsePayloadSerializer: KSerializer<U>,
    @PublishedApi
    internal val workersCoroutineScope: CoroutineScope,
    private val publishingExchangeSpec: AMQPExchangeSpec,
    private val routingKey: String,
    private val publishingConnection: Connection,
    private val consumingConnection: Connection,
    private val consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher
) {
    @PublishedApi
    internal val logger = KotlinLogging.logger { }

    private val consumer: AMQPConsumer<U, Unit>

    @PublishedApi
    internal val consumerQueueName: String

    @PublishedApi
    internal val publisher: AMQPPublisher

    @PublishedApi
    internal val pendingRequestsMap = mutableMapOf<UUID, CancellableContinuation<Result<U, AMQPRpcError>>>()

    private val responseMessageHandler: (message: AMQPMessage<U>) -> Result<Unit, AMQPConsumingError> = { message ->
        logger.debug { "AMQP RPC Client - Received reply message with correlation ID: [${message.correlationId}]" }

        val correlationIdResult = message.correlationId
            ?.let(::getValidatedUUID)
            ?.mapError { AMQPConsumingError("AMQP RPC Client - Invalid correlation ID") }
            ?: Failure(AMQPConsumingError("AMQP RPC Client - Missing correlationId"))

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
        val defaultExchangeSpec = AMQPExchangeSpec(
            name = "", //unused for Default
            type = AMQPExchangeType.DEFAULT
        )

        val queueSpec = AMQPQueueSpec(
            name = "", //Empty string means that the name will be assigned by the brokerExecutorCoroutineDispatcher
            durable = false,
            exclusive = true,
            autoDelete = true
        )

        val deadLetterSpec = AMQPDeadLetterSpec(
            enabled = false, //Since the dead letter forwarding is disabled, the below arguments are not relevant
            exchangeSpec = defaultExchangeSpec,
            routingKey = DeadLetterRoutingKey.SameAsOriginalMessage,
            implicitQueueEnabled = false
        )

        consumer = AMQPConsumer(
            defaultExchangeSpec,
            "",
            1,
            responseMessageHandler,
            responsePayloadSerializer,
            serializer(),
            16,
            queueSpec,
            deadLetterSpec,
            AMQPReplyingMode.Never,
            workersCoroutineScope
        )

        publisher = AMQPPublisher(
            publishingExchangeSpec,
            routingKey,
            publishingConnection
        )

        consumerQueueName = consumer.startConsuming(consumingConnection, consumerThreadPoolDispatcher)
    }

    //TODO: extract internal body into a separate non-inline function and remove the @PublishedApi annotations
    suspend inline operator fun <reified T: Any> invoke(
        payload: T,
        headers: Map<String, String>? = null
    ): Result<U, AMQPRpcError> {
        val correlationId = UUID.randomUUID()
        return when (val result = publisher(payload, headers, consumerQueueName, correlationId.toString())) {
            is Success -> {
                withContext(workersCoroutineScope.coroutineContext) {
                    suspendCancellableCoroutine {
                        logger.debug { "AMQP RPC Client - Request sent, correlation ID: [${correlationId}]" }
                        pendingRequestsMap[correlationId] = it
                    }
                }
            }
            is Failure -> result
                .mapError { AMQPRpcError("AMQP RPC Client - Failed to send RPC request", mapOf("cause" to it)) }
        }
    }

}