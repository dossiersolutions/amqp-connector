package no.dossier.libraries.amqpconnector.utils

import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success
import java.net.URI
import java.net.URISyntaxException
import java.util.*

class ValidationError(
    override val message: String,
) : InternalError()

fun getValidatedUri(urlString: String): Outcome<ValidationError, URI> = try {
    Success(URI(urlString).parseServerAuthority())
} catch (e: URISyntaxException) {
    Failure(ValidationError("'$urlString' is not valid URI"))
}

/* Parse UUID-string into a UUID instance */
fun getValidatedUUID(uuidString: String): Outcome<ValidationError, UUID> = try {
    if (isValidUUIDString(uuidString)) {
        Success(UUID.fromString(uuidString))
    } else {
        Failure(ValidationError("Invalid UUID format: $uuidString"))
    }
} catch (ex: IllegalArgumentException) {
    Failure(ValidationError("Unable to parse UUID (${ex.message})"))
}

/* UUID validation (see https://tools.ietf.org/html/rfc4122) */
private fun isValidUUIDString(uuidString: String): Boolean {
    val regex = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-5][0-9a-f]{3}-[089ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE
    )

    return uuidString.matches(regex)
}