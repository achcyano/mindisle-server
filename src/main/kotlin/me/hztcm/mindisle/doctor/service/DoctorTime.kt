package me.hztcm.mindisle.doctor.service

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

internal fun LocalDateTime.toIsoInstant(): String = atOffset(ZoneOffset.UTC).toInstant().toString()

internal fun LocalDateTime.toIsoOffsetPlus8(): String {
    return atOffset(ZoneOffset.UTC)
        .withOffsetSameInstant(ZoneOffset.ofHours(8))
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

internal fun parseInstantToUtcDateTime(value: String): LocalDateTime {
    return Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDateTime()
}

internal fun LocalDateTime.toLocalDatePlus8(): LocalDate {
    return atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDate()
}
