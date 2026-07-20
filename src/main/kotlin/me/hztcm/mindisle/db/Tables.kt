package me.hztcm.mindisle.db

import me.hztcm.mindisle.model.Gender
import me.hztcm.mindisle.model.MedicationDoseUnit
import me.hztcm.mindisle.model.MedicationStrengthUnit
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
    YES_NO,
    TESS_ITEM
}

enum class ScaleScoringMethod {
    SIMPLE_SUM,
    PHQ9,
    GAD7,
    PSQI,
    SCL90,
    EPQ,
    TESS
}

enum class ScaleSessionStatus {
    IN_PROGRESS,
    SUBMITTED,
    ABANDONED
}

enum class DoctorPatientBindingStatus {
    ACTIVE,
    UNBOUND
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
    val usesTcm = bool("uses_tcm").default(false)
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

object DoctorsTable : LongIdTable("doctors") {
    val phone = varchar("phone", 20).uniqueIndex()
    val fullName = varchar("full_name", 200)
    val hospital = varchar("hospital", 200).nullable()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    val lastLoginAt = datetime("last_login_at").nullable()
}

object DoctorSessionsTable : LongIdTable("doctor_sessions") {
    val doctorId = reference("doctor_id", DoctorsTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("device_id", 128)
    val refreshTokenHash = varchar("refresh_token_hash", 64).uniqueIndex()
    val status = enumerationByName("status", 16, SessionStatus::class)
    val createdAt = datetime("created_at")
    val lastUsedAt = datetime("last_used_at")
    val expiresAt = datetime("expires_at")
    val revokedAt = datetime("revoked_at").nullable()

    init {
        uniqueIndex(doctorId, deviceId)
        index(false, doctorId, deviceId, status)
    }
}

object DoctorLoginTicketsTable : LongIdTable("doctor_login_tickets") {
    val doctorId = reference("doctor_id", DoctorsTable, onDelete = ReferenceOption.CASCADE)
    val phone = varchar("phone", 20)
    val deviceId = varchar("device_id", 128)
    val ticketHash = varchar("ticket_hash", 64).uniqueIndex()
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")
    val consumedAt = datetime("consumed_at").nullable()

    init {
        index(false, doctorId, deviceId, expiresAt)
    }
}

object DoctorThresholdSettingsTable : Table("doctor_threshold_settings") {
    val doctorId = reference("doctor_id", DoctorsTable, onDelete = ReferenceOption.CASCADE)
    val scl90Threshold = decimal("scl90_threshold", 10, 2).nullable()
    val phq9Threshold = decimal("phq9_threshold", 10, 2).nullable()
    val gad7Threshold = decimal("gad7_threshold", 10, 2).nullable()
    val psqiThreshold = decimal("psqi_threshold", 10, 2).nullable()
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(doctorId)
}

object DoctorBindingCodesTable : LongIdTable("doctor_binding_codes") {
    val doctorId = reference("doctor_id", DoctorsTable, onDelete = ReferenceOption.CASCADE)
    val codeHash = varchar("code_hash", 64)
    val expiresAt = datetime("expires_at")
    val consumedAt = datetime("consumed_at").nullable()
    val createdAt = datetime("created_at")
    val qrPayload = varchar("qr_payload", 255)

    init {
        index(false, doctorId, createdAt)
        index(false, codeHash, expiresAt, consumedAt)
    }
}

object DoctorPatientBindingsTable : LongIdTable("doctor_patient_bindings") {
    val patientUserId = reference("patient_user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val doctorId = reference("doctor_id", DoctorsTable, onDelete = ReferenceOption.CASCADE)
    val status = enumerationByName("status", 16, DoctorPatientBindingStatus::class)
    val severityGroup = varchar("severity_group", 64).nullable()
    val diagnosis = varchar("diagnosis", 512).nullable()
    val treatmentPhase = varchar("treatment_phase", 64).nullable()
    val boundAt = datetime("bound_at")
    val unboundAt = datetime("unbound_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    init {
        index(false, patientUserId, status, updatedAt)
        index(false, doctorId, status, updatedAt)
    }
}

object DoctorPatientGroupsTable : LongIdTable("doctor_patient_groups") {
    val doctorId = reference("doctor_id", DoctorsTable, onDelete = ReferenceOption.CASCADE)
    val severityGroup = varchar("severity_group", 64)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    init {
        uniqueIndex(doctorId, severityGroup)
        index(false, doctorId, updatedAt)
    }
}

object DoctorPatientGroupChangesTable : LongIdTable("doctor_patient_group_changes") {
    val bindingId = reference("binding_id", DoctorPatientBindingsTable, onDelete = ReferenceOption.CASCADE)
    val fieldName = varchar("field_name", 32)
    val oldValue = varchar("old_value", 64).nullable()
    val newValue = varchar("new_value", 64).nullable()
    val changedByDoctorId = optReference("changed_by_doctor_id", DoctorsTable, onDelete = ReferenceOption.SET_NULL)
    val reason = varchar("reason", 512).nullable()
    val changedAt = datetime("changed_at")

    init {
        index(false, bindingId, changedAt)
        index(false, changedByDoctorId, changedAt)
    }
}

object DoctorPatientAssessmentReportsTable : LongIdTable("doctor_patient_assessment_reports") {
    val doctorId = reference("doctor_id", DoctorsTable, onDelete = ReferenceOption.CASCADE)
    val patientUserId = reference("patient_user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val templateReport = text("template_report")
    val report = text("report")
    val polished = bool("polished").default(false)
    val model = varchar("model", 128).nullable()
    val days = integer("days")
    val generatedAt = datetime("generated_at")
    val createdAt = datetime("created_at")

    init {
        index(false, doctorId, patientUserId, generatedAt)
        index(false, patientUserId, generatedAt)
    }
}

object UserDoctorBindingsTable : Table("user_doctor_bindings") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val isBound = bool("is_bound").default(false)
    val boundAt = datetime("bound_at").nullable()
    val unboundAt = datetime("unbound_at").nullable()
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

object UserMedicationsTable : LongIdTable("user_medications") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val drugName = varchar("drug_name", 200)
    val doseTimesJson = text("dose_times_json")
    val recordedDateLocal = date("recorded_date_local")
    val endDateLocal = date("end_date_local")
    val doseAmount = decimal("dose_amount", 10, 3)
    val doseUnit = enumerationByName("dose_unit", 16, MedicationDoseUnit::class)
    val tabletStrengthAmount = decimal("tablet_strength_amount", 10, 3).nullable()
    val tabletStrengthUnit = enumerationByName("tablet_strength_unit", 16, MedicationStrengthUnit::class).nullable()
    val deletedAt = datetime("deleted_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    init {
        index(false, userId, endDateLocal, updatedAt)
        index(false, userId, createdAt)
        index(false, userId, deletedAt, updatedAt)
    }
}

object UserSideEffectsTable : LongIdTable("user_side_effects") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val symptom = varchar("symptom", 200)
    val severity = integer("severity")
    val note = varchar("note", 1000).nullable()
    val recordedAt = datetime("recorded_at")
    val createdAt = datetime("created_at")

    init {
        index(false, userId, recordedAt)
    }
}

object UserWeightLogsTable : LongIdTable("user_weight_logs") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val weightKg = decimal("weight_kg", 5, 2)
    val recordedAt = datetime("recorded_at")
    val sourceType = varchar("source", 32)
    val createdAt = datetime("created_at")

    init {
        index(false, userId, recordedAt)
    }
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

object UserScaleAnswerRecordsTable : LongIdTable("user_scale_answer_records") {
    val sessionId = reference("session_id", UserScaleSessionsTable, onDelete = ReferenceOption.CASCADE)
    val questionId = reference("question_id", ScaleQuestionsTable, onDelete = ReferenceOption.RESTRICT)
    val rawAnswerJson = text("raw_answer_json")
    val normalizedAnswerJson = text("normalized_answer_json")
    val numericScore = decimal("numeric_score", 10, 2).nullable()
    val answeredAt = datetime("answered_at")

    init {
        index(false, sessionId, questionId, answeredAt)
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

enum class EmaSlot {
    MORNING,
    EVENING,
    ADHOC
}

enum class EmaActivityLevel {
    LOW,
    MID,
    HIGH
}

enum class DoseLogStatus {
    TAKEN,
    SKIPPED,
    MISSED
}

enum class SeverityLevel {
    NONE,
    MILD,
    MODERATE,
    SEVERE
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class UiTaskType {
    EMA,
    SCALE,
    INTERVENTION,
    SIDE_EFFECT,
    SAFETY
}

enum class UiTaskStatus {
    PENDING,
    DONE,
    DISMISSED,
    EXPIRED
}

enum class InterventionDeliveryStatus {
    PENDING,
    SHOWN,
    STARTED,
    COMPLETED,
    DISMISSED
}

enum class SafetyAlertStatus {
    OPEN,
    ACKED,
    RESOLVED
}

object EmaEntriesTable : LongIdTable("ema_entries") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val localDate = date("local_date")
    val slot = enumerationByName("slot", 16, EmaSlot::class)
    val mood = integer("mood")
    val sleepQuality = integer("sleep_quality").nullable()
    val activity = enumerationByName("activity", 8, EmaActivityLevel::class).nullable()
    val socialContact = integer("social_contact").nullable()
    val stressText = text("stress_text").nullable()
    val eventTagsJson = text("event_tags_json").nullable()
    val bodyTagsJson = text("body_tags_json").nullable()
    val note = text("note").nullable()
    val responseLatencyMs = long("response_latency_ms").nullable()
    val submittedAt = datetime("submitted_at")

    init {
        uniqueIndex(userId, localDate, slot)
        index(false, userId, localDate)
    }
}

object MedicationDoseLogsTable : LongIdTable("medication_dose_logs") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val medicationId = reference("medication_id", UserMedicationsTable, onDelete = ReferenceOption.CASCADE)
    val localDate = date("local_date")
    val plannedTime = varchar("planned_time", 8)
    val status = enumerationByName("status", 16, DoseLogStatus::class)
    val actedAt = datetime("acted_at")
    val note = varchar("note", 255).nullable()

    init {
        uniqueIndex(userId, medicationId, localDate, plannedTime)
        index(false, userId, localDate)
    }
}

object PatientStateSnapshotsTable : LongIdTable("patient_state_snapshots") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val lowMood = enumerationByName("low_mood", 16, SeverityLevel::class)
    val anxiety = enumerationByName("anxiety", 16, SeverityLevel::class)
    val rumination = enumerationByName("rumination", 16, SeverityLevel::class)
    val sleepDisturbance = enumerationByName("sleep_disturbance", 16, SeverityLevel::class)
    val reducedActivity = enumerationByName("reduced_activity", 16, SeverityLevel::class)
    val socialWithdrawal = enumerationByName("social_withdrawal", 16, SeverityLevel::class)
    val medicationDistress = enumerationByName("medication_distress", 16, SeverityLevel::class)
    val riskLevel = enumerationByName("risk_level", 16, RiskLevel::class)
    val featureJson = text("feature_json").nullable()
    val snapshotSource = varchar("snapshot_source", 64).default("RULE_ENGINE")
    val createdAt = datetime("created_at")

    init {
        index(false, userId, createdAt)
    }
}

object AiNlpFeaturesTable : LongIdTable("ai_nlp_features") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val conversationId = long("conversation_id").nullable()
    val messageId = long("message_id").nullable()
    val polarity = double("polarity").nullable()
    val negativeIntensity = double("negative_intensity").nullable()
    val anxietyIntensity = double("anxiety_intensity").nullable()
    val ruminationHit = bool("rumination_hit").default(false)
    val hopelessHit = bool("hopeless_hit").default(false)
    val anhedoniaHit = bool("anhedonia_hit").default(false)
    val riskHit = bool("risk_hit").default(false)
    val topicsJson = text("topics_json").nullable()
    val rawJson = text("raw_json").nullable()
    val createdAt = datetime("created_at")

    init {
        index(false, userId, createdAt)
        index(false, conversationId, createdAt)
    }
}

object InterventionModulesTable : LongIdTable("intervention_modules") {
    val code = varchar("code", 64).uniqueIndex()
    val title = varchar("title", 200)
    val category = varchar("category", 64)
    val summary = text("summary").nullable()
    val durationMinutes = integer("duration_minutes").default(5)
    val contentJson = text("content_json")
    val active = bool("active").default(true)
    val createdAt = datetime("created_at")
}

object InterventionDeliveriesTable : LongIdTable("intervention_deliveries") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val moduleCode = varchar("module_code", 64)
    val triggerType = varchar("trigger_type", 32)
    val stateDimsJson = text("state_dims_json").nullable()
    val status = enumerationByName("status", 16, InterventionDeliveryStatus::class)
    val conversationId = long("conversation_id").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    init {
        index(false, userId, status, createdAt)
        index(false, userId, moduleCode, createdAt)
    }
}

object InterventionFeedbackTable : LongIdTable("intervention_feedback") {
    val deliveryId = reference("delivery_id", InterventionDeliveriesTable, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val adopted = bool("adopted").default(false)
    val completed = bool("completed").default(false)
    val durationSec = integer("duration_sec").nullable()
    val moodBefore = integer("mood_before").nullable()
    val moodAfter = integer("mood_after").nullable()
    val createdAt = datetime("created_at")
}

object UiTasksTable : LongIdTable("ui_tasks") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val taskType = enumerationByName("task_type", 24, UiTaskType::class)
    val title = varchar("title", 200)
    val status = enumerationByName("status", 16, UiTaskStatus::class).default(UiTaskStatus.PENDING)
    val payloadJson = text("payload_json").nullable()
    val taskSource = varchar("task_source", 64).default("SYSTEM")
    val dueAt = datetime("due_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    init {
        index(false, userId, status, createdAt)
    }
}

object SafetyAlertsTable : LongIdTable("safety_alerts") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val doctorId = long("doctor_id").nullable()
    val riskLevel = enumerationByName("risk_level", 16, RiskLevel::class)
    val reasonCodesJson = text("reason_codes_json")
    val evidenceJson = text("evidence_json").nullable()
    val status = enumerationByName("status", 16, SafetyAlertStatus::class).default(SafetyAlertStatus.OPEN)
    val ackNote = text("ack_note").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    init {
        index(false, userId, status, createdAt)
        index(false, doctorId, status, createdAt)
    }
}

object AppUsageEventsTable : LongIdTable("app_usage_events") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val eventType = varchar("event_type", 64)
    val payloadJson = text("payload_json").nullable()
    val createdAt = datetime("created_at")

    init {
        index(false, userId, createdAt)
    }
}

object InterventionMatchWeightsTable : LongIdTable("intervention_match_weights") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val stateDim = varchar("state_dim", 32)
    val moduleCode = varchar("module_code", 64)
    val weight = double("weight").default(1.0)
    val updatedAt = datetime("updated_at")

    init {
        uniqueIndex(userId, stateDim, moduleCode)
        index(false, userId, moduleCode)
    }
}

enum class ResearchArm {
    ADAPTIVE,
    USUAL_CARE
}

enum class ResearchEnrollmentStatus {
    SCREENING,
    ENROLLED,
    WITHDRAWN,
    COMPLETED
}

enum class ResearchAeSeverity {
    MILD,
    MODERATE,
    SEVERE,
    SAE
}

object ResearchEnrollmentsTable : LongIdTable("research_enrollments") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val arm = enumerationByName("arm", 16, ResearchArm::class).nullable()
    val status = enumerationByName("status", 16, ResearchEnrollmentStatus::class)
        .default(ResearchEnrollmentStatus.SCREENING)
    val consentAt = datetime("consent_at").nullable()
    val randomizedAt = datetime("randomized_at").nullable()
    val baselinePhq9 = double("baseline_phq9").nullable()
    val inclusionNotes = text("inclusion_notes").nullable()
    val exclusionHit = bool("exclusion_hit").default(false)
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

object ResearchVisitsTable : LongIdTable("research_visits") {
    val enrollmentId = reference("enrollment_id", ResearchEnrollmentsTable, onDelete = ReferenceOption.CASCADE)
    val visitCode = varchar("visit_code", 32)
    val windowStart = datetime("window_start").nullable()
    val windowEnd = datetime("window_end").nullable()
    val completedAt = datetime("completed_at").nullable()
    val instrumentsJson = text("instruments_json").nullable()
    val createdAt = datetime("created_at")

    init {
        uniqueIndex(enrollmentId, visitCode)
        index(false, enrollmentId, visitCode)
    }
}

object ResearchAeEventsTable : LongIdTable("research_ae_events") {
    val enrollmentId = reference("enrollment_id", ResearchEnrollmentsTable, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 200)
    val severity = enumerationByName("severity", 16, ResearchAeSeverity::class)
    val description = text("description").nullable()
    val onsetAt = datetime("onset_at")
    val resolvedAt = datetime("resolved_at").nullable()
    val reportedToEthicsAt = datetime("reported_to_ethics_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    init {
        index(false, enrollmentId, createdAt)
    }
}

object ResearchQcReviewsTable : LongIdTable("research_qc_reviews") {
    val enrollmentId = reference("enrollment_id", ResearchEnrollmentsTable, onDelete = ReferenceOption.CASCADE)
    val conversationId = long("conversation_id").nullable()
    val reviewer = varchar("reviewer", 120)
    val appropriatenessScore = integer("appropriateness_score").nullable()
    val notes = text("notes").nullable()
    val createdAt = datetime("created_at")

    init {
        index(false, enrollmentId, createdAt)
    }
}

