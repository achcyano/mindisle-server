package me.hztcm.mindisle.ai.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.AssistantOptionDto

internal class AiOptionResolver(
    private val deepSeekClient: DeepSeekAliyunClient,
    private val json: Json
) {
    suspend fun resolveOptions(
        userMessage: String,
        assistantAnswer: String,
        primary: List<AssistantOptionDto>?
    ): Pair<List<AssistantOptionDto>, String> {
        if (!primary.isNullOrEmpty()) {
            return primary to "primary"
        }
        val fallback = runCatching {
            generateOptionsWithFallback(userMessage, assistantAnswer)
        }.getOrNull()
        if (!fallback.isNullOrEmpty()) {
            return fallback to "fallback"
        }
        return defaultOptions() to "default"
    }

    fun extractAnswerAndPrimaryOptions(raw: String): Pair<String, List<AssistantOptionDto>?> {
        val trimmed = raw.trim()
        val start = trimmed.indexOf(OPTIONS_START_MARKER)
        val end = trimmed.indexOf(OPTIONS_END_MARKER)
        if (start < 0 || end < 0 || end <= start) {
            return trimmed to null
        }
        val jsonBlock = trimmed.substring(start + OPTIONS_START_MARKER.length, end).trim()
        val options = parseOptionsJson(jsonBlock)
        val answer = (trimmed.substring(0, start) + trimmed.substring(end + OPTIONS_END_MARKER.length)).trim()
        return answer to options
    }

    fun parseStoredOptionsJson(raw: String): List<AssistantOptionDto>? {
        val parsed = runCatching {
            json.decodeFromString<List<AssistantOptionDto>>(raw)
        }.getOrNull() ?: return null
        return parsed.takeIf { it.isNotEmpty() }
    }

    private suspend fun generateOptionsWithFallback(
        userMessage: String,
        assistantAnswer: String
    ): List<AssistantOptionDto> {
        val fallbackMessages = listOf(
            ChatMessage(role = "system", content = AI_FALLBACK_OPTIONS_SYSTEM_PROMPT),
            ChatMessage(
                role = "user",
                content = buildString {
                    appendLine("User message:")
                    appendLine(userMessage.trim())
                    appendLine()
                    appendLine("Assistant answer:")
                    appendLine(assistantAnswer.trim())
                    appendLine()
                    appendLine("Now return options JSON only.")
                }
            )
        )
        val (raw, _) = deepSeekClient.completeTextChat(
            messages = fallbackMessages,
            temperature = 0.2,
            maxTokens = 256
        )
        val parsed = parseOptionsFromAnyText(raw)
        return parsed ?: throw AppException(
            code = ErrorCodes.AI_OPTIONS_FALLBACK_FAILED,
            message = "Fallback options generation failed",
            status = HttpStatusCode.BadGateway
        )
    }

    private fun parseOptionsFromAnyText(raw: String): List<AssistantOptionDto>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val fromMarkers = extractAnswerAndPrimaryOptions(trimmed).second
        if (!fromMarkers.isNullOrEmpty()) {
            return fromMarkers
        }
        parseOptionsJson(trimmed)?.let { return it }
        val firstBrace = trimmed.indexOf("{")
        val lastBrace = trimmed.lastIndexOf("}")
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return parseOptionsJson(trimmed.substring(firstBrace, lastBrace + 1))
        }
        return null
    }

    private fun parseOptionsJson(rawJson: String): List<AssistantOptionDto>? {
        val parsed = runCatching {
            json.decodeFromString<OptionBlock>(rawJson)
        }.getOrNull() ?: return null
        return normalizeOptions(parsed.items)
    }

    private fun normalizeOptions(items: List<OptionDraft>): List<AssistantOptionDto>? {
        if (items.isEmpty()) {
            return null
        }
        val distinct = linkedSetOf<String>()
        val normalized = mutableListOf<OptionDraft>()
        for (item in items) {
            val label = item.label.trim()
            if (label.isEmpty()) {
                continue
            }
            if (label.any { it.isISOControl() }) {
                continue
            }
            if (label.codePointCount(0, label.length) > OPTION_LABEL_MAX_CHARS) {
                continue
            }
            if (!distinct.add(label)) {
                continue
            }
            normalized += OptionDraft(label = label)
            if (normalized.size == OPTIONS_REQUIRED_COUNT) {
                break
            }
        }
        if (normalized.size < OPTIONS_REQUIRED_COUNT) {
            return null
        }
        return normalized.mapIndexed { index, option ->
            AssistantOptionDto(
                id = "opt_${index + 1}",
                label = option.label
            )
        }
    }

    private fun defaultOptions(): List<AssistantOptionDto> {
        return listOf(
            AssistantOptionDto(
                id = "opt_1",
                label = "💡 请继续解释"
            ),
            AssistantOptionDto(
                id = "opt_2",
                label = "🧭 帮我做总结"
            ),
            AssistantOptionDto(
                id = "opt_3",
                label = "✅ 下一步怎么做"
            )
        )
    }
}

@Serializable
private data class OptionDraft(
    val label: String = ""
)

@Serializable
private data class OptionBlock(
    val items: List<OptionDraft> = emptyList()
)
