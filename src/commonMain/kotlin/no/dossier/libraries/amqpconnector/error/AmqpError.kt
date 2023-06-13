package no.dossier.libraries.amqpconnector.error

import no.dossier.libraries.amqpconnector.primitives.AmqpOutboundMessage

expect sealed class AmqpError() {
    abstract val message: String
    abstract val causes: Map<String, AmqpError>

    override fun toString(): String
}

class AmqpConnectionFactoryError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()

class AmqpConfigurationError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()

class AmqpConnectionError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()

class AmqpPublishingError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap(),
    val amqpMessage: AmqpOutboundMessage<*>
) : AmqpError()

class AmqpConsumingError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()

class AmqpRpcError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()