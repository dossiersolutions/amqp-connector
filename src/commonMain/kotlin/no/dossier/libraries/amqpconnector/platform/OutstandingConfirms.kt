package no.dossier.libraries.amqpconnector.platform

import kotlinx.coroutines.CancellableContinuation
import no.dossier.libraries.amqpconnector.error.AmqpPublishingError
import no.dossier.libraries.amqpconnector.primitives.AmqpOutboundMessage
import no.dossier.libraries.functional.Outcome

expect class OutstandingConfirms() {
    fun remove(sequenceNumber: Long)
    operator fun set(sequenceNumber: Long, value: Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>)
    fun headMap(sequenceNumber: Long, inclusive: Boolean): MutableMap<Long, Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>>
    operator fun get(sequenceNumber: Long): Pair<CancellableContinuation<Outcome<AmqpPublishingError, Unit>>, AmqpOutboundMessage<*>>?
}