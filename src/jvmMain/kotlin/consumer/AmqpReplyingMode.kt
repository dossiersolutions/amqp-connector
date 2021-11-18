package no.dossier.libraries.amqpconnector.consumer

sealed class AmqpReplyingMode {
    object Always: AmqpReplyingMode()
    object IfRequired: AmqpReplyingMode()
    object Never: AmqpReplyingMode()
}