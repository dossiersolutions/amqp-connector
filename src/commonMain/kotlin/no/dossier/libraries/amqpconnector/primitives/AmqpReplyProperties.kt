package no.dossier.libraries.amqpconnector.primitives

class AmqpReplyProperties(
    val correlationId: String? = null,
    val deliveryMode: AmqpMessageDeliveryMode = AmqpMessageDeliveryMode.TRANSIENT,
    val replyTo: String? = null,
    val headers: MutableMap<String, String> = mutableMapOf(),
) {

}