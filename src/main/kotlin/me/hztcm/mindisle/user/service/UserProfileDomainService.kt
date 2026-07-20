package me.hztcm.mindisle.user.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.auth.ensureNoControlChars as ensureNoControlCharsCommon
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.UserAvatarsTable
import me.hztcm.mindisle.db.UserDiseaseHistoriesTable
import me.hztcm.mindisle.db.UserFamilyHistoriesTable
import me.hztcm.mindisle.db.UserMedicalHistoriesTable
import me.hztcm.mindisle.db.UserMedicationHistoriesTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UserWeightLogsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.Gender
import me.hztcm.mindisle.model.UpsertBasicProfileRequest
import me.hztcm.mindisle.model.UpsertProfileRequest
import me.hztcm.mindisle.model.UserAvatarMetaResponse
import me.hztcm.mindisle.model.UserBasicProfileResponse
import me.hztcm.mindisle.model.UserProfileResponse
import me.hztcm.mindisle.util.sha256Hex
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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

/**
 * Patient profile / avatar domain logic extracted from [UserManagementService].
 * Public behavior remains identical; auth still goes through the facade.
 */
class UserProfileDomainService {
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
        const val AVATAR_SIZE_PX = 1024
        const val AVATAR_MAX_UPLOAD_BYTES = 5 * 1024 * 1024
        const val AVATAR_CONTENT_TYPE = "image/png"
        const val AVATAR_STORAGE_DIR = "data/avatars"
        const val AVATAR_API_URL = "/api/v1/users/me/avatar"
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

    fun validateProfileUpdateRequest(request: UpsertProfileRequest) {
        validateTextLength("fullName", request.fullName)
        request.familyHistory?.forEach { validateTextLength("familyHistory item", it) }
        request.medicalHistory?.forEach { validateTextLength("medicalHistory item", it) }
        request.medicationHistory?.forEach { validateTextLength("medicationHistory item", it) }
    }

    fun validateBasicProfileUpdateRequest(request: UpsertBasicProfileRequest) {
        validateTextLength("fullName", request.fullName)
        parseBirthDateOrThrow(request.birthDate)
        validateMetricRange("heightCm", request.heightCm, HEIGHT_CM_MIN, HEIGHT_CM_MAX)
        validateMetricRange("weightKg", request.weightKg, WEIGHT_KG_MIN, WEIGHT_KG_MAX)
        validateMetricRange("waistCm", request.waistCm, WAIST_CM_MIN, WAIST_CM_MAX)
        request.diseaseHistory?.let { validateStringList("diseaseHistory", it) }
    }

    fun validateMetricRange(fieldName: String, value: Double?, min: Double, max: Double) {
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

    fun validateStringList(fieldName: String, items: List<String>) {
        if (items.size > PROFILE_LIST_MAX_SIZE) {
            throw AppException(
                code = ErrorCodes.INVALID_REQUEST,
                message = "$fieldName exceeds $PROFILE_LIST_MAX_SIZE items",
                status = HttpStatusCode.BadRequest
            )
        }
        items.forEach { validateTextLength("$fieldName item", it) }
    }

    fun parseBirthDateOrThrow(raw: String?): LocalDate? {
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

    fun normalizeStringList(items: List<String>): List<String> {
        val deduped = LinkedHashSet<String>()
        items.forEach { raw ->
            val item = raw.trim()
            if (item.isNotEmpty()) {
                deduped.add(item)
            }
        }
        return deduped.toList()
    }

    fun validateTextLength(fieldName: String, value: String?) {
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

    fun ensureNoControlChars(fieldName: String, value: String) {
        ensureNoControlCharsCommon(fieldName, value)
    }

    fun ensureProfileRow(userId: EntityID<Long>, now: LocalDateTime) {
        val existing = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userId }.any()
        if (!existing) {
            UserProfilesTable.insert {
                it[UserProfilesTable.userId] = userId
                it[gender] = Gender.UNKNOWN
                it[updatedAt] = now
            }
        }
    }

    fun applyProfileUpdate(
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
        val oldWeight = current[UserProfilesTable.weightKg]
        val newWeight = request.weightKg?.let { kg -> BigDecimal.valueOf(kg) } ?: oldWeight
        UserProfilesTable.update({ UserProfilesTable.userId eq userId }) {
            it[fullName] = request.fullName ?: current[UserProfilesTable.fullName]
            it[gender] = request.gender ?: current[UserProfilesTable.gender]
            it[birthDate] = parsedBirthDate ?: current[UserProfilesTable.birthDate]
            it[weightKg] = newWeight
            it[updatedAt] = now
        }
        recordWeightLogIfChanged(userId, oldWeight, newWeight, now, source = "PROFILE_UPDATE")

        request.familyHistory?.let { replaceFamilyHistory(userId, it, now) }
        request.medicalHistory?.let { replaceMedicalHistory(userId, it, now) }
        request.medicationHistory?.let { replaceMedicationHistory(userId, it, now) }
    }

    fun replaceFamilyHistory(userId: EntityID<Long>, items: List<String>, now: LocalDateTime) {
        UserFamilyHistoriesTable.deleteWhere { UserFamilyHistoriesTable.userId eq userId }
        items.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
            UserFamilyHistoriesTable.insert {
                it[UserFamilyHistoriesTable.userId] = userId
                it[item] = value
                it[createdAt] = now
            }
        }
    }

    fun replaceMedicalHistory(userId: EntityID<Long>, items: List<String>, now: LocalDateTime) {
        UserMedicalHistoriesTable.deleteWhere { UserMedicalHistoriesTable.userId eq userId }
        items.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
            UserMedicalHistoriesTable.insert {
                it[UserMedicalHistoriesTable.userId] = userId
                it[item] = value
                it[createdAt] = now
            }
        }
    }

    fun replaceMedicationHistory(userId: EntityID<Long>, items: List<String>, now: LocalDateTime) {
        UserMedicationHistoriesTable.deleteWhere { UserMedicationHistoriesTable.userId eq userId }
        items.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
            UserMedicationHistoriesTable.insert {
                it[UserMedicationHistoriesTable.userId] = userId
                it[item] = value
                it[createdAt] = now
            }
        }
    }

    fun applyBasicProfileUpdate(
        userId: EntityID<Long>,
        request: UpsertBasicProfileRequest,
        now: LocalDateTime
    ) {
        val parsedBirthDate = parseBirthDateOrThrow(request.birthDate)
        val current = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userId }.first()
        val oldWeight = current[UserProfilesTable.weightKg]
        val newWeight = request.weightKg?.let { kg -> BigDecimal.valueOf(kg) } ?: oldWeight
        UserProfilesTable.update({ UserProfilesTable.userId eq userId }) {
            it[fullName] = request.fullName ?: current[UserProfilesTable.fullName]
            it[gender] = request.gender ?: current[UserProfilesTable.gender]
            it[birthDate] = parsedBirthDate ?: current[UserProfilesTable.birthDate]
            it[heightCm] = request.heightCm?.let { cm -> BigDecimal.valueOf(cm) } ?: current[UserProfilesTable.heightCm]
            it[weightKg] = newWeight
            it[waistCm] = request.waistCm?.let { cm -> BigDecimal.valueOf(cm) } ?: current[UserProfilesTable.waistCm]
            it[usesTcm] = request.usesTcm ?: current[UserProfilesTable.usesTcm]
            it[updatedAt] = now
        }
        recordWeightLogIfChanged(userId, oldWeight, newWeight, now, source = "BASIC_PROFILE_UPDATE")

        request.diseaseHistory?.let { replaceDiseaseHistory(userId, normalizeStringList(it), now) }
    }

    fun recordWeightLogIfChanged(
        userId: EntityID<Long>,
        oldWeight: BigDecimal?,
        newWeight: BigDecimal?,
        now: LocalDateTime,
        source: String
    ) {
        if (newWeight == null) {
            return
        }
        if (oldWeight != null && oldWeight.compareTo(newWeight) == 0) {
            return
        }
        UserWeightLogsTable.insert {
            it[UserWeightLogsTable.userId] = userId
            it[weightKg] = newWeight
            it[recordedAt] = now
            it[UserWeightLogsTable.sourceType] = source
            it[createdAt] = now
        }
    }

    fun replaceDiseaseHistory(userId: EntityID<Long>, items: List<String>, now: LocalDateTime) {
        UserDiseaseHistoriesTable.deleteWhere { UserDiseaseHistoriesTable.userId eq userId }
        items.forEach { value ->
            UserDiseaseHistoriesTable.insert {
                it[UserDiseaseHistoriesTable.userId] = userId
                it[item] = value
                it[createdAt] = now
            }
        }
    }

    fun buildProfileResponse(userId: EntityID<Long>, phone: String): UserProfileResponse {
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

    fun buildBasicProfileResponse(userId: EntityID<Long>): UserBasicProfileResponse {
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
            usesTcm = profile[UserProfilesTable.usesTcm],
            diseaseHistory = diseaseHistory
        )
    }

    data class AvatarMeta(
        val fileName: String,
        val contentType: String,
        val sha256: String,
        val updatedAt: LocalDateTime
    )

    fun normalizeAvatarToPng(rawBytes: ByteArray): ByteArray {
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

    fun avatarFileName(userId: Long): String = "u_${userId}.png"

    fun avatarFilePath(fileName: String): Path = Paths.get(AVATAR_STORAGE_DIR, fileName)

    fun writeAvatarFile(fileName: String, bytes: ByteArray) {
        val filePath = avatarFilePath(fileName)
        runCatching {
            Files.createDirectories(filePath.parent)
            Files.write(filePath, bytes)
        }.getOrElse {
            throw IllegalStateException("Failed to persist avatar file: $filePath", it)
        }
    }

    fun deleteAvatarFileIfExists(fileName: String) {
        val filePath = avatarFilePath(fileName)
        runCatching { Files.deleteIfExists(filePath) }
    }

    fun upsertAvatarMeta(
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

    fun buildAvatarMetaResponse(sizeBytes: Long, updatedAt: LocalDateTime): UserAvatarMetaResponse {
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
