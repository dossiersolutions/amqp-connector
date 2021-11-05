package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.asCoroutineDispatcher
import no.dossier.libraries.functional.*
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class AMQPConnectorRole {
    PUBLISHER,
    CONSUMER,
    BOTH
}

data class AMQPConnectorConfig(
    val connectionName: String?,
    val connectionURI: URI,
    val role: AMQPConnectorRole,
    val consumers: List<AMQPConsumer<*>>
) {
    val isPublishing get() = role in (listOf(AMQPConnectorRole.BOTH, AMQPConnectorRole.PUBLISHER))
    val isConsuming get() = role in (listOf(AMQPConnectorRole.BOTH, AMQPConnectorRole.CONSUMER))
}

class AMQPConnector private constructor(
    val amqpConnectionConfig: AMQPConnectorConfig,
    val connectionFactory: ConnectionFactory,
    val publishingConnection: Connection?,
    val consumingConnection: Connection?
) {
    companion object {
        private fun newConnection(
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

        private fun createFactory(
            amqpConnectionConfig: AMQPConnectorConfig,
        ): Result<ConnectionFactory, AMQPConnectionFactoryError> = try {
            val factory = ConnectionFactory()
            factory.setUri(amqpConnectionConfig.connectionURI)
            Success(factory)
        } catch (e: Exception) {
            Failure(AMQPConnectionFactoryError("Cannot configure connection factory: ${e.message}"))
        }

        fun create(amqpConnectorConfig: AMQPConnectorConfig): Result<AMQPConnector, AMQPError> = attemptBuildResult {
            val connectionFactory = !createFactory(amqpConnectorConfig)
            connectionFactory.virtualHost = "/"

            val publishingConnection: Connection? = amqpConnectorConfig.isPublishing.takeIf { it }
                ?.let { !newConnection(connectionFactory, amqpConnectorConfig.connectionName) }

            val consumingConnection: Connection? = amqpConnectorConfig.isConsuming.takeIf { it }
                ?.let {
                    val executorService = Executors.newFixedThreadPool(1)
                    val connection = !newConnection(
                        connectionFactory,
                        amqpConnectorConfig.connectionName,
                        executorService
                    )
                    amqpConnectorConfig.consumers.forEach {
                        it.startConsuming(connection, executorService.asCoroutineDispatcher())
                    }
                    connection
                }

            Success(AMQPConnector(
                amqpConnectorConfig,
                connectionFactory,
                publishingConnection,
                consumingConnection
            ))
        }
    }

}