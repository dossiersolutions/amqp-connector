package no.dossier.libraries.amqpconnector.rabbitmq

sealed class AmqpReplyingMode {
    object Always: AmqpReplyingMode()
    object IfRequired: AmqpReplyingMode()
    object Never: AmqpReplyingMode()
}