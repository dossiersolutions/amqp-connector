package no.dossier.libraries.amqpconnector.platform

import com.rabbitmq.client.ConnectionFactory
import java.util.concurrent.Executors

actual class ConnectionFactory actual constructor(connectionUri: Uri) {

    private val rmqConnectionFactory = ConnectionFactory().apply {
        setUri(connectionUri.jvmURI)
        if (virtualHost == "" || virtualHost == null) {
            // default virtual host, because it if the URI contains just trailing "/"
            // it will use an empty string as virtualHost
            virtualHost = "/"
        }
    }

    actual fun newConnection(connectionName: String): Connection {
        return Connection(
            rmqConnectionFactory.newConnection(connectionName)
        )
    }

    actual fun newConsumingConnection(connectionName: String): Connection {
        val executorService = Executors.newFixedThreadPool(32)
        return Connection(
            rmqConnectionFactory.newConnection(executorService, connectionName),
            executorService
        )
    }
}