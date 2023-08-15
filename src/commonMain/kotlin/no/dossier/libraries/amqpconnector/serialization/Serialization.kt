package no.dossier.libraries.amqpconnector.serialization

import kotlinx.serialization.json.Json

val amqpJsonConfig = Json { classDiscriminator = "#class" }