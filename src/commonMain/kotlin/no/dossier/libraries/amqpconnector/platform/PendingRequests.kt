package no.dossier.libraries.amqpconnector.platform

import kotlinx.coroutines.CancellableContinuation
import no.dossier.libraries.amqpconnector.error.AmqpRpcError
import no.dossier.libraries.functional.Outcome

expect class PendingRequests<U: Any>() {
    operator fun get(correlationId: Uuid): CancellableContinuation<Outcome<AmqpRpcError, U>>?
    fun remove(correlationId: Uuid)
    operator fun set(correlationId: Uuid, value: CancellableContinuation<Outcome<AmqpRpcError, U>>)

}