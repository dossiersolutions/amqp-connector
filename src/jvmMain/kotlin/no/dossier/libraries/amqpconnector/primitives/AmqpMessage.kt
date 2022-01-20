package no.dossier.libraries.amqpconnector.primitives

import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success

/* TODO: Reconsider Headers vs Properties naming, maybe there shouldn't be any pre-defined set of Properties at all */
enum class AmqpMessageProperty {
    USER,
    API_KEY,
    REPLY_TO_EXCHANGE
}

data class AmqpInboundMessage<T>(
    val payload: T,
    val headers: Map<String, String> = mapOf(),
    val reply: suspend (
        serializedPayload: String,
        replyTo: String,
        correlationId: String,
        replyToExchange: String
    ) -> Unit = { _, _, _, _ ->  },
    val acknowledge: suspend () -> Unit = { },
    val reject: suspend () -> Unit = { },
    val replyTo: String? = null,
    val correlationId: String? = null,
    val routingKey: String
) {
    operator fun get(key: AmqpMessageProperty): Outcome<AmqpConsumingError, String> =
        headers[key.name]
            ?.let { Success(it) }
            ?: Failure(AmqpConsumingError("Message doesn't contain property: ${key.name}"))
}

data class AmqpOutboundMessage<T>(
    val payload: T,
    val headers: Map<String, String> = mapOf(),
    val replyTo: String? = null,
    val correlationId: String? = null,
    val routingKey: AmqpRoutingKey = AmqpRoutingKey.PublisherDefault
) {
    operator fun get(key: AmqpMessageProperty): Outcome<AmqpConsumingError, String> =
        headers[key.name]
            ?.let { Success(it) }
            ?: Failure(AmqpConsumingError("Message doesn't contain property: ${key.name}"))
}