package me.hztcm.mindisle.doctor.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.auth.invalidRequest
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DoctorSessionsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.SessionStatus
import me.hztcm.mindisle.db.SmsVerificationAttemptsTable
import me.hztcm.mindisle.db.SmsVerificationCodesTable
import me.hztcm.mindisle.model.DoctorTokenPairResponse
import me.hztcm.mindisle.model.SmsPurpose
import me.hztcm.mindisle.util.generateSecureToken
import me.hztcm.mindisle.util.sha256Hex
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime

internal class DoctorAuthPersistenceSupport(private val deps: DoctorServiceDeps) {
    fun issueDoctorTokenPair(
        tx: org.jetbrains.exposed.sql.Transaction,
        doctorId: EntityID<Long>,
        deviceId: String,
        now: LocalDateTime
    ): DoctorTokenPairResponse {
        val refreshToken = generateSecureToken()
        val refreshHash = sha256Hex(refreshToken)
        val expiresAt = now.plusSeconds(deps.authConfig.refreshTokenTtlSeconds)
        with(tx) {
            val existing = DoctorSessionsTable.selectAll().where {
                (DoctorSessionsTable.doctorId eq doctorId) and (DoctorSessionsTable.deviceId eq deviceId)
            }.firstOrNull()

            if (existing == null) {
                DoctorSessionsTable.insert {
                    it[DoctorSessionsTable.doctorId] = doctorId
                    it[DoctorSessionsTable.deviceId] = deviceId
                    it[DoctorSessionsTable.refreshTokenHash] = refreshHash
                    it[DoctorSessionsTable.status] = SessionStatus.ACTIVE
                    it[DoctorSessionsTable.createdAt] = now
                    it[DoctorSessionsTable.lastUsedAt] = now
                    it[DoctorSessionsTable.expiresAt] = expiresAt
                    it[DoctorSessionsTable.revokedAt] = null
                }
            } else {
                DoctorSessionsTable.update({ DoctorSessionsTable.id eq existing[DoctorSessionsTable.id] }) {
                    it[DoctorSessionsTable.refreshTokenHash] = refreshHash
                    it[DoctorSessionsTable.status] = SessionStatus.ACTIVE
                    it[DoctorSessionsTable.lastUsedAt] = now
                    it[DoctorSessionsTable.expiresAt] = expiresAt
                    it[DoctorSessionsTable.revokedAt] = null
                }
            }
        }
        val (accessToken, accessTtl) = deps.jwtService.generateDoctorAccessToken(doctorId.value, deviceId)
        return DoctorTokenPairResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresInSeconds = accessTtl,
            refreshTokenExpiresInSeconds = deps.authConfig.refreshTokenTtlSeconds
        )
    }

    fun validateDoctorSmsBusinessRules(
        tx: org.jetbrains.exposed.sql.Transaction,
        phone: String,
        purpose: SmsPurpose,
        now: LocalDateTime
    ) {
        with(tx) {
            when (purpose) {
                SmsPurpose.DOCTOR_REGISTER -> {
                    val exists = DoctorsTable.selectAll().where { DoctorsTable.phone eq phone }.any()
                    if (exists) {
                        throw AppException(
                            code = ErrorCodes.PHONE_ALREADY_REGISTERED,
                            message = "Phone already registered",
                            status = HttpStatusCode.Conflict
                        )
                    }
                }

                SmsPurpose.DOCTOR_RESET_PASSWORD -> {
                    val exists = DoctorsTable.selectAll().where { DoctorsTable.phone eq phone }.any()
                    if (!exists) {
                        throw AppException(
                            code = ErrorCodes.PHONE_NOT_REGISTERED,
                            message = "Phone is not registered",
                            status = HttpStatusCode.NotFound
                        )
                    }
                }

                else -> throw invalidRequest("Unsupported sms purpose in doctor auth flow")
            }

            val latest = SmsVerificationCodesTable.selectAll().where {
                SmsVerificationCodesTable.phone eq phone
            }.orderBy(SmsVerificationCodesTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
            if (latest != null) {
                val latestAt = latest[SmsVerificationCodesTable.createdAt]
                if (latestAt.plusSeconds(deps.authConfig.smsCooldownSeconds) > now) {
                    throw AppException(
                        code = ErrorCodes.SMS_TOO_FREQUENT,
                        message = "Sms code requested too frequently",
                        status = HttpStatusCode.TooManyRequests
                    )
                }
            }
            val dayStart = now.toLocalDate().atStartOfDay()
            val todayCount = SmsVerificationCodesTable.selectAll().where {
                (SmsVerificationCodesTable.phone eq phone) and
                    (SmsVerificationCodesTable.createdAt greaterEq dayStart)
            }.count()
            if (todayCount >= deps.authConfig.smsDailyLimit) {
                throw AppException(
                    code = ErrorCodes.SMS_DAILY_LIMIT,
                    message = "Daily sms code limit reached",
                    status = HttpStatusCode.TooManyRequests
                )
            }
        }
    }

    fun consumeSmsCode(
        tx: org.jetbrains.exposed.sql.Transaction,
        phone: String,
        purpose: SmsPurpose,
        code: String,
        now: LocalDateTime
    ): Boolean {
        if (deps.smsGateway != null) {
            ensureSmsVerificationAttemptAllowed(tx, phone, purpose, now)
            val success = deps.smsGateway.verifySmsCode(phone = phone, purpose = purpose, code = code)
            recordSmsVerificationAttempt(tx, phone, purpose, success, now)
            return success
        }
        ensureSmsVerificationAttemptAllowed(tx, phone, purpose, now)
        val codeHash = sha256Hex(code)
        val row = with(tx) {
            SmsVerificationCodesTable.selectAll().where {
                (SmsVerificationCodesTable.phone eq phone) and
                    (SmsVerificationCodesTable.purpose eq purpose) and
                    (SmsVerificationCodesTable.codeHash eq codeHash) and
                    SmsVerificationCodesTable.consumedAt.isNull() and
                    (SmsVerificationCodesTable.expiresAt greaterEq now)
            }.orderBy(SmsVerificationCodesTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
        }
        if (row == null) {
            recordSmsVerificationAttempt(tx, phone, purpose, false, now)
            return false
        }
        val affected = with(tx) {
            SmsVerificationCodesTable.update({
                (SmsVerificationCodesTable.id eq row[SmsVerificationCodesTable.id]) and
                    SmsVerificationCodesTable.consumedAt.isNull()
            }) {
                it[SmsVerificationCodesTable.consumedAt] = now
            }
        }
        val success = affected > 0
        recordSmsVerificationAttempt(tx, phone, purpose, success, now)
        return success
    }

    private fun ensureSmsVerificationAttemptAllowed(
        tx: org.jetbrains.exposed.sql.Transaction,
        phone: String,
        purpose: SmsPurpose,
        now: LocalDateTime
    ) {
        val windowStart = now.minusSeconds(deps.authConfig.smsVerifyWindowSeconds)
        val latestSuccessAt = with(tx) {
            SmsVerificationAttemptsTable.selectAll().where {
                (SmsVerificationAttemptsTable.phone eq phone) and
                    (SmsVerificationAttemptsTable.purpose eq purpose) and
                    (SmsVerificationAttemptsTable.success eq true)
            }.orderBy(SmsVerificationAttemptsTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
                ?.get(SmsVerificationAttemptsTable.createdAt)
        }
        val effectiveStart = if (latestSuccessAt != null && latestSuccessAt > windowStart) {
            latestSuccessAt
        } else {
            windowStart
        }
        val failedAttempts = with(tx) {
            SmsVerificationAttemptsTable.selectAll().where {
                (SmsVerificationAttemptsTable.phone eq phone) and
                    (SmsVerificationAttemptsTable.purpose eq purpose) and
                    (SmsVerificationAttemptsTable.success eq false) and
                    (SmsVerificationAttemptsTable.createdAt greaterEq effectiveStart)
            }.count()
        }
        if (failedAttempts >= deps.authConfig.smsVerifyMaxAttempts) {
            throw AppException(
                code = ErrorCodes.SMS_VERIFY_TOO_MANY_ATTEMPTS,
                message = "Sms code verification attempts exceeded, please retry later",
                status = HttpStatusCode.TooManyRequests
            )
        }
    }

    private fun recordSmsVerificationAttempt(
        tx: org.jetbrains.exposed.sql.Transaction,
        phone: String,
        purpose: SmsPurpose,
        success: Boolean,
        now: LocalDateTime
    ) {
        with(tx) {
            SmsVerificationAttemptsTable.insert {
                it[SmsVerificationAttemptsTable.phone] = phone
                it[SmsVerificationAttemptsTable.purpose] = purpose
                it[SmsVerificationAttemptsTable.success] = success
                it[SmsVerificationAttemptsTable.createdAt] = now
            }
        }
    }
}
