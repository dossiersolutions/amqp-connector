package no.dossier.libraries.amqpconnector.platform

actual class ConnectionFactory actual constructor(connectionUri: Uri) {
    actual fun newConnection(connectionName: String): Connection {
        TODO("Not yet implemented")
    }

    actual fun newConsumingConnection(connectionName: String): Connection {
        TODO("Not yet implemented")
    }
}