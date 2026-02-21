package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateConversationRequest(
    val title: String? = null
)

@Serializable
data class CreateConversationResponse(
    val conversationId: Long,
    val title: String? = null,
    val createdAt: String
)

@Serializable
data class ConversationListItem(
    val conversationId: Long,
    val title: String? = null,
    val summary: String? = null,
    val lastMessageAt: String,
    val createdAt: String
)

@Serializable
data class ListConversationsResponse(
    val items: List<ConversationListItem>,
    val nextCursor: String? = null
)

@Serializable
enum class AiMessageRoleDto {
    SYSTEM,
    USER,
    ASSISTANT
}

@Serializable
data class MessageListItem(
    val messageId: Long,
    val role: AiMessageRoleDto,
    val content: String,
    val generationId: String? = null,
    val createdAt: String
)

@Serializable
data class ListMessagesResponse(
    val items: List<MessageListItem>,
    val nextBefore: String? = null
)

@Serializable
data class StreamChatRequest(
    val userMessage: String,
    val clientMessageId: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null
)

@Serializable
data class StreamMetaEvent(
    val generationId: String,
    val conversationId: Long,
    val model: String,
    val createdAt: String
)

@Serializable
data class StreamDeltaEvent(
    val text: String
)

@Serializable
data class StreamUsageEvent(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)

@Serializable
data class StreamDoneEvent(
    val assistantMessageId: Long? = null,
    val finishReason: String? = null
)

@Serializable
data class StreamErrorEvent(
    val code: Int,
    val message: String
)
