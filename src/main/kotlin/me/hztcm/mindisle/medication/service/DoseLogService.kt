package me.hztcm.mindisle.medication.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.common.parseLocalDateOrTodayPlus8
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.toLocalDatePlus8
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoseLogStatus
import me.hztcm.mindisle.db.MedicationDoseLogsTable
import me.hztcm.mindisle.db.UserMedicationsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.DoseCheckInRequest
import me.hztcm.mindisle.model.DoseLogItem
import me.hztcm.mindisle.model.TodayDosePlanItem
import me.hztcm.mindisle.model.TodayDosePlanResponse
import me.hztcm.mindisle.state.service.PatientStateService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDate

class DoseLogService(
    private val stateService: PatientStateService
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun todayPlan(userId: Long): TodayDosePlanResponse {
        val today = utcNow().toLocalDatePlus8()
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val meds = UserMedicationsTable.selectAll().where {
                (UserMedicationsTable.userId eq userRef) and
                    UserMedicationsTable.deletedAt.isNull()
            }.toList().filter { row ->
                val end = row[UserMedicationsTable.endDateLocal]
                !end.isBefore(today) && !row[UserMedicationsTable.recordedDateLocal].isAfter(today)
            }
            val logs = MedicationDoseLogsTable.selectAll().where {
                (MedicationDoseLogsTable.userId eq userRef) and
                    (MedicationDoseLogsTable.localDate eq today)
            }.toList()
            val logMap = logs.associateBy {
                it[MedicationDoseLogsTable.medicationId].value to it[MedicationDoseLogsTable.plannedTime]
            }
            val items = mutableListOf<TodayDosePlanItem>()
            meds.forEach { med ->
                val times = parseDoseTimes(med[UserMedicationsTable.doseTimesJson])
                times.forEach { time ->
                    val log = logMap[med[UserMedicationsTable.id].value to time]
                    items += TodayDosePlanItem(
                        medicationId = med[UserMedicationsTable.id].value,
                        drugName = med[UserMedicationsTable.drugName],
                        plannedTime = time,
                        status = log?.get(MedicationDoseLogsTable.status)?.name,
                        doseLogId = log?.get(MedicationDoseLogsTable.id)?.value
                    )
                }
            }
            TodayDosePlanResponse(
                localDate = today.toString(),
                items = items.sortedWith(compareBy({ it.plannedTime }, { it.medicationId }))
            )
        }
    }

    suspend fun checkIn(userId: Long, request: DoseCheckInRequest): DoseLogItem {
        val status = runCatching { DoseLogStatus.valueOf(request.status.uppercase()) }.getOrElse {
            throw AppException(ErrorCodes.MEDICATION_INVALID_ARGUMENT, "Invalid dose status", HttpStatusCode.BadRequest)
        }
        val plannedTime = request.plannedTime.trim()
        if (!plannedTime.matches(Regex("^\\d{2}:\\d{2}$"))) {
            throw AppException(ErrorCodes.MEDICATION_INVALID_ARGUMENT, "plannedTime must be HH:mm", HttpStatusCode.BadRequest)
        }
        val localDate = parseLocalDateOrTodayPlus8(request.localDate)
        val now = utcNow()
        val item = DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val med = UserMedicationsTable.selectAll().where {
                (UserMedicationsTable.id eq request.medicationId) and
                    (UserMedicationsTable.userId eq userRef) and
                    UserMedicationsTable.deletedAt.isNull()
            }.firstOrNull() ?: throw AppException(
                ErrorCodes.MEDICATION_NOT_FOUND,
                "Medication not found",
                HttpStatusCode.NotFound
            )
            val existing = MedicationDoseLogsTable.selectAll().where {
                (MedicationDoseLogsTable.userId eq userRef) and
                    (MedicationDoseLogsTable.medicationId eq med[UserMedicationsTable.id]) and
                    (MedicationDoseLogsTable.localDate eq localDate) and
                    (MedicationDoseLogsTable.plannedTime eq plannedTime)
            }.firstOrNull()
            val id = if (existing == null) {
                MedicationDoseLogsTable.insert {
                    it[MedicationDoseLogsTable.userId] = userRef
                    it[medicationId] = med[UserMedicationsTable.id]
                    it[MedicationDoseLogsTable.localDate] = localDate
                    it[MedicationDoseLogsTable.plannedTime] = plannedTime
                    it[MedicationDoseLogsTable.status] = status
                    it[actedAt] = now
                    it[note] = request.note
                }[MedicationDoseLogsTable.id]
            } else {
                MedicationDoseLogsTable.update({ MedicationDoseLogsTable.id eq existing[MedicationDoseLogsTable.id] }) {
                    it[MedicationDoseLogsTable.status] = status
                    it[actedAt] = now
                    it[note] = request.note
                }
                existing[MedicationDoseLogsTable.id]
            }
            DoseLogItem(
                doseLogId = id.value,
                medicationId = med[UserMedicationsTable.id].value,
                drugName = med[UserMedicationsTable.drugName],
                localDate = localDate.toString(),
                plannedTime = plannedTime,
                status = status.name,
                actedAt = now.toIsoOffsetUtc()
            )
        }
        runCatching { stateService.recompute(userId, source = "DOSE_CHECKIN") }
        return item
    }

    suspend fun adherenceRate(userId: Long, days: Int = 7): Double? {
        val end = utcNow().toLocalDatePlus8()
        val start = end.minusDays((days - 1).toLong())
        return DatabaseFactory.dbQuery {
            computeAdherence(userId, start, end)
        }
    }

    fun computeAdherence(userId: Long, start: LocalDate, end: LocalDate): Double? {
        val userRef = EntityID(userId, UsersTable)
        val meds = UserMedicationsTable.selectAll().where {
            (UserMedicationsTable.userId eq userRef) and UserMedicationsTable.deletedAt.isNull()
        }.toList()
        if (meds.isEmpty()) return null
        var planned = 0
        var taken = 0
        var day = start
        while (!day.isAfter(end)) {
            meds.forEach { med ->
                val recorded = med[UserMedicationsTable.recordedDateLocal]
                val endDate = med[UserMedicationsTable.endDateLocal]
                if (!day.isBefore(recorded) && !day.isAfter(endDate)) {
                    val times = parseDoseTimes(med[UserMedicationsTable.doseTimesJson])
                    planned += times.size
                    times.forEach { time ->
                        val log = MedicationDoseLogsTable.selectAll().where {
                            (MedicationDoseLogsTable.userId eq userRef) and
                                (MedicationDoseLogsTable.medicationId eq med[UserMedicationsTable.id]) and
                                (MedicationDoseLogsTable.localDate eq day) and
                                (MedicationDoseLogsTable.plannedTime eq time)
                        }.firstOrNull()
                        if (log != null && log[MedicationDoseLogsTable.status] == DoseLogStatus.TAKEN) {
                            taken += 1
                        }
                    }
                }
            }
            day = day.plusDays(1)
        }
        if (planned <= 0) return null
        return taken.toDouble() / planned.toDouble()
    }

    private fun parseDoseTimes(raw: String): List<String> {
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.matches(Regex("^\\d{2}:\\d{2}$")) }
    }
}
