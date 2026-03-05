package me.hztcm.mindisle.event.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val EVENT_JSON = Json { ignoreUnknownKeys = true }

internal fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

internal fun LocalDateTime.toIsoInstant(): String = atOffset(ZoneOffset.UTC).toInstant().toString()

internal fun LocalDateTime.toIsoOffsetPlus8(): String {
    return atOffset(ZoneOffset.UTC)
        .withOffsetSameInstant(ZoneOffset.ofHours(8))
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

internal fun nextRecurringDueByDays(anchor: LocalDateTime, intervalDays: Int, now: LocalDateTime): LocalDateTime {
    require(intervalDays > 0) { "intervalDays must be positive" }
    val step = intervalDays.toLong()
    val firstDue = anchor.plusDays(step)
    if (!firstDue.isBefore(now)) {
        return firstDue
    }

    val periodSeconds = step * 24L * 60L * 60L
    val elapsedSeconds = java.time.Duration.between(firstDue, now).seconds
    val cycles = (elapsedSeconds / periodSeconds) + 1
    return firstDue.plusDays(cycles * step)
}

internal fun nextRecurringDueByMonths(anchor: LocalDateTime, intervalMonths: Int, now: LocalDateTime): LocalDateTime {
    require(intervalMonths > 0) { "intervalMonths must be positive" }
    var due = anchor.plusMonths(intervalMonths.toLong())
    while (due.isBefore(now)) {
        due = due.plusMonths(intervalMonths.toLong())
    }
    return due
}

internal fun parseRedoIntervalDays(configJson: String?, defaultValue: Int): Int {
    if (configJson.isNullOrBlank()) {
        return defaultValue
    }
    val root = runCatching { EVENT_JSON.parseToJsonElement(configJson) as? JsonObject }.getOrNull()
        ?: return defaultValue
    val primitive = root["redoIntervalDays"] as? JsonPrimitive ?: return defaultValue
    val parsed = primitive.intOrNull ?: primitive.content.toIntOrNull() ?: return defaultValue
    return if (parsed > 0) parsed else defaultValue
}
