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
import me.hztcm.mindisle.model.UpsertProfileRequest
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class UserManagementService(
    private val config: AuthConfig,
    private val jwtService: JwtService,
    private val smsGateway: SmsGateway?
) {
    private companion object {
        const val PROFILE_TEXT_MAX_LENGTH = 200
    }

    suspend fun sendSmsCode(request: SendSmsCodeRequest, requestIp: String?) {
        val phone = normalizePhone(request.phone)
        val now = LocalDateTime.now(ZoneOffset.UTC)
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
                    it[SmsVerificationCodesTable.expiresAt] = now.plusSeconds(config.smsCodeTtlSeconds)
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
            ttlSeconds = config.smsCodeTtlSeconds,
            intervalSeconds = config.smsCooldownSeconds
        )
        DatabaseFactory.dbQuery {
            SmsVerificationCodesTable.insert {
                it[SmsVerificationCodesTable.phone] = phone
                it[SmsVerificationCodesTable.purpose] = request.purpose
                it[SmsVerificationCodesTable.codeHash] = sha256Hex(sendResult.outId ?: generateSecureToken(16))
                it[SmsVerificationCodesTable.expiresAt] = now.plusSeconds(config.smsCodeTtlSeconds)
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

    suspend fun deleteAccountByPhoneForDebug(phone: String) {
        val normalizedPhone = normalizePhone(phone)
        DatabaseFactory.dbQuery {
            val user = UsersTable.selectAll().where { UsersTable.phone eq normalizedPhone }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.PHONE_NOT_REGISTERED,
                    message = "Phone is not registered",
                    status = HttpStatusCode.NotFound
                )
            val userId = user[UsersTable.id]
            UsersTable.deleteWhere { UsersTable.id eq userId }
            SmsVerificationCodesTable.deleteWhere { SmsVerificationCodesTable.phone eq normalizedPhone }
            SmsVerificationAttemptsTable.deleteWhere { SmsVerificationAttemptsTable.phone eq normalizedPhone }
        }
    }

    private fun validatePassword(password: String) {
        if (password.length < 6) {
            throw AppException(
                code = ErrorCodes.PASSWORD_TOO_SHORT,
                message = "Password must be at least 6 characters",
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

    private fun validateTextLength(fieldName: String, value: String?) {
        if (value == null) {
            return
        }
        val charCount = value.codePointCount(0, value.length)
        if (charCount > PROFILE_TEXT_MAX_LENGTH) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "$fieldName exceeds $PROFILE_TEXT_MAX_LENGTH characters",
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
}
