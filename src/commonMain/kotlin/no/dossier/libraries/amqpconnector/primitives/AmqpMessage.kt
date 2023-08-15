package no.dossier.libraries.amqpconnector.primitives

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import no.dossier.libraries.amqpconnector.error.AmqpConsumingError
import no.dossier.libraries.amqpconnector.serialization.amqpJsonConfig
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success

/* TODO: Reconsider Headers vs Properties naming, maybe there shouldn't be any pre-defined set of Properties at all */
enum class AmqpMessageProperty {
    USER,
    API_KEY,
    REPLY_TO_EXCHANGE
}

enum class AmqpMessageDeliveryMode(val code: Int) {
    TRANSIENT(1),
    PERSISTENT(2);
}

data class AmqpInboundMessage<T>(
    val rawPayload: ByteArray,
    val headers: Map<String, String> = mapOf(),
    val reply: suspend (
        message: AmqpOutboundMessage<*>,
        replyToExchange: String
    ) -> Unit = { _, _ ->  },
    val acknowledge: suspend () -> Unit = { },
    val reject: suspend () -> Unit = { },
    val replyTo: String? = null,
    val correlationId: String? = null,
    val routingKey: String,
    private val serializer: KSerializer<T>
) {
    val payload: Outcome<AmqpConsumingError ,T> by lazy {
        try {
            val payload = amqpJsonConfig.decodeFromString(serializer, rawPayload.decodeToString())
            Success(payload)
        }
        catch (e: Throwable) {
            Failure(AmqpConsumingError("Unable to deserialize message payload: ${e.message}" +
                    "\n Headers: ${this.headers} \n RoutingKey: ${this.routingKey}"))
        }
    }

    operator fun get(key: AmqpMessageProperty): Outcome<AmqpConsumingError, String> =
        headers[key.name]
            ?.let { Success(it) }
            ?: Failure(AmqpConsumingError("Message doesn't contain property: ${key.name}"))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        other as AmqpInboundMessage<*>

        if (!rawPayload.contentEquals(other.rawPayload)) return false
        if (headers != other.headers) return false
        if (reply != other.reply) return false
        if (acknowledge != other.acknowledge) return false
        if (reject != other.reject) return false
        if (replyTo != other.replyTo) return false
        if (correlationId != other.correlationId) return false
        if (routingKey != other.routingKey) return false
        if (serializer != other.serializer) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rawPayload.contentHashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + reply.hashCode()
        result = 31 * result + acknowledge.hashCode()
        result = 31 * result + reject.hashCode()
        result = 31 * result + (replyTo?.hashCode() ?: 0)
        result = 31 * result + (correlationId?.hashCode() ?: 0)
        result = 31 * result + routingKey.hashCode()
        result = 31 * result + serializer.hashCode()
        return result
    }

    override fun toString(): String = """
        AmqpInboundMessage(
          routingKey = $routingKey
          headers = $headers
          replyTo = $replyTo, correlationId = $correlationId
          rawPayload = ${rawPayload.decodeToString()}
        )
    """.trimIndent()
}

/*
* We need this inline factory method because inline constructors are not yet supported in Kotlin
* see https://youtrack.jetbrains.com/issue/KT-30915
*/
@Suppress("FunctionName")
inline fun <reified T> AmqpOutboundMessage(
    payload: T,
    headers: Map<String, String> = mapOf(),
    replyTo: String? = null,
    correlationId: String? = null,
    routingKey: AmqpRoutingKey = AmqpRoutingKey.PublisherDefault,
    deliveryMode: AmqpMessageDeliveryMode = AmqpMessageDeliveryMode.PERSISTENT
): AmqpOutboundMessage<T> = AmqpOutboundMessage(
    payload, headers, replyTo, correlationId, routingKey, serializer(), deliveryMode
)

data class AmqpOutboundMessage<T>(
    val payload: T,
    val headers: Map<String, String>,
    val replyTo: String?,
    val correlationId: String?,
    val routingKey: AmqpRoutingKey,
    private val serializer: KSerializer<T>,
    val deliveryMode: AmqpMessageDeliveryMode = AmqpMessageDeliveryMode.PERSISTENT
) {

    val rawPayload: ByteArray by lazy { amqpJsonConfig.encodeToString(serializer, payload).encodeToByteArray() }

    operator fun get(key: AmqpMessageProperty): Outcome<AmqpConsumingError, String> =
        headers[key.name]
            ?.let { Success(it) }
            ?: Failure(AmqpConsumingError("Message doesn't contain property: ${key.name}"))
}