package no.dossier.libraries.amqpconnector.platform

expect class Uuid {
    companion object {
        fun fromString(uuidString: String): Uuid
        fun random(): Uuid
    }
}