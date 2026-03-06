package me.hztcm.mindisle.doctor.service

import kotlinx.serialization.encodeToString
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.UserMedicationsTable
import me.hztcm.mindisle.db.UserSideEffectsTable
import me.hztcm.mindisle.db.UserWeightLogsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.medication.service.MedicationValidators
import me.hztcm.mindisle.model.CreateMedicationRequest
import me.hztcm.mindisle.model.CreateSideEffectRequest
import me.hztcm.mindisle.model.MedicationItemResponse
import me.hztcm.mindisle.model.MedicationListResponse
import me.hztcm.mindisle.model.SideEffectItemResponse
import me.hztcm.mindisle.model.SideEffectSummaryItem
import me.hztcm.mindisle.model.SideEffectSummaryResponse
import me.hztcm.mindisle.model.UpdateMedicationRequest
import me.hztcm.mindisle.model.WeightTrendPoint
import me.hztcm.mindisle.model.WeightTrendResponse
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDate

private const val NOTE_MAX_LENGTH = 1000
private const val SIDE_EFFECT_SYMPTOM_MAX_LENGTH = 200

internal class DoctorMonitoringDomainService(private val deps: DoctorServiceDeps) {
    suspend fun createPatientMedication(
        doctorId: Long,
        patientUserId: Long,
        request: CreateMedicationRequest
    ): MedicationItemResponse {
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val nowUtc = utcNow()
            val todayPlus8 = nowUtc.toLocalDatePlus8()
            val validated = MedicationValidators.validateCreateRequest(request, todayPlus8)
            val patientRef = EntityID(patientUserId, UsersTable)
            val insertedId = UserMedicationsTable.insert {
                it[UserMedicationsTable.userId] = patientRef
                it[drugName] = validated.drugName
                it[doseTimesJson] = deps.json.encodeToString(validated.doseTimes)
                it[recordedDateLocal] = todayPlus8
                it[endDateLocal] = validated.endDate
                it[doseAmount] = validated.doseAmount
                it[doseUnit] = validated.doseUnit
                it[tabletStrengthAmount] = validated.tabletStrengthAmount
                it[tabletStrengthUnit] = validated.tabletStrengthUnit
                it[deletedAt] = null
                it[createdAt] = nowUtc
                it[updatedAt] = nowUtc
            }[UserMedicationsTable.id]
            val row = UserMedicationsTable.selectAll().where { UserMedicationsTable.id eq insertedId }.first()
            row.toMedicationResponse(todayPlus8, deps)
        }
    }

    suspend fun listPatientMedications(
        doctorId: Long,
        patientUserId: Long,
        limit: Int,
        cursor: Long?,
        onlyActive: Boolean
    ): MedicationListResponse {
        val safeLimit = limit.coerceIn(1, 200)
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val nowUtc = utcNow()
            val todayPlus8 = nowUtc.toLocalDatePlus8()
            val patientRef = EntityID(patientUserId, UsersTable)
            val condition = buildMedicationListCondition(patientRef, cursor, onlyActive, todayPlus8)
            val rows = UserMedicationsTable.selectAll().where {
                condition
            }.orderBy(UserMedicationsTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val items = page.map { it.toMedicationResponse(todayPlus8, deps) }
            MedicationListResponse(
                items = items,
                activeCount = items.count { it.isActive },
                nextCursor = if (hasMore) page.last()[UserMedicationsTable.id].value.toString() else null
            )
        }
    }

    suspend fun updatePatientMedication(
        doctorId: Long,
        patientUserId: Long,
        medicationId: Long,
        request: UpdateMedicationRequest
    ): MedicationItemResponse {
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val nowUtc = utcNow()
            val todayPlus8 = nowUtc.toLocalDatePlus8()
            val patientRef = EntityID(patientUserId, UsersTable)
            val row = requireOwnedMedication(patientRef, medicationId)
            val validated = MedicationValidators.validateUpdateRequest(
                request = request,
                minEndDateInclusive = row[UserMedicationsTable.recordedDateLocal]
            )
            UserMedicationsTable.update({ UserMedicationsTable.id eq row[UserMedicationsTable.id] }) {
                it[drugName] = validated.drugName
                it[doseTimesJson] = deps.json.encodeToString(validated.doseTimes)
                it[endDateLocal] = validated.endDate
                it[doseAmount] = validated.doseAmount
                it[doseUnit] = validated.doseUnit
                it[tabletStrengthAmount] = validated.tabletStrengthAmount
                it[tabletStrengthUnit] = validated.tabletStrengthUnit
                it[updatedAt] = nowUtc
            }
            val updated = UserMedicationsTable.selectAll().where {
                UserMedicationsTable.id eq row[UserMedicationsTable.id]
            }.first()
            updated.toMedicationResponse(todayPlus8, deps)
        }
    }

    suspend fun deletePatientMedication(doctorId: Long, patientUserId: Long, medicationId: Long) {
        DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val now = utcNow()
            val patientRef = EntityID(patientUserId, UsersTable)
            val row = requireOwnedMedication(patientRef, medicationId, includeDeleted = true)
            if (row[UserMedicationsTable.deletedAt] != null) return@dbQuery
            UserMedicationsTable.update({
                (UserMedicationsTable.id eq row[UserMedicationsTable.id]) and UserMedicationsTable.deletedAt.isNull()
            }) {
                it[deletedAt] = now
                it[updatedAt] = now
            }
        }
    }

    suspend fun createSideEffect(userId: Long, request: CreateSideEffectRequest): SideEffectItemResponse {
        val symptom = request.symptom.trim()
        if (symptom.isEmpty()) throw doctorInvalidArg("symptom cannot be blank")
        validateTextLength("symptom", symptom, SIDE_EFFECT_SYMPTOM_MAX_LENGTH)
        if (request.severity !in 1..10) throw doctorInvalidArg("severity must be between 1 and 10")
        validateTextLength("note", request.note, NOTE_MAX_LENGTH)
        val recordedAt = parseRecordedAt(request.recordedAt)

        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val userRef = EntityID(userId, UsersTable)
            requireUser(userRef)
            val insertedId = UserSideEffectsTable.insert {
                it[UserSideEffectsTable.userId] = userRef
                it[UserSideEffectsTable.symptom] = symptom
                it[UserSideEffectsTable.severity] = request.severity
                it[UserSideEffectsTable.note] = request.note?.trim()?.takeIf { v -> v.isNotEmpty() }
                it[UserSideEffectsTable.recordedAt] = recordedAt
                it[UserSideEffectsTable.createdAt] = now
            }[UserSideEffectsTable.id]
            val row = UserSideEffectsTable.selectAll().where { UserSideEffectsTable.id eq insertedId }.first()
            row.toSideEffectResponse()
        }
    }

    suspend fun listUserSideEffects(userId: Long, limit: Int, cursor: Long?): List<SideEffectItemResponse> {
        val safeLimit = limit.coerceIn(1, 100)
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            requireUser(userRef)
            var condition: Op<Boolean> = UserSideEffectsTable.userId eq userRef
            if (cursor != null) {
                condition = condition and (UserSideEffectsTable.id less cursor)
            }
            UserSideEffectsTable.selectAll().where {
                condition
            }.orderBy(UserSideEffectsTable.id, SortOrder.DESC).limit(safeLimit).map { it.toSideEffectResponse() }
        }
    }

    suspend fun summarizePatientSideEffects(
        doctorId: Long,
        patientUserId: Long,
        days: Int?
    ): SideEffectSummaryResponse {
        val safeDays = (days ?: 30).coerceIn(1, 3650)
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val patientRef = EntityID(patientUserId, UsersTable)
            val startAt = utcNow().minusDays(safeDays.toLong())
            val rows = UserSideEffectsTable.selectAll().where {
                (UserSideEffectsTable.userId eq patientRef) and
                    (UserSideEffectsTable.recordedAt greaterEq startAt)
            }.toList()
            val grouped = rows.groupBy { it[UserSideEffectsTable.symptom] }
            val summaryItems = grouped.map { (symptom, symptomRows) ->
                val severities = symptomRows.map { it[UserSideEffectsTable.severity] }
                val avg = if (severities.isEmpty()) 0.0 else severities.average()
                SideEffectSummaryItem(
                    symptom = symptom,
                    count = symptomRows.size,
                    averageSeverity = avg.round(2),
                    maxSeverity = severities.maxOrNull() ?: 0
                )
            }.sortedWith(compareByDescending<SideEffectSummaryItem> { it.count }.thenBy { it.symptom })
            SideEffectSummaryResponse(
                totalCount = rows.size,
                items = summaryItems
            )
        }
    }

    suspend fun getPatientWeightTrend(
        doctorId: Long,
        patientUserId: Long,
        days: Int?
    ): WeightTrendResponse {
        val safeDays = (days ?: 180).coerceIn(1, 3650)
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val patientRef = EntityID(patientUserId, UsersTable)
            val startAt = utcNow().minusDays(safeDays.toLong())
            val points = UserWeightLogsTable.selectAll().where {
                (UserWeightLogsTable.userId eq patientRef) and
                    (UserWeightLogsTable.recordedAt greaterEq startAt)
            }.orderBy(UserWeightLogsTable.recordedAt, SortOrder.ASC).map { row ->
                WeightTrendPoint(
                    recordedAt = row[UserWeightLogsTable.recordedAt].toIsoOffsetPlus8(),
                    weightKg = row[UserWeightLogsTable.weightKg].toDouble(),
                    source = row[UserWeightLogsTable.sourceType]
                )
            }
            WeightTrendResponse(
                patientUserId = patientUserId,
                points = points
            )
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.requireOwnedMedication(
        userId: EntityID<Long>,
        medicationId: Long,
        includeDeleted: Boolean = false
    ): ResultRow {
        val ref = EntityID(medicationId, UserMedicationsTable)
        val row = UserMedicationsTable.selectAll().where { UserMedicationsTable.id eq ref }.firstOrNull()
            ?: throw medicationNotFound()
        if (row[UserMedicationsTable.userId].value != userId.value) throw medicationNotFound()
        if (!includeDeleted && row[UserMedicationsTable.deletedAt] != null) throw medicationNotFound()
        return row
    }

    private fun ResultRow.toMedicationResponse(todayPlus8: LocalDate, deps: DoctorServiceDeps): MedicationItemResponse {
        val recordedDate = this[UserMedicationsTable.recordedDateLocal]
        val endDate = this[UserMedicationsTable.endDateLocal]
        val doseTimes = parseDoseTimes(this[UserMedicationsTable.doseTimesJson], deps)
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
            createdAt = this[UserMedicationsTable.createdAt].toIsoInstant(),
            updatedAt = this[UserMedicationsTable.updatedAt].toIsoInstant()
        )
    }

    private fun parseDoseTimes(raw: String, deps: DoctorServiceDeps): List<String> {
        return runCatching { deps.json.decodeFromString<List<String>>(raw) }
            .getOrElse { throw IllegalStateException("Invalid dose_times_json payload", it) }
    }

    private fun ResultRow.toSideEffectResponse(): SideEffectItemResponse {
        return SideEffectItemResponse(
            sideEffectId = this[UserSideEffectsTable.id].value,
            symptom = this[UserSideEffectsTable.symptom],
            severity = this[UserSideEffectsTable.severity],
            note = this[UserSideEffectsTable.note],
            recordedAt = this[UserSideEffectsTable.recordedAt].toIsoInstant(),
            createdAt = this[UserSideEffectsTable.createdAt].toIsoInstant()
        )
    }

    private fun parseRecordedAt(value: String?): java.time.LocalDateTime {
        if (value.isNullOrBlank()) return utcNow()
        return runCatching { parseInstantToUtcDateTime(value.trim()) }.getOrElse {
            throw doctorInvalidArg("recordedAt must be ISO-8601 instant")
        }
    }

    private fun validateTextLength(fieldName: String, value: String?, maxLength: Int) {
        if (value == null) return
        if (value.any { it.isISOControl() }) throw doctorInvalidArg("$fieldName contains control characters")
        if (value.length > maxLength) throw doctorInvalidArg("$fieldName exceeds $maxLength characters")
    }
}
