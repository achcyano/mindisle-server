package me.hztcm.mindisle.doctor.service

import me.hztcm.mindisle.auth.ensureNoControlChars
import me.hztcm.mindisle.auth.invalidRequest
import java.math.BigDecimal
import java.math.RoundingMode

internal fun Double.toDoctorThresholdDecimal(): BigDecimal =
    BigDecimal.valueOf(this).setScale(2, RoundingMode.HALF_UP)

internal fun validateDoctorThreshold(fieldName: String, value: Double?) {
    if (value == null) {
        return
    }
    if (!value.isFinite() || value < 0.0 || value > 10_000.0) {
        throw doctorInvalidArg("$fieldName must be between 0 and 10000")
    }
}

internal fun validateDoctorTextLength(fieldName: String, value: String?, maxLength: Int) {
    if (value == null) {
        return
    }
    ensureNoControlChars(fieldName, value)
    if (value.length > maxLength) {
        throw invalidRequest("$fieldName exceeds $maxLength characters")
    }
}

internal fun normalizeDoctorProfileField(fieldName: String, value: String?, maxLength: Int): String? {
    if (value == null) {
        return null
    }
    val normalized = value.trim()
    if (normalized.isEmpty()) {
        throw invalidRequest("$fieldName cannot be blank when provided")
    }
    validateDoctorTextLength(fieldName, normalized, maxLength)
    return normalized
}
