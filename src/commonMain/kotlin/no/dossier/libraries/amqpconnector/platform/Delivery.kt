package no.dossier.libraries.amqpconnector.platform

expect class Delivery {
    val envelope: DeliveryEnvelope
    val body: ByteArray
    val properties: DeliveryProperties
}

expect class DeliveryEnvelope {
    val routingKey: String
    val deliveryTag: Long
}

expect class DeliveryProperties {
    val correlationId: String?
    val replyTo: String?
    val headers: Map<String, Any>?
}
