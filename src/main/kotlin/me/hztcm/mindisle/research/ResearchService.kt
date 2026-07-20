package me.hztcm.mindisle.research

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.ResearchAeEventsTable
import me.hztcm.mindisle.db.ResearchAeSeverity
import me.hztcm.mindisle.db.ResearchArm
import me.hztcm.mindisle.db.ResearchEnrollmentStatus
import me.hztcm.mindisle.db.ResearchEnrollmentsTable
import me.hztcm.mindisle.db.ResearchQcReviewsTable
import me.hztcm.mindisle.db.ResearchVisitsTable
import me.hztcm.mindisle.db.UsersTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.concurrent.atomic.AtomicInteger

class ResearchService {
    private val json = Json { encodeDefaults = true }
    private val armCounter = AtomicInteger(0)

    suspend fun enroll(request: ResearchEnrollRequest): ResearchEnrollmentResponse {
        if (request.exclusionHit) {
            throw AppException(ErrorCodes.INVALID_REQUEST, "Subject hits exclusion criteria", HttpStatusCode.UnprocessableEntity)
        }
        if (!request.consent) {
            throw AppException(ErrorCodes.INVALID_REQUEST, "Informed consent required", HttpStatusCode.UnprocessableEntity)
        }
        val phq = request.baselinePhq9
        if (phq != null && (phq < 5 || phq > 14)) {
            throw AppException(
                ErrorCodes.INVALID_REQUEST,
                "Baseline PHQ-9 must be 5-14 for enrollment",
                HttpStatusCode.UnprocessableEntity
            )
        }
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(request.userId, UsersTable)
            val existing = ResearchEnrollmentsTable.selectAll().where {
                ResearchEnrollmentsTable.userId eq userRef
            }.firstOrNull()
            if (existing != null) {
                return@dbQuery existing.toEnrollment()
            }
            val id = ResearchEnrollmentsTable.insert {
                it[userId] = userRef
                it[status] = ResearchEnrollmentStatus.ENROLLED
                it[consentAt] = now
                it[baselinePhq9] = phq
                it[inclusionNotes] = request.inclusionNotes
                it[exclusionHit] = false
                it[createdAt] = now
                it[updatedAt] = now
            }[ResearchEnrollmentsTable.id]
            // Default visit schedule skeleton: baseline, w4, w8, w12, m6
            listOf("BASELINE", "W4", "W8", "W12", "M6").forEach { code ->
                ResearchVisitsTable.insert {
                    it[enrollmentId] = id
                    it[visitCode] = code
                    it[instrumentsJson] = json.encodeToString(defaultInstruments(code))
                    it[createdAt] = now
                }
            }
            ResearchEnrollmentsTable.selectAll().where { ResearchEnrollmentsTable.id eq id }.first().toEnrollment()
        }
    }

    suspend fun randomize(enrollmentId: Long): ResearchRandomizeResponse {
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            val row = ResearchEnrollmentsTable.selectAll().where {
                ResearchEnrollmentsTable.id eq enrollmentId
            }.firstOrNull() ?: throw AppException(
                ErrorCodes.INVALID_REQUEST,
                "Enrollment not found",
                HttpStatusCode.NotFound
            )
            val existingArm = row[ResearchEnrollmentsTable.arm]
            if (existingArm != null) {
                return@dbQuery ResearchRandomizeResponse(
                    enrollmentId = enrollmentId,
                    arm = existingArm.name,
                    randomizedAt = row[ResearchEnrollmentsTable.randomizedAt]?.toIsoOffsetUtc() ?: now.toIsoOffsetUtc()
                )
            }
            // Simple 1:1 alternating block placeholder (replace with sealed envelope table in production).
            val arm = if (armCounter.getAndIncrement() % 2 == 0) ResearchArm.ADAPTIVE else ResearchArm.USUAL_CARE
            ResearchEnrollmentsTable.update({ ResearchEnrollmentsTable.id eq enrollmentId }) {
                it[ResearchEnrollmentsTable.arm] = arm
                it[randomizedAt] = now
                it[updatedAt] = now
            }
            ResearchRandomizeResponse(
                enrollmentId = enrollmentId,
                arm = arm.name,
                randomizedAt = now.toIsoOffsetUtc()
            )
        }
    }

    suspend fun upsertVisit(enrollmentId: Long, request: ResearchVisitUpsertRequest): ResearchVisitResponse {
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            requireEnrollment(enrollmentId)
            val existing = ResearchVisitsTable.selectAll().where {
                (ResearchVisitsTable.enrollmentId eq enrollmentId) and
                    (ResearchVisitsTable.visitCode eq request.visitCode)
            }.firstOrNull()
            val id = if (existing == null) {
                ResearchVisitsTable.insert {
                    it[ResearchVisitsTable.enrollmentId] = EntityID(enrollmentId, ResearchEnrollmentsTable)
                    it[visitCode] = request.visitCode
                    it[instrumentsJson] = json.encodeToString(request.instruments)
                    it[completedAt] = if (request.markCompleted) now else null
                    it[createdAt] = now
                }[ResearchVisitsTable.id].value
            } else {
                ResearchVisitsTable.update({ ResearchVisitsTable.id eq existing[ResearchVisitsTable.id] }) {
                    if (request.instruments.isNotEmpty()) {
                        it[instrumentsJson] = json.encodeToString(request.instruments)
                    }
                    if (request.markCompleted) {
                        it[completedAt] = now
                    }
                }
                existing[ResearchVisitsTable.id].value
            }
            val row = ResearchVisitsTable.selectAll().where { ResearchVisitsTable.id eq id }.first()
            ResearchVisitResponse(
                visitId = id,
                visitCode = row[ResearchVisitsTable.visitCode],
                completedAt = row[ResearchVisitsTable.completedAt]?.toIsoOffsetUtc(),
                instruments = runCatching {
                    json.decodeFromString<List<String>>(row[ResearchVisitsTable.instrumentsJson] ?: "[]")
                }.getOrDefault(emptyList())
            )
        }
    }

    suspend fun createAe(enrollmentId: Long, request: ResearchAeCreateRequest): ResearchAeResponse {
        val severity = runCatching { ResearchAeSeverity.valueOf(request.severity.uppercase()) }.getOrElse {
            throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid AE severity", HttpStatusCode.BadRequest)
        }
        val now = utcNow()
        val onset = now
        return DatabaseFactory.dbQuery {
            requireEnrollment(enrollmentId)
            val id = ResearchAeEventsTable.insert {
                it[ResearchAeEventsTable.enrollmentId] = EntityID(enrollmentId, ResearchEnrollmentsTable)
                it[title] = request.title.trim().take(200)
                it[ResearchAeEventsTable.severity] = severity
                it[description] = request.description
                it[onsetAt] = onset
                it[createdAt] = now
                it[updatedAt] = now
                if (severity == ResearchAeSeverity.SAE) {
                    it[reportedToEthicsAt] = now
                }
            }[ResearchAeEventsTable.id].value
            val row = ResearchAeEventsTable.selectAll().where { ResearchAeEventsTable.id eq id }.first()
            ResearchAeResponse(
                aeId = id,
                title = row[ResearchAeEventsTable.title],
                severity = row[ResearchAeEventsTable.severity].name,
                onsetAt = row[ResearchAeEventsTable.onsetAt].toIsoOffsetUtc(),
                resolvedAt = row[ResearchAeEventsTable.resolvedAt]?.toIsoOffsetUtc(),
                reportedToEthicsAt = row[ResearchAeEventsTable.reportedToEthicsAt]?.toIsoOffsetUtc()
            )
        }
    }

    suspend fun createQc(enrollmentId: Long, request: ResearchQcCreateRequest): ResearchQcResponse {
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            requireEnrollment(enrollmentId)
            val id = ResearchQcReviewsTable.insert {
                it[ResearchQcReviewsTable.enrollmentId] = EntityID(enrollmentId, ResearchEnrollmentsTable)
                it[conversationId] = request.conversationId
                it[reviewer] = request.reviewer.trim().take(120)
                it[appropriatenessScore] = request.appropriatenessScore
                it[notes] = request.notes
                it[createdAt] = now
            }[ResearchQcReviewsTable.id].value
            ResearchQcResponse(
                reviewId = id,
                reviewer = request.reviewer,
                appropriatenessScore = request.appropriatenessScore,
                createdAt = now.toIsoOffsetUtc()
            )
        }
    }

    suspend fun export(): ResearchExportResponse {
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            val rows = ResearchEnrollmentsTable.selectAll()
                .orderBy(ResearchEnrollmentsTable.id, SortOrder.ASC)
                .map { row ->
                    val eid = row[ResearchEnrollmentsTable.id].value
                    val aeCount = ResearchAeEventsTable.selectAll().where {
                        ResearchAeEventsTable.enrollmentId eq row[ResearchEnrollmentsTable.id]
                    }.count().toInt()
                    val qcCount = ResearchQcReviewsTable.selectAll().where {
                        ResearchQcReviewsTable.enrollmentId eq row[ResearchEnrollmentsTable.id]
                    }.count().toInt()
                    ResearchExportRow(
                        enrollmentId = eid,
                        userId = row[ResearchEnrollmentsTable.userId].value,
                        arm = row[ResearchEnrollmentsTable.arm]?.name,
                        status = row[ResearchEnrollmentsTable.status].name,
                        baselinePhq9 = row[ResearchEnrollmentsTable.baselinePhq9],
                        aeCount = aeCount,
                        qcCount = qcCount
                    )
                }
            ResearchExportResponse(exportedAt = now.toIsoOffsetUtc(), rows = rows)
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.requireEnrollment(enrollmentId: Long) {
        val ok = ResearchEnrollmentsTable.selectAll().where {
            ResearchEnrollmentsTable.id eq enrollmentId
        }.any()
        if (!ok) {
            throw AppException(ErrorCodes.INVALID_REQUEST, "Enrollment not found", HttpStatusCode.NotFound)
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toEnrollment(): ResearchEnrollmentResponse {
        return ResearchEnrollmentResponse(
            enrollmentId = this[ResearchEnrollmentsTable.id].value,
            userId = this[ResearchEnrollmentsTable.userId].value,
            arm = this[ResearchEnrollmentsTable.arm]?.name,
            status = this[ResearchEnrollmentsTable.status].name,
            consentAt = this[ResearchEnrollmentsTable.consentAt]?.toIsoOffsetUtc(),
            randomizedAt = this[ResearchEnrollmentsTable.randomizedAt]?.toIsoOffsetUtc(),
            baselinePhq9 = this[ResearchEnrollmentsTable.baselinePhq9]
        )
    }

    private fun defaultInstruments(visitCode: String): List<String> = when (visitCode) {
        "BASELINE" -> listOf("MINI", "PHQ9", "GAD7", "ISI", "WSAS", "C-SSRS", "TESS")
        "W4", "W8" -> listOf("PHQ9", "GAD7", "ISI", "C-SSRS", "TESS")
        "W12" -> listOf("PHQ9", "GAD7", "ISI", "WSAS", "C-SSRS", "TESS", "CSQ8", "UEQ")
        "M6" -> listOf("PHQ9", "GAD7", "ISI", "WSAS", "C-SSRS", "TESS")
        else -> listOf("PHQ9")
    }
}
