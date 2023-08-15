package no.dossier.libraries.amqpconnector.primitives

data class AmqpDeadLetterSpec(
    val enabled: Boolean,
    val exchangeSpec: AmqpExchangeSpec,
    val routingKey: DeadLetterRoutingKey,
    val implicitQueueEnabled: Boolean
)

sealed class DeadLetterRoutingKey {
    object SameAsOriginalMessage: DeadLetterRoutingKey()
    object OriginalQueueName: DeadLetterRoutingKey()
    class Custom(val routingKey: String): DeadLetterRoutingKey()
}