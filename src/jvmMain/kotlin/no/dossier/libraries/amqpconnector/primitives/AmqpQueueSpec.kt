package no.dossier.libraries.amqpconnector.primitives

data class AmqpQueueSpec(
    val name: String,
    val durable: Boolean,
    val exclusive: Boolean,
    val autoDelete: Boolean,
)