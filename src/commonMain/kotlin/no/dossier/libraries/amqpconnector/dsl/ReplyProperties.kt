package no.dossier.libraries.amqpconnector.dsl

import no.dossier.libraries.amqpconnector.primitives.AmqpMessageProperties

fun messageProperties(builderBlock: AmqpMessagePropertiesPrototype.() -> Unit): AmqpMessageProperties =
    AmqpMessagePropertiesPrototype()
        .apply(builderBlock)
        .build()