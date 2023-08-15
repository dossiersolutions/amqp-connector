package no.dossier.libraries.amqpconnector.platform

expect class Uri(url: String) {
    fun parseServerAuthority(): Uri
}