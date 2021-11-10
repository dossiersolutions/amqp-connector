@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import no.dossier.libraries.functional.*
import no.dossier.libraries.stl.getValidatedUri
import java.lang.RuntimeException

sealed class AMQPConnectorRole<F: AMQPConnectorFactory<out AMQPConnector>> {
    object Publisher : AMQPConnectorRole<PublishingAMQPConnectorFactory>() {
        override fun getConnectorFactory() = PublishingAMQPConnectorFactory
    }

    object Consumer : AMQPConnectorRole<ConsumingAMQPConnectorFactory>() {
        override fun getConnectorFactory() = ConsumingAMQPConnectorFactory
    }

    object PublisherAndConsumer : AMQPConnectorRole<PublishingConsumingAMQPConnectorFactory>() {
        override fun getConnectorFactory() = PublishingConsumingAMQPConnectorFactory
    }

    abstract fun getConnectorFactory(): F
}

class AMQPConnectionConfigPrototype(
    var clientName: String? = null,
    var connectionString: String = "amqp://guest:guest@localhost:5672/",
    var consumerBuilderResults: List<Result<AMQPConsumer<out Any, out Any>, AMQPConfigurationError>> = listOf()
) {
    fun build(): Result<AMQPConnectorConfig, AMQPConfigurationError> = attemptBuildResult {

        val consumers = !consumerBuilderResults.sequenceToResult()

        val uri = !getValidatedUri(connectionString).mapError { AMQPConfigurationError(it.message) }

        Success(AMQPConnectorConfig(
            clientName,
            uri,
            consumers
        ))
    }
}

fun <C: AMQPConnector, R: AMQPConnectorRole<F>, F : AMQPConnectorFactory<C>> amqpConnector(
    connectorRole: R,
    builderBlock: AMQPConnectionConfigPrototype.() -> Unit
): C = AMQPConnectionConfigPrototype()
    .apply(builderBlock).build()
    .andThen { configuration -> connectorRole.getConnectorFactory().create(configuration) }
    .getOrElse { throw RuntimeException(it.error.toString()) }

class AMQPExchangeSpecPrototype(
    var name: String = "",
    var type: AMQPExchangeType = AMQPExchangeType.TOPIC
) {
    fun build(): Result<AMQPExchangeSpec, AMQPConfigurationError> {
        return Success(AMQPExchangeSpec(
            name,
            type,
        ))
    }
}

class AMQPQueueSpecPrototype(
    var name: String = "",
    var durable: Boolean = false,
    var exclusive: Boolean = true,
    var autoDelete: Boolean = true
) {
    fun build(): Result<AMQPQueueSpec, AMQPConfigurationError> {
        return Success(AMQPQueueSpec(
            name,
            durable,
            exclusive,
            autoDelete
        ))
    }
}

class AMQPDeadLetterSpecPrototype(
    var enabled: Boolean = false,
    var routingKey: DeadLetterRoutingKey = DeadLetterRoutingKey.OriginalQueueName,
    var implicitQueueEnabled: Boolean = true
) {
    private val amqpExchangeSpecPrototype = AMQPExchangeSpecPrototype().apply {
        name = "error" //overridden default value
    }

    fun exchangeSpec(builder: AMQPExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun build(): Result<AMQPDeadLetterSpec, AMQPConfigurationError> = attemptBuildResult {
        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        Success(AMQPDeadLetterSpec(
            enabled,
            exchangeSpec,
            routingKey,
            implicitQueueEnabled
        ))
    }
}

class AMQPConsumerPrototype<T: Any>(
    var bindingKey: String = "#",
    var numberOfWorkers: Int = 2,
    var workersPipeBuffer: Int = 16,
    var replyingMode: AMQPReplyingMode = AMQPReplyingMode.Never,
    var workersCoroutineScope: CoroutineScope? = null
) {
    private val amqpExchangeSpecPrototype = AMQPExchangeSpecPrototype()
    private val amqpQueueSpecPrototype = AMQPQueueSpecPrototype()
    private val amqpDeadLetterSpecPrototype = AMQPDeadLetterSpecPrototype()

    fun exchange(builder: AMQPExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun queue(builder: AMQPQueueSpecPrototype.() -> Unit) {
        amqpQueueSpecPrototype.apply(builder)
    }

    fun deadLetterForwarding(builder: AMQPDeadLetterSpecPrototype.() -> Unit) {
        amqpDeadLetterSpecPrototype.apply(builder)
    }

    fun <U: Any> build(
        messageHandler: (AMQPMessage<T>) -> Result<U, AMQPConsumingError>,
        payloadSerializer: KSerializer<T>,
        replyPayloadSerializer: KSerializer<U>
    ): Result<AMQPConsumer<T, U>, AMQPConfigurationError> = attemptBuildResult {

        val (workersCoroutineScope) = workersCoroutineScope
            ?.let { Success(it) }
            ?: Failure(AMQPConfigurationError("Consumer workersCoroutineScope must be specified"))

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()
        val (queueSpec) = amqpQueueSpecPrototype.build()
        val (deadLetterSpec) = amqpDeadLetterSpecPrototype.build()

        Success(AMQPConsumer(
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

inline fun <reified T: Any, reified U: Any> AMQPConnectionConfigPrototype.consumer(
    noinline messageHandler: (AMQPMessage<T>) ->  Result<U, AMQPConsumingError>,
    builderBlock: AMQPConsumerPrototype<T>.() -> Unit,
) {
    val consumer = AMQPConsumerPrototype<T>().apply(builderBlock).build(messageHandler, serializer(), serializer())
    consumerBuilderResults += consumer
}

class AMQPPublisherPrototype(
    var routingKey: String = "",
) {
    private val amqpExchangeSpecPrototype = AMQPExchangeSpecPrototype()

    fun exchange(builder: AMQPExchangeSpecPrototype.() -> Unit) {
        amqpExchangeSpecPrototype.apply(builder)
    }

    fun build(
        connection: Connection
    ): Result<AMQPPublisher, AMQPConfigurationError> = attemptBuildResult {

        val (exchangeSpec) = amqpExchangeSpecPrototype.build()

        Success(AMQPPublisher(
            exchangeSpec,
            routingKey,
            connection
        ))
    }
}

fun PublishingAMQPConnector.publisher(builder: AMQPPublisherPrototype.() -> Unit): AMQPPublisher =
    AMQPPublisherPrototype().apply(builder).build(publishingConnection)
        .getOrElse { throw RuntimeException(it.error.toString()) }