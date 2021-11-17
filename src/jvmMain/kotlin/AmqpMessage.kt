package no.dossier.libraries.amqpconnector.rabbitmq

import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success

enum class AmqpMessageProperty {
    USER,
    API_KEY,
}

data class AmqpMessage<T>(
    val payload: T,
    val headers: Map<String, String> = mapOf(),
    val reply: suspend (serializedPayload: String, replyTo: String, correlationId: String) -> Unit = { _, _, _ ->  },
    val acknowledge: suspend () -> Unit = { },
    val reject: suspend () -> Unit = { },
    val replyTo: String? = null,
    val correlationId: String? = null
) {
    operator fun get(key: AmqpMessageProperty): Outcome<AmqpConsumingError, String> =
        headers[key.name]
            ?.let { Success(it) }
            ?: Failure(AmqpConsumingError("Message doesn't contain property: ${key.name}"))
}