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

enum class ScaleStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}

enum class ScaleQuestionType {
    SINGLE_CHOICE,
    MULTI_CHOICE,
    TEXT,
    TIME,
    DURATION,
    YES_NO
}

enum class ScaleScoringMethod {
    SIMPLE_SUM,
    PHQ9,
    GAD7,
    PSQI,
    SCL90,
    EPQ
}

enum class ScaleSessionStatus {
    IN_PROGRESS,
    SUBMITTED,
    ABANDONED
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
    val heightCm = decimal("height_cm", 6, 2).nullable()
    val weightKg = decimal("weight_kg", 5, 2).nullable()
    val waistCm = decimal("waist_cm", 6, 2).nullable()
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

object UserAvatarsTable : Table("user_avatars") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val fileName = varchar("file_name", 255)
    val contentType = varchar("content_type", 64)
    val sizeBytes = long("size_bytes")
    val sha256 = varchar("sha256", 64)
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

object UserDiseaseHistoriesTable : LongIdTable("user_disease_histories") {
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

object ScalesTable : LongIdTable("scales") {
    val code = varchar("code", 64).uniqueIndex()
    val name = varchar("name", 200)
    val description = text("description").nullable()
    val status = enumerationByName("status", 16, ScaleStatus::class).default(ScaleStatus.PUBLISHED)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

object ScaleVersionsTable : LongIdTable("scale_versions") {
    val scaleId = reference("scale_id", ScalesTable, onDelete = ReferenceOption.CASCADE)
    val version = integer("version")
    val status = enumerationByName("status", 16, ScaleStatus::class).default(ScaleStatus.PUBLISHED)
    val publishedAt = datetime("published_at").nullable()
    val configJson = text("config_json").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    init {
        uniqueIndex(scaleId, version)
        index(false, scaleId, status, publishedAt)
    }
}

object ScaleQuestionsTable : LongIdTable("scale_questions") {
    val versionId = reference("version_id", ScaleVersionsTable, onDelete = ReferenceOption.CASCADE)
    val questionKey = varchar("question_key", 64)
    val orderNo = integer("order_no")
    val type = enumerationByName("type", 24, ScaleQuestionType::class)
    val dimension = varchar("dimension", 64).nullable()
    val required = bool("required").default(true)
    val scorable = bool("scorable").default(true)
    val reverseScored = bool("reverse_scored").default(false)
    val stem = text("stem")
    val hint = text("hint").nullable()
    val note = text("note").nullable()
    val optionSetCode = varchar("option_set_code", 64).nullable()
    val metaJson = text("meta_json").nullable()

    init {
        uniqueIndex(versionId, questionKey)
        index(false, versionId, orderNo)
    }
}

object ScaleOptionsTable : LongIdTable("scale_options") {
    val questionId = reference("question_id", ScaleQuestionsTable, onDelete = ReferenceOption.CASCADE)
    val optionKey = varchar("option_key", 64)
    val orderNo = integer("order_no")
    val label = varchar("label", 255)
    val scoreValue = decimal("score_value", 10, 2).nullable()
    val extJson = text("ext_json").nullable()

    init {
        uniqueIndex(questionId, optionKey)
        index(false, questionId, orderNo)
    }
}

object ScaleScoringRulesTable : LongIdTable("scale_scoring_rules") {
    val versionId = reference("version_id", ScaleVersionsTable, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val method = enumerationByName("method", 24, ScaleScoringMethod::class)
    val ruleJson = text("rule_json")
    val createdAt = datetime("created_at")
}

object ScaleResultBandsTable : LongIdTable("scale_result_bands") {
    val versionId = reference("version_id", ScaleVersionsTable, onDelete = ReferenceOption.CASCADE)
    val dimension = varchar("dimension", 64).nullable()
    val minScore = decimal("min_score", 10, 2)
    val maxScore = decimal("max_score", 10, 2)
    val levelCode = varchar("level_code", 64)
    val levelName = varchar("level_name", 100)
    val interpretation = text("interpretation")

    init {
        index(false, versionId, dimension, minScore, maxScore)
    }
}

object UserScaleSessionsTable : LongIdTable("user_scale_sessions") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val scaleId = reference("scale_id", ScalesTable, onDelete = ReferenceOption.RESTRICT)
    val versionId = reference("version_id", ScaleVersionsTable, onDelete = ReferenceOption.RESTRICT)
    val status = enumerationByName("status", 16, ScaleSessionStatus::class)
    val progress = integer("progress").default(0)
    val startedAt = datetime("started_at")
    val updatedAt = datetime("updated_at")
    val submittedAt = datetime("submitted_at").nullable()

    init {
        index(false, userId, status, updatedAt)
        index(false, userId, scaleId, status)
    }
}

object UserScaleAnswersTable : LongIdTable("user_scale_answers") {
    val sessionId = reference("session_id", UserScaleSessionsTable, onDelete = ReferenceOption.CASCADE)
    val questionId = reference("question_id", ScaleQuestionsTable, onDelete = ReferenceOption.RESTRICT)
    val answerJson = text("answer_json")
    val numericScore = decimal("numeric_score", 10, 2).nullable()
    val answeredAt = datetime("answered_at")
    val updatedAt = datetime("updated_at")

    init {
        uniqueIndex(sessionId, questionId)
        index(false, sessionId, updatedAt)
    }
}

object UserScaleResultsTable : LongIdTable("user_scale_results") {
    val sessionId = reference("session_id", UserScaleSessionsTable, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val totalScore = decimal("total_score", 10, 2).nullable()
    val dimensionScoresJson = text("dimension_scores_json").nullable()
    val resultDetailJson = text("result_detail_json").nullable()
    val bandLevelCode = varchar("band_level_code", 64).nullable()
    val resultText = text("result_text").nullable()
    val computedAt = datetime("computed_at")
}
