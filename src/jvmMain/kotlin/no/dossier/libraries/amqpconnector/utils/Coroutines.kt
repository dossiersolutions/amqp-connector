package no.dossier.libraries.amqpconnector.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.andThen

suspend inline fun <E, T> suspendCancellableCoroutineWithTimeout(
    timeoutMillis: Long,
    crossinline onTimeout: () -> E,
    crossinline block: (CancellableContinuation<Outcome<E, T>>) -> Unit
): Outcome<E, T> = no.dossier.libraries.functional.runCatching({ onTimeout() }) {
    withTimeout(timeoutMillis) {
        suspendCancellableCoroutine(block)
    }
}.andThen { it }