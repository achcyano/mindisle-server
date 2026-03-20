package me.hztcm.mindisle.doctor.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.auth.invalidRequest
import me.hztcm.mindisle.auth.validatePassword
import me.hztcm.mindisle.auth.validateSmsCode
import me.hztcm.mindisle.auth.validateToken
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorLoginTicketsTable
import me.hztcm.mindisle.db.DoctorSessionsTable
import me.hztcm.mindisle.db.DoctorThresholdSettingsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.SessionStatus
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
import me.hztcm.mindisle.model.DirectLoginRequest
import me.hztcm.mindisle.model.SendDoctorSmsCodeRequest
import me.hztcm.mindisle.model.SmsPurpose
import me.hztcm.mindisle.model.LoginCheckRequest
import me.hztcm.mindisle.model.LoginCheckResponse
import me.hztcm.mindisle.model.LoginDecision
import me.hztcm.mindisle.security.PasswordHasher
import me.hztcm.mindisle.model.UpsertDoctorProfileRequest
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

private const val NAME_MAX_LENGTH = 200
private const val HOSPITAL_MAX_LENGTH = 200

internal class DoctorAuthDomainService(private val deps: DoctorServiceDeps) {
    private val authPersistence = DoctorAuthPersistenceSupport(deps)

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
            authPersistence.validateDoctorSmsBusinessRules(this, phone, purpose, now)
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
        val normalizedFullName = normalizeDoctorProfileField("fullName", request.fullName, NAME_MAX_LENGTH)
        val normalizedHospital = normalizeDoctorProfileField("hospital", request.hospital, HOSPITAL_MAX_LENGTH)
        val fullName = normalizedFullName ?: "Doctor-${phone.takeLast(4)}"
        validateDoctorTextLength("fullName", fullName, NAME_MAX_LENGTH)

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

            val consumed = authPersistence.consumeSmsCode(this, phone, SmsPurpose.DOCTOR_REGISTER, request.smsCode, now)
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
                it[DoctorsTable.hospital] = normalizedHospital
                it[DoctorsTable.passwordHash] = PasswordHasher.hash(request.password)
                it[DoctorsTable.createdAt] = now
                it[DoctorsTable.updatedAt] = now
                it[DoctorsTable.lastLoginAt] = now
            }[DoctorsTable.id]

            val token = authPersistence.issueDoctorTokenPair(this, doctorId, deviceId, now)
            DoctorAuthResponse(
                doctorId = doctorId.value,
                token = token
            )
        }
    }

    suspend fun loginCheck(request: LoginCheckRequest, deviceId: String): LoginCheckResponse {
        val phone = normalizePhone(request.phone)
        return DatabaseFactory.dbQuery {
            val doctor = DoctorsTable.selectAll().where { DoctorsTable.phone eq phone }.firstOrNull()
                ?: return@dbQuery LoginCheckResponse(decision = LoginDecision.REGISTER_REQUIRED)
            val doctorId = doctor[DoctorsTable.id]
            val hasTrustedDevice = DoctorSessionsTable.selectAll().where {
                (DoctorSessionsTable.doctorId eq doctorId) and
                    (DoctorSessionsTable.deviceId eq deviceId) and
                    (DoctorSessionsTable.status eq SessionStatus.ACTIVE)
            }.any()

            if (!hasTrustedDevice) {
                LoginCheckResponse(decision = LoginDecision.PASSWORD_REQUIRED)
            } else {
                val now = utcNow()
                val ticket = generateSecureToken(32)
                DoctorLoginTicketsTable.insert {
                    it[DoctorLoginTicketsTable.doctorId] = doctorId
                    it[DoctorLoginTicketsTable.phone] = phone
                    it[DoctorLoginTicketsTable.deviceId] = deviceId
                    it[DoctorLoginTicketsTable.ticketHash] = sha256Hex(ticket)
                    it[DoctorLoginTicketsTable.createdAt] = now
                    it[DoctorLoginTicketsTable.expiresAt] = now.plusSeconds(deps.authConfig.loginTicketTtlSeconds)
                }
                LoginCheckResponse(decision = LoginDecision.DIRECT_LOGIN_ALLOWED, ticket = ticket)
            }
        }
    }

    suspend fun loginDirect(request: DirectLoginRequest, deviceId: String): DoctorAuthResponse {
        val phone = normalizePhone(request.phone)
        validateToken(fieldName = "ticket", value = request.ticket)
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctor = DoctorsTable.selectAll().where { DoctorsTable.phone eq phone }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.REGISTER_REQUIRED,
                    message = "Phone is not registered",
                    status = HttpStatusCode.NotFound
                )
            val doctorId = doctor[DoctorsTable.id]
            val validTicket = DoctorLoginTicketsTable.selectAll().where {
                (DoctorLoginTicketsTable.doctorId eq doctorId) and
                    (DoctorLoginTicketsTable.phone eq phone) and
                    (DoctorLoginTicketsTable.deviceId eq deviceId) and
                    (DoctorLoginTicketsTable.ticketHash eq sha256Hex(request.ticket)) and
                    DoctorLoginTicketsTable.consumedAt.isNull() and
                    (DoctorLoginTicketsTable.expiresAt greaterEq now)
            }.orderBy(DoctorLoginTicketsTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()

            if (validTicket == null) {
                throw AppException(
                    code = ErrorCodes.LOGIN_TICKET_INVALID,
                    message = "Invalid or expired login ticket",
                    status = HttpStatusCode.BadRequest
                )
            }
            DoctorLoginTicketsTable.update({ DoctorLoginTicketsTable.id eq validTicket[DoctorLoginTicketsTable.id] }) {
                it[DoctorLoginTicketsTable.consumedAt] = now
            }

            DoctorsTable.update({ DoctorsTable.id eq doctorId }) {
                it[DoctorsTable.lastLoginAt] = now
                it[DoctorsTable.updatedAt] = now
            }
            val token = authPersistence.issueDoctorTokenPair(this, doctorId, deviceId, now)
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
                ?: throw AppException(
                    code = ErrorCodes.REGISTER_REQUIRED,
                    message = "Phone is not registered",
                    status = HttpStatusCode.NotFound
                )
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
            val token = authPersistence.issueDoctorTokenPair(this, doctor[DoctorsTable.id], deviceId, now)
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
                ?: throw AppException(
                    code = ErrorCodes.PHONE_NOT_REGISTERED,
                    message = "Phone is not registered",
                    status = HttpStatusCode.NotFound
                )
            val consumed = authPersistence.consumeSmsCode(this, phone, SmsPurpose.DOCTOR_RESET_PASSWORD, request.smsCode, now)
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
                hospital = doctor[DoctorsTable.hospital]
            )
        }
    }

    suspend fun upsertProfile(doctorId: Long, request: UpsertDoctorProfileRequest): DoctorProfileResponse {
        val normalizedFullName = normalizeDoctorProfileField("fullName", request.fullName, NAME_MAX_LENGTH)
        val normalizedHospital = normalizeDoctorProfileField("hospital", request.hospital, HOSPITAL_MAX_LENGTH)
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val doctor = requireDoctor(doctorRef)
            if (normalizedFullName != null || normalizedHospital != null) {
                DoctorsTable.update({ DoctorsTable.id eq doctorRef }) {
                    if (normalizedFullName != null) {
                        it[DoctorsTable.fullName] = normalizedFullName
                    }
                    if (normalizedHospital != null) {
                        it[DoctorsTable.hospital] = normalizedHospital
                    }
                    it[DoctorsTable.updatedAt] = now
                }
            }
            DoctorProfileResponse(
                doctorId = doctorId,
                phone = doctor[DoctorsTable.phone],
                fullName = normalizedFullName ?: doctor[DoctorsTable.fullName],
                hospital = normalizedHospital ?: doctor[DoctorsTable.hospital]
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
        validateDoctorThreshold("scl90Threshold", request.scl90Threshold)
        validateDoctorThreshold("phq9Threshold", request.phq9Threshold)
        validateDoctorThreshold("gad7Threshold", request.gad7Threshold)
        validateDoctorThreshold("psqiThreshold", request.psqiThreshold)

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
                    it[scl90Threshold] = request.scl90Threshold?.toDoctorThresholdDecimal()
                    it[phq9Threshold] = request.phq9Threshold?.toDoctorThresholdDecimal()
                    it[gad7Threshold] = request.gad7Threshold?.toDoctorThresholdDecimal()
                    it[psqiThreshold] = request.psqiThreshold?.toDoctorThresholdDecimal()
                    it[updatedAt] = now
                }
            } else {
                DoctorThresholdSettingsTable.update({ DoctorThresholdSettingsTable.doctorId eq doctorRef }) {
                    it[scl90Threshold] = request.scl90Threshold?.toDoctorThresholdDecimal()
                    it[phq9Threshold] = request.phq9Threshold?.toDoctorThresholdDecimal()
                    it[gad7Threshold] = request.gad7Threshold?.toDoctorThresholdDecimal()
                    it[psqiThreshold] = request.psqiThreshold?.toDoctorThresholdDecimal()
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
}
