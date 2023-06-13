package no.dossier.libraries.amqpconnector.platform

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Delivery
import com.rabbitmq.client.Envelope

actual class Delivery(private val rmqDelivery: Delivery) {
    actual val envelope: DeliveryEnvelope = DeliveryEnvelope(rmqDelivery.envelope)
    actual val body: ByteArray get() = rmqDelivery.body
    actual val properties: DeliveryProperties = DeliveryProperties(rmqDelivery.properties)
}

actual class DeliveryEnvelope(private val rmqDeliveryEnvelope: Envelope) {
    actual val routingKey: String get() = rmqDeliveryEnvelope.routingKey
    actual val deliveryTag: Long get() = rmqDeliveryEnvelope.deliveryTag
}

actual class DeliveryProperties(private val rmqDeliveryProperties: AMQP.BasicProperties) {
    actual val correlationId: String? get() = rmqDeliveryProperties.correlationId
    actual val replyTo: String? get() = rmqDeliveryProperties.replyTo
    actual val headers: Map<String, Any>? get() = rmqDeliveryProperties.headers
}
