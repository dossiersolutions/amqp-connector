package no.dossier.libraries.amqpconnector.rabbitmq

import no.dossier.libraries.errorhandling.InternalError

sealed class AMQPError: InternalError()

class AMQPConnectionFactoryError(
    override val message: String,
    override val causes: Map<String, AMQPError> = emptyMap()
) : AMQPError()

class AMQPConfigurationError(
    override val message: String,
    override val causes: Map<String, AMQPError> = emptyMap()
) : AMQPError()

class AMQPConnectionError(
    override val message: String,
    override val causes: Map<String, AMQPError> = emptyMap()
) : AMQPError()

class AMQPPublishingError(
    override val message: String,
    override val causes: Map<String, AMQPError> = emptyMap()
) : AMQPError()

class AMQPConsumingError(
    override val message: String,
    override val causes: Map<String, AMQPError> = emptyMap()
) : AMQPError()

class AMQPRpcError(
    override val message: String,
    override val causes: Map<String, AMQPError> = emptyMap()
) : AMQPError()