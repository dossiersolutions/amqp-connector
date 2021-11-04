package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import no.dossier.libraries.functional.*
import no.dossier.libraries.stl.getValidatedUri
import java.io.IOException
import java.lang.RuntimeException

class AMQPConnectionConfigPrototype(
    var connectionString: String = "amqp://guest:guest@localhost:5672/",
    var role: AMQPConnectorRole = AMQPConnectorRole.BOTH,
    var consumerBuilderResults: List<Result<AMQPConsumer<*>, AMQPConfigurationError>> = listOf()
) {
    fun build(): Result<AMQPConnectorConfig, AMQPConfigurationError> = attemptBuildResult {

        val consumers = !consumerBuilderResults.sequenceToResult()

        val uri = !getValidatedUri(connectionString).mapError { AMQPConfigurationError(it.message) }

        Success(AMQPConnectorConfig(
            uri,
            role,
            consumers
        ))
    }
}

fun amqpConnector(
    builderBlock: AMQPConnectionConfigPrototype.() -> Unit
): AMQPConnector = AMQPConnectionConfigPrototype()
    .apply(builderBlock).build()
    .andThen { configuration -> AMQPConnector.create(configuration) }
    .getOrElse { throw RuntimeException(it.error.toString()) }


class AMQPConsumerPrototype<T: Any>(
    var topicName: String? = null,
    var bindingKey: String = "#",
    var numberOfWorkers: Int = 2,
) {
    fun build(
        messageHandler: ((AMQPMessage<T>) -> Result<Unit, AMQPConsumingError>),
        payloadSerializer: KSerializer<T>
    ): Result<AMQPConsumer<T>, AMQPConfigurationError> {
        val topicName = topicName ?: return Failure(AMQPConfigurationError("topicName must be specified"))

        return Success(AMQPConsumer(
            topicName,
            bindingKey,
            numberOfWorkers,
            messageHandler,
            payloadSerializer
        ))
    }
}

inline fun <reified T: Any> AMQPConnectionConfigPrototype.consumer(
    noinline messageHandler: ((AMQPMessage<T>) ->  Result<Unit, AMQPConsumingError>),
    builderBlock: AMQPConsumerPrototype<T>.() -> Unit,
) {
    val consumer = AMQPConsumerPrototype<T>().apply(builderBlock).build(messageHandler, serializer())
    consumerBuilderResults += consumer
}

class AMQPPublisherPrototype(
    var topicName: String? = null,
    var routingKey: String = "",
) {
    fun build(
        connection: Connection
    ): Result<AMQPPublisher, AMQPConfigurationError> {
        val topicName = topicName ?: return Failure(AMQPConfigurationError("topicName must be specified"))

        return Success(AMQPPublisher(
            topicName,
            routingKey,
            connection
        ))
    }
}

fun AMQPConnector.publisher(builder: AMQPPublisherPrototype.() -> Unit): AMQPPublisher = try {
    val isPublishing = amqpConnectionConfig.role in (listOf(AMQPConnectorRole.BOTH, AMQPConnectorRole.PUBLISHER))
    if (isPublishing) {
        AMQPPublisherPrototype().apply(builder).build(publishingConnection!!)
    }
    else {
        Failure(AMQPConfigurationError("This AMQP Connector is not configured as publishing, " +
                "therefore it cannot register any publishers"))
    }
}
catch (e: IOException) {
    Failure(AMQPPublishingError("Unable to publish message: ${e.message}"))
}
    .getOrElse { throw RuntimeException(it.error.toString()) }