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

sealed class AmqpConnectorRole<F: AmqpConnectorFactory<out AmqpConnector>> {
    object Publisher : AmqpConnectorRole<PublishingAmqpConnectorFactory>() {
        override fun getConnectorFactory() = PublishingAmqpConnectorFactory
    }

    object Consumer : AmqpConnectorRole<ConsumingAmqpConnectorFactory>() {
        override fun getConnectorFactory() = ConsumingAmqpConnectorFactory
    }

    object PublisherAndConsumer : AmqpConnectorRole<PublishingConsumingAmqpConnectorFactory>() {
        override fun getConnectorFactory() = PublishingConsumingAmqpConnectorFactory
    }

    abstract fun getConnectorFactory(): F
}

class AmqpConnectionConfigPrototype(
    var clientName: String? = null,
    var connectionString: String = "amqp://guest:guest@localhost:5672/",
    var consumerBuilderResults: List<Result<AmqpConsumer<out Any, out Any>, AmqpConfigurationError>> = listOf()
) {
    fun build(): Result<AmqpConnectorConfig, AmqpConfigurationError> = attemptBuildResult {

        val consumers = !consumerBuilderResults.sequenceToResult()

        val uri = !getValidatedUri(connectionString).mapError { AmqpConfigurationError(it.message) }

        Success(AmqpConnectorConfig(
            clientName,
            uri,
            consumers
        ))
    }
}

fun <C: AmqpConnector, R: AmqpConnectorRole<F>, F : AmqpConnectorFactory<C>> connector(
    role: R,
    builderBlock: AmqpConnectionConfigPrototype.() -> Unit
): C = AmqpConnectionConfigPrototype()
    .apply(builderBlock).build()
    .andThen { configuration -> role.getConnectorFactory().create(configuration) }
    .getOrElse { throw RuntimeException(it.error.toString()) }

class AmqpExchangeSpecPrototype(
    var name: String = "",
    var type: AmqpExchangeType = AmqpExchangeType.TOPIC
) {
    fun build(): Result<AmqpExchangeSpec, AmqpConfigurationError> {
        return Success(AmqpExchangeSpec(
            name,
            type,
        ))
    }
}

class AmqpQueueSpecPrototype(
    var name: String = "",
    var durable: Boolean = false,
    var exclusive: Boolean = true,
    var autoDelete: Boolean = true
) {
    fun build(): Result<AmqpQueueSpec, AmqpConfigurationError> {
        return Success(AmqpQueueSpec(
            name,
            durable,
            exclusive,
            autoDelete
        ))
    }
}

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

    fun build(): Result<AmqpDeadLetterSpec, AmqpConfigurationError> = attemptBuildResult {
        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        Success(AmqpDeadLetterSpec(
            enabled,
            exchangeSpec,
            routingKey,
            implicitQueueEnabled
        ))
    }
}

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
        messageHandler: (AmqpMessage<T>) -> Result<U, AmqpConsumingError>,
        payloadSerializer: KSerializer<T>,
        replyPayloadSerializer: KSerializer<U>
    ): Result<AmqpConsumer<T, U>, AmqpConfigurationError> = attemptBuildResult {

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

inline fun <reified T: Any, reified U: Any> AmqpConnectionConfigPrototype.consumer(
    noinline messageHandler: (AmqpMessage<T>) ->  Result<U, AmqpConsumingError>,
    builderBlock: AmqpConsumerPrototype<T>.() -> Unit,
) {
    val consumer = AmqpConsumerPrototype<T>().apply(builderBlock).build(messageHandler, serializer(), serializer())
    consumerBuilderResults += consumer
}

class AmqpPublisherPrototype(
    var routingKey: String = "",
) {
    private val amqpExchangeSpecPrototype = AmqpExchangeSpecPrototype()

    fun exchange(builder: AmqpExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun build(
        connection: Connection
    ): Result<AmqpPublisher, AmqpConfigurationError> = attemptBuildResult {

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        Success(AmqpPublisher(
            exchangeSpec,
            routingKey,
            connection
        ))
    }
}

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
    ): Result<AmqpRpcClient<U>, AmqpConfigurationError> = attemptBuildResult {

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