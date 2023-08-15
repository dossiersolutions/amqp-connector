package no.dossier.libraries.amqpconnector.utils

import kotlinx.coroutines.*
import java.util.concurrent.Executors

actual fun getSingleThreadedDispatcher(): CoroutineDispatcher =
    Executors.newFixedThreadPool(1).asCoroutineDispatcher()