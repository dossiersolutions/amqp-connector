package no.dossier.libraries.amqpconnector.rabbitmq

class AMQPQueueSpec(
    val name: String,
    val durable: Boolean,
    val exclusive: Boolean,
    val autoDelete: Boolean
)