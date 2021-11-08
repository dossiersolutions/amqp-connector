package no.dossier.libraries.amqpconnector.rabbitmq

class AMQPDeadLetterSpec(
    val exchangeEnabled: Boolean,
    val exchangeName: String,
    val routingKey: DeadLetterRoutingKey,
    val implicitQueueEnabled: Boolean
)

sealed class DeadLetterRoutingKey {
    object SameAsOriginalMessage: DeadLetterRoutingKey()
    object OriginalQueueName: DeadLetterRoutingKey()
    class Custom(val routingKey: String): DeadLetterRoutingKey()
}