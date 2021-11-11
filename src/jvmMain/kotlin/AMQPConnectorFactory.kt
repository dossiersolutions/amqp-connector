package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.asCoroutineDispatcher
import no.dossier.libraries.functional.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed class AMQPConnectorFactory<T> {
    abstract fun create(amqpConnectorConfig: AMQPConnectorConfig): Result<T, AMQPError>

    protected fun createNewConnection(
        connectionFactory: ConnectionFactory,
        connectionName: String?,
        executorService: ExecutorService? = null
    ): Result<Connection, AMQPConnectionError> =
        try {
            Success(executorService
                ?.let { connectionFactory.newConnection(it, connectionName) }
                ?: connectionFactory.newConnection(connectionName)
            )
        }
        catch (e: Exception) {
            Failure(AMQPConnectionError("Cannot connect to AMQP broker: ${e.message}"))
        }

    protected fun createNewConsumingConnection(
        connectionFactory: ConnectionFactory,
        connectionName: String?,
        consumers: List<AMQPConsumer<out Any, out Any>>,
        executorService: ExecutorService
    ): Result<Connection, AMQPConnectionError> {
        return createNewConnection(connectionFactory, connectionName, executorService)
            .andThen { connection ->
                consumers.forEach {
                    //TODO check for exceptions
                    it.startConsuming(connection, executorService.asCoroutineDispatcher())
                }
                Success(connection)
            }
    }

    protected fun createConnectionFactory(
        amqpConnectionConfig: AMQPConnectorConfig,
    ): Result<ConnectionFactory, AMQPConnectionFactoryError> = try {
        val factory = ConnectionFactory()
        factory.setUri(amqpConnectionConfig.connectionURI)
        factory.virtualHost = "/"
        Success(factory)
    } catch (e: Exception) {
        Failure(AMQPConnectionFactoryError("Cannot configure connection factory: ${e.message}"))
    }
}

object PublishingAMQPConnectorFactory : AMQPConnectorFactory<PublishingAMQPConnectorImpl>() {
    override fun create(
        amqpConnectorConfig: AMQPConnectorConfig
    ): Result<PublishingAMQPConnectorImpl, AMQPError> = attemptBuildResult {
        Success(PublishingAMQPConnectorImpl(
            amqpConnectorConfig,
            !createNewConnection(
                !createConnectionFactory(amqpConnectorConfig),
                amqpConnectorConfig.connectionName
            )
        ))
    }
}

object ConsumingAMQPConnectorFactory : AMQPConnectorFactory<ConsumingAMQPConnectorImpl>() {
    override fun create(
        amqpConnectorConfig: AMQPConnectorConfig
    ): Result<ConsumingAMQPConnectorImpl, AMQPError> = attemptBuildResult {
        val executorService = Executors.newFixedThreadPool(1)
        Success(ConsumingAMQPConnectorImpl(
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

object PublishingConsumingAMQPConnectorFactory : AMQPConnectorFactory<PublishingConsumingAMQPConnectorImpl>() {
    override fun create(
        amqpConnectorConfig: AMQPConnectorConfig
    ): Result<PublishingConsumingAMQPConnectorImpl, AMQPError> = attemptBuildResult {
        val connectionFactory = !createConnectionFactory(amqpConnectorConfig)
        val executorService = Executors.newFixedThreadPool(1)
        Success(PublishingConsumingAMQPConnectorImpl(
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