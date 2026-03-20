package me.hztcm.mindisle.auth

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes

private const val DEVICE_ID_HEADER = "X-Device-Id"
private const val DEVICE_ID_MAX_LENGTH = 128
private const val PASSWORD_MIN_LENGTH = 6
private const val PASSWORD_MAX_LENGTH = 20
private const val TOKEN_MAX_LENGTH = 512
private val SMS_CODE_REGEX = Regex("^\\d{6}$")

fun requireDeviceIdHeader(rawValue: String?): String {
    val value = rawValue?.trim().orEmpty()
    if (value.isEmpty()) {
        throw invalidRequest("Missing required header: $DEVICE_ID_HEADER")
    }
    if (value.length > DEVICE_ID_MAX_LENGTH) {
        throw invalidRequest("$DEVICE_ID_HEADER exceeds $DEVICE_ID_MAX_LENGTH characters")
    }
    ensureNoControlChars(fieldName = DEVICE_ID_HEADER, value = value)
    return value
}

fun validatePassword(password: String) {
    ensureNoControlChars(fieldName = "password", value = password)
    if (password.length !in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH) {
        throw AppException(
            code = ErrorCodes.PASSWORD_TOO_SHORT,
            message = "Password length must be between $PASSWORD_MIN_LENGTH and $PASSWORD_MAX_LENGTH characters",
            status = HttpStatusCode.BadRequest
        )
    }
}

fun validateSmsCode(code: String) {
    if (!SMS_CODE_REGEX.matches(code)) {
        throw AppException(
            code = ErrorCodes.INVALID_SMS_CODE,
            message = "Invalid sms code format",
            status = HttpStatusCode.BadRequest
        )
    }
}

fun validateToken(fieldName: String, value: String) {
    ensureNoControlChars(fieldName = fieldName, value = value)
    if (value.length > TOKEN_MAX_LENGTH) {
        throw invalidRequest("$fieldName exceeds $TOKEN_MAX_LENGTH characters")
    }
}

fun ensureNoControlChars(fieldName: String, value: String) {
    if (value.any { it.isISOControl() }) {
        throw invalidRequest("$fieldName contains control characters")
    }
}

fun invalidRequest(message: String): AppException {
    return AppException(
        code = ErrorCodes.INVALID_REQUEST,
        message = message,
        status = HttpStatusCode.BadRequest
    )
}
