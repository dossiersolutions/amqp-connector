package no.dossier.libraries.amqpconnector.primitives

sealed class AmqpBindingKey {
    object QueueName: AmqpBindingKey()
    class Custom(val key: String): AmqpBindingKey()
}

sealed class AmqpRoutingKey {
    object PublisherDefault: AmqpRoutingKey()
    class Custom(val key: String): AmqpRoutingKey()
}