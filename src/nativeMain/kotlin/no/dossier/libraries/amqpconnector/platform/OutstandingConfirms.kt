package no.dossier.libraries.amqpconnector.platform

import kotlinx.coroutines.CancellableContinuation
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.primitives.AmqpOutboundMessage
import no.dossier.libraries.functional.Outcome

actual class OutstandingConfirms {
    actual fun remove(sequenceNumber: Long) {
        TODO()
    }

    actual operator fun set(sequenceNumber: Long, value: Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>) {
        TODO()
    }
    actual fun headMap(sequenceNumber: Long, inclusive: Boolean): MutableMap<Long, Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>> {
        TODO()
    }
    actual operator fun get(sequenceNumber: Long): Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>?
    {
        TODO()
    }
}