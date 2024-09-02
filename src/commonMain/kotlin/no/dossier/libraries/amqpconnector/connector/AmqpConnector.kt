package no.dossier.libraries.amqpconnector.connector

import no.dossier.libraries.amqpconnector.error.AmqpConnectionError
import no.dossier.libraries.amqpconnector.platform.Connection
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.runCatching

sealed interface AmqpConnector {
    val amqpConnectionConfig: AmqpConnectorConfig
    fun shutdown(): Outcome<AmqpConnectionError, Unit>
    val connected: Boolean
}

sealed interface PublishingAmqpConnector: AmqpConnector {
    val publishingConnection: Connection
}

sealed interface ConsumingAmqpConnector: AmqpConnector {
    val consumingConnection: Connection
}

class PublishingAmqpConnectorImpl(
    override val amqpConnectionConfig: AmqpConnectorConfig,
    override val publishingConnection: Connection
): PublishingAmqpConnector {
    override fun shutdown(): Outcome<AmqpConnectionError, Unit> = runCatching(connectionClosingErrorProducer) {
        publishingConnection.close()
    }

    override val connected: Boolean get() = publishingConnection.isOpen()
}

class ConsumingAmqpConnectorImpl(
    override val amqpConnectionConfig: AmqpConnectorConfig,
    override val consumingConnection: Connection
): ConsumingAmqpConnector {
    override fun shutdown(): Outcome<AmqpConnectionError, Unit> = runCatching(connectionClosingErrorProducer) {
        consumingConnection.close()
    }

    override val connected: Boolean get() = consumingConnection.isOpen()
}

class PublishingConsumingAmqpConnectorImpl(
    override val amqpConnectionConfig: AmqpConnectorConfig,
    override val publishingConnection: Connection,
    override val consumingConnection: Connection
): PublishingAmqpConnector, ConsumingAmqpConnector {
    override fun shutdown(): Outcome<AmqpConnectionError, Unit> = runCatching(connectionClosingErrorProducer) {
        publishingConnection.close()
        consumingConnection.close()
    }

    override val connected: Boolean get() = publishingConnection.isOpen() && consumingConnection.isOpen()
}

internal val connectionClosingErrorProducer: (Exception) -> AmqpConnectionError =
    { e -> AmqpConnectionError("Unable to close connection: ${e.message}") }