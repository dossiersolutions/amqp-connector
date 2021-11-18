package no.dossier.libraries.amqpconnector.error

import no.dossier.libraries.amqpconnector.primitives.AmqpMessage
import no.dossier.libraries.errorhandling.InternalError

sealed class AmqpError: InternalError()

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
    val amqpMessage: AmqpMessage<*>
) : AmqpError()

class AmqpConsumingError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()

class AmqpRpcError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()