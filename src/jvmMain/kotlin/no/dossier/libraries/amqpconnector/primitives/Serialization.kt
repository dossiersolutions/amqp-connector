package no.dossier.libraries.amqpconnector.primitives

import kotlinx.serialization.json.Json

val amqpJsonConfig = Json { classDiscriminator = "#class" }