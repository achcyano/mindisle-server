package me.hztcm.mindisle.scale.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.ScaleQuestionType

internal fun enforceOpenTextAnswerLength(
    questionType: ScaleQuestionType,
    normalizedAnswer: JsonElement,
    minChars: Int,
    maxChars: Int
) {
    if (questionType != ScaleQuestionType.TEXT) {
        return
    }
    val normalizedMin = minChars.coerceAtLeast(0)
    val normalizedMax = maxChars.coerceAtLeast(normalizedMin)
    val text = extractOpenTextForLength(normalizedAnswer) ?: return
    val length = text.codePointCount(0, text.length)
    if (length < normalizedMin) {
        throw AppException(
            code = ErrorCodes.SCALE_ANSWER_FORMAT_INVALID,
            message = "Answer length must be at least $normalizedMin",
            status = HttpStatusCode.BadRequest
        )
    }
    if (length > normalizedMax) {
        throw AppException(
            code = ErrorCodes.SCALE_ANSWER_FORMAT_INVALID,
            message = "Answer length must be at most $normalizedMax",
            status = HttpStatusCode.BadRequest
        )
    }
}

private fun extractOpenTextForLength(answer: JsonElement): String? {
    return when (answer) {
        is JsonPrimitive -> {
            if (answer.isString) {
                answer.content.trim().takeIf { it.isNotBlank() }
            } else {
                null
            }
        }

        is JsonObject -> {
            val text = (answer["text"] as? JsonPrimitive)?.content?.trim()
            if (!text.isNullOrBlank()) {
                text
            } else {
                val value = answer["value"] as? JsonPrimitive
                if (value != null && value.isString) {
                    value.content.trim().takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }

        else -> null
    }
}
