package no.dossier.libraries.amqpconnector.platform

import java.net.URI

actual class Uri actual constructor(url: String) {
    val jvmURI = URI(url)

    actual fun parseServerAuthority(): Uri {
        jvmURI.parseServerAuthority()
        return this
    }

}