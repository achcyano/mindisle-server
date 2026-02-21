package me.hztcm.mindisle.ai.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.config.LlmConfig
import me.hztcm.mindisle.model.StreamChatRequest

internal object AiRequestValidators {

    fun validateStreamChatRequest(request: StreamChatRequest, config: LlmConfig) {
        if (request.userMessage.isBlank()) {
            throw invalidArgument("userMessage is required")
        }
        if (request.userMessage.length > config.maxUserMessageChars) {
            throw invalidArgument("userMessage exceeds ${config.maxUserMessageChars} characters")
        }
        if (request.clientMessageId.isBlank()) {
            throw invalidArgument("clientMessageId is required")
        }
        if (request.clientMessageId.length > config.maxClientMessageIdChars) {
            throw invalidArgument("clientMessageId exceeds ${config.maxClientMessageIdChars} characters")
        }
        if (request.clientMessageId.any { it.isISOControl() } || request.userMessage.any { it.isISOControl() }) {
            throw invalidArgument("request contains control characters")
        }
        if (request.temperature != null && request.temperature !in 0.0..2.0) {
            throw invalidArgument("temperature must be in [0, 2]")
        }
        if (request.maxTokens != null && request.maxTokens !in 1..8192) {
            throw invalidArgument("maxTokens must be in [1, 8192]")
        }
    }

    fun validateTitle(title: String?) {
        val value = title ?: return
        if (value.length > 100) {
            throw invalidArgument("title exceeds 100 characters")
        }
        if (value.any { it.isISOControl() }) {
            throw invalidArgument("title contains control characters")
        }
    }

    fun parseCursor(raw: String?, name: String): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return raw.toLongOrNull() ?: throw invalidArgument("$name must be a valid long value")
    }

    private fun invalidArgument(message: String): AppException {
        return AppException(
            code = ErrorCodes.AI_INVALID_ARGUMENT,
            message = message,
            status = HttpStatusCode.BadRequest
        )
    }
}
