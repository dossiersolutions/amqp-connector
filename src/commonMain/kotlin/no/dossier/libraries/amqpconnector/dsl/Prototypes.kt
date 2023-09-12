@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package no.dossier.libraries.amqpconnector.dsl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.KSerializer
import no.dossier.libraries.amqpconnector.connector.AmqpConnectorConfig
import no.dossier.libraries.amqpconnector.connector.ConsumingAmqpConnectorConfig
import no.dossier.libraries.amqpconnector.connector.GenericAmqpConnectorConfig
import no.dossier.libraries.amqpconnector.consumer.AmqpConsumer
import no.dossier.libraries.amqpconnector.consumer.AmqpReplyingMode
import no.dossier.libraries.amqpconnector.error.AmqpConfigurationError
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.platform.Connection
import no.dossier.libraries.amqpconnector.primitives.AmqpMessageProperties
import no.dossier.libraries.amqpconnector.primitives.*
import no.dossier.libraries.amqpconnector.publisher.AmqpPublisher
import no.dossier.libraries.amqpconnector.rpc.AmqpRpcClient
import no.dossier.libraries.amqpconnector.utils.getSingleThreadedDispatcher
import no.dossier.libraries.amqpconnector.utils.getValidatedUri
import no.dossier.libraries.functional.*

/**
 * This annotation will prevent polluting local DSL contexts with properties and functions from
 * every available implicit receiver (from the outer lambda bodies).
 *
 * See [Kotlin @DslMarker documentation](https://kotlinlang.org/docs/type-safe-builders.html#scope-control-dslmarker).
 */
@DslMarker
annotation class AmqpConnectorDsl

@AmqpConnectorDsl
sealed class AmqpConnectorConfigPrototype<C: AmqpConnectorConfig> {
    var clientName: String? = null
    var connectionString: String = "amqp://guest:guest@localhost:5672/"

    abstract fun build(): Outcome<AmqpConfigurationError, C>
}

@AmqpConnectorDsl
class GenericAmqpConnectorConfigPrototype: AmqpConnectorConfigPrototype<GenericAmqpConnectorConfig>() {
    override fun build(): Outcome<AmqpConfigurationError, GenericAmqpConnectorConfig> = composeOutcome {
        val uri = !getValidatedUri(connectionString).mapError { AmqpConfigurationError(it.message) }

        Success(
            GenericAmqpConnectorConfig(
                clientName,
                uri,
            )
        )
    }
}

@AmqpConnectorDsl
class ConsumingAmqpConnectorConfigPrototype: AmqpConnectorConfigPrototype<ConsumingAmqpConnectorConfig>() {
    val consumerBuilderOutcomes: MutableList<Outcome<AmqpConfigurationError, AmqpConsumer<out Any, out Any>>> =
        mutableListOf()

    override fun build(): Outcome<AmqpConfigurationError, ConsumingAmqpConnectorConfig> = composeOutcome {

        val consumers = !consumerBuilderOutcomes.sequenceToOutcome()

        val uri = !getValidatedUri(connectionString).mapError { AmqpConfigurationError(it.message) }

        Success(
            ConsumingAmqpConnectorConfig(
                clientName,
                uri,
                consumers
            )
        )
    }
}

@AmqpConnectorDsl
class AmqpQueueSpecPrototype(
    var name: String = "",
    var durable: Boolean = false,
    var exclusive: Boolean = true,
    var autoDelete: Boolean = true
) {
    fun build(): Outcome<AmqpConfigurationError, AmqpQueueSpec> =
        Success(
            AmqpQueueSpec(
                name,
                durable,
                exclusive,
                autoDelete
            )
        )
}

@AmqpConnectorDsl
class AmqpDeadLetterSpecPrototype(
    var enabled: Boolean = false,
    var routingKey: DeadLetterRoutingKey = DeadLetterRoutingKey.OriginalQueueName,
    var implicitQueueEnabled: Boolean = true
) {
    private val amqpExchangeSpecPrototype = AmqpExchangeSpecPrototype().apply {
        name = "error" // overridden default value
    }

    fun exchange(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun build(): Outcome<AmqpConfigurationError, AmqpDeadLetterSpec> = composeOutcome {
        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        Success(
            AmqpDeadLetterSpec(
                enabled,
                exchangeSpec,
                routingKey,
                implicitQueueEnabled
            )
        )
    }
}

@AmqpConnectorDsl
class AmqpConsumerPrototype<T: Any>(
    var bindingKey: AmqpBindingKey = AmqpBindingKey.Custom("#"),
    var replyingMode: AmqpReplyingMode = AmqpReplyingMode.Never,
    var messageProcessingCoroutineScope: CoroutineScope? = null,
    var onMessageConsumed: suspend (message: AmqpInboundMessage<T>) -> Unit = { _ -> },
    var onMessageRejected: suspend (message: AmqpInboundMessage<T>) -> Unit = { _ -> },
    var onMessageReplyPublished: suspend (message: AmqpOutboundMessage<*>, actualRoutingKey: String) -> Unit = { _, _ -> },
    var autoAckEnabled: Boolean = false,
    var prefetchCount: Int = 5000
) {
    private val amqpExchangeSpecPrototype = AmqpExchangeSpecPrototype()
    private val amqpQueueSpecPrototype = AmqpQueueSpecPrototype()
    private val amqpDeadLetterSpecPrototype = AmqpDeadLetterSpecPrototype()

    fun exchange(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun queue(builder: AmqpQueueSpecPrototype.() -> Unit) {
        amqpQueueSpecPrototype.apply(builder)
    }

    fun deadLetterForwarding(builder: AmqpDeadLetterSpecPrototype.() -> Unit) {
        amqpDeadLetterSpecPrototype.apply(builder)
    }

    fun <U: Any> build(
        messageHandler: suspend (AmqpInboundMessage<T>) -> Outcome<AmqpConsumingError, U>,
        payloadSerializer: KSerializer<T>,
        replyPayloadSerializer: KSerializer<U>
    ): Outcome<AmqpConfigurationError, AmqpConsumer<T, U>> = composeOutcome {

        val (messageProcessingCoroutineScope) = messageProcessingCoroutineScope
            ?.let { Success(it) }
            ?: Failure(AmqpConfigurationError("Consumer messageProcessingCoroutineScope must be specified"))

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()
        val (queueSpec) = amqpQueueSpecPrototype.build()
        val (deadLetterSpec) = amqpDeadLetterSpecPrototype.build()

        Success(
            AmqpConsumer(
                exchangeSpec,
                bindingKey,
                messageHandler,
                payloadSerializer,
                replyPayloadSerializer,
                queueSpec,
                deadLetterSpec,
                replyingMode,
                messageProcessingCoroutineScope,
                onMessageConsumed,
                onMessageRejected,
                onMessageReplyPublished,
                autoAckEnabled,
                prefetchCount
            )
        )
    }
}

@AmqpConnectorDsl
class AmqpExchangeSpecPrototype(
    var name: String = "",
    var type: AmqpExchangeType = AmqpExchangeType.TOPIC,
    var durable: Boolean = false
) {
    fun build(): Outcome<AmqpConfigurationError, AmqpExchangeSpec> =
        Success(
            AmqpExchangeSpec(
                name,
                type,
                durable
            )
        )
}

@AmqpConnectorDsl
class AmqpPublisherPrototype(
    var routingKey: String = "",
    var confirmations: Boolean = true,
    var onMessagePublished: (message: AmqpOutboundMessage<*>, actualRoutingKey: String) -> Unit = { _, _ -> },
    var coroutineDispatcher: CoroutineDispatcher = getSingleThreadedDispatcher()
) {
    private val amqpExchangeSpecPrototype = AmqpExchangeSpecPrototype()

    fun exchange(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun build(
        connection: Connection
    ): Outcome<AmqpConfigurationError, AmqpPublisher> = composeOutcome {

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        Success(
            AmqpPublisher(
                exchangeSpec,
                routingKey,
                confirmations,
                connection,
                coroutineDispatcher,
                onMessagePublished
            )
        )
    }
}

@AmqpConnectorDsl
class AmqpRpcClientPrototype<U: Any>(
    var routingKey: String = "",
    var messageProcessingCoroutineScope: CoroutineScope? = null,
    var replyQueueBindingKey: AmqpBindingKey = AmqpBindingKey.QueueName,
    var onReplyConsumed: (message: AmqpInboundMessage<U>) -> Unit = { _ -> },
    var onReplyRejected: (message: AmqpInboundMessage<U>) -> Unit = { _ -> },
    var onRequestPublished: (message: AmqpOutboundMessage<*>, actualRoutingKey: String) -> Unit = { _, _ -> },
    var prefetchCount: Int = 5000
) {
    private val amqpQueueSpecPrototype = AmqpQueueSpecPrototype()

    private val amqpExchangeSpecPrototype = AmqpExchangeSpecPrototype().apply {
        type = AmqpExchangeType.DEFAULT // overridden default value
    }

    private val amqpReplyToExchangeSpecPrototype = AmqpExchangeSpecPrototype().apply {
        type = AmqpExchangeType.DEFAULT
    }

    fun exchange(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun replyToExchange(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpReplyToExchangeSpecPrototype.apply(builder)
    }

    fun replyQueue(builder: AmqpQueueSpecPrototype.() -> Unit) {
        amqpQueueSpecPrototype.apply(builder)
    }

    fun build(
        consumingConnection: Connection,
        publishingConnection: Connection,
        responsePayloadSerializer: KSerializer<U>
    ): Outcome<AmqpConfigurationError, AmqpRpcClient<U>> = composeOutcome {

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()
        val (replyToExchangeSpec) = amqpReplyToExchangeSpecPrototype.build()
        val (replyQueueSpec) = amqpQueueSpecPrototype.build()

        if ((replyToExchangeSpec.name == "" && replyToExchangeSpec.type != AmqpExchangeType.DEFAULT)
            || (replyToExchangeSpec.name != "" && replyToExchangeSpec.type !in setOf(AmqpExchangeType.DIRECT, AmqpExchangeType.TOPIC))) {

            !(Failure(
                AmqpConfigurationError(
                "AMQP RPC Client - The replyToExchange type must be either DEFAULT, DIRECT, or TOPIC. " +
                        "In case it is DIRECT / TOPIC, it must have a non-empty name"
                )
            ) as Outcome<AmqpConfigurationError, *>)
        }

        val (messageProcessingCoroutineScope) = messageProcessingCoroutineScope
            ?.let { Success(it) }
            ?: Failure(AmqpConfigurationError("Consumer messageProcessingCoroutineScope must be specified"))

        Success(
            AmqpRpcClient(
                responsePayloadSerializer,
                messageProcessingCoroutineScope,
                exchangeSpec,
                replyToExchangeSpec,
                replyQueueBindingKey,
                routingKey,
                publishingConnection,
                consumingConnection,
                getSingleThreadedDispatcher(),
                onReplyConsumed,
                onReplyRejected,
                onRequestPublished,
                prefetchCount,
                replyQueueSpec
            )
        )
    }
}

@AmqpConnectorDsl
class AmqpMessagePropertiesPrototype(
    var correlationId: String? = null,
    var deliveryMode: AmqpMessageDeliveryMode = AmqpMessageDeliveryMode.TRANSIENT,
    var replyTo: String? = null,
    var headers: MutableMap<String, String> = mutableMapOf(),
) {

    fun header(name: String, value: String) {
        headers[name] = value
    }

    fun build(): AmqpMessageProperties =
        AmqpMessageProperties(
            correlationId,
            deliveryMode,
            replyTo,
            headers
        )
}
