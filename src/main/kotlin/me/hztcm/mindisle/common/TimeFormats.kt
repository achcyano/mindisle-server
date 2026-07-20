package me.hztcm.mindisle.common

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

fun LocalDateTime.toIsoOffsetUtc(): String = atOffset(ZoneOffset.UTC).format(ISO_OFFSET)

fun LocalDateTime.toLocalDatePlus8(): LocalDate =
    atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDate()

fun parseLocalDateOrTodayPlus8(raw: String?): LocalDate {
    if (raw.isNullOrBlank()) return utcNow().toLocalDatePlus8()
    return runCatching { LocalDate.parse(raw.trim()) }.getOrElse {
        throw AppException(
            code = ErrorCodes.INVALID_REQUEST,
            message = "localDate must be yyyy-MM-dd",
            status = io.ktor.http.HttpStatusCode.BadRequest
        )
    }
}
