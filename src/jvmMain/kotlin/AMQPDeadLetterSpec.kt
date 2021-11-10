package no.dossier.libraries.amqpconnector.rabbitmq

data class AMQPDeadLetterSpec(
    val enabled: Boolean,
    val exchangeSpec: AMQPExchangeSpec,
    val routingKey: DeadLetterRoutingKey,
    val implicitQueueEnabled: Boolean
)

sealed class DeadLetterRoutingKey {
    object SameAsOriginalMessage: DeadLetterRoutingKey()
    object OriginalQueueName: DeadLetterRoutingKey()
    class Custom(val routingKey: String): DeadLetterRoutingKey()
}