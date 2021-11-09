package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import java.net.URI

data class AMQPConnectorConfig(
    val connectionName: String?,
    val connectionURI: URI,
    val consumers: List<AMQPConsumer<*>>
)

sealed interface PublishingAMQPConnector {
    val publishingConnection: Connection
}

sealed interface ConsumingAMQPConnector {
    val consumingConnection: Connection
}

sealed class AMQPConnector {
    abstract val amqpConnectionConfig: AMQPConnectorConfig
}

class PublishingAMQPConnectorImpl(
    override val amqpConnectionConfig: AMQPConnectorConfig,
    override val publishingConnection: Connection
): AMQPConnector(), PublishingAMQPConnector

class ConsumingAMQPConnectorImpl(
    override val amqpConnectionConfig: AMQPConnectorConfig,
    override val consumingConnection: Connection
): AMQPConnector(), ConsumingAMQPConnector

class PublishingConsumingAMQPConnectorImpl(
    override val amqpConnectionConfig: AMQPConnectorConfig,
    override val publishingConnection: Connection,
    override val consumingConnection: Connection
): AMQPConnector(), PublishingAMQPConnector, ConsumingAMQPConnector