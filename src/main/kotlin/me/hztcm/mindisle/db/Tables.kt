package me.hztcm.mindisle.db

import me.hztcm.mindisle.model.Gender
import me.hztcm.mindisle.model.SmsPurpose
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

enum class SessionStatus {
    ACTIVE,
    REVOKED
}

enum class AiMessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}

enum class AiGenerationStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

object UsersTable : LongIdTable("users") {
    val phone = varchar("phone", 20).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val lastLoginAt = datetime("last_login_at").nullable()
}

object UserProfilesTable : Table("user_profiles") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val fullName = varchar("full_name", 200).nullable()
    val gender = enumerationByName("gender", 16, Gender::class).default(Gender.UNKNOWN)
    val birthDate = date("birth_date").nullable()
    val weightKg = decimal("weight_kg", 5, 2).nullable()
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

object UserFamilyHistoriesTable : LongIdTable("user_family_histories") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val item = varchar("item", 512)
    val createdAt = datetime("created_at")
}

object UserMedicalHistoriesTable : LongIdTable("user_medical_histories") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val item = varchar("item", 512)
    val createdAt = datetime("created_at")
}

object UserMedicationHistoriesTable : LongIdTable("user_medication_histories") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val item = varchar("item", 512)
    val createdAt = datetime("created_at")
}

object SmsVerificationCodesTable : LongIdTable("sms_verification_codes") {
    val phone = varchar("phone", 20)
    val purpose = enumerationByName("purpose", 32, SmsPurpose::class)
    val codeHash = varchar("code_hash", 64)
    val expiresAt = datetime("expires_at")
    val consumedAt = datetime("consumed_at").nullable()
    val createdAt = datetime("created_at")
    val requestIp = varchar("request_ip", 45).nullable()

    init {
        index(false, phone, createdAt)
        index(false, phone, purpose, createdAt)
        index(false, phone, purpose, consumedAt)
    }
}

object SmsVerificationAttemptsTable : LongIdTable("sms_verification_attempts") {
    val phone = varchar("phone", 20)
    val purpose = enumerationByName("purpose", 32, SmsPurpose::class)
    val success = bool("success")
    val createdAt = datetime("created_at")

    init {
        index(false, phone, purpose, createdAt)
        index(false, phone, purpose, success, createdAt)
    }
}

object UserSessionsTable : LongIdTable("user_sessions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("device_id", 128)
    val refreshTokenHash = varchar("refresh_token_hash", 64).uniqueIndex()
    val status = enumerationByName("status", 16, SessionStatus::class)
    val createdAt = datetime("created_at")
    val lastUsedAt = datetime("last_used_at")
    val expiresAt = datetime("expires_at")
    val revokedAt = datetime("revoked_at").nullable()

    init {
        uniqueIndex(userId, deviceId)
        index(false, userId, deviceId, status)
    }
}

object LoginTicketsTable : LongIdTable("login_tickets") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val phone = varchar("phone", 20)
    val deviceId = varchar("device_id", 128)
    val ticketHash = varchar("ticket_hash", 64).uniqueIndex()
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")
    val consumedAt = datetime("consumed_at").nullable()

    init {
        index(false, userId, deviceId, expiresAt)
    }
}

object AiConversationsTable : LongIdTable("ai_conversations") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 100).nullable()
    val summary = text("summary").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val lastMessageAt = datetime("last_message_at")

    init {
        index(false, userId, lastMessageAt)
    }
}

object AiMessagesTable : LongIdTable("ai_messages") {
    val conversationId = reference("conversation_id", AiConversationsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val role = enumerationByName("role", 16, AiMessageRole::class)
    val content = text("content")
    val optionsJson = text("options_json").nullable()
    val clientMessageId = varchar("client_message_id", 128).nullable()
    val generationId = varchar("generation_id", 64).nullable()
    val tokenCount = integer("token_count").nullable()
    val createdAt = datetime("created_at")

    init {
        uniqueIndex("uq_ai_user_conv_client_msg", userId, conversationId, clientMessageId)
        index(false, conversationId, createdAt)
        index(false, generationId)
    }
}

object AiGenerationsTable : Table("ai_generations") {
    val generationId = varchar("generation_id", 64)
    val conversationId = reference("conversation_id", AiConversationsTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val status = enumerationByName("status", 16, AiGenerationStatus::class)
    val requestPayloadJson = text("request_payload_json")
    val errorCode = integer("error_code").nullable()
    val errorMessage = varchar("error_message", 1000).nullable()
    val startedAt = datetime("started_at")
    val completedAt = datetime("completed_at").nullable()

    override val primaryKey = PrimaryKey(generationId)

    init {
        index(false, userId, startedAt)
        index(false, conversationId, startedAt)
    }
}

object AiStreamEventsTable : LongIdTable("ai_stream_events") {
    val generationId = varchar("generation_id", 64)
    val seq = long("seq")
    val eventType = varchar("event_type", 16)
    val eventJson = text("event_json")
    val createdAt = datetime("created_at")

    init {
        uniqueIndex(generationId, seq)
        index(false, generationId, createdAt)
    }
}
