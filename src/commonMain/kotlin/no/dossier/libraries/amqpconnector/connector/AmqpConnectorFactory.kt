package no.dossier.libraries.amqpconnector.connector

import no.dossier.libraries.amqpconnector.consumer.AmqpConsumer
import no.dossier.libraries.amqpconnector.error.AmqpConnectionError
import no.dossier.libraries.amqpconnector.error.AmqpConnectionFactoryError
import no.dossier.libraries.amqpconnector.error.AmqpError
import no.dossier.libraries.amqpconnector.platform.Connection
import no.dossier.libraries.amqpconnector.platform.ConnectionFactory
import no.dossier.libraries.functional.*

sealed class AmqpConnectorFactory<C: AmqpConnector, S: AmqpConnectorConfig> {

    abstract fun create(amqpConnectorConfig: S): Outcome<AmqpError, C>

    protected fun createNewConnection(
        connectionFactory: ConnectionFactory,
        connectionName: String,
        isConsuming: Boolean = false
    ): Outcome<AmqpConnectionError, Connection> = try {
        Success(
            if (isConsuming)
                connectionFactory.newConsumingConnection(connectionName)
            else
                connectionFactory.newConnection(connectionName)
        )
    }
    catch (e: Exception) {
        Failure(AmqpConnectionError("Cannot connect to AMQP broker: ${e.message ?: e.cause?.message}"))
    }

    protected fun createNewConsumingConnection(
        connectionFactory: ConnectionFactory,
        connectionName: String,
        consumers: List<AmqpConsumer<out Any, out Any>>
    ): Outcome<AmqpConnectionError, Connection> =
        createNewConnection(connectionFactory, connectionName, true)
            .andThen { connection ->
                consumers.forEach {
                    //TODO check for exceptions
                    it.startConsuming(connection)
                }
                Success(connection)
            }

    protected fun createConnectionFactory(
        amqpConnectionConfig: AmqpConnectorConfig,
    ): Outcome<AmqpConnectionFactoryError, ConnectionFactory> = try {
        Success(ConnectionFactory(amqpConnectionConfig.connectionURI))
    } catch (e: Exception) {
        Failure(AmqpConnectionFactoryError("Cannot configure connection factory: ${e.message}"))
    }
}

object PublishingAmqpConnectorFactory
    : AmqpConnectorFactory<PublishingAmqpConnectorImpl, GenericAmqpConnectorConfig>() {

    override fun create(
        amqpConnectorConfig: GenericAmqpConnectorConfig
    ): Outcome<AmqpError, PublishingAmqpConnectorImpl> = composeOutcome {

        Success(PublishingAmqpConnectorImpl(
            amqpConnectorConfig,
            !createNewConnection(
                !createConnectionFactory(amqpConnectorConfig),
                amqpConnectorConfig.connectionName + "-publisher"
            )
        ))
    }
}

object ConsumingAmqpConnectorFactory
    : AmqpConnectorFactory<ConsumingAmqpConnectorImpl, ConsumingAmqpConnectorConfig>() {

    override fun create(
        amqpConnectorConfig: ConsumingAmqpConnectorConfig
    ): Outcome<AmqpError, ConsumingAmqpConnectorImpl> = composeOutcome {

        Success(
            ConsumingAmqpConnectorImpl(
                amqpConnectorConfig,
                !createNewConsumingConnection(
                    !createConnectionFactory(amqpConnectorConfig),
                    amqpConnectorConfig.connectionName + "-consumer",
                    amqpConnectorConfig.consumers,
                )
            )
        )
    }
}

object PublishingConsumingAmqpConnectorFactory
    : AmqpConnectorFactory<PublishingConsumingAmqpConnectorImpl, ConsumingAmqpConnectorConfig>() {

    override fun create(
        amqpConnectorConfig: ConsumingAmqpConnectorConfig
    ): Outcome<AmqpError, PublishingConsumingAmqpConnectorImpl> = composeOutcome {
        val connectionFactory = !createConnectionFactory(amqpConnectorConfig)

        Success(
            PublishingConsumingAmqpConnectorImpl(
                amqpConnectorConfig,
                !createNewConnection(
                    connectionFactory,
                    amqpConnectorConfig.connectionName + "-publisher"
                ),
                !createNewConsumingConnection(
                    connectionFactory,
                    amqpConnectorConfig.connectionName + "-consumer",
                    amqpConnectorConfig.consumers,
                )
            )
        )
    }
}