package no.dossier.libraries.amqpconnector.platform

import java.util.UUID

actual class Uuid(val jvmUUID: UUID) {

    override fun equals(other: Any?): Boolean {
        return jvmUUID.equals((other as Uuid).jvmUUID)
    }

    override fun hashCode(): Int {
        return jvmUUID.hashCode()
    }

    override fun toString(): String {
        return jvmUUID.toString()
    }

    actual companion object {
        actual fun fromString(uuidString: String): Uuid {
            return Uuid(UUID.fromString(uuidString))
        }

        actual fun random(): Uuid {
            return Uuid(UUID.randomUUID())
        }
    }
}