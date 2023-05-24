package no.dossier.libraries.amqpconnector.connector

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.asCoroutineDispatcher
import no.dossier.libraries.amqpconnector.consumer.AmqpConsumer
import no.dossier.libraries.amqpconnector.error.AmqpConnectionError
import no.dossier.libraries.amqpconnector.error.AmqpConnectionFactoryError
import no.dossier.libraries.amqpconnector.error.AmqpError
import no.dossier.libraries.functional.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed class AmqpConnectorFactory<C: AmqpConnector, S: AmqpConnectorConfig> {

    abstract fun create(amqpConnectorConfig: S): Outcome<AmqpError, C>

    protected fun createNewConnection(
        connectionFactory: ConnectionFactory,
        connectionName: String?,
        executorService: ExecutorService? = null
    ): Outcome<AmqpConnectionError, Connection> = try {
        Success(executorService
            ?.let { connectionFactory.newConnection(it, connectionName) }
            ?: connectionFactory.newConnection(connectionName)
        )
    }
    catch (e: Exception) {
        Failure(AmqpConnectionError("Cannot connect to AMQP broker: ${e.message}"))
    }

    protected fun createNewConsumingConnection(
        connectionFactory: ConnectionFactory,
        connectionName: String?,
        consumers: List<AmqpConsumer<out Any, out Any>>,
        executorService: ExecutorService
    ): Outcome<AmqpConnectionError, Connection> =
        createNewConnection(connectionFactory, connectionName, executorService)
            .andThen { connection ->
                consumers.forEach {
                    //TODO check for exceptions
                    it.startConsuming(connection, executorService.asCoroutineDispatcher())
                }
                Success(connection)
            }

    protected fun createConnectionFactory(
        amqpConnectionConfig: AmqpConnectorConfig,
    ): Outcome<AmqpConnectionFactoryError, ConnectionFactory> = try {
        Success(ConnectionFactory().apply {
            setUri(amqpConnectionConfig.connectionURI)
        })
    } catch (e: Exception) {
        Failure(AmqpConnectionFactoryError("Cannot configure connection factory: ${e.message}"))
    }
}

object PublishingAmqpConnectorFactory
    : AmqpConnectorFactory<PublishingAmqpConnectorImpl, GenericAmqpConnectorConfig>() {

    override fun create(
        amqpConnectorConfig: GenericAmqpConnectorConfig
    ): Outcome<AmqpError, PublishingAmqpConnectorImpl> = attemptBuildResult {

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
    ): Outcome<AmqpError, ConsumingAmqpConnectorImpl> = attemptBuildResult {
        val executorService = Executors.newFixedThreadPool(32) //This should be a parameter

        Success(ConsumingAmqpConnectorImpl(
            amqpConnectorConfig,
            !createNewConsumingConnection(
                !createConnectionFactory(amqpConnectorConfig),
                amqpConnectorConfig.connectionName + "-consumer",
                amqpConnectorConfig.consumers,
                executorService
            ),
            executorService.asCoroutineDispatcher()
        ))
    }
}

object PublishingConsumingAmqpConnectorFactory
    : AmqpConnectorFactory<PublishingConsumingAmqpConnectorImpl, ConsumingAmqpConnectorConfig>() {

    override fun create(
        amqpConnectorConfig: ConsumingAmqpConnectorConfig
    ): Outcome<AmqpError, PublishingConsumingAmqpConnectorImpl> = attemptBuildResult {
        val connectionFactory = !createConnectionFactory(amqpConnectorConfig)
        val executorService = Executors.newFixedThreadPool(32) //This should be a parameter

        Success(PublishingConsumingAmqpConnectorImpl(
            amqpConnectorConfig,
            !createNewConnection(
                connectionFactory,
                amqpConnectorConfig.connectionName + "-publisher"
            ),
            !createNewConsumingConnection(
                connectionFactory,
                amqpConnectorConfig.connectionName + "-consumer",
                amqpConnectorConfig.consumers,
                executorService
            ),
            executorService.asCoroutineDispatcher()
        ))
    }
}