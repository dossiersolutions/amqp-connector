@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import no.dossier.libraries.functional.*
import no.dossier.libraries.stl.getValidatedUri
import java.lang.RuntimeException

@DslMarker
annotation class AmqpConnectorDsl

sealed class AmqpConnectorRole<
        P: AmqpConnectorConfigPrototype<out AmqpConnectorConfig>,
        F: AmqpConnectorFactory<out AmqpConnector, out AmqpConnectorConfig>> {

    object Publisher
        : AmqpConnectorRole<GenericAmqpConnectorConfigPrototype, PublishingAmqpConnectorFactory>() {

        override val connectorFactory get() = PublishingAmqpConnectorFactory
        override val connectorConfigPrototypeCtor get() = ::GenericAmqpConnectorConfigPrototype
    }

    object Consumer
        : AmqpConnectorRole<ConsumingAmqpConnectorConfigPrototype, ConsumingAmqpConnectorFactory>() {

        override val connectorFactory get() = ConsumingAmqpConnectorFactory
        override val connectorConfigPrototypeCtor get() = ::ConsumingAmqpConnectorConfigPrototype
    }

    object PublisherAndConsumer
        : AmqpConnectorRole<ConsumingAmqpConnectorConfigPrototype, PublishingConsumingAmqpConnectorFactory>() {

        override val connectorFactory get() = PublishingConsumingAmqpConnectorFactory
        override val connectorConfigPrototypeCtor get() = ::ConsumingAmqpConnectorConfigPrototype
    }

    abstract val connectorFactory: F
    abstract val connectorConfigPrototypeCtor: () -> P
}

@AmqpConnectorDsl
sealed class AmqpConnectorConfigPrototype<C: AmqpConnectorConfig> {
    var clientName: String? = null
    var connectionString: String = "amqp://guest:guest@localhost:5672/"

    abstract fun build(): Outcome<AmqpConfigurationError, C>
}

@AmqpConnectorDsl
class GenericAmqpConnectorConfigPrototype: AmqpConnectorConfigPrototype<GenericAmqpConnectorConfig>() {
    override fun build(): Outcome<AmqpConfigurationError, GenericAmqpConnectorConfig> = attemptBuildResult {
        val uri = !getValidatedUri(connectionString).mapError { AmqpConfigurationError(it.message) }

        Success(GenericAmqpConnectorConfig(
            clientName,
            uri,
        ))
    }
}

@AmqpConnectorDsl
class ConsumingAmqpConnectorConfigPrototype: AmqpConnectorConfigPrototype<ConsumingAmqpConnectorConfig>() {
    val consumerBuilderOutcomes: MutableList<Outcome<AmqpConfigurationError, AmqpConsumer<out Any, out Any>>> =
        mutableListOf()

    override fun build(): Outcome<AmqpConfigurationError, ConsumingAmqpConnectorConfig> = attemptBuildResult {

        val consumers = !consumerBuilderOutcomes.sequenceToOutcome()

        val uri = !getValidatedUri(connectionString).mapError { AmqpConfigurationError(it.message) }

        Success(ConsumingAmqpConnectorConfig(
            clientName,
            uri,
            consumers
        ))
    }
}

fun <C, S, F: AmqpConnectorFactory<C, S>, P: AmqpConnectorConfigPrototype<S>, R: AmqpConnectorRole<P, F>> connector(
    role: R,
    builderBlock: P.() -> Unit
): C = role.connectorConfigPrototypeCtor()
    .apply(builderBlock).build()
    .andThen { configuration -> role.connectorFactory.create(configuration) }
    .getOrElse { throw RuntimeException(it.error.toString()) }

@AmqpConnectorDsl
class AmqpExchangeSpecPrototype(
    var name: String = "",
    var type: AmqpExchangeType = AmqpExchangeType.TOPIC
) {
    fun build(): Outcome<AmqpConfigurationError, AmqpExchangeSpec> = Success(AmqpExchangeSpec(
        name,
        type,
    ))
}

@AmqpConnectorDsl
class AmqpQueueSpecPrototype(
    var name: String = "",
    var durable: Boolean = false,
    var exclusive: Boolean = true,
    var autoDelete: Boolean = true
) {
    fun build(): Outcome<AmqpConfigurationError, AmqpQueueSpec> = Success(AmqpQueueSpec(
        name,
        durable,
        exclusive,
        autoDelete
    ))
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

    fun exchangeSpec(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun build(): Outcome<AmqpConfigurationError, AmqpDeadLetterSpec> = attemptBuildResult {
        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        Success(AmqpDeadLetterSpec(
            enabled,
            exchangeSpec,
            routingKey,
            implicitQueueEnabled
        ))
    }
}

@AmqpConnectorDsl
class AmqpConsumerPrototype<T: Any>(
    var bindingKey: String = "#",
    var numberOfWorkers: Int = 2,
    var workersPipeBuffer: Int = 16,
    var replyingMode: AmqpReplyingMode = AmqpReplyingMode.Never,
    var workersCoroutineScope: CoroutineScope? = null
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
        messageHandler: (AmqpMessage<T>) -> Outcome<AmqpConsumingError, U>,
        payloadSerializer: KSerializer<T>,
        replyPayloadSerializer: KSerializer<U>
    ): Outcome<AmqpConfigurationError, AmqpConsumer<T, U>> = attemptBuildResult {

        val (workersCoroutineScope) = workersCoroutineScope
            ?.let { Success(it) }
            ?: Failure(AmqpConfigurationError("Consumer workersCoroutineScope must be specified"))

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()
        val (queueSpec) = amqpQueueSpecPrototype.build()
        val (deadLetterSpec) = amqpDeadLetterSpecPrototype.build()

        Success(AmqpConsumer(
            exchangeSpec,
            bindingKey,
            numberOfWorkers,
            messageHandler,
            payloadSerializer,
            replyPayloadSerializer,
            workersPipeBuffer,
            queueSpec,
            deadLetterSpec,
            replyingMode,
            workersCoroutineScope
        ))
    }
}

inline fun <reified T: Any, reified U: Any> ConsumingAmqpConnectorConfigPrototype.consumer(
    noinline messageHandler: (AmqpMessage<T>) ->  Outcome<AmqpConsumingError, U>,
    builderBlock: AmqpConsumerPrototype<T>.() -> Unit,
) {
    val consumer = AmqpConsumerPrototype<T>().apply(builderBlock).build(messageHandler, serializer(), serializer())
    consumerBuilderOutcomes += consumer
}

@AmqpConnectorDsl
class AmqpPublisherPrototype(
    var routingKey: String = "",
    var confirmations: Boolean = true,
) {
    private val amqpExchangeSpecPrototype = AmqpExchangeSpecPrototype()

    fun exchange(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun build(
        connection: Connection
    ): Outcome<AmqpConfigurationError, AmqpPublisher> = attemptBuildResult {

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        Success(AmqpPublisher(
            exchangeSpec,
            routingKey,
            confirmations,
            connection,
        ))
    }
}

@AmqpConnectorDsl
class AmqpRpcClientPrototype<U: Any>(
    var routingKey: String = "",
    var workersCoroutineScope: CoroutineScope? = null
) {
    private val amqpExchangeSpecPrototype = AmqpExchangeSpecPrototype().apply {
        type = AmqpExchangeType.DEFAULT // overridden default value
    }

    fun exchange(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun build(
        consumingConnection: Connection,
        publishingConnection: Connection,
        consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher,
        responsePayloadSerializer: KSerializer<U>
    ): Outcome<AmqpConfigurationError, AmqpRpcClient<U>> = attemptBuildResult {

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        val (workersCoroutineScope) = workersCoroutineScope
            ?.let { Success(it) }
            ?: Failure(AmqpConfigurationError("Consumer workersCoroutineScope must be specified"))

        Success(AmqpRpcClient(
            responsePayloadSerializer,
            workersCoroutineScope,
            exchangeSpec,
            routingKey,
            publishingConnection,
            consumingConnection,
            consumerThreadPoolDispatcher
        ))
    }
}

fun PublishingAmqpConnector.publisher(builder: AmqpPublisherPrototype.() -> Unit): AmqpPublisher =
    AmqpPublisherPrototype().apply(builder).build(publishingConnection)
        .getOrElse { throw RuntimeException(it.error.toString()) }

inline fun <reified U: Any> PublishingConsumingAmqpConnectorImpl.rpcClient(
    builder: AmqpRpcClientPrototype<U>.() -> Unit
): AmqpRpcClient<U> =
    AmqpRpcClientPrototype<U>()
        .apply(builder)
        .apply {

        }
        .build(consumingConnection, publishingConnection, consumerThreadPoolDispatcher, serializer())
        .getOrElse { throw RuntimeException(it.error.toString()) }