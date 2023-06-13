package no.dossier.libraries.amqpconnector.platform

import kotlinx.coroutines.CancellableContinuation
import no.dossier.libraries.amqpconnector.error.AmqpRpcError
import no.dossier.libraries.functional.Outcome
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

actual class PendingRequests<U : Any> actual constructor() {

    private val pendingRequestsMap: ConcurrentMap<Uuid, CancellableContinuation<Outcome<AmqpRpcError, U>>> = ConcurrentHashMap()

    actual operator fun get(correlationId: Uuid): CancellableContinuation<Outcome<AmqpRpcError, U>>? {
        return pendingRequestsMap[correlationId]
    }

    actual fun remove(correlationId: Uuid) {
        pendingRequestsMap.remove(correlationId)
    }

    actual operator fun set(
        correlationId: Uuid,
        value: CancellableContinuation<Outcome<AmqpRpcError, U>>
    ) {
        pendingRequestsMap[correlationId] = value
    }

}