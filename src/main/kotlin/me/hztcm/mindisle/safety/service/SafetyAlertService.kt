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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

class SafetyAlertService {
    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(SafetyAlertService::class.java)

    suspend fun raise(
        userId: Long,
        riskLevel: RiskLevel,
        reasonCodes: List<String>,
        evidence: String?,
        cooldownHours: Long = DEFAULT_COOLDOWN_HOURS
    ): Long {
        val now = utcNow()
        val cooldownFrom = now.minusHours(cooldownHours)
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val doctorId = DoctorPatientBindingsTable.selectAll().where {
                (DoctorPatientBindingsTable.patientUserId eq userRef) and
                    (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE)
            }.firstOrNull()?.get(DoctorPatientBindingsTable.doctorId)?.value

            val normalizedReasons = reasonCodes.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
            val reasonKey = json.encodeToString(normalizedReasons)
            val recent = SafetyAlertsTable.selectAll().where {
                (SafetyAlertsTable.userId eq userRef) and
                    (SafetyAlertsTable.status eq SafetyAlertStatus.OPEN) and
                    (SafetyAlertsTable.createdAt greaterEq cooldownFrom)
            }.toList().firstOrNull { row ->
                row[SafetyAlertsTable.reasonCodesJson] == reasonKey ||
                    normalizedReasons.any { code ->
                        row[SafetyAlertsTable.reasonCodesJson].contains(code)
                    }
            }
            if (recent != null) {
                val existingId = recent[SafetyAlertsTable.id].value
                if (recent[SafetyAlertsTable.doctorId] == null && doctorId != null) {
                    SafetyAlertsTable.update({ SafetyAlertsTable.id eq recent[SafetyAlertsTable.id] }) {
                        it[SafetyAlertsTable.doctorId] = doctorId
                        it[updatedAt] = now
                    }
                }
                log.info("Deduped safety alert userId={} alertId={} reasons={}", userId, existingId, normalizedReasons)
                return@dbQuery existingId
            }

            SafetyAlertsTable.insert {
                it[SafetyAlertsTable.userId] = userRef
                it[SafetyAlertsTable.doctorId] = doctorId
                it[SafetyAlertsTable.riskLevel] = riskLevel
                it[reasonCodesJson] = reasonKey
                it[evidenceJson] = evidence
                it[status] = SafetyAlertStatus.OPEN
                it[createdAt] = now
                it[updatedAt] = now
            }[SafetyAlertsTable.id].value
        }
    }

    /** Assign OPEN alerts that were raised before doctor binding. */
    suspend fun assignOrphanAlerts(userId: Long, doctorId: Long): Int {
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            SafetyAlertsTable.update({
                (SafetyAlertsTable.userId eq userRef) and
                    SafetyAlertsTable.doctorId.isNull() and
                    (
                        (SafetyAlertsTable.status eq SafetyAlertStatus.OPEN) or
                            (SafetyAlertsTable.status eq SafetyAlertStatus.ACKED)
                        )
            }) {
                it[SafetyAlertsTable.doctorId] = doctorId
                it[updatedAt] = now
            }
        }
    }

    suspend fun listForDoctor(doctorId: Long, onlyOpen: Boolean = true): SafetyAlertListResponse {
        return DatabaseFactory.dbQuery {
            val rows = SafetyAlertsTable.selectAll().where {
                SafetyAlertsTable.doctorId eq doctorId
            }.orderBy(SafetyAlertsTable.createdAt, SortOrder.DESC).limit(100).toList()
                .filter {
                    if (!onlyOpen) true
                    else it[SafetyAlertsTable.status] == SafetyAlertStatus.OPEN
                }

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

    companion object {
        private const val DEFAULT_COOLDOWN_HOURS = 24L
    }
}
