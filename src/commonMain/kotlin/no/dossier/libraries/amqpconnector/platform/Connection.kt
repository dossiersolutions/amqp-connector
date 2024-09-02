package no.dossier.libraries.amqpconnector.platform

import kotlinx.coroutines.CoroutineDispatcher

expect class Connection {
    val consumerThreadPoolDispatcher: CoroutineDispatcher?

    fun close(): Unit
    fun createChannel(): Channel
    fun isOpen(): Boolean
}