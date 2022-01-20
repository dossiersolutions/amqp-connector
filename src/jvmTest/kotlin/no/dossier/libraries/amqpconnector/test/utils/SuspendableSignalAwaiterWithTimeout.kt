package no.dossier.libraries.amqpconnector.test.utils

import kotlinx.coroutines.CancellableContinuation
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.stl.suspendCancellableCoroutineWithTimeout
import kotlin.coroutines.resume

class SuspendableSignalAwaiterWithTimeout<E, T>(
    private val timeoutError: E,
    private val timeoutMillis: Long = 5_000
) {
    private lateinit var continuation: CancellableContinuation<Outcome<E, T>>

    fun resume(value: Outcome<E, T>) {
        continuation.resume(value)
    }

    suspend fun runAndAwaitSignal(block: () -> Outcome<E, *>): Outcome<E, T> =
        suspendCancellableCoroutineWithTimeout(timeoutMillis, {
            timeoutError
        }, {
            continuation = it
            val outcome = block()
            if (outcome is Failure) continuation.resume(outcome)
        })
}