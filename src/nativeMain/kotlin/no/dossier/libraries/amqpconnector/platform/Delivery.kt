package no.dossier.libraries.amqpconnector.platform

actual class Delivery {
    actual val envelope: DeliveryEnvelope
        get() = TODO("Not yet implemented")
    actual val body: ByteArray
        get() = TODO("Not yet implemented")
    actual val properties: DeliveryProperties
        get() = TODO("Not yet implemented")
}

actual class DeliveryEnvelope {
    actual val routingKey: String
        get() = TODO("Not yet implemented")
    actual val deliveryTag: Long
        get() = TODO("Not yet implemented")
}

actual class DeliveryProperties {
    actual val correlationId: String?
        get() = TODO("Not yet implemented")
    actual val replyTo: String?
        get() = TODO("Not yet implemented")
    actual val headers: Map<String, Any>?
        get() = TODO("Not yet implemented")
}