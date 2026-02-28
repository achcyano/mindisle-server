package me.hztcm.mindisle.user.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.DEBUG
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.config.AuthConfig
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.LoginTicketsTable
import me.hztcm.mindisle.db.SessionStatus
import me.hztcm.mindisle.db.SmsVerificationAttemptsTable
import me.hztcm.mindisle.db.SmsVerificationCodesTable
import me.hztcm.mindisle.db.UserAvatarsTable
import me.hztcm.mindisle.db.UserDiseaseHistoriesTable
import me.hztcm.mindisle.db.UserFamilyHistoriesTable
import me.hztcm.mindisle.db.UserMedicalHistoriesTable
import me.hztcm.mindisle.db.UserMedicationHistoriesTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UserSessionsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.AuthResponse
import me.hztcm.mindisle.model.DirectLoginRequest
import me.hztcm.mindisle.model.Gender
import me.hztcm.mindisle.model.LoginCheckRequest
import me.hztcm.mindisle.model.LoginCheckResponse
import me.hztcm.mindisle.model.LoginDecision
import me.hztcm.mindisle.model.PasswordLoginRequest
import me.hztcm.mindisle.model.RegisterRequest
import me.hztcm.mindisle.model.ResetPasswordRequest
import me.hztcm.mindisle.model.SendSmsCodeRequest
import me.hztcm.mindisle.model.SmsPurpose
import me.hztcm.mindisle.model.TokenPairResponse
import me.hztcm.mindisle.model.TokenRefreshRequest
import me.hztcm.mindisle.model.UpsertBasicProfileRequest
import me.hztcm.mindisle.model.UpsertProfileRequest
import me.hztcm.mindisle.model.UserAvatarMetaResponse
import me.hztcm.mindisle.model.UserBasicProfileResponse
import me.hztcm.mindisle.model.UserProfileResponse
import me.hztcm.mindisle.security.JwtService
import me.hztcm.mindisle.security.PasswordHasher
import me.hztcm.mindisle.sms.SmsGateway
import me.hztcm.mindisle.util.generateSecureToken
import me.hztcm.mindisle.util.generateSmsCode
import me.hztcm.mindisle.util.normalizePhone
import me.hztcm.mindisle.util.sha256Hex
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.imageio.ImageIO

class UserManagementService(
    private val config: AuthConfig,
    private val jwtService: JwtService,
    private val smsGateway: SmsGateway?
) {
    data class AvatarBinaryPayload(
        val bytes: ByteArray,
        val contentType: String,
        val sha256: String,
        val updatedAt: LocalDateTime
    )

    private companion object {
        const val PROFILE_TEXT_MAX_LENGTH = 200
        const val PROFILE_LIST_MAX_SIZE = 50
        const val HEIGHT_CM_MIN = 50.0
        const val HEIGHT_CM_MAX = 260.0
        const val WEIGHT_KG_MIN = 10.0
        const val WEIGHT_KG_MAX = 500.0
        const val WAIST_CM_MIN = 30.0
        const val WAIST_CM_MAX = 220.0
        const val SMS_CODE_LENGTH = 6
        const val TOKEN_MAX_LENGTH = 512
        const val PASSWORD_MIN_LENGTH = 6
        const val PASSWORD_MAX_LENGTH = 20
        const val AVATAR_SIZE_PX = 1024
        const val AVATAR_MAX_UPLOAD_BYTES = 5 * 1024 * 1024
        const val AVATAR_CONTENT_TYPE = "image/png"
        const val AVATAR_STORAGE_DIR = "data/avatars"
        const val AVATAR_API_URL = "/api/v1/users/me/avatar"
        val SMS_CODE_REGEX = Regex("^\\d{6}$")
    }

    suspend fun sendSmsCode(request: SendSmsCodeRequest, requestIp: String?) {
        val phone = normalizePhone(request.phone)
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val smsTtlSeconds = when (request.purpose) {
            SmsPurpose.REGISTER -> config.smsCodeTtlSeconds + config.registerSmsCodeGraceSeconds
            SmsPurpose.RESET_PASSWORD -> config.smsCodeTtlSeconds
        }
        DatabaseFactory.dbQuery {
            validateSmsBusinessRules(phone, request.purpose, now)
        }

        if (smsGateway == null) {
            DatabaseFactory.dbQuery {
                val code = generateSmsCode()
                SmsVerificationCodesTable.insert {
                    it[SmsVerificationCodesTable.phone] = phone
                    it[SmsVerificationCodesTable.purpose] = request.purpose
                    it[SmsVerificationCodesTable.codeHash] = sha256Hex(code)
                    it[SmsVerificationCodesTable.expiresAt] = now.plusSeconds(smsTtlSeconds)
                    it[SmsVerificationCodesTable.createdAt] = now
                    it[SmsVerificationCodesTable.requestIp] = requestIp
                }
                if (DEBUG) {
                    println("Sms code generated in local provider, phone=$phone purpose=${request.purpose}")
                }
            }
            return
        }

        val sendResult = smsGateway.sendSmsCode(
            phone = phone,
            purpose = request.purpose,
            ttlSeconds = smsTtlSeconds,
            intervalSeconds = config.smsCooldownSeconds
        )
        DatabaseFactory.dbQuery {
            SmsVerificationCodesTable.insert {
                it[SmsVerificationCodesTable.phone] = phone
                it[SmsVerificationCodesTable.purpose] = request.purpose
                it[SmsVerificationCodesTable.codeHash] = sha256Hex(sendResult.outId ?: generateSecureToken(16))
                it[SmsVerificationCodesTable.expiresAt] = now.plusSeconds(smsTtlSeconds)
                it[SmsVerificationCodesTable.consumedAt] = now
                it[SmsVerificationCodesTable.createdAt] = now
                it[SmsVerificationCodesTable.requestIp] = requestIp
            }
        }
        if (DEBUG) {
            println(
                "Sms code sent via provider, phone=$phone purpose=${request.purpose} outId=${sendResult.outId} bizId=${sendResult.bizId}"
            )
        }
    }

    suspend fun register(request: RegisterRequest, deviceId: String): AuthResponse {
        val phone = normalizePhone(request.phone)
        validatePassword(request.password)
        validateSmsCode(request.smsCode)
        request.profile?.let { validateProfileUpdateRequest(it) }
        return DatabaseFactory.dbQuery {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val existing = UsersTable.selectAll().where { UsersTable.phone eq phone }.firstOrNull()
            if (existing != null) {
                throw AppException(
                    code = ErrorCodes.PHONE_ALREADY_REGISTERED,
                    message = "Phone already registered",
                    status = HttpStatusCode.Conflict
                )
            }
            val consumed = consumeSmsCode(phone, SmsPurpose.REGISTER, request.smsCode, now)
            if (!consumed) {
                throw AppException(
                    code = ErrorCodes.INVALID_SMS_CODE,
                    message = "Invalid or expired sms code",
                    status = HttpStatusCode.BadRequest
                )
            }

            val userId = UsersTable.insert {
                it[UsersTable.phone] = phone
                it[passwordHash] = PasswordHasher.hash(request.password)
                it[createdAt] = now
                it[updatedAt] = now
                it[lastLoginAt] = now
            }[UsersTable.id]

            ensureProfileRow(userId, now)
            request.profile?.let { applyProfileUpdate(userId, it, now) }
            val token = issueTokenPair(userId, deviceId, now)
            AuthResponse(userId = userId.value, token = token)
        }
    }

    suspend fun loginCheck(request: LoginCheckRequest, deviceId: String): LoginCheckResponse {
        val phone = normalizePhone(request.phone)
        return DatabaseFactory.dbQuery {
            val user = UsersTable.selectAll().where { UsersTable.phone eq phone }.firstOrNull()
                ?: return@dbQuery LoginCheckResponse(decision = LoginDecision.REGISTER_REQUIRED)
            val userId = user[UsersTable.id]
            val hasTrustedDevice = UserSessionsTable.selectAll().where {
                (UserSessionsTable.userId eq userId) and
                    (UserSessionsTable.deviceId eq deviceId) and
                    (UserSessionsTable.status eq SessionStatus.ACTIVE)
            }.any()

            if (!hasTrustedDevice) {
                LoginCheckResponse(decision = LoginDecision.PASSWORD_REQUIRED)
            } else {
                val now = LocalDateTime.now(ZoneOffset.UTC)
                val ticket = generateSecureToken(32)
                LoginTicketsTable.insert {
                    it[LoginTicketsTable.userId] = userId
                    it[LoginTicketsTable.phone] = phone
                    it[LoginTicketsTable.deviceId] = deviceId
                    it[ticketHash] = sha256Hex(ticket)
                    it[createdAt] = now
                    it[expiresAt] = now.plusSeconds(config.loginTicketTtlSeconds)
                }
                LoginCheckResponse(decision = LoginDecision.DIRECT_LOGIN_ALLOWED, ticket = ticket)
            }
        }
    }

    suspend fun loginDirect(request: DirectLoginRequest, deviceId: String): AuthResponse {
        val phone = normalizePhone(request.phone)
        validateToken("ticket", request.ticket)
        return DatabaseFactory.dbQuery {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val user = UsersTable.selectAll().where { UsersTable.phone eq phone }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.REGISTER_REQUIRED,
                    message = "Phone is not registered",
                    status = HttpStatusCode.NotFound
                )
            val userId = user[UsersTable.id]
            val validTicket = LoginTicketsTable.selectAll().where {
                (LoginTicketsTable.userId eq userId) and
                    (LoginTicketsTable.phone eq phone) and
                    (LoginTicketsTable.deviceId eq deviceId) and
                    (LoginTicketsTable.ticketHash eq sha256Hex(request.ticket)) and
                    LoginTicketsTable.consumedAt.isNull() and
                    (LoginTicketsTable.expiresAt greaterEq now)
            }.orderBy(LoginTicketsTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()

            if (validTicket == null) {
                throw AppException(
                    code = ErrorCodes.LOGIN_TICKET_INVALID,
                    message = "Invalid or expired login ticket",
                    status = HttpStatusCode.BadRequest
                )
            }
            LoginTicketsTable.update({ LoginTicketsTable.id eq validTicket[LoginTicketsTable.id] }) {
                it[consumedAt] = now
            }

            UsersTable.update({ UsersTable.id eq userId }) {
                it[lastLoginAt] = now
                it[updatedAt] = now
            }
            val token = issueTokenPair(userId, deviceId, now)
            AuthResponse(userId = userId.value, token = token)
        }
    }

    suspend fun loginWithPassword(request: PasswordLoginRequest, deviceId: String): AuthResponse {
        val phone = normalizePhone(request.phone)
        return DatabaseFactory.dbQuery {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val user = UsersTable.selectAll().where { UsersTable.phone eq phone }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.REGISTER_REQUIRED,
                    message = "Phone is not registered",
                    status = HttpStatusCode.NotFound
                )
            val userId = user[UsersTable.id]
            val hash = user[UsersTable.passwordHash]
            if (!PasswordHasher.verify(request.password, hash)) {
                throw AppException(
                    code = ErrorCodes.INVALID_CREDENTIALS,
                    message = "Invalid credentials",
                    status = HttpStatusCode.Unauthorized
                )
            }

            UsersTable.update({ UsersTable.id eq userId }) {
                it[lastLoginAt] = now
                it[updatedAt] = now
            }
            val token = issueTokenPair(userId, deviceId, now)
            AuthResponse(userId = userId.value, token = token)
        }
    }

    suspend fun refreshToken(request: TokenRefreshRequest, deviceId: String): AuthResponse {
        validateToken("refreshToken", request.refreshToken)
        return DatabaseFactory.dbQuery {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val tokenHash = sha256Hex(request.refreshToken)
            val session = UserSessionsTable.selectAll().where {
                (UserSessionsTable.refreshTokenHash eq tokenHash) and
                    (UserSessionsTable.deviceId eq deviceId) and
                    (UserSessionsTable.status eq SessionStatus.ACTIVE) and
                    (UserSessionsTable.expiresAt greaterEq now)
            }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "Invalid refresh token",
                    status = HttpStatusCode.Unauthorized
                )

            val userId = session[UserSessionsTable.userId]
            val refreshToken = generateSecureToken()
            UserSessionsTable.update({ UserSessionsTable.id eq session[UserSessionsTable.id] }) {
                it[refreshTokenHash] = sha256Hex(refreshToken)
                it[lastUsedAt] = now
                it[expiresAt] = now.plusSeconds(config.refreshTokenTtlSeconds)
                it[status] = SessionStatus.ACTIVE
                it[revokedAt] = null
            }

            val (accessToken, accessTtl) = jwtService.generateAccessToken(userId.value, deviceId)
            AuthResponse(
                userId = userId.value,
                token = TokenPairResponse(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accessTokenExpiresInSeconds = accessTtl,
                    refreshTokenExpiresInSeconds = config.refreshTokenTtlSeconds
                )
            )
        }
    }

    suspend fun resetPassword(request: ResetPasswordRequest) {
        val phone = normalizePhone(request.phone)
        validatePassword(request.newPassword)
        validateSmsCode(request.smsCode)
        DatabaseFactory.dbQuery {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val user = UsersTable.selectAll().where { UsersTable.phone eq phone }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.PHONE_NOT_REGISTERED,
                    message = "Phone is not registered",
                    status = HttpStatusCode.NotFound
                )
            val userId = user[UsersTable.id]
            val consumed = consumeSmsCode(phone, SmsPurpose.RESET_PASSWORD, request.smsCode, now)
            if (!consumed) {
                throw AppException(
                    code = ErrorCodes.INVALID_SMS_CODE,
                    message = "Invalid or expired sms code",
                    status = HttpStatusCode.BadRequest
                )
            }

            UsersTable.update({ UsersTable.id eq userId }) {
                it[passwordHash] = PasswordHasher.hash(request.newPassword)
                it[updatedAt] = now
            }
            UserSessionsTable.update({ UserSessionsTable.userId eq userId }) {
                it[status] = SessionStatus.REVOKED
                it[revokedAt] = now
                it[lastUsedAt] = now
            }
        }
    }

    suspend fun logout(userId: Long, deviceId: String, refreshToken: String?) {
        refreshToken?.let { validateToken("refreshToken", it) }
        DatabaseFactory.dbQuery {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val userEntityId = EntityID(userId, UsersTable)
            val session = UserSessionsTable.selectAll().where {
                (UserSessionsTable.userId eq userEntityId) and
                    (UserSessionsTable.deviceId eq deviceId) and
                    (UserSessionsTable.status eq SessionStatus.ACTIVE)
            }.firstOrNull() ?: return@dbQuery

            if (refreshToken != null && session[UserSessionsTable.refreshTokenHash] != sha256Hex(refreshToken)) {
                throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "Refresh token does not match device session",
                    status = HttpStatusCode.Unauthorized
                )
            }
            UserSessionsTable.update({ UserSessionsTable.id eq session[UserSessionsTable.id] }) {
                it[status] = SessionStatus.REVOKED
                it[revokedAt] = now
                it[lastUsedAt] = now
            }
        }
    }

    suspend fun getProfile(userId: Long): UserProfileResponse {
        return DatabaseFactory.dbQuery {
            val userEntityId = EntityID(userId, UsersTable)
            val user = UsersTable.selectAll().where { UsersTable.id eq userEntityId }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "User not found",
                    status = HttpStatusCode.Unauthorized
                )
            val now = LocalDateTime.now(ZoneOffset.UTC)
            ensureProfileRow(userEntityId, now)
            buildProfileResponse(userEntityId, user[UsersTable.phone])
        }
    }

    suspend fun upsertProfile(userId: Long, request: UpsertProfileRequest): UserProfileResponse {
        validateProfileUpdateRequest(request)
        return DatabaseFactory.dbQuery {
            val userEntityId = EntityID(userId, UsersTable)
            val user = UsersTable.selectAll().where { UsersTable.id eq userEntityId }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "User not found",
                    status = HttpStatusCode.Unauthorized
                )
            val now = LocalDateTime.now(ZoneOffset.UTC)
            ensureProfileRow(userEntityId, now)
            applyProfileUpdate(userEntityId, request, now)
            UsersTable.update({ UsersTable.id eq userEntityId }) {
                it[updatedAt] = now
            }
            buildProfileResponse(userEntityId, user[UsersTable.phone])
        }
    }

    suspend fun getBasicProfile(userId: Long): UserBasicProfileResponse {
        return DatabaseFactory.dbQuery {
            val userEntityId = EntityID(userId, UsersTable)
            val exists = UsersTable.selectAll().where { UsersTable.id eq userEntityId }.any()
            if (!exists) {
                throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "User not found",
                    status = HttpStatusCode.Unauthorized
                )
            }
            val now = LocalDateTime.now(ZoneOffset.UTC)
            ensureProfileRow(userEntityId, now)
            buildBasicProfileResponse(userEntityId)
        }
    }

    suspend fun upsertBasicProfile(userId: Long, request: UpsertBasicProfileRequest): UserBasicProfileResponse {
        validateBasicProfileUpdateRequest(request)
        return DatabaseFactory.dbQuery {
            val userEntityId = EntityID(userId, UsersTable)
            val exists = UsersTable.selectAll().where { UsersTable.id eq userEntityId }.any()
            if (!exists) {
                throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "User not found",
                    status = HttpStatusCode.Unauthorized
                )
            }
            val now = LocalDateTime.now(ZoneOffset.UTC)
            ensureProfileRow(userEntityId, now)
            applyBasicProfileUpdate(userEntityId, request, now)
            UsersTable.update({ UsersTable.id eq userEntityId }) {
                it[updatedAt] = now
            }
            buildBasicProfileResponse(userEntityId)
        }
    }

    suspend fun upsertAvatar(userId: Long, rawBytes: ByteArray): UserAvatarMetaResponse {
        if (rawBytes.isEmpty()) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "Avatar file cannot be empty",
                status = HttpStatusCode.BadRequest
            )
        }
        if (rawBytes.size > AVATAR_MAX_UPLOAD_BYTES) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "Avatar file exceeds $AVATAR_MAX_UPLOAD_BYTES bytes",
                status = HttpStatusCode.BadRequest
            )
        }

        val userEntityId = EntityID(userId, UsersTable)
        DatabaseFactory.dbQuery {
            val exists = UsersTable.selectAll().where { UsersTable.id eq userEntityId }.any()
            if (!exists) {
                throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "User not found",
                    status = HttpStatusCode.Unauthorized
                )
            }
        }

        val pngBytes = normalizeAvatarToPng(rawBytes)
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val fileName = avatarFileName(userId)
        writeAvatarFile(fileName, pngBytes)
        val sha256 = sha256Hex(pngBytes)

        return DatabaseFactory.dbQuery {
            upsertAvatarMeta(userEntityId, fileName, pngBytes.size.toLong(), sha256, now)
            buildAvatarMetaResponse(sizeBytes = pngBytes.size.toLong(), updatedAt = now)
        }
    }

    suspend fun getAvatarBinary(userId: Long): AvatarBinaryPayload {
        val userEntityId = EntityID(userId, UsersTable)
        val meta = DatabaseFactory.dbQuery {
            val exists = UsersTable.selectAll().where { UsersTable.id eq userEntityId }.any()
            if (!exists) {
                throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "User not found",
                    status = HttpStatusCode.Unauthorized
                )
            }

            val row = UserAvatarsTable.selectAll().where { UserAvatarsTable.userId eq userEntityId }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.AVATAR_NOT_FOUND,
                    message = "Avatar not found",
                    status = HttpStatusCode.NotFound
                )
            AvatarMeta(
                fileName = row[UserAvatarsTable.fileName],
                contentType = row[UserAvatarsTable.contentType],
                sha256 = row[UserAvatarsTable.sha256],
                updatedAt = row[UserAvatarsTable.updatedAt]
            )
        }

        val filePath = avatarFilePath(meta.fileName)
        if (!Files.exists(filePath)) {
            throw AppException(
                code = ErrorCodes.AVATAR_NOT_FOUND,
                message = "Avatar not found",
                status = HttpStatusCode.NotFound
            )
        }
        val bytes = runCatching { Files.readAllBytes(filePath) }
            .getOrElse { throw IllegalStateException("Failed to read avatar file: $filePath", it) }

        return AvatarBinaryPayload(
            bytes = bytes,
            contentType = meta.contentType,
            sha256 = meta.sha256,
            updatedAt = meta.updatedAt
        )
    }

    suspend fun deleteAccountByPhoneForDebug(phone: String) {
        val normalizedPhone = normalizePhone(phone)
        val avatarFileName = DatabaseFactory.dbQuery {
            val user = UsersTable.selectAll().where { UsersTable.phone eq normalizedPhone }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.PHONE_NOT_REGISTERED,
                    message = "Phone is not registered",
                    status = HttpStatusCode.NotFound
                )
            val userId = user[UsersTable.id]
            val existingAvatarFileName = UserAvatarsTable.selectAll().where { UserAvatarsTable.userId eq userId }
                .firstOrNull()
                ?.get(UserAvatarsTable.fileName)
            UsersTable.deleteWhere { UsersTable.id eq userId }
            SmsVerificationCodesTable.deleteWhere { SmsVerificationCodesTable.phone eq normalizedPhone }
            SmsVerificationAttemptsTable.deleteWhere { SmsVerificationAttemptsTable.phone eq normalizedPhone }
            existingAvatarFileName
        }
        avatarFileName?.let { deleteAvatarFileIfExists(it) }
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

    private fun validateSmsCode(smsCode: String) {
        if (!SMS_CODE_REGEX.matches(smsCode)) {
            throw AppException(
                code = ErrorCodes.INVALID_SMS_CODE,
                message = "Invalid sms code format",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun validateToken(fieldName: String, value: String) {
        ensureNoControlChars(fieldName, value)
        if (value.length > TOKEN_MAX_LENGTH) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "$fieldName exceeds $TOKEN_MAX_LENGTH characters",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun Transaction.validateSmsBusinessRules(phone: String, purpose: SmsPurpose, now: LocalDateTime) {
        when (purpose) {
            SmsPurpose.REGISTER -> {
                val exists = UsersTable.selectAll().where { UsersTable.phone eq phone }.any()
                if (exists) {
                    throw AppException(
                        code = ErrorCodes.PHONE_ALREADY_REGISTERED,
                        message = "Phone already registered",
                        status = HttpStatusCode.Conflict
                    )
                }
            }

            SmsPurpose.RESET_PASSWORD -> {
                val exists = UsersTable.selectAll().where { UsersTable.phone eq phone }.any()
                if (!exists) {
                    throw AppException(
                        code = ErrorCodes.PHONE_NOT_REGISTERED,
                        message = "Phone is not registered",
                        status = HttpStatusCode.NotFound
                    )
                }
            }
        }

        val latest = SmsVerificationCodesTable.selectAll().where {
            SmsVerificationCodesTable.phone eq phone
        }.orderBy(SmsVerificationCodesTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
        if (latest != null) {
            val latestAt = latest[SmsVerificationCodesTable.createdAt]
            if (latestAt.plusSeconds(config.smsCooldownSeconds) > now) {
                throw AppException(
                    code = ErrorCodes.SMS_TOO_FREQUENT,
                    message = "Sms code requested too frequently",
                    status = HttpStatusCode.TooManyRequests
                )
            }
        }

        val dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay()
        val todayCount = SmsVerificationCodesTable.selectAll().where {
            (SmsVerificationCodesTable.phone eq phone) and
                (SmsVerificationCodesTable.createdAt greaterEq dayStart)
        }.count()
        if (todayCount >= config.smsDailyLimit) {
            throw AppException(
                code = ErrorCodes.SMS_DAILY_LIMIT,
                message = "Daily sms code limit reached",
                status = HttpStatusCode.TooManyRequests
            )
        }
    }

    private fun Transaction.consumeSmsCode(
        phone: String,
        purpose: SmsPurpose,
        code: String,
        now: LocalDateTime
    ): Boolean {
        if (smsGateway != null) {
            ensureSmsVerificationAttemptAllowed(phone, purpose, now)
            val success = smsGateway.verifySmsCode(phone = phone, purpose = purpose, code = code)
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
            it[consumedAt] = now
        }
        val success = affected > 0
        recordSmsVerificationAttempt(phone, purpose, success, now)
        return success
    }

    private fun Transaction.ensureSmsVerificationAttemptAllowed(
        phone: String,
        purpose: SmsPurpose,
        now: LocalDateTime
    ) {
        val windowStart = now.minusSeconds(config.smsVerifyWindowSeconds)
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
        if (failedAttempts >= config.smsVerifyMaxAttempts) {
            throw AppException(
                code = ErrorCodes.SMS_VERIFY_TOO_MANY_ATTEMPTS,
                message = "Sms code verification attempts exceeded, please retry later",
                status = HttpStatusCode.TooManyRequests
            )
        }
    }

    private fun Transaction.recordSmsVerificationAttempt(
        phone: String,
        purpose: SmsPurpose,
        success: Boolean,
        now: LocalDateTime
    ) {
        SmsVerificationAttemptsTable.insert {
            it[SmsVerificationAttemptsTable.phone] = phone
            it[SmsVerificationAttemptsTable.purpose] = purpose
            it[SmsVerificationAttemptsTable.success] = success
            it[SmsVerificationAttemptsTable.createdAt] = now
        }
    }

    private fun validateProfileUpdateRequest(request: UpsertProfileRequest) {
        validateTextLength("fullName", request.fullName)
        request.familyHistory?.forEach { validateTextLength("familyHistory item", it) }
        request.medicalHistory?.forEach { validateTextLength("medicalHistory item", it) }
        request.medicationHistory?.forEach { validateTextLength("medicationHistory item", it) }
    }

    private fun validateBasicProfileUpdateRequest(request: UpsertBasicProfileRequest) {
        validateTextLength("fullName", request.fullName)
        parseBirthDateOrThrow(request.birthDate)
        validateMetricRange("heightCm", request.heightCm, HEIGHT_CM_MIN, HEIGHT_CM_MAX)
        validateMetricRange("weightKg", request.weightKg, WEIGHT_KG_MIN, WEIGHT_KG_MAX)
        validateMetricRange("waistCm", request.waistCm, WAIST_CM_MIN, WAIST_CM_MAX)
        request.diseaseHistory?.let { validateStringList("diseaseHistory", it) }
    }

    private fun validateMetricRange(fieldName: String, value: Double?, min: Double, max: Double) {
        if (value == null) {
            return
        }
        if (!value.isFinite()) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "$fieldName must be a finite number",
                status = HttpStatusCode.BadRequest
            )
        }
        if (value < min || value > max) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "$fieldName must be between $min and $max",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun validateStringList(fieldName: String, items: List<String>) {
        if (items.size > PROFILE_LIST_MAX_SIZE) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "$fieldName exceeds $PROFILE_LIST_MAX_SIZE items",
                status = HttpStatusCode.BadRequest
            )
        }
        items.forEach { validateTextLength("$fieldName item", it) }
    }

    private fun parseBirthDateOrThrow(raw: String?): LocalDate? {
        if (raw == null) {
            return null
        }
        val parsed = runCatching { LocalDate.parse(raw) }.getOrElse {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "birthDate must be ISO-8601 format (yyyy-MM-dd)",
                status = HttpStatusCode.BadRequest
            )
        }
        val today = LocalDate.now(ZoneOffset.UTC)
        if (parsed > today) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "birthDate cannot be in the future",
                status = HttpStatusCode.BadRequest
            )
        }
        return parsed
    }

    private fun normalizeStringList(items: List<String>): List<String> {
        val deduped = LinkedHashSet<String>()
        items.forEach { raw ->
            val item = raw.trim()
            if (item.isNotEmpty()) {
                deduped.add(item)
            }
        }
        return deduped.toList()
    }

    private fun validateTextLength(fieldName: String, value: String?) {
        if (value == null) {
            return
        }
        ensureNoControlChars(fieldName, value)
        val charCount = value.codePointCount(0, value.length)
        if (charCount > PROFILE_TEXT_MAX_LENGTH) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "$fieldName exceeds $PROFILE_TEXT_MAX_LENGTH characters",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun ensureNoControlChars(fieldName: String, value: String) {
        if (value.any { it.isISOControl() }) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "$fieldName contains control characters",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun Transaction.issueTokenPair(
        userId: EntityID<Long>,
        deviceId: String,
        now: LocalDateTime
    ): TokenPairResponse {
        val refreshToken = generateSecureToken()
        val refreshHash = sha256Hex(refreshToken)
        val expiresAt = now.plusSeconds(config.refreshTokenTtlSeconds)
        val existing = UserSessionsTable.selectAll().where {
            (UserSessionsTable.userId eq userId) and (UserSessionsTable.deviceId eq deviceId)
        }.firstOrNull()

        if (existing == null) {
            UserSessionsTable.insert {
                it[UserSessionsTable.userId] = userId
                it[UserSessionsTable.deviceId] = deviceId
                it[refreshTokenHash] = refreshHash
                it[status] = SessionStatus.ACTIVE
                it[createdAt] = now
                it[lastUsedAt] = now
                it[UserSessionsTable.expiresAt] = expiresAt
                it[revokedAt] = null
            }
        } else {
            UserSessionsTable.update({ UserSessionsTable.id eq existing[UserSessionsTable.id] }) {
                it[UserSessionsTable.refreshTokenHash] = refreshHash
                it[status] = SessionStatus.ACTIVE
                it[lastUsedAt] = now
                it[UserSessionsTable.expiresAt] = expiresAt
                it[revokedAt] = null
            }
        }
        val (accessToken, accessTtl) = jwtService.generateAccessToken(userId.value, deviceId)
        return TokenPairResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresInSeconds = accessTtl,
            refreshTokenExpiresInSeconds = config.refreshTokenTtlSeconds
        )
    }

    private fun Transaction.ensureProfileRow(userId: EntityID<Long>, now: LocalDateTime) {
        val existing = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userId }.any()
        if (!existing) {
            UserProfilesTable.insert {
                it[UserProfilesTable.userId] = userId
                it[gender] = Gender.UNKNOWN
                it[updatedAt] = now
            }
        }
    }

    private fun Transaction.applyProfileUpdate(
        userId: EntityID<Long>,
        request: UpsertProfileRequest,
        now: LocalDateTime
    ) {
        val parsedBirthDate = request.birthDate?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrElse {
                throw AppException(
                    code = ErrorCodes.INVALID_REQUEST,
                    message = "birthDate must be ISO-8601 format (yyyy-MM-dd)",
                    status = HttpStatusCode.BadRequest
                )
            }
        }

        val current = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userId }.first()
        UserProfilesTable.update({ UserProfilesTable.userId eq userId }) {
            it[fullName] = request.fullName ?: current[UserProfilesTable.fullName]
            it[gender] = request.gender ?: current[UserProfilesTable.gender]
            it[birthDate] = parsedBirthDate ?: current[UserProfilesTable.birthDate]
            it[weightKg] = request.weightKg?.let { kg -> BigDecimal.valueOf(kg) } ?: current[UserProfilesTable.weightKg]
            it[updatedAt] = now
        }

        request.familyHistory?.let { replaceFamilyHistory(userId, it, now) }
        request.medicalHistory?.let { replaceMedicalHistory(userId, it, now) }
        request.medicationHistory?.let { replaceMedicationHistory(userId, it, now) }
    }

    private fun Transaction.replaceFamilyHistory(userId: EntityID<Long>, items: List<String>, now: LocalDateTime) {
        UserFamilyHistoriesTable.deleteWhere { UserFamilyHistoriesTable.userId eq userId }
        items.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
            UserFamilyHistoriesTable.insert {
                it[UserFamilyHistoriesTable.userId] = userId
                it[item] = value
                it[createdAt] = now
            }
        }
    }

    private fun Transaction.replaceMedicalHistory(userId: EntityID<Long>, items: List<String>, now: LocalDateTime) {
        UserMedicalHistoriesTable.deleteWhere { UserMedicalHistoriesTable.userId eq userId }
        items.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
            UserMedicalHistoriesTable.insert {
                it[UserMedicalHistoriesTable.userId] = userId
                it[item] = value
                it[createdAt] = now
            }
        }
    }

    private fun Transaction.replaceMedicationHistory(userId: EntityID<Long>, items: List<String>, now: LocalDateTime) {
        UserMedicationHistoriesTable.deleteWhere { UserMedicationHistoriesTable.userId eq userId }
        items.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
            UserMedicationHistoriesTable.insert {
                it[UserMedicationHistoriesTable.userId] = userId
                it[item] = value
                it[createdAt] = now
            }
        }
    }

    private fun Transaction.applyBasicProfileUpdate(
        userId: EntityID<Long>,
        request: UpsertBasicProfileRequest,
        now: LocalDateTime
    ) {
        val parsedBirthDate = parseBirthDateOrThrow(request.birthDate)
        val current = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userId }.first()
        UserProfilesTable.update({ UserProfilesTable.userId eq userId }) {
            it[fullName] = request.fullName ?: current[UserProfilesTable.fullName]
            it[gender] = request.gender ?: current[UserProfilesTable.gender]
            it[birthDate] = parsedBirthDate ?: current[UserProfilesTable.birthDate]
            it[heightCm] = request.heightCm?.let { cm -> BigDecimal.valueOf(cm) } ?: current[UserProfilesTable.heightCm]
            it[weightKg] = request.weightKg?.let { kg -> BigDecimal.valueOf(kg) } ?: current[UserProfilesTable.weightKg]
            it[waistCm] = request.waistCm?.let { cm -> BigDecimal.valueOf(cm) } ?: current[UserProfilesTable.waistCm]
            it[updatedAt] = now
        }

        request.diseaseHistory?.let { replaceDiseaseHistory(userId, normalizeStringList(it), now) }
    }

    private fun Transaction.replaceDiseaseHistory(userId: EntityID<Long>, items: List<String>, now: LocalDateTime) {
        UserDiseaseHistoriesTable.deleteWhere { UserDiseaseHistoriesTable.userId eq userId }
        items.forEach { value ->
            UserDiseaseHistoriesTable.insert {
                it[UserDiseaseHistoriesTable.userId] = userId
                it[item] = value
                it[createdAt] = now
            }
        }
    }

    private fun Transaction.buildProfileResponse(userId: EntityID<Long>, phone: String): UserProfileResponse {
        val profile = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userId }.first()
        val familyHistory = UserFamilyHistoriesTable.selectAll().where { UserFamilyHistoriesTable.userId eq userId }
            .orderBy(UserFamilyHistoriesTable.id, SortOrder.ASC).map { it[UserFamilyHistoriesTable.item] }
        val medicalHistory = UserMedicalHistoriesTable.selectAll().where { UserMedicalHistoriesTable.userId eq userId }
            .orderBy(UserMedicalHistoriesTable.id, SortOrder.ASC).map { it[UserMedicalHistoriesTable.item] }
        val medicationHistory = UserMedicationHistoriesTable.selectAll().where { UserMedicationHistoriesTable.userId eq userId }
            .orderBy(UserMedicationHistoriesTable.id, SortOrder.ASC).map { it[UserMedicationHistoriesTable.item] }

        return UserProfileResponse(
            userId = userId.value,
            phone = phone,
            fullName = profile[UserProfilesTable.fullName],
            gender = profile[UserProfilesTable.gender],
            birthDate = profile[UserProfilesTable.birthDate]?.toString(),
            weightKg = profile[UserProfilesTable.weightKg]?.toDouble(),
            familyHistory = familyHistory,
            medicalHistory = medicalHistory,
            medicationHistory = medicationHistory
        )
    }

    private fun Transaction.buildBasicProfileResponse(userId: EntityID<Long>): UserBasicProfileResponse {
        val profile = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userId }.first()
        val diseaseHistory = UserDiseaseHistoriesTable.selectAll().where { UserDiseaseHistoriesTable.userId eq userId }
            .orderBy(UserDiseaseHistoriesTable.id, SortOrder.ASC)
            .map { it[UserDiseaseHistoriesTable.item] }

        return UserBasicProfileResponse(
            userId = userId.value,
            fullName = profile[UserProfilesTable.fullName],
            gender = profile[UserProfilesTable.gender],
            birthDate = profile[UserProfilesTable.birthDate]?.toString(),
            heightCm = profile[UserProfilesTable.heightCm]?.toDouble(),
            weightKg = profile[UserProfilesTable.weightKg]?.toDouble(),
            waistCm = profile[UserProfilesTable.waistCm]?.toDouble(),
            diseaseHistory = diseaseHistory
        )
    }

    private data class AvatarMeta(
        val fileName: String,
        val contentType: String,
        val sha256: String,
        val updatedAt: LocalDateTime
    )

    private fun normalizeAvatarToPng(rawBytes: ByteArray): ByteArray {
        val image = runCatching {
            ByteArrayInputStream(rawBytes).use { input -> ImageIO.read(input) }
        }.getOrElse {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "Avatar file is not a valid image",
                status = HttpStatusCode.BadRequest
            )
        } ?: throw AppException(
            code = ErrorCodes.INVALID_REQUEST,
            message = "Avatar file is not a valid image",
            status = HttpStatusCode.BadRequest
        )

        if (image.width != AVATAR_SIZE_PX || image.height != AVATAR_SIZE_PX) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "Avatar image must be exactly ${AVATAR_SIZE_PX}x${AVATAR_SIZE_PX}",
                status = HttpStatusCode.BadRequest
            )
        }

        val normalized = BufferedImage(AVATAR_SIZE_PX, AVATAR_SIZE_PX, BufferedImage.TYPE_INT_ARGB)
        val graphics = normalized.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()

        val output = ByteArrayOutputStream()
        val written = ImageIO.write(normalized, "png", output)
        if (!written) {
            throw IllegalStateException("Failed to encode avatar as PNG")
        }
        return output.toByteArray()
    }

    private fun avatarFileName(userId: Long): String = "u_${userId}.png"

    private fun avatarFilePath(fileName: String): Path = Paths.get(AVATAR_STORAGE_DIR, fileName)

    private fun writeAvatarFile(fileName: String, bytes: ByteArray) {
        val filePath = avatarFilePath(fileName)
        runCatching {
            Files.createDirectories(filePath.parent)
            Files.write(filePath, bytes)
        }.getOrElse {
            throw IllegalStateException("Failed to persist avatar file: $filePath", it)
        }
    }

    private fun deleteAvatarFileIfExists(fileName: String) {
        val filePath = avatarFilePath(fileName)
        runCatching { Files.deleteIfExists(filePath) }
    }

    private fun Transaction.upsertAvatarMeta(
        userId: EntityID<Long>,
        fileName: String,
        sizeBytes: Long,
        sha256: String,
        now: LocalDateTime
    ) {
        val existing = UserAvatarsTable.selectAll().where { UserAvatarsTable.userId eq userId }.firstOrNull()
        if (existing == null) {
            UserAvatarsTable.insert {
                it[UserAvatarsTable.userId] = userId
                it[UserAvatarsTable.fileName] = fileName
                it[UserAvatarsTable.contentType] = AVATAR_CONTENT_TYPE
                it[UserAvatarsTable.sizeBytes] = sizeBytes
                it[UserAvatarsTable.sha256] = sha256
                it[UserAvatarsTable.updatedAt] = now
            }
            return
        }

        UserAvatarsTable.update({ UserAvatarsTable.userId eq userId }) {
            it[UserAvatarsTable.fileName] = fileName
            it[UserAvatarsTable.contentType] = AVATAR_CONTENT_TYPE
            it[UserAvatarsTable.sizeBytes] = sizeBytes
            it[UserAvatarsTable.sha256] = sha256
            it[UserAvatarsTable.updatedAt] = now
        }
    }

    private fun buildAvatarMetaResponse(sizeBytes: Long, updatedAt: LocalDateTime): UserAvatarMetaResponse {
        return UserAvatarMetaResponse(
            avatarUrl = AVATAR_API_URL,
            contentType = AVATAR_CONTENT_TYPE,
            width = AVATAR_SIZE_PX,
            height = AVATAR_SIZE_PX,
            sizeBytes = sizeBytes,
            updatedAt = updatedAt.atOffset(ZoneOffset.UTC).toString()
        )
    }
}
