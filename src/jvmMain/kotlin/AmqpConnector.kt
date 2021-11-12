package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.coroutines.ExecutorCoroutineDispatcher

sealed interface AmqpConnector {
    val amqpConnectionConfig: AmqpConnectorConfig
}

sealed interface PublishingAmqpConnector: AmqpConnector {
    val publishingConnection: Connection
}

sealed interface ConsumingAmqpConnector: AmqpConnector {
    val consumingConnection: Connection
    val consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher
}

class PublishingAmqpConnectorImpl(
    override val amqpConnectionConfig: AmqpConnectorConfig,
    override val publishingConnection: Connection
): PublishingAmqpConnector

class ConsumingAmqpConnectorImpl(
    override val amqpConnectionConfig: AmqpConnectorConfig,
    override val consumingConnection: Connection,
    override val consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher
): ConsumingAmqpConnector

class PublishingConsumingAmqpConnectorImpl(
    override val amqpConnectionConfig: AmqpConnectorConfig,
    override val publishingConnection: Connection,
    override val consumingConnection: Connection,
    override val consumerThreadPoolDispatcher: ExecutorCoroutineDispatcher
): PublishingAmqpConnector, ConsumingAmqpConnector