package no.dossier.libraries.amqpconnector.utils

import java.time.Instant

actual fun getCurrentTimeStamp() = Instant.now().toString()