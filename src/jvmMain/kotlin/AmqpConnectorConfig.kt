package no.dossier.libraries.amqpconnector.rabbitmq

import java.net.URI

sealed interface AmqpConnectorConfig {
    val connectionName: String?
    val connectionURI: URI
}

data class ConsumingAmqpConnectorConfig(
    override val connectionName: String?,
    override val connectionURI: URI,
    val consumers: List<AmqpConsumer<out Any, out Any>>
): AmqpConnectorConfig

data class GenericAmqpConnectorConfig(
    override val connectionName: String?,
    override val connectionURI: URI,
): AmqpConnectorConfig