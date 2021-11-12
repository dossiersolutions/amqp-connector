package no.dossier.libraries.amqpconnector.rabbitmq

data class AmqpQueueSpec(
    val name: String,
    val durable: Boolean,
    val exclusive: Boolean,
    val autoDelete: Boolean,
)