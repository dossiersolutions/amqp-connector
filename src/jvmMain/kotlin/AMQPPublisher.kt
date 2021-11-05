package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.MessageProperties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Result
import no.dossier.libraries.functional.Success
import no.dossier.libraries.functional.andThen
import java.io.IOException

class AMQPPublisher(
    private val topicName: String,
    private val routingKey: String,
    connection: Connection
) {
    private val logger = KotlinLogging.logger { }

    private val amqpChannel: Channel = connection.createChannel()

    init {
        amqpChannel.exchangeDeclare(topicName, "topic")
    }

    inline operator fun <reified T: Any> invoke(
        payload: T,
        headers: Map<String, String>? = null
    ): Result<Unit, AMQPPublishingError> {
        return try {
            Success(Json.encodeToString(payload))
        } catch (e: Exception) {
            Failure(AMQPPublishingError("Unable to serialize payload: ${e.message}"))
        }
            .andThen { serializedPayload -> publish(serializedPayload, headers) }
    }

    @PublishedApi
    internal fun publish(
        serializedPayload: String,
        headers: Map<String, String>? = null
    ): Result<Unit, AMQPPublishingError> = try {
        val amqpProperties = AMQP.BasicProperties().builder()
            .deliveryMode(2 /*persistent*/)
            .headers(headers).build()

        logger.debug {
            "-> \uD83D\uDCE8 AMQP Publisher - sending message to [$topicName] using routing key [$routingKey]"
        }

        amqpChannel.basicPublish(topicName, routingKey, amqpProperties, serializedPayload.toByteArray())
        Success(Unit)
    }
    catch (e: IOException) {
        Failure(AMQPPublishingError("Unable to publish message: ${e.message}"))
    }
}