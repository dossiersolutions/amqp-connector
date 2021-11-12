package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.asCoroutineDispatcher
import no.dossier.libraries.functional.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed class AmqpConnectorFactory<C: AmqpConnector, S: AmqpConnectorConfig> {

    abstract fun create(amqpConnectorConfig: S): Result<C, AmqpError>

    protected fun createNewConnection(
        connectionFactory: ConnectionFactory,
        connectionName: String?,
        executorService: ExecutorService? = null
    ): Result<Connection, AmqpConnectionError> = try {
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
    ): Result<Connection, AmqpConnectionError> =
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
    ): Result<ConnectionFactory, AmqpConnectionFactoryError> = try {
        Success(ConnectionFactory().apply {
            setUri(amqpConnectionConfig.connectionURI)
            virtualHost = "/"
        })
    } catch (e: Exception) {
        Failure(AmqpConnectionFactoryError("Cannot configure connection factory: ${e.message}"))
    }
}

object PublishingAmqpConnectorFactory
    : AmqpConnectorFactory<PublishingAmqpConnectorImpl, GenericAmqpConnectorConfig>() {

    override fun create(
        amqpConnectorConfig: GenericAmqpConnectorConfig
    ): Result<PublishingAmqpConnectorImpl, AmqpError> = attemptBuildResult {

        Success(PublishingAmqpConnectorImpl(
            amqpConnectorConfig,
            !createNewConnection(
                !createConnectionFactory(amqpConnectorConfig),
                amqpConnectorConfig.connectionName
            )
        ))
    }
}

object ConsumingAmqpConnectorFactory
    : AmqpConnectorFactory<ConsumingAmqpConnectorImpl, ConsumingAmqpConnectorConfig>() {

    override fun create(
        amqpConnectorConfig: ConsumingAmqpConnectorConfig
    ): Result<ConsumingAmqpConnectorImpl, AmqpError> = attemptBuildResult {
        val executorService = Executors.newFixedThreadPool(1)

        Success(ConsumingAmqpConnectorImpl(
            amqpConnectorConfig,
            !createNewConsumingConnection(
                !createConnectionFactory(amqpConnectorConfig),
                amqpConnectorConfig.connectionName,
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
    ): Result<PublishingConsumingAmqpConnectorImpl, AmqpError> = attemptBuildResult {
        val connectionFactory = !createConnectionFactory(amqpConnectorConfig)
        val executorService = Executors.newFixedThreadPool(1)

        Success(PublishingConsumingAmqpConnectorImpl(
            amqpConnectorConfig,
            !createNewConnection(
                connectionFactory,
                amqpConnectorConfig.connectionName
            ),
            !createNewConsumingConnection(
                connectionFactory,
                amqpConnectorConfig.connectionName,
                amqpConnectorConfig.consumers,
                executorService
            ),
            executorService.asCoroutineDispatcher()
        ))
    }
}