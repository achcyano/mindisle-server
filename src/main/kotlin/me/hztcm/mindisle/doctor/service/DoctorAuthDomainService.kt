package me.hztcm.mindisle.doctor.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorBindingCodesTable
import me.hztcm.mindisle.db.DoctorSessionsTable
import me.hztcm.mindisle.db.DoctorThresholdSettingsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.SessionStatus
import me.hztcm.mindisle.db.SmsVerificationAttemptsTable
import me.hztcm.mindisle.db.SmsVerificationCodesTable
import me.hztcm.mindisle.model.DoctorAuthResponse
import me.hztcm.mindisle.model.DoctorChangePasswordRequest
import me.hztcm.mindisle.model.DoctorLogoutRequest
import me.hztcm.mindisle.model.DoctorPasswordLoginRequest
import me.hztcm.mindisle.model.DoctorProfileResponse
import me.hztcm.mindisle.model.DoctorRegisterRequest
import me.hztcm.mindisle.model.DoctorResetPasswordRequest
import me.hztcm.mindisle.model.DoctorThresholdSettingsRequest
import me.hztcm.mindisle.model.DoctorThresholdSettingsResponse
import me.hztcm.mindisle.model.DoctorTokenPairResponse
import me.hztcm.mindisle.model.DoctorTokenRefreshRequest
import me.hztcm.mindisle.model.SendDoctorSmsCodeRequest
import me.hztcm.mindisle.model.SmsPurpose
import me.hztcm.mindisle.model.GenerateBindingCodeResponse
import me.hztcm.mindisle.security.PasswordHasher
import me.hztcm.mindisle.util.generateSecureToken
import me.hztcm.mindisle.util.generateSmsCode
import me.hztcm.mindisle.util.normalizePhone
import me.hztcm.mindisle.util.sha256Hex
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal

private const val BINDING_CODE_TTL_SECONDS = 10 * 60L
private const val PASSWORD_MIN_LENGTH = 6
private const val PASSWORD_MAX_LENGTH = 20
private const val TOKEN_MAX_LENGTH = 512
private const val DEVICE_ID_MAX_LENGTH = 128
private const val NAME_MAX_LENGTH = 200
private const val TITLE_MAX_LENGTH = 100
private const val HOSPITAL_MAX_LENGTH = 200
private val SMS_CODE_REGEX = Regex("^\\d{6}$")

internal class DoctorAuthDomainService(private val deps: DoctorServiceDeps) {
    suspend fun sendSmsCode(request: SendDoctorSmsCodeRequest, requestIp: String?) {
        val phone = normalizePhone(request.phone)
        val purpose = request.purpose.toSmsPurpose()
        val now = utcNow()
        val smsTtlSeconds = if (purpose == SmsPurpose.DOCTOR_REGISTER) {
            deps.authConfig.smsCodeTtlSeconds + deps.authConfig.registerSmsCodeGraceSeconds
        } else {
            deps.authConfig.smsCodeTtlSeconds
        }
        DatabaseFactory.dbQuery {
            validateDoctorSmsBusinessRules(phone, purpose, now)
        }

        if (deps.smsGateway == null) {
            DatabaseFactory.dbQuery {
                val code = generateSmsCode()
                SmsVerificationCodesTable.insert {
                    it[SmsVerificationCodesTable.phone] = phone
                    it[SmsVerificationCodesTable.purpose] = purpose
                    it[SmsVerificationCodesTable.codeHash] = sha256Hex(code)
                    it[SmsVerificationCodesTable.expiresAt] = now.plusSeconds(smsTtlSeconds)
                    it[SmsVerificationCodesTable.createdAt] = now
                    it[SmsVerificationCodesTable.requestIp] = requestIp
                }
            }
            return
        }

        val result = deps.smsGateway.sendSmsCode(
            phone = phone,
            purpose = purpose,
            ttlSeconds = smsTtlSeconds,
            intervalSeconds = deps.authConfig.smsCooldownSeconds
        )
        DatabaseFactory.dbQuery {
            SmsVerificationCodesTable.insert {
                it[SmsVerificationCodesTable.phone] = phone
                it[SmsVerificationCodesTable.purpose] = purpose
                it[SmsVerificationCodesTable.codeHash] = sha256Hex(result.outId ?: generateSecureToken(16))
                it[SmsVerificationCodesTable.expiresAt] = now.plusSeconds(smsTtlSeconds)
                it[SmsVerificationCodesTable.consumedAt] = now
                it[SmsVerificationCodesTable.createdAt] = now
                it[SmsVerificationCodesTable.requestIp] = requestIp
            }
        }
    }

    suspend fun register(request: DoctorRegisterRequest, deviceId: String): DoctorAuthResponse {
        val phone = normalizePhone(request.phone)
        validatePassword(request.password)
        validateSmsCode(request.smsCode)
        val fullName = request.fullName.trim()
        if (fullName.isEmpty()) {
            throw doctorInvalidArg("fullName cannot be blank")
        }
        validateTextLength("fullName", fullName, NAME_MAX_LENGTH)
        validateTextLength("title", request.title, TITLE_MAX_LENGTH)
        validateTextLength("hospital", request.hospital, HOSPITAL_MAX_LENGTH)

        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val existing = DoctorsTable.selectAll().where { DoctorsTable.phone eq phone }.firstOrNull()
            if (existing != null) {
                throw AppException(
                    code = ErrorCodes.PHONE_ALREADY_REGISTERED,
                    message = "Phone already registered",
                    status = HttpStatusCode.Conflict
                )
            }

            val consumed = consumeSmsCode(phone, SmsPurpose.DOCTOR_REGISTER, request.smsCode, now)
            if (!consumed) {
                throw AppException(
                    code = ErrorCodes.INVALID_SMS_CODE,
                    message = "Invalid or expired sms code",
                    status = HttpStatusCode.BadRequest
                )
            }

            val doctorId = DoctorsTable.insert {
                it[DoctorsTable.phone] = phone
                it[DoctorsTable.fullName] = fullName
                it[DoctorsTable.title] = request.title?.trim()?.takeIf { v -> v.isNotEmpty() }
                it[DoctorsTable.hospital] = request.hospital?.trim()?.takeIf { v -> v.isNotEmpty() }
                it[DoctorsTable.passwordHash] = PasswordHasher.hash(request.password)
                it[DoctorsTable.createdAt] = now
                it[DoctorsTable.updatedAt] = now
                it[DoctorsTable.lastLoginAt] = now
            }[DoctorsTable.id]

            val token = issueDoctorTokenPair(doctorId, deviceId, now)
            DoctorAuthResponse(
                doctorId = doctorId.value,
                token = token
            )
        }
    }

    suspend fun loginWithPassword(request: DoctorPasswordLoginRequest, deviceId: String): DoctorAuthResponse {
        val phone = normalizePhone(request.phone)
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctor = DoctorsTable.selectAll().where { DoctorsTable.phone eq phone }.firstOrNull()
                ?: throw doctorNotFound("Doctor not found")
            if (!PasswordHasher.verify(request.password, doctor[DoctorsTable.passwordHash])) {
                throw AppException(
                    code = ErrorCodes.INVALID_CREDENTIALS,
                    message = "Invalid credentials",
                    status = HttpStatusCode.Unauthorized
                )
            }
            DoctorsTable.update({ DoctorsTable.id eq doctor[DoctorsTable.id] }) {
                it[DoctorsTable.lastLoginAt] = now
                it[DoctorsTable.updatedAt] = now
            }
            val token = issueDoctorTokenPair(doctor[DoctorsTable.id], deviceId, now)
            DoctorAuthResponse(
                doctorId = doctor[DoctorsTable.id].value,
                token = token
            )
        }
    }

    suspend fun refreshToken(request: DoctorTokenRefreshRequest, deviceId: String): DoctorAuthResponse {
        validateToken("refreshToken", request.refreshToken)
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val tokenHash = sha256Hex(request.refreshToken)
            val session = DoctorSessionsTable.selectAll().where {
                (DoctorSessionsTable.refreshTokenHash eq tokenHash) and
                    (DoctorSessionsTable.deviceId eq deviceId) and
                    (DoctorSessionsTable.status eq SessionStatus.ACTIVE) and
                    (DoctorSessionsTable.expiresAt greaterEq now)
            }.firstOrNull() ?: throw AppException(
                code = ErrorCodes.UNAUTHORIZED,
                message = "Invalid refresh token",
                status = HttpStatusCode.Unauthorized
            )
            val doctorId = session[DoctorSessionsTable.doctorId]
            val refreshToken = generateSecureToken()
            DoctorSessionsTable.update({ DoctorSessionsTable.id eq session[DoctorSessionsTable.id] }) {
                it[DoctorSessionsTable.refreshTokenHash] = sha256Hex(refreshToken)
                it[DoctorSessionsTable.status] = SessionStatus.ACTIVE
                it[DoctorSessionsTable.lastUsedAt] = now
                it[DoctorSessionsTable.expiresAt] = now.plusSeconds(deps.authConfig.refreshTokenTtlSeconds)
                it[DoctorSessionsTable.revokedAt] = null
            }
            val (accessToken, accessTtl) = deps.jwtService.generateDoctorAccessToken(doctorId.value, deviceId)
            DoctorAuthResponse(
                doctorId = doctorId.value,
                token = DoctorTokenPairResponse(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accessTokenExpiresInSeconds = accessTtl,
                    refreshTokenExpiresInSeconds = deps.authConfig.refreshTokenTtlSeconds
                )
            )
        }
    }

    suspend fun resetPassword(request: DoctorResetPasswordRequest) {
        val phone = normalizePhone(request.phone)
        validatePassword(request.newPassword)
        validateSmsCode(request.smsCode)
        DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctor = DoctorsTable.selectAll().where { DoctorsTable.phone eq phone }.firstOrNull()
                ?: throw doctorNotFound("Doctor not found")
            val consumed = consumeSmsCode(phone, SmsPurpose.DOCTOR_RESET_PASSWORD, request.smsCode, now)
            if (!consumed) {
                throw AppException(
                    code = ErrorCodes.INVALID_SMS_CODE,
                    message = "Invalid or expired sms code",
                    status = HttpStatusCode.BadRequest
                )
            }
            DoctorsTable.update({ DoctorsTable.id eq doctor[DoctorsTable.id] }) {
                it[DoctorsTable.passwordHash] = PasswordHasher.hash(request.newPassword)
                it[DoctorsTable.updatedAt] = now
            }
            DoctorSessionsTable.update({ DoctorSessionsTable.doctorId eq doctor[DoctorsTable.id] }) {
                it[DoctorSessionsTable.status] = SessionStatus.REVOKED
                it[DoctorSessionsTable.revokedAt] = now
                it[DoctorSessionsTable.lastUsedAt] = now
            }
        }
    }

    suspend fun changePassword(doctorId: Long, request: DoctorChangePasswordRequest) {
        validatePassword(request.newPassword)
        validatePassword(request.oldPassword)
        DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val doctor = requireDoctor(doctorRef)
            if (!PasswordHasher.verify(request.oldPassword, doctor[DoctorsTable.passwordHash])) {
                throw AppException(
                    code = ErrorCodes.DOCTOR_INVALID_OLD_PASSWORD,
                    message = "Invalid old password",
                    status = HttpStatusCode.BadRequest
                )
            }
            DoctorsTable.update({ DoctorsTable.id eq doctorRef }) {
                it[DoctorsTable.passwordHash] = PasswordHasher.hash(request.newPassword)
                it[DoctorsTable.updatedAt] = now
            }
            DoctorSessionsTable.update({ DoctorSessionsTable.doctorId eq doctorRef }) {
                it[DoctorSessionsTable.status] = SessionStatus.REVOKED
                it[DoctorSessionsTable.revokedAt] = now
                it[DoctorSessionsTable.lastUsedAt] = now
            }
        }
    }

    suspend fun logout(doctorId: Long, deviceId: String, request: DoctorLogoutRequest) {
        request.refreshToken?.let { validateToken("refreshToken", it) }
        DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val session = DoctorSessionsTable.selectAll().where {
                (DoctorSessionsTable.doctorId eq doctorRef) and
                    (DoctorSessionsTable.deviceId eq deviceId) and
                    (DoctorSessionsTable.status eq SessionStatus.ACTIVE)
            }.firstOrNull() ?: return@dbQuery
            if (request.refreshToken != null && session[DoctorSessionsTable.refreshTokenHash] != sha256Hex(request.refreshToken)) {
                throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "Refresh token does not match device session",
                    status = HttpStatusCode.Unauthorized
                )
            }
            DoctorSessionsTable.update({ DoctorSessionsTable.id eq session[DoctorSessionsTable.id] }) {
                it[DoctorSessionsTable.status] = SessionStatus.REVOKED
                it[DoctorSessionsTable.revokedAt] = now
                it[DoctorSessionsTable.lastUsedAt] = now
            }
        }
    }

    suspend fun getProfile(doctorId: Long): DoctorProfileResponse {
        return DatabaseFactory.dbQuery {
            val doctor = requireDoctor(EntityID(doctorId, DoctorsTable))
            DoctorProfileResponse(
                doctorId = doctor[DoctorsTable.id].value,
                phone = doctor[DoctorsTable.phone],
                fullName = doctor[DoctorsTable.fullName],
                title = doctor[DoctorsTable.title],
                hospital = doctor[DoctorsTable.hospital]
            )
        }
    }

    suspend fun getThresholdSettings(doctorId: Long): DoctorThresholdSettingsResponse {
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val doctor = requireDoctor(doctorRef)
            val row = DoctorThresholdSettingsTable.selectAll().where {
                DoctorThresholdSettingsTable.doctorId eq doctorRef
            }.firstOrNull()
            DoctorThresholdSettingsResponse(
                scl90Threshold = row?.get(DoctorThresholdSettingsTable.scl90Threshold)?.toDouble(),
                phq9Threshold = row?.get(DoctorThresholdSettingsTable.phq9Threshold)?.toDouble(),
                gad7Threshold = row?.get(DoctorThresholdSettingsTable.gad7Threshold)?.toDouble(),
                psqiThreshold = row?.get(DoctorThresholdSettingsTable.psqiThreshold)?.toDouble(),
                updatedAt = (row?.get(DoctorThresholdSettingsTable.updatedAt) ?: doctor[DoctorsTable.updatedAt]).toIsoInstant()
            )
        }
    }

    suspend fun upsertThresholdSettings(
        doctorId: Long,
        request: DoctorThresholdSettingsRequest
    ): DoctorThresholdSettingsResponse {
        validateThreshold("scl90Threshold", request.scl90Threshold)
        validateThreshold("phq9Threshold", request.phq9Threshold)
        validateThreshold("gad7Threshold", request.gad7Threshold)
        validateThreshold("psqiThreshold", request.psqiThreshold)

        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctorRef = EntityID(doctorId, DoctorsTable)
            requireDoctor(doctorRef)
            val existing = DoctorThresholdSettingsTable.selectAll().where {
                DoctorThresholdSettingsTable.doctorId eq doctorRef
            }.firstOrNull()
            if (existing == null) {
                DoctorThresholdSettingsTable.insert {
                    it[DoctorThresholdSettingsTable.doctorId] = doctorRef
                    it[scl90Threshold] = request.scl90Threshold?.toBigDecimalSafe()
                    it[phq9Threshold] = request.phq9Threshold?.toBigDecimalSafe()
                    it[gad7Threshold] = request.gad7Threshold?.toBigDecimalSafe()
                    it[psqiThreshold] = request.psqiThreshold?.toBigDecimalSafe()
                    it[updatedAt] = now
                }
            } else {
                DoctorThresholdSettingsTable.update({ DoctorThresholdSettingsTable.doctorId eq doctorRef }) {
                    it[scl90Threshold] = request.scl90Threshold?.toBigDecimalSafe()
                    it[phq9Threshold] = request.phq9Threshold?.toBigDecimalSafe()
                    it[gad7Threshold] = request.gad7Threshold?.toBigDecimalSafe()
                    it[psqiThreshold] = request.psqiThreshold?.toBigDecimalSafe()
                    it[updatedAt] = now
                }
            }
            DoctorThresholdSettingsResponse(
                scl90Threshold = request.scl90Threshold,
                phq9Threshold = request.phq9Threshold,
                gad7Threshold = request.gad7Threshold,
                psqiThreshold = request.psqiThreshold,
                updatedAt = now.toIsoInstant()
            )
        }
    }

    suspend fun generateBindingCode(doctorId: Long): GenerateBindingCodeResponse {
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctorRef = EntityID(doctorId, DoctorsTable)
            requireDoctor(doctorRef)
            val code = generateSmsCode()
            val expiresAt = now.plusSeconds(BINDING_CODE_TTL_SECONDS)
            val qrPayload = "mindisle://doctor-bind?code=$code"
            DoctorBindingCodesTable.insert {
                it[DoctorBindingCodesTable.doctorId] = doctorRef
                it[DoctorBindingCodesTable.codeHash] = sha256Hex(code)
                it[DoctorBindingCodesTable.expiresAt] = expiresAt
                it[DoctorBindingCodesTable.consumedAt] = null
                it[DoctorBindingCodesTable.createdAt] = now
                it[DoctorBindingCodesTable.qrPayload] = qrPayload
            }
            GenerateBindingCodeResponse(
                code = code,
                expiresAt = expiresAt.toIsoInstant(),
                qrPayload = qrPayload
            )
        }
    }

    fun validateDeviceId(deviceId: String): String {
        val value = deviceId.trim()
        if (value.isEmpty()) {
            throw doctorInvalidArg("Missing required header: X-Device-Id")
        }
        if (value.length > DEVICE_ID_MAX_LENGTH) {
            throw doctorInvalidArg("X-Device-Id exceeds $DEVICE_ID_MAX_LENGTH characters")
        }
        ensureNoControlChars("X-Device-Id", value)
        return value
    }

    private fun Double.toBigDecimalSafe(): BigDecimal = BigDecimal.valueOf(this).setScale(2, java.math.RoundingMode.HALF_UP)

    private fun validateThreshold(fieldName: String, value: Double?) {
        if (value == null) {
            return
        }
        if (!value.isFinite() || value < 0.0 || value > 10_000.0) {
            throw doctorInvalidArg("$fieldName must be between 0 and 10000")
        }
    }

    private fun validatePassword(password: String) {
        ensureNoControlChars("password", password)
        if (password.length !in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH) {
            throw AppException(
                code = ErrorCodes.PASSWORD_TOO_SHORT,
                message = "Password length must be between $PASSWORD_MIN_LENGTH and $PASSWORD_MAX_LENGTH characters",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun validateToken(fieldName: String, value: String) {
        ensureNoControlChars(fieldName, value)
        if (value.length > TOKEN_MAX_LENGTH) {
            throw doctorInvalidArg("$fieldName exceeds $TOKEN_MAX_LENGTH characters")
        }
    }

    private fun validateSmsCode(code: String) {
        if (!SMS_CODE_REGEX.matches(code)) {
            throw AppException(
                code = ErrorCodes.INVALID_SMS_CODE,
                message = "Invalid sms code format",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun validateTextLength(fieldName: String, value: String?, maxLength: Int) {
        if (value == null) {
            return
        }
        ensureNoControlChars(fieldName, value)
        if (value.length > maxLength) {
            throw doctorInvalidArg("$fieldName exceeds $maxLength characters")
        }
    }

    private fun ensureNoControlChars(fieldName: String, value: String) {
        if (value.any { it.isISOControl() }) {
            throw doctorInvalidArg("$fieldName contains control characters")
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.issueDoctorTokenPair(
        doctorId: EntityID<Long>,
        deviceId: String,
        now: java.time.LocalDateTime
    ): DoctorTokenPairResponse {
        val refreshToken = generateSecureToken()
        val refreshHash = sha256Hex(refreshToken)
        val expiresAt = now.plusSeconds(deps.authConfig.refreshTokenTtlSeconds)
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
        val (accessToken, accessTtl) = deps.jwtService.generateDoctorAccessToken(doctorId.value, deviceId)
        return DoctorTokenPairResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresInSeconds = accessTtl,
            refreshTokenExpiresInSeconds = deps.authConfig.refreshTokenTtlSeconds
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.validateDoctorSmsBusinessRules(
        phone: String,
        purpose: SmsPurpose,
        now: java.time.LocalDateTime
    ) {
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
                    throw doctorNotFound("Doctor not found")
                }
            }

            else -> throw doctorInvalidArg("Unsupported sms purpose for doctor flow")
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
        val dayStart = utcNow().toLocalDate().atStartOfDay()
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

    private fun org.jetbrains.exposed.sql.Transaction.consumeSmsCode(
        phone: String,
        purpose: SmsPurpose,
        code: String,
        now: java.time.LocalDateTime
    ): Boolean {
        if (deps.smsGateway != null) {
            ensureSmsVerificationAttemptAllowed(phone, purpose, now)
            val success = deps.smsGateway.verifySmsCode(phone = phone, purpose = purpose, code = code)
            recordSmsVerificationAttempt(phone, purpose, success, now)
            return success
        }
        ensureSmsVerificationAttemptAllowed(phone, purpose, now)
        val codeHash = sha256Hex(code)
        val row = SmsVerificationCodesTable.selectAll().where {
            (SmsVerificationCodesTable.phone eq phone) and
                (SmsVerificationCodesTable.purpose eq purpose) and
                (SmsVerificationCodesTable.codeHash eq codeHash) and
                SmsVerificationCodesTable.consumedAt.isNull() and
                (SmsVerificationCodesTable.expiresAt greaterEq now)
        }.orderBy(SmsVerificationCodesTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
        if (row == null) {
            recordSmsVerificationAttempt(phone, purpose, false, now)
            return false
        }
        val affected = SmsVerificationCodesTable.update({
            (SmsVerificationCodesTable.id eq row[SmsVerificationCodesTable.id]) and
                SmsVerificationCodesTable.consumedAt.isNull()
        }) {
            it[SmsVerificationCodesTable.consumedAt] = now
        }
        val success = affected > 0
        recordSmsVerificationAttempt(phone, purpose, success, now)
        return success
    }

    private fun org.jetbrains.exposed.sql.Transaction.ensureSmsVerificationAttemptAllowed(
        phone: String,
        purpose: SmsPurpose,
        now: java.time.LocalDateTime
    ) {
        val windowStart = now.minusSeconds(deps.authConfig.smsVerifyWindowSeconds)
        val latestSuccessAt = SmsVerificationAttemptsTable.selectAll().where {
            (SmsVerificationAttemptsTable.phone eq phone) and
                (SmsVerificationAttemptsTable.purpose eq purpose) and
                (SmsVerificationAttemptsTable.success eq true)
        }.orderBy(SmsVerificationAttemptsTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
            ?.get(SmsVerificationAttemptsTable.createdAt)
        val effectiveStart = if (latestSuccessAt != null && latestSuccessAt > windowStart) {
            latestSuccessAt
        } else {
            windowStart
        }
        val failedAttempts = SmsVerificationAttemptsTable.selectAll().where {
            (SmsVerificationAttemptsTable.phone eq phone) and
                (SmsVerificationAttemptsTable.purpose eq purpose) and
                (SmsVerificationAttemptsTable.success eq false) and
                (SmsVerificationAttemptsTable.createdAt greaterEq effectiveStart)
        }.count()
        if (failedAttempts >= deps.authConfig.smsVerifyMaxAttempts) {
            throw AppException(
                code = ErrorCodes.SMS_VERIFY_TOO_MANY_ATTEMPTS,
                message = "Sms code verification attempts exceeded, please retry later",
                status = HttpStatusCode.TooManyRequests
            )
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.recordSmsVerificationAttempt(
        phone: String,
        purpose: SmsPurpose,
        success: Boolean,
        now: java.time.LocalDateTime
    ) {
        SmsVerificationAttemptsTable.insert {
            it[SmsVerificationAttemptsTable.phone] = phone
            it[SmsVerificationAttemptsTable.purpose] = purpose
            it[SmsVerificationAttemptsTable.success] = success
            it[SmsVerificationAttemptsTable.createdAt] = now
        }
    }
}
