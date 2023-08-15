package no.dossier.libraries.amqpconnector.platform

import kotlinx.coroutines.CancellableContinuation
import no.dossier.libraries.amqpconnector.error.AmqpRpcError
import no.dossier.libraries.functional.Outcome

actual class PendingRequests<U : Any> actual constructor() {
    actual operator fun get(correlationId: Uuid): CancellableContinuation<Outcome<AmqpRpcError, U>>? {
        TODO("Not yet implemented")
    }

    actual fun remove(correlationId: Uuid) {
    }

    actual operator fun set(
        correlationId: Uuid,
        value: CancellableContinuation<Outcome<AmqpRpcError, U>>
    ) {
    }

}