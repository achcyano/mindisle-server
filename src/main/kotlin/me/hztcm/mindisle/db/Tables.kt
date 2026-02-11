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
