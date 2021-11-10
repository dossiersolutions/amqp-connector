package no.dossier.libraries.amqpconnector.rabbitmq

sealed class AMQPReplyingMode {
    object Always: AMQPReplyingMode()
    object IfRequired: AMQPReplyingMode()
    object Never: AMQPReplyingMode()
}