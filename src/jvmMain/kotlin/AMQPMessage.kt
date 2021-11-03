package no.dossier.libraries.amqpconnector.rabbitmq

data class AMQPMessage<T>(
    val payload: T,
)