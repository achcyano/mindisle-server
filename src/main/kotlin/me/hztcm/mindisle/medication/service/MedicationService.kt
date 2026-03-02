package me.hztcm.mindisle.medication.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.UserMedicationsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.CreateMedicationRequest
import me.hztcm.mindisle.model.MedicationItemResponse
import me.hztcm.mindisle.model.MedicationListResponse
import me.hztcm.mindisle.model.UpdateMedicationRequest
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDate
import java.time.ZoneOffset

class MedicationService {
    private val json = Json

    suspend fun createMedication(userId: Long, request: CreateMedicationRequest): MedicationItemResponse {
        return DatabaseFactory.dbQuery {
            val nowUtc = utcNow()
            val todayPlus8 = nowUtc.toLocalDatePlus8()
            val validated = MedicationValidators.validateCreateRequest(request, todayPlus8)
            val userEntityId = EntityID(userId, UsersTable)
            ensureUserExists(userEntityId)

            val insertedId = UserMedicationsTable.insert {
                it[UserMedicationsTable.userId] = userEntityId
                it[drugName] = validated.drugName
                it[doseTimesJson] = json.encodeToString(validated.doseTimes)
                it[recordedDateLocal] = todayPlus8
                it[endDateLocal] = validated.endDate
                it[doseAmount] = validated.doseAmount
                it[doseUnit] = validated.doseUnit
                it[tabletStrengthAmount] = validated.tabletStrengthAmount
                it[tabletStrengthUnit] = validated.tabletStrengthUnit
                it[createdAt] = nowUtc
                it[updatedAt] = nowUtc
            }[UserMedicationsTable.id]

            val row = UserMedicationsTable.selectAll().where {
                UserMedicationsTable.id eq insertedId
            }.firstOrNull() ?: throw IllegalStateException("Failed to load inserted medication")
            row.toResponse(todayPlus8)
        }
    }

    suspend fun listMedications(
        userId: Long,
        limit: Int,
        cursor: Long?,
        onlyActive: Boolean
    ): MedicationListResponse {
        return DatabaseFactory.dbQuery {
            val safeLimit = limit.coerceIn(1, 200)
            val nowUtc = utcNow()
            val todayPlus8 = nowUtc.toLocalDatePlus8()
            val userEntityId = EntityID(userId, UsersTable)
            ensureUserExists(userEntityId)

            val condition = buildListCondition(userEntityId, cursor, onlyActive, todayPlus8)
            val rows = UserMedicationsTable.selectAll().where { condition }
                .orderBy(UserMedicationsTable.id, SortOrder.DESC)
                .limit(safeLimit + 1)
                .toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val items = page.map { it.toResponse(todayPlus8) }
            val nextCursor = if (hasMore) page.last()[UserMedicationsTable.id].value.toString() else null
            MedicationListResponse(
                items = items,
                activeCount = items.count { it.isActive },
                nextCursor = nextCursor
            )
        }
    }

    suspend fun updateMedication(
        userId: Long,
        medicationId: Long,
        request: UpdateMedicationRequest
    ): MedicationItemResponse {
        return DatabaseFactory.dbQuery {
            val nowUtc = utcNow()
            val todayPlus8 = nowUtc.toLocalDatePlus8()
            val userEntityId = EntityID(userId, UsersTable)
            ensureUserExists(userEntityId)

            val existing = requireOwnedMedication(userEntityId, medicationId)
            val validated = MedicationValidators.validateUpdateRequest(
                request = request,
                minEndDateInclusive = existing[UserMedicationsTable.recordedDateLocal]
            )

            UserMedicationsTable.update({ UserMedicationsTable.id eq existing[UserMedicationsTable.id] }) {
                it[drugName] = validated.drugName
                it[doseTimesJson] = json.encodeToString(validated.doseTimes)
                it[endDateLocal] = validated.endDate
                it[doseAmount] = validated.doseAmount
                it[doseUnit] = validated.doseUnit
                it[tabletStrengthAmount] = validated.tabletStrengthAmount
                it[tabletStrengthUnit] = validated.tabletStrengthUnit
                it[updatedAt] = nowUtc
            }

            val updated = UserMedicationsTable.selectAll().where {
                UserMedicationsTable.id eq existing[UserMedicationsTable.id]
            }.firstOrNull() ?: throw IllegalStateException("Failed to load updated medication")
            updated.toResponse(todayPlus8)
        }
    }

    suspend fun deleteMedication(userId: Long, medicationId: Long) {
        DatabaseFactory.dbQuery {
            val userEntityId = EntityID(userId, UsersTable)
            ensureUserExists(userEntityId)
            val row = requireOwnedMedication(userEntityId, medicationId)
            UserMedicationsTable.deleteWhere { UserMedicationsTable.id eq row[UserMedicationsTable.id] }
        }
    }

    private fun buildListCondition(
        userId: EntityID<Long>,
        cursor: Long?,
        onlyActive: Boolean,
        todayPlus8: LocalDate
    ): org.jetbrains.exposed.sql.Op<Boolean> {
        return org.jetbrains.exposed.sql.SqlExpressionBuilder.run {
            val cursorCondition = if (cursor != null) {
                UserMedicationsTable.id less cursor
            } else {
                UserMedicationsTable.id greater 0L
            }
            var condition = (UserMedicationsTable.userId eq userId) and cursorCondition
            if (onlyActive) {
                condition = condition and
                    (UserMedicationsTable.recordedDateLocal lessEq todayPlus8) and
                    (UserMedicationsTable.endDateLocal greaterEq todayPlus8)
            }
            condition
        }
    }

    private fun requireOwnedMedication(userId: EntityID<Long>, medicationId: Long): ResultRow {
        val ref = EntityID(medicationId, UserMedicationsTable)
        val row = UserMedicationsTable.selectAll().where {
            UserMedicationsTable.id eq ref
        }.firstOrNull() ?: throw medicationNotFound()
        if (row[UserMedicationsTable.userId].value != userId.value) {
            throw medicationNotFound()
        }
        return row
    }

    private fun ensureUserExists(userId: EntityID<Long>) {
        val exists = UsersTable.selectAll().where { UsersTable.id eq userId }.any()
        if (!exists) {
            throw AppException(
                code = ErrorCodes.UNAUTHORIZED,
                message = "User not found",
                status = HttpStatusCode.Unauthorized
            )
        }
    }

    private fun ResultRow.toResponse(todayPlus8: LocalDate): MedicationItemResponse {
        val recordedDate = this[UserMedicationsTable.recordedDateLocal]
        val endDate = this[UserMedicationsTable.endDateLocal]
        val doseTimes = parseDoseTimes(this[UserMedicationsTable.doseTimesJson])
        val createdAt = this[UserMedicationsTable.createdAt].atOffset(ZoneOffset.UTC).toInstant().toString()
        val updatedAt = this[UserMedicationsTable.updatedAt].atOffset(ZoneOffset.UTC).toInstant().toString()
        val isActive = recordedDate <= todayPlus8 && endDate >= todayPlus8
        return MedicationItemResponse(
            medicationId = this[UserMedicationsTable.id].value,
            drugName = this[UserMedicationsTable.drugName],
            doseTimes = doseTimes,
            recordedDate = recordedDate.toString(),
            endDate = endDate.toString(),
            doseAmount = this[UserMedicationsTable.doseAmount].toDouble(),
            doseUnit = this[UserMedicationsTable.doseUnit],
            tabletStrengthAmount = this[UserMedicationsTable.tabletStrengthAmount]?.toDouble(),
            tabletStrengthUnit = this[UserMedicationsTable.tabletStrengthUnit],
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun parseDoseTimes(raw: String): List<String> {
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrElse { throw IllegalStateException("Invalid dose_times_json payload", it) }
    }

    private fun medicationNotFound(): AppException {
        return AppException(
            code = ErrorCodes.MEDICATION_NOT_FOUND,
            message = "Medication not found",
            status = HttpStatusCode.NotFound
        )
    }
}
