package no.dossier.libraries.amqpconnector.rabbitmq

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
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()

class AmqpConsumingError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()

class AmqpRpcError(
    override val message: String,
    override val causes: Map<String, AmqpError> = emptyMap()
) : AmqpError()