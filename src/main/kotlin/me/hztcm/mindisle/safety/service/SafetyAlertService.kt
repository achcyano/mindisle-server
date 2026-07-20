package me.hztcm.mindisle.safety.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorPatientBindingStatus
import me.hztcm.mindisle.db.DoctorPatientBindingsTable
import me.hztcm.mindisle.db.RiskLevel
import me.hztcm.mindisle.db.SafetyAlertStatus
import me.hztcm.mindisle.db.SafetyAlertsTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.AckSafetyAlertRequest
import me.hztcm.mindisle.model.SafetyAlertItem
import me.hztcm.mindisle.model.SafetyAlertListResponse
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class SafetyAlertService {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun raise(
        userId: Long,
        riskLevel: RiskLevel,
        reasonCodes: List<String>,
        evidence: String?
    ): Long {
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val doctorId = DoctorPatientBindingsTable.selectAll().where {
                (DoctorPatientBindingsTable.patientUserId eq userRef) and
                    (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE)
            }.firstOrNull()?.get(DoctorPatientBindingsTable.doctorId)?.value

            SafetyAlertsTable.insert {
                it[SafetyAlertsTable.userId] = userRef
                it[SafetyAlertsTable.doctorId] = doctorId
                it[SafetyAlertsTable.riskLevel] = riskLevel
                it[reasonCodesJson] = json.encodeToString(reasonCodes)
                it[evidenceJson] = evidence
                it[status] = SafetyAlertStatus.OPEN
                it[createdAt] = now
                it[updatedAt] = now
            }[SafetyAlertsTable.id].value
        }
    }

    suspend fun listForDoctor(doctorId: Long, onlyOpen: Boolean = true): SafetyAlertListResponse {
        return DatabaseFactory.dbQuery {
            val rows = SafetyAlertsTable.selectAll().where {
                SafetyAlertsTable.doctorId eq doctorId
            }.orderBy(SafetyAlertsTable.createdAt, SortOrder.DESC).limit(100).toList()
                .filter { !onlyOpen || it[SafetyAlertsTable.status] == SafetyAlertStatus.OPEN }

            val items = rows.map { row ->
                val uid = row[SafetyAlertsTable.userId].value
                val name = UserProfilesTable.selectAll().where {
                    UserProfilesTable.userId eq EntityID(uid, UsersTable)
                }.firstOrNull()?.get(UserProfilesTable.fullName)
                SafetyAlertItem(
                    alertId = row[SafetyAlertsTable.id].value,
                    userId = uid,
                    riskLevel = row[SafetyAlertsTable.riskLevel].name,
                    reasonCodes = runCatching {
                        json.decodeFromString<List<String>>(row[SafetyAlertsTable.reasonCodesJson])
                    }.getOrDefault(emptyList()),
                    evidence = row[SafetyAlertsTable.evidenceJson],
                    status = row[SafetyAlertsTable.status].name,
                    createdAt = row[SafetyAlertsTable.createdAt].toIsoOffsetUtc(),
                    patientName = name
                )
            }
            SafetyAlertListResponse(items = items)
        }
    }

    suspend fun listForUser(userId: Long): SafetyAlertListResponse {
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val rows = SafetyAlertsTable.selectAll().where {
                SafetyAlertsTable.userId eq userRef
            }.orderBy(SafetyAlertsTable.createdAt, SortOrder.DESC).limit(50).toList()
            SafetyAlertListResponse(
                items = rows.map { row ->
                    SafetyAlertItem(
                        alertId = row[SafetyAlertsTable.id].value,
                        userId = userId,
                        riskLevel = row[SafetyAlertsTable.riskLevel].name,
                        reasonCodes = runCatching {
                            json.decodeFromString<List<String>>(row[SafetyAlertsTable.reasonCodesJson])
                        }.getOrDefault(emptyList()),
                        evidence = row[SafetyAlertsTable.evidenceJson],
                        status = row[SafetyAlertsTable.status].name,
                        createdAt = row[SafetyAlertsTable.createdAt].toIsoOffsetUtc()
                    )
                }
            )
        }
    }

    suspend fun ack(doctorId: Long, alertId: Long, request: AckSafetyAlertRequest) {
        DatabaseFactory.dbQuery {
            val row = SafetyAlertsTable.selectAll().where {
                SafetyAlertsTable.id eq alertId
            }.firstOrNull() ?: throw AppException(
                ErrorCodes.DOCTOR_REPORT_NOT_FOUND,
                "Alert not found",
                HttpStatusCode.NotFound
            )
            if (row[SafetyAlertsTable.doctorId] != doctorId) {
                throw AppException(ErrorCodes.DOCTOR_FORBIDDEN, "Forbidden", HttpStatusCode.Forbidden)
            }
            val now = utcNow()
            SafetyAlertsTable.update({ SafetyAlertsTable.id eq alertId }) {
                it[status] = if (request.resolve) SafetyAlertStatus.RESOLVED else SafetyAlertStatus.ACKED
                it[ackNote] = request.note
                it[updatedAt] = now
            }
        }
    }
}
