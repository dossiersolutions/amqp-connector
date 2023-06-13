package no.dossier.libraries.amqpconnector.platform

import kotlinx.coroutines.CoroutineDispatcher

actual class Connection {
    actual val consumerThreadPoolDispatcher: CoroutineDispatcher?
        get() = TODO("Not yet implemented")

    actual fun close() {
    }

    actual fun createChannel(): Channel {
        TODO("Not yet implemented")
    }

}