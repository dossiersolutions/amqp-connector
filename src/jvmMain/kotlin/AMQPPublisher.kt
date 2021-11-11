package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Result
import no.dossier.libraries.functional.Success
import no.dossier.libraries.functional.andThen
import java.io.IOException

class AMQPPublisher(
    private val exchangeSpec: AMQPExchangeSpec,
    private val routingKey: String,
    publishingConnection: Connection
) {
    private val logger = KotlinLogging.logger { }

    private val amqpChannel: Channel = publishingConnection.createChannel()

    init {
        if (exchangeSpec.type != AMQPExchangeType.DEFAULT)
            amqpChannel.exchangeDeclare(exchangeSpec.name, exchangeSpec.type.stringRepresentation)
    }

    inline operator fun <reified T: Any> invoke(
        payload: T,
        headers: Map<String, String>? = null,
        replyTo: String? = null,
        correlationId: String? = null
    ): Result<Unit, AMQPPublishingError> {
        return try {
            Success(Json.encodeToString(payload))
        } catch (e: Exception) {
            Failure(AMQPPublishingError("Unable to serialize payload: ${e.message}"))
        }
            .andThen { serializedPayload -> publish(serializedPayload, headers, replyTo, correlationId) }
    }

    @PublishedApi
    internal fun publish(
        serializedPayload: String,
        headers: Map<String, String>? = null,
        replyTo: String? = null,
        correlationId: String? = null
    ): Result<Unit, AMQPPublishingError> = try {
        val amqpPropertiesBuilder = AMQP.BasicProperties().builder()
            .deliveryMode(2 /*persistent*/)
            .headers(headers)

        if (replyTo != null) amqpPropertiesBuilder.replyTo(replyTo)
        if (correlationId != null) amqpPropertiesBuilder.correlationId(correlationId)

        val amqpProperties = amqpPropertiesBuilder.build()

        val exchangeName = exchangeSpec.type.stringRepresentation

        logger.debug {
            "← \uD83D\uDCE8 AMQP Publisher - sending message to [$exchangeName] using routing key [$routingKey]"
        }

        amqpChannel.basicPublish(exchangeName, routingKey, amqpProperties, serializedPayload.toByteArray())
        Success(Unit)
    }
    catch (e: IOException) {
        Failure(AMQPPublishingError("Unable to publish message: ${e.message}"))
    }
}