package me.hztcm.mindisle.event.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import me.hztcm.mindisle.model.ScaleDeliveryModeDto
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
    return anchor.plusDays(intervalDays.toLong())
}

internal fun nextRecurringDueByMonths(anchor: LocalDateTime, intervalMonths: Int, now: LocalDateTime): LocalDateTime {
    require(intervalMonths > 0) { "intervalMonths must be positive" }
    var due = anchor.plusMonths(intervalMonths.toLong())
    while (due.isBefore(now)) {
        due = due.plusMonths(intervalMonths.toLong())
    }
    return due
}

internal fun normalizeImmediateDueAt(anchor: LocalDateTime, now: LocalDateTime): LocalDateTime {
    return if (anchor.isAfter(now)) now else anchor
}

internal data class EventScaleDelivery(
    val mode: ScaleDeliveryModeDto = ScaleDeliveryModeDto.NATIVE,
    val webPath: String? = null
) {
    fun historyWebPath(sessionId: Long): String? {
        val path = webPath ?: return null
        val separator = if (path.contains("?")) "&" else "?"
        return "$path${separator}sessionId=$sessionId"
    }
}

internal fun parseEventScaleDelivery(configJson: String?): EventScaleDelivery {
    if (configJson.isNullOrBlank()) {
        return EventScaleDelivery()
    }
    val root = runCatching { EVENT_JSON.parseToJsonElement(configJson) as? JsonObject }.getOrNull()
        ?: return EventScaleDelivery()
    val delivery = root["delivery"] as? JsonObject ?: return EventScaleDelivery()
    val modeText = (delivery["mode"] as? JsonPrimitive)?.content?.trim()?.uppercase()
    if (modeText != ScaleDeliveryModeDto.WEBVIEW.name) {
        return EventScaleDelivery()
    }
    val webPath = (delivery["webPath"] as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf { it.startsWith("/") }
        ?: return EventScaleDelivery()
    return EventScaleDelivery(mode = ScaleDeliveryModeDto.WEBVIEW, webPath = webPath)
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
