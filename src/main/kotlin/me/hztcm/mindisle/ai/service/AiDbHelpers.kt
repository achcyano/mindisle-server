package me.hztcm.mindisle.ai.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.AiConversationsTable
import me.hztcm.mindisle.db.AiGenerationStatus
import me.hztcm.mindisle.db.AiGenerationsTable
import me.hztcm.mindisle.db.AiMessageRole
import me.hztcm.mindisle.db.AiMessagesTable
import me.hztcm.mindisle.model.AiMessageRoleDto
import me.hztcm.mindisle.util.generateSecureToken
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.Transaction
import java.time.LocalDateTime
import java.time.ZoneOffset

internal fun Transaction.createGenerationForMessage(
    userRef: EntityID<Long>,
    conversationRef: EntityID<Long>,
    messageId: EntityID<Long>,
    requestPayloadJson: String,
    now: LocalDateTime
): String {
    val generationId = generateSecureToken(24)
    AiGenerationsTable.insert {
        it[AiGenerationsTable.generationId] = generationId
        it[AiGenerationsTable.userId] = userRef
        it[AiGenerationsTable.conversationId] = conversationRef
        it[status] = AiGenerationStatus.RUNNING
        it[AiGenerationsTable.requestPayloadJson] = requestPayloadJson
        it[errorCode] = null
        it[errorMessage] = null
        it[startedAt] = now
        it[completedAt] = null
    }
    AiMessagesTable.update({ AiMessagesTable.id eq messageId }) {
        it[AiMessagesTable.generationId] = generationId
    }
    AiConversationsTable.update({ AiConversationsTable.id eq conversationRef }) {
        it[updatedAt] = now
        it[lastMessageAt] = now
    }
    return generationId
}

internal fun Transaction.getOwnedConversation(userId: Long, conversationId: Long): ResultRow {
    val row = AiConversationsTable.selectAll().where {
        AiConversationsTable.id eq EntityID(conversationId, AiConversationsTable)
    }.firstOrNull()
        ?: throw AppException(
            code = ErrorCodes.AI_CONVERSATION_NOT_FOUND,
            message = "Conversation not found",
            status = HttpStatusCode.NotFound
        )

    if (row[AiConversationsTable.userId].value != userId) {
        throw AppException(
            code = ErrorCodes.AI_CONVERSATION_FORBIDDEN,
            message = "Conversation does not belong to current user",
            status = HttpStatusCode.Forbidden
        )
    }
    return row
}

internal fun AiMessageRole.toDto(): AiMessageRoleDto = when (this) {
    AiMessageRole.SYSTEM -> AiMessageRoleDto.SYSTEM
    AiMessageRole.USER -> AiMessageRoleDto.USER
    AiMessageRole.ASSISTANT -> AiMessageRoleDto.ASSISTANT
}

internal fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

internal fun LocalDateTime.toIsoInstant(): String = atOffset(ZoneOffset.UTC).toInstant().toString()
