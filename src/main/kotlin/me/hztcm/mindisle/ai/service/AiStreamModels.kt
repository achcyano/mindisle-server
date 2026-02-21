package me.hztcm.mindisle.ai.service

import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.db.AiGenerationStatus

data class StreamEventRecord(
    val generationId: String,
    val seq: Long,
    val eventType: String,
    val eventJson: String
) {
    val eventId: String get() = "$generationId:$seq"

    fun isTerminal(): Boolean = eventType == EVENT_DONE || eventType == EVENT_ERROR
}

data class ReplayEventsResult(
    val generationId: String,
    val events: List<StreamEventRecord>,
    val terminalStatus: Boolean
)

data class GenerationOwnership(
    val generationId: String,
    val conversationId: Long,
    val status: AiGenerationStatus
)

internal data class GenerationContext(
    val conversationId: Long,
    val currentUserMessage: String,
    val temperature: Double?,
    val maxTokens: Int?,
    val messages: List<ChatMessage>
)
