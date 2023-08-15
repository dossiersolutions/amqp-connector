package no.dossier.libraries.amqpconnector.utils

import no.dossier.libraries.amqpconnector.platform.Uri
import no.dossier.libraries.amqpconnector.platform.Uuid
import no.dossier.libraries.errorhandling.InternalError
import no.dossier.libraries.functional.Failure
import no.dossier.libraries.functional.Outcome
import no.dossier.libraries.functional.Success

class ValidationError(
    override val message: String,
    override val causes: Map<String, InternalError> = emptyMap(),
) : InternalError()

fun getValidatedUri(urlString: String): Outcome<ValidationError, Uri> = try {
    Success(Uri(urlString).parseServerAuthority())
} catch (e: Throwable) {
    Failure(ValidationError("'$urlString' is not valid URI"))
}

/* Parse UUID-string into a UUID instance */
fun getValidatedUUID(uuidString: String): Outcome<ValidationError, Uuid> = try {
    if (isValidUUIDString(uuidString)) {
        Success(Uuid.fromString(uuidString))
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