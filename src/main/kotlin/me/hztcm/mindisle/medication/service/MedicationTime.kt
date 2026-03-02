package me.hztcm.mindisle.medication.service

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

internal val UTC_PLUS_8: ZoneOffset = ZoneOffset.ofHours(8)

internal fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

internal fun LocalDateTime.toLocalDatePlus8(): LocalDate {
    return atOffset(ZoneOffset.UTC).withOffsetSameInstant(UTC_PLUS_8).toLocalDate()
}
