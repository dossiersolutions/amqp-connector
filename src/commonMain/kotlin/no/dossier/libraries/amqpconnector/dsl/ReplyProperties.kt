package no.dossier.libraries.amqpconnector.dsl

import no.dossier.libraries.amqpconnector.primitives.AmqpReplyProperties

fun replyProperties(builderBlock: AmqpReplyPropertiesPrototype.() -> Unit): AmqpReplyProperties =
    AmqpReplyPropertiesPrototype()
        .apply(builderBlock)
        .build()