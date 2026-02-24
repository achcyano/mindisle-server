package me.hztcm.mindisle.scale.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.ScaleQuestionType
import java.math.BigDecimal
import kotlin.math.roundToInt

internal data class ScaleOptionScore(
    val optionId: Long,
    val optionKey: String,
    val scoreValue: BigDecimal?
)

internal data class ScaleAnswerEvaluation(
    val normalizedAnswer: JsonElement,
    val numericScore: BigDecimal?
)

internal object ScaleAnswerEvaluator {
    private val timeRegex = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private val chineseTimeRegex = Regex("^(凌晨|早上|上午|中午|下午|晚上)?\\s*(\\d{1,2})(?:点|:)(\\d{1,2})?$")
    private val durationHourRegex = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(h|hr|hour|hours|小时|时)$", RegexOption.IGNORE_CASE)
    private val durationMinuteRegex = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(m|min|mins|minute|minutes|分钟|分)$", RegexOption.IGNORE_CASE)
    private val durationClockRegex = Regex("^(\\d{1,2}):(\\d{1,2})$")

    fun evaluate(
        type: ScaleQuestionType,
        scorable: Boolean,
        reverseScored: Boolean,
        options: List<ScaleOptionScore>,
        answer: JsonElement
    ): ScaleAnswerEvaluation {
        return when (type) {
            ScaleQuestionType.SINGLE_CHOICE,
            ScaleQuestionType.YES_NO -> evaluateSingleChoice(scorable, reverseScored, options, answer)

            ScaleQuestionType.MULTI_CHOICE -> evaluateMultiChoice(scorable, options, answer)
            ScaleQuestionType.TEXT,
            ScaleQuestionType.TIME,
            ScaleQuestionType.DURATION -> evaluateOpenAnswer(type, scorable, answer)
        }
    }

    private fun evaluateSingleChoice(
        scorable: Boolean,
        reverseScored: Boolean,
        options: List<ScaleOptionScore>,
        answer: JsonElement
    ): ScaleAnswerEvaluation {
        val obj = answer.requireObject("Single choice answer must be an object")
        val optionById = options.associateBy { it.optionId }
        val optionByKey = options.associateBy { it.optionKey }
        val selected = resolveSingleOption(obj, optionById, optionByKey)
        val numeric = selected.scoreValue?.takeIf { scorable }
        val finalScore = if (reverseScored && numeric != null) {
            reverseScore(numeric, options)
        } else {
            numeric
        }
        return ScaleAnswerEvaluation(
            normalizedAnswer = buildJsonObject {
                put("optionId", selected.optionId)
                put("optionKey", selected.optionKey)
                selected.scoreValue?.let { put("score", it.toDouble()) }
            },
            numericScore = finalScore
        )
    }

    private fun evaluateMultiChoice(
        scorable: Boolean,
        options: List<ScaleOptionScore>,
        answer: JsonElement
    ): ScaleAnswerEvaluation {
        val obj = answer.requireObject("Multi choice answer must be an object")
        val optionById = options.associateBy { it.optionId }
        val optionByKey = options.associateBy { it.optionKey }
        val selected = resolveMultiOptions(obj, optionById, optionByKey)
        val total = if (!scorable) {
            null
        } else {
            selected.mapNotNull { it.scoreValue }.fold(BigDecimal.ZERO) { acc, value -> acc + value }
        }
        return ScaleAnswerEvaluation(
            normalizedAnswer = buildJsonObject {
                put("optionIds", JsonArray(selected.map { JsonPrimitive(it.optionId) }))
                put("optionKeys", JsonArray(selected.map { JsonPrimitive(it.optionKey) }))
                val rawScore = selected.mapNotNull { it.scoreValue }.fold(BigDecimal.ZERO) { acc, value -> acc + value }
                put("score", rawScore.toDouble())
            },
            numericScore = total
        )
    }

    private fun evaluateOpenAnswer(
        type: ScaleQuestionType,
        scorable: Boolean,
        answer: JsonElement
    ): ScaleAnswerEvaluation {
        val score = extractOpenAnswerScore(answer)
        val normalized = when (type) {
            ScaleQuestionType.TEXT -> normalizeTextAnswer(answer)
            ScaleQuestionType.TIME -> normalizeTimeAnswer(answer)
            ScaleQuestionType.DURATION -> normalizeDurationAnswer(answer)
            else -> throw answerFormatError("Unsupported open answer type: $type")
        }
        return ScaleAnswerEvaluation(
            normalizedAnswer = normalized,
            numericScore = if (scorable) score?.toBigDecimal() else null
        )
    }

    private fun resolveSingleOption(
        obj: JsonObject,
        optionById: Map<Long, ScaleOptionScore>,
        optionByKey: Map<String, ScaleOptionScore>
    ): ScaleOptionScore {
        val optionId = objNumber(obj, "optionId")?.toLong()
        if (optionId != null) {
            return optionById[optionId] ?: throw answerFormatError("optionId=$optionId not found")
        }
        val optionKey = objString(obj, "optionKey")
        if (!optionKey.isNullOrBlank()) {
            return optionByKey[optionKey] ?: throw answerFormatError("optionKey=$optionKey not found")
        }
        throw answerFormatError("Single choice answer requires optionId or optionKey")
    }

    private fun resolveMultiOptions(
        obj: JsonObject,
        optionById: Map<Long, ScaleOptionScore>,
        optionByKey: Map<String, ScaleOptionScore>
    ): List<ScaleOptionScore> {
        val ids = readLongArray(obj["optionIds"])
        if (ids.isNotEmpty()) {
            return ids.distinct().map { id ->
                optionById[id] ?: throw answerFormatError("optionId=$id not found")
            }
        }
        val keys = readStringArray(obj["optionKeys"])
        if (keys.isNotEmpty()) {
            return keys.distinct().map { key ->
                optionByKey[key] ?: throw answerFormatError("optionKey=$key not found")
            }
        }
        throw answerFormatError("Multi choice answer requires optionIds or optionKeys")
    }

    private fun reverseScore(score: BigDecimal, options: List<ScaleOptionScore>): BigDecimal {
        val values = options.mapNotNull { it.scoreValue }
        if (values.isEmpty()) {
            return score
        }
        val min = values.minOrNull() ?: return score
        val max = values.maxOrNull() ?: return score
        return min + max - score
    }

    private fun JsonElement.requireObject(message: String): JsonObject {
        return this as? JsonObject ?: throw answerFormatError(message)
    }

    private fun normalizeTextAnswer(answer: JsonElement): JsonObject {
        return when (answer) {
            is JsonPrimitive -> {
                if (answer.isString) {
                    val text = answer.content.trim()
                    if (text.isBlank()) {
                        throw answerFormatError("Open answer text must not be blank")
                    }
                    ensureNoControlChars(text)
                    buildJsonObject { put("text", text) }
                } else if (answer.doubleOrNull != null) {
                    buildJsonObject { put("value", answer.doubleOrNull!!) }
                } else {
                    throw answerFormatError("Open answer primitive must be string or number")
                }
            }

            is JsonObject -> {
                val text = objString(answer, "text")?.trim()
                val value = objString(answer, "value")?.trim()
                val score = objNumber(answer, "score")
                if (text.isNullOrBlank() && value.isNullOrBlank() && score == null) {
                    throw answerFormatError("Open answer requires text, value or score")
                }
                if (!text.isNullOrBlank()) {
                    ensureNoControlChars(text)
                }
                if (!value.isNullOrBlank()) {
                    ensureNoControlChars(value)
                }
                buildJsonObject {
                    if (!text.isNullOrBlank()) {
                        put("text", text)
                    }
                    if (!value.isNullOrBlank()) {
                        put("value", value)
                    }
                    if (score != null) {
                        put("score", score)
                    }
                }
            }

            else -> throw answerFormatError("Open answer must be an object, string or number")
        }
    }

    private fun normalizeTimeAnswer(answer: JsonElement): JsonObject {
        val raw = extractOpenValue(answer)
        val normalized = parseTimeToHhmm(raw)
            ?: throw answerFormatError("Invalid time format, expected HH:mm")
        return buildJsonObject {
            put("value", normalized)
            val score = extractOpenAnswerScore(answer)
            if (score != null) {
                put("score", score)
            }
        }
    }

    private fun normalizeDurationAnswer(answer: JsonElement): JsonObject {
        val raw = extractOpenValue(answer)
        val minutes = parseDurationToMinutes(raw)
            ?: throw answerFormatError("Invalid duration format")
        return buildJsonObject {
            put("value", raw)
            put("minutes", minutes)
            put("hours", (minutes.toDouble() / 60.0))
            val score = extractOpenAnswerScore(answer)
            if (score != null) {
                put("score", score)
            }
        }
    }

    private fun extractOpenValue(answer: JsonElement): String {
        return when (answer) {
            is JsonPrimitive -> {
                if (answer.isString) {
                    answer.content.trim()
                } else {
                    answer.content.trim()
                }
            }

            is JsonObject -> {
                val raw = objString(answer, "value")?.trim()
                    ?: objString(answer, "text")?.trim()
                    ?: objNumber(answer, "value")?.toString()
                raw?.takeIf { it.isNotBlank() } ?: throw answerFormatError("Open answer requires value")
            }

            else -> throw answerFormatError("Open answer must be an object, string or number")
        }.also(::ensureNoControlChars)
    }

    private fun extractOpenAnswerScore(answer: JsonElement): Double? {
        return when (answer) {
            is JsonPrimitive -> answer.doubleOrNull
            is JsonObject -> objNumber(answer, "score")
            else -> null
        }
    }

    private fun parseTimeToHhmm(raw: String): String? {
        val normalized = raw.trim()
        timeRegex.matchEntire(normalized)?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            return "%02d:%02d".format(hour, minute)
        }
        chineseTimeRegex.matchEntire(normalized)?.let { match ->
            var hour = match.groupValues[2].toIntOrNull() ?: return null
            val minute = (match.groupValues[3].ifBlank { "0" }).toIntOrNull() ?: return null
            val period = match.groupValues[1]
            if ((period == "下午" || period == "晚上" || period == "中午") && hour in 1..11) {
                hour += 12
            }
            if (period == "凌晨" && hour == 12) {
                hour = 0
            }
            if (hour !in 0..23 || minute !in 0..59) {
                return null
            }
            return "%02d:%02d".format(hour, minute)
        }
        return null
    }

    private fun parseDurationToMinutes(raw: String): Int? {
        val normalized = raw.trim()
        durationHourRegex.matchEntire(normalized)?.let { match ->
            val hours = match.groupValues[1].toDoubleOrNull() ?: return null
            return (hours * 60).roundToInt().coerceAtLeast(0)
        }
        durationMinuteRegex.matchEntire(normalized)?.let { match ->
            val minutes = match.groupValues[1].toDoubleOrNull() ?: return null
            return minutes.roundToInt().coerceAtLeast(0)
        }
        durationClockRegex.matchEntire(normalized)?.let { match ->
            val hours = match.groupValues[1].toIntOrNull() ?: return null
            val minutes = match.groupValues[2].toIntOrNull() ?: return null
            if (minutes !in 0..59) {
                return null
            }
            return (hours * 60 + minutes).coerceAtLeast(0)
        }
        normalized.toDoubleOrNull()?.let { numeric ->
            if (numeric < 0) {
                return null
            }
            return if (numeric <= 24.0) {
                (numeric * 60).roundToInt()
            } else {
                numeric.roundToInt()
            }
        }
        return null
    }

    private fun readLongArray(element: JsonElement?): List<Long> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            (item as? JsonPrimitive)?.doubleOrNull?.toLong()
        }
    }

    private fun readStringArray(element: JsonElement?): List<String> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val value = (item as? JsonPrimitive)?.content?.trim().orEmpty()
            value.takeIf { it.isNotEmpty() }
        }
    }

    private fun objString(obj: JsonObject, key: String): String? {
        val value = obj[key] as? JsonPrimitive ?: return null
        if (!value.isString) {
            return null
        }
        return value.content
    }

    private fun objNumber(obj: JsonObject, key: String): Double? {
        val value = obj[key] as? JsonPrimitive ?: return null
        return value.doubleOrNull
    }

    private fun ensureNoControlChars(value: String) {
        if (value.any { it.isISOControl() }) {
            throw answerFormatError("Answer contains control characters")
        }
    }

    private fun answerFormatError(message: String): AppException {
        return AppException(
            code = ErrorCodes.SCALE_ANSWER_FORMAT_INVALID,
            message = message,
            status = HttpStatusCode.BadRequest
        )
    }
}
