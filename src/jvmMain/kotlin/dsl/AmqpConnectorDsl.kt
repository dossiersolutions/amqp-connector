@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package no.dossier.libraries.amqpconnector

import com.rabbitmq.client.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import no.dossier.libraries.amqpconnector.connector.*
import no.dossier.libraries.amqpconnector.consumer.AmqpConsumer
import no.dossier.libraries.amqpconnector.consumer.AmqpReplyingMode
import no.dossier.libraries.amqpconnector.error.AmqpConfigurationError
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.primitives.*
import no.dossier.libraries.amqpconnector.publisher.AmqpPublisher
import no.dossier.libraries.amqpconnector.rpc.AmqpRpcClient
import no.dossier.libraries.functional.*
import no.dossier.libraries.stl.getValidatedUri

/**
 * Calling the [connector] DSL function is a preferred way of instantiating [AmqpConnector].
 * The [AmqpConnector] has few different implementations, providing different numbers of underlying AMQP connections
 * for publishing and consuming messages. A specific implementation is chosen based on the [role] parameter.
 *
 * The role parameter also determines which implementation of [AmqpConnectorConfigPrototype] will be used
 * as a receiver to the [builderBlock]. Each configuration prototype implementation offers different set of
 * attributes and functions which can be used for configuration of the connector.
 *
 * Note that connections are automatically established upon creation of a connector instance. If a connection is lost,
 * the connector tries to recover it automatically. However, if a connection cannot be established yet at the beginning,
 * the [connector] will throw a RuntimeException.
 *
 * @param C Specific type of [AmqpConnector]
 * @param S Specific type of [AmqpConnectorConfig]
 * @param F Specific type of [AmqpConnectorFactory]
 * @param P Specific type of [AmqpConnectorConfigPrototype]
 * @param R Specific type of [AmqpConnectorRole]
 * @param role Implies which [AmqpConnectorFactory] and [AmqpConnectorConfigPrototype] will be used,
 * and thus which [AmqpConnector] implementation will be constructed
 * @param builderBlock A contextual lambda applied to [AmqpConnectorConfigPrototype]
 * in order to configure the connector.
 * @return An instance of a specific subtype of [AmqpConnector]
 * @throws RuntimeException if the connector cannot be instantiated
 *
 * @see consumer Consumer initialization and configuration
 * @see publisher Publisher initialization and configuration
 * @see rpcClient RPC Client initialization and configuration
 * @see AmqpConnector Connector types, its lifecycle, description of underlying connections
 * @see AmqpConnectorRole Connector types and use-cases
 * @see AmqpConnectorConfigPrototype Configuration attributes and functions
 * @sample no.dossier.libraries.amqpconnector.samples.basicConsumingConnectorInitialization
 * @sample no.dossier.libraries.amqpconnector.samples.basicPublishingConnectorInitialization
 * @sample no.dossier.libraries.amqpconnector.samples.basicPublishingAndConsumingConnectorInitialization
 * @sample no.dossier.libraries.amqpconnector.samples.basicSpringIntegration
 */
fun <C, S, F: AmqpConnectorFactory<C, S>, P: AmqpConnectorConfigPrototype<S>, R: AmqpConnectorRole<P, F>> connector(
    role: R,
    builderBlock: P.() -> Unit
): C = role.connectorConfigPrototypeCtor()
    .apply(builderBlock).build()
    .andThen { configuration -> role.connectorFactory.create(configuration) }
    .getOrElse { throw RuntimeException(it.error.toString()) }

/**
 * Defines new [AmqpConsumer] within the scope of [AmqpConnector]. Consumers defined this way are
 * non-cancellable, which means that their lifecycle is the same as the lifecycle of the whole connector.
 *
 * The consumer is configured using a set of attributes and functions on [AmqpConsumerPrototype]
 * (the receiver of the [builderBlock] contextual lambda).
 * Check [AmqpConsumerPrototype] for details regarding each configuration option.
 *
 * @param T Type of payload of the messages this consumer is supposed to consume
 * @param U Type of payload of the reply messages (Usually [Unit] when replying is not configured)
 * @param messageHandler Typically reference to a function or a lambda
 * which is supposed to process the incoming messages
 * @param builderBlock A contextual lambda applied to [AmqpConsumerPrototype]
 * in order to configure the consumer.
 *
 * @see AmqpConsumer
 * @see AmqpConsumerPrototype
 * @Sample no.dossier.libraries.amqpconnector.samples.sampleConsumerWithTemporaryQueue
 * @Sample no.dossier.libraries.amqpconnector.samples.sampleConsumerWithPersistentQueue
 * @Sample no.dossier.libraries.amqpconnector.samples.consumerWithExhaustiveConfiguration
 */
inline fun <reified T: Any, reified U: Any> ConsumingAmqpConnectorConfigPrototype.consumer(
    noinline messageHandler: (AmqpMessage<T>) ->  Outcome<AmqpConsumingError, U>,
    builderBlock: AmqpConsumerPrototype<T>.() -> Unit,
) {
    val consumer = AmqpConsumerPrototype<T>()
        .apply(builderBlock).build(messageHandler, serializer(), serializer())

    consumerBuilderOutcomes += consumer
}

/**
 * Defines new [AmqpPublisher] within the scope of [AmqpConnector].
 *
 * The publisher is configured using a set of attributes and functions on [AmqpPublisherPrototype]
 * (the receiver of the [builderBlock] contextual lambda).
 * Check [AmqpPublisherPrototype] for details regarding each configuration option.
 *
 * @param builderBlock A contextual lambda applied to [AmqpPublisherPrototype]
 * in order to configure the consumer.
 *
 * @see AmqpPublisher
 * @see AmqpPublisherPrototype
 * @sample no.dossier.libraries.amqpconnector.samples.samplePublisherPublishingToSpecificQueueWithoutConfirmations
 * @sample no.dossier.libraries.amqpconnector.samples.samplePublisherPublishingToTopicExchange
 * @sample no.dossier.libraries.amqpconnector.samples.samplePublisherWithExhaustiveConfiguration
 * @sample no.dossier.libraries.amqpconnector.samples.sampleSendMethod
 * @sample no.dossier.libraries.amqpconnector.samples.sampleSendLotOfMessages
 */
fun PublishingAmqpConnector.publisher(
    builderBlock: AmqpPublisherPrototype.() -> Unit
): AmqpPublisher =
    AmqpPublisherPrototype()
        .apply(builderBlock).build(publishingConnection)
        .getOrElse { throw RuntimeException(it.error.toString()) }

/**
 * Defines new [AmqpRpcClient] within the scope of [AmqpConnector].
 *
 * The RPC client is configured using a set of attributes and functions on [AmqpRpcClientPrototype]
 * (the receiver of the [builderBlock] contextual lambda).
 * Check [AmqpRpcClientPrototype] for details regarding each configuration option.
 *
 * @param U Type of response payload (note that the request payload is bound only on invocation of the RPC client)
 * @param builderBlock A contextual lambda applied to [AmqpRpcClientPrototype]
 * in order to configure the consumer.
 *
 * @see AmqpRpcClient
 * @see AmqpRpcClientPrototype
 * @sample no.dossier.libraries.amqpconnector.samples.sampleRpcClient
 */
inline fun <reified U: Any> PublishingConsumingAmqpConnectorImpl.rpcClient(
    builderBlock: AmqpRpcClientPrototype<U>.() -> Unit
): AmqpRpcClient<U> =
    AmqpRpcClientPrototype<U>()
        .apply(builderBlock)
        .build(consumingConnection, publishingConnection, consumerThreadPoolDispatcher, serializer())
        .getOrElse { throw RuntimeException(it.error.toString()) }

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

@AmqpConnectorDsl
class AmqpQueueSpecPrototype(
    var name: String = "",
    var durable: Boolean = false,
    var exclusive: Boolean = true,
    var autoDelete: Boolean = true
) {
    fun build(): Outcome<AmqpConfigurationError, AmqpQueueSpec> = Success(
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

    fun build(): Outcome<AmqpConfigurationError, AmqpDeadLetterSpec> = attemptBuildResult {
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

        Success(
            AmqpConsumer(
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
        )
        )
    }
}

@AmqpConnectorDsl
class AmqpExchangeSpecPrototype(
    var name: String = "",
    var type: AmqpExchangeType = AmqpExchangeType.TOPIC
) {
    fun build(): Outcome<AmqpConfigurationError, AmqpExchangeSpec> = Success(
        AmqpExchangeSpec(
        name,
        type,
    )
    )
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

        Success(
            AmqpRpcClient(
            responsePayloadSerializer,
            workersCoroutineScope,
            exchangeSpec,
            routingKey,
            publishingConnection,
            consumingConnection,
            consumerThreadPoolDispatcher
        )
        )
    }
}