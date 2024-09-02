package no.dossier.libraries.amqpconnector.platform

import com.rabbitmq.client.Connection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService

actual class Connection(private val rmqConnection: Connection, private val executorService: ExecutorService? = null) {

    actual val consumerThreadPoolDispatcher: CoroutineDispatcher?
        get() = executorService?.asCoroutineDispatcher()

    actual fun close() {
        rmqConnection.close()
    }

    actual fun createChannel(): Channel {
        return Channel(rmqConnection.createChannel())
    }

    actual fun isOpen(): Boolean = rmqConnection.isOpen
}