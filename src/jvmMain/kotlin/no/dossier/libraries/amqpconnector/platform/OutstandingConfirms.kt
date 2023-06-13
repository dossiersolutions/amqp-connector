package no.dossier.libraries.amqpconnector.platform

import kotlinx.coroutines.CancellableContinuation
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.primitives.AmqpOutboundMessage
import no.dossier.libraries.functional.Outcome
import java.util.concurrent.ConcurrentSkipListMap

actual class OutstandingConfirms {

    private val outstandingConfirms: ConcurrentSkipListMap<Long, Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>> =
        ConcurrentSkipListMap()

    actual fun remove(sequenceNumber: Long) {
        outstandingConfirms.remove(sequenceNumber)
    }

    actual operator fun set(sequenceNumber: Long, value: Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>) {
        outstandingConfirms[sequenceNumber] = value
    }
    actual fun headMap(sequenceNumber: Long, inclusive: Boolean): MutableMap<Long, Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>> {
        return outstandingConfirms.headMap(sequenceNumber, inclusive)
    }
    actual operator fun get(sequenceNumber: Long): Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>?
    {
        return outstandingConfirms[sequenceNumber]
    }
}