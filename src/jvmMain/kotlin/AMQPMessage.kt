package no.dossier.libraries.amqpconnector.rabbitmq

import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Result
import no.dossier.libraries.functional.Success

enum class AMQPMessageProperty {
    USER,
    API_KEY,
}

data class AMQPMessage<T>(
    private val headers: Map<String, String>,
    val payload: T,
    val reply: suspend (serializedPayload: String, replyTo: String, correlationId: String) -> Unit,
    val acknowledge: suspend () -> Unit,
    val reject: suspend () -> Unit,
    val replyTo: String? = null,
    val correlationId: String? = null
) {
    operator fun get(key: AMQPMessageProperty): Result<String, AMQPConsumingError> =
        headers[key.name]
            ?.let { Success(it) }
            ?: Failure(AMQPConsumingError(("Message doesn't contain property: ${key.name}")))
}