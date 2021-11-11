package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import java.net.URI

data class AMQPConnectorConfig(
    val connectionName: String?,
    val connectionURI: URI,
    val consumers: List<AMQPConsumer<out Any, out Any>>
)

sealed interface AMQPConnector {
    val amqpConnectionConfig: AMQPConnectorConfig
}

sealed interface PublishingAMQPConnector: AMQPConnector {
    val publishingConnection: Connection
}

sealed interface ConsumingAMQPConnector: AMQPConnector {
    val consumingConnection: Connection
    val consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher
}

class PublishingAMQPConnectorImpl(
    override val amqpConnectionConfig: AMQPConnectorConfig,
    override val publishingConnection: Connection
): PublishingAMQPConnector

class ConsumingAMQPConnectorImpl(
    override val amqpConnectionConfig: AMQPConnectorConfig,
    override val consumingConnection: Connection,
    override val consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher
): ConsumingAMQPConnector

class PublishingConsumingAMQPConnectorImpl(
    override val amqpConnectionConfig: AMQPConnectorConfig,
    override val publishingConnection: Connection,
    override val consumingConnection: Connection,
    override val consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher
): PublishingAMQPConnector, ConsumingAMQPConnector