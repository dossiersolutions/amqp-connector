package no.dossier.libraries.amqpconnector.platform

expect class ConnectionFactory(connectionUri: Uri) {
    fun newConnection(connectionName: String): Connection
    fun newConsumingConnection(connectionName: String): Connection
}