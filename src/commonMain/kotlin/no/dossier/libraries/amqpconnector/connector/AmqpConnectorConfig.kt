package no.dossier.libraries.amqpconnector.connector

import no.dossier.libraries.amqpconnector.consumer.AmqpConsumer
import no.dossier.libraries.amqpconnector.platform.Uri

sealed interface AmqpConnectorConfig {
    val connectionName: String?
    val connectionURI: Uri
}

data class ConsumingAmqpConnectorConfig(
    override val connectionName: String?,
    override val connectionURI: Uri,
    val consumers: List<AmqpConsumer<out Any, out Any>>
): AmqpConnectorConfig

data class GenericAmqpConnectorConfig(
    override val connectionName: String?,
    override val connectionURI: Uri,
): AmqpConnectorConfig