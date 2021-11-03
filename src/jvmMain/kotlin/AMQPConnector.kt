package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import no.dossier.libraries.functional.*
import java.net.URI

enum class AMQPConnectorRole {
    PUBLISHER,
    CONSUMER,
    BOTH
}

data class AMQPConnectorConfig(
    val connectionURI: URI,
    val role: AMQPConnectorRole,
    val consumers: List<AMQPConsumer<*>>
)

class AMQPConnector private constructor(
    val amqpConnectionConfig: AMQPConnectorConfig,
    private val connectionFactory: ConnectionFactory,
    val publishingConnection: Connection?,
    val consumingConnection: Connection?
) {
    companion object {
        private fun newConnection(connectionFactory: ConnectionFactory): Result<Connection, AMQPConnectionError> =
            try {
                Success(connectionFactory.newConnection())
            }
            catch (e: Exception) {
                Failure(AMQPConnectionError("Cannot connect to AMQP borker: ${e.message}"))
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

        fun create(amqpConnectionConfig: AMQPConnectorConfig): Result<AMQPConnector, AMQPError> = attemptBuildResult {
            val connectionFactory = !createFactory(amqpConnectionConfig)
            connectionFactory.virtualHost = "/"

            val isPublishing = amqpConnectionConfig.role in (listOf(AMQPConnectorRole.BOTH, AMQPConnectorRole.PUBLISHER))
            val isConsuming = amqpConnectionConfig.role in (listOf(AMQPConnectorRole.BOTH, AMQPConnectorRole.CONSUMER))

            val publishingConnection: Connection? = if (isPublishing) !newConnection(connectionFactory) else null
            val consumingConnection: Connection? = if (isConsuming) !newConnection(connectionFactory) else null

            if (isConsuming) amqpConnectionConfig.consumers.forEach { it.startConsuming(consumingConnection!!) }

            Success(AMQPConnector(amqpConnectionConfig, connectionFactory, publishingConnection, consumingConnection))
        }
    }

}