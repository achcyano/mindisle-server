package me.hztcm.mindisle.util

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes

private val CN_MOBILE_REGEX = Regex("^1[3-9]\\d{9}$")

fun normalizePhone(raw: String): String {
    val cleaned = raw.trim().replace(" ", "").replace("-", "")
    val normalized = when {
        cleaned.startsWith("+86") -> cleaned.substring(3)
        cleaned.startsWith("86") && cleaned.length == 13 -> cleaned.substring(2)
        else -> cleaned
    }
    if (!CN_MOBILE_REGEX.matches(normalized)) {
        throw AppException(
            code = ErrorCodes.INVALID_PHONE,
            message = "Invalid phone format, mainland China mobile only",
            status = HttpStatusCode.BadRequest
        )
    }
    return normalized
}
