package no.dossier.libraries.amqpconnector.utils

import kotlinx.coroutines.*
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome

suspend inline fun <E, T> suspendCancellableCoroutineWithTimeout(
    timeoutMillis: Long,
    crossinline onTimeout: () -> E,
    crossinline block: (CancellableContinuation<Outcome<E, T>>) -> Unit
): Outcome<E, T> = try {
    withTimeout(timeoutMillis) {
        suspendCancellableCoroutine(block)
    }
} catch (ex: TimeoutCancellationException) {
    Failure(onTimeout())
}

expect fun getSingleThreadedDispatcher(): CoroutineDispatcher