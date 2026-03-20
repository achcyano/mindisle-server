package me.hztcm.mindisle.doctor.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorPatientAssessmentReportsTable
import me.hztcm.mindisle.db.DoctorPatientBindingStatus
import me.hztcm.mindisle.db.DoctorPatientBindingsTable
import me.hztcm.mindisle.db.DoctorPatientGroupChangesTable
import me.hztcm.mindisle.db.DoctorThresholdSettingsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.ScaleSessionStatus
import me.hztcm.mindisle.db.ScalesTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UserScaleResultsTable
import me.hztcm.mindisle.db.UserScaleSessionsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.AssessmentReportListResponse
import me.hztcm.mindisle.model.AssessmentReportResponse
import me.hztcm.mindisle.model.AssessmentReportSummaryItem
import me.hztcm.mindisle.model.DoctorPatientDiagnosisStateResponse
import me.hztcm.mindisle.model.DoctorPatientGroupingStateResponse
import me.hztcm.mindisle.model.DoctorPatientItem
import me.hztcm.mindisle.model.DoctorPatientListResponse
import me.hztcm.mindisle.model.DoctorPatientSortBy
import me.hztcm.mindisle.model.DoctorPatientSortOrder
import me.hztcm.mindisle.model.Gender
import me.hztcm.mindisle.model.GenerateAssessmentReportRequest
import me.hztcm.mindisle.model.GroupingChangeHistoryResponse
import me.hztcm.mindisle.model.GroupingChangeItem
import me.hztcm.mindisle.model.ListDoctorPatientsQuery
import me.hztcm.mindisle.model.PatientMetricSnapshot
import me.hztcm.mindisle.model.PatientScaleTrendPoint
import me.hztcm.mindisle.model.PatientScaleTrendSeries
import me.hztcm.mindisle.model.PatientScaleTrendsResponse
import me.hztcm.mindisle.model.UpdatePatientDiagnosisRequest
import me.hztcm.mindisle.model.UpdatePatientGroupingRequest
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.util.Base64

private const val GROUP_VALUE_MAX_LENGTH = 64
private const val GROUP_REASON_MAX_LENGTH = 512
private const val DIAGNOSIS_MAX_LENGTH = 512
private const val CURSOR_VERSION = 1
private val TRACKED_SCALE_CODES = listOf("SCL90", "PHQ9", "GAD7", "PSQI")

private const val REPORT_POLISH_SYSTEM_PROMPT = """
你是精神心理科医生助理。请在不杜撰数据的前提下，将输入的结构化评估草稿润色为简洁、专业、可执行的中文报告。
要求：
1) 不添加输入中不存在的事实或数值。
2) 用词克制，不做确定性医疗诊断。
3) 输出结构：总体结论、关键量表变化、风险提示、随访建议。
4) 字数控制在 300-600 字。
"""

internal class DoctorPatientDomainService(private val deps: DoctorServiceDeps) {
    suspend fun listDoctorPatients(
        doctorId: Long,
        query: ListDoctorPatientsQuery
    ): DoctorPatientListResponse {
        val safeLimit = query.limit.coerceIn(1, 50)
        val normalizedKeyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedSeverityGroup = normalizeGroupingFilter("severityGroup", query.severityGroup)
        val cursorPayload = query.cursor?.let { decodeCursor(it) }
        val filterHash = computeFilterHash(
            keyword = normalizedKeyword,
            gender = query.gender,
            severityGroup = normalizedSeverityGroup,
            abnormalOnly = query.abnormalOnly,
            scl90ScoreMin = query.scl90ScoreMin,
            scl90ScoreMax = query.scl90ScoreMax
        )
        if (cursorPayload != null) {
            if (cursorPayload.v != CURSOR_VERSION) throw doctorCursorInvalid("Unsupported cursor version")
            if (cursorPayload.sortBy != query.sortBy.name || cursorPayload.sortOrder != query.sortOrder.name) {
                throw doctorCursorInvalid("Cursor does not match current sort options")
            }
            if (cursorPayload.filterHash != filterHash) throw doctorCursorInvalid("Cursor does not match current filter options")
        }
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            requireDoctor(doctorRef)
            val snapshotAt = cursorPayload?.let { parseCursorSnapshotAt(it.snapshotAt) } ?: utcNow()
            val scaleCodeById = loadTrackedScaleCodeByScaleId()
            val thresholds = loadDoctorThresholds(doctorRef)
            val bindings = DoctorPatientBindingsTable.selectAll().where {
                (DoctorPatientBindingsTable.doctorId eq doctorRef) and
                    (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE) and
                    DoctorPatientBindingsTable.unboundAt.isNull() and
                    (DoctorPatientBindingsTable.updatedAt lessEq snapshotAt)
            }.toList()
            val entries = bindings.mapNotNull { binding ->
                val userRef = binding[DoctorPatientBindingsTable.patientUserId]
                val userRow = UsersTable.selectAll().where { UsersTable.id eq userRef }.firstOrNull() ?: return@mapNotNull null
                val profileRow = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userRef }.firstOrNull()
                val fullName = profileRow?.get(UserProfilesTable.fullName)
                if (!keywordMatched(normalizedKeyword, userRow[UsersTable.phone], fullName)) return@mapNotNull null
                val gender = profileRow?.get(UserProfilesTable.gender) ?: Gender.UNKNOWN
                if (query.gender != null && query.gender != gender) return@mapNotNull null
                val severityGroup = binding[DoctorPatientBindingsTable.severityGroup]
                if (normalizedSeverityGroup != null && severityGroup != normalizedSeverityGroup) return@mapNotNull null
                val diagnosis = binding[DoctorPatientBindingsTable.diagnosis]
                val metricSnapshot = loadLatestMetricSnapshot(userRef, scaleCodeById, snapshotAt)
                val latestScl90Score = metricSnapshot.metrics.scl90Total
                if (query.scl90ScoreMin != null && (latestScl90Score == null || latestScl90Score < query.scl90ScoreMin)) return@mapNotNull null
                if (query.scl90ScoreMax != null && (latestScl90Score == null || latestScl90Score > query.scl90ScoreMax)) return@mapNotNull null
                val latestAssessmentAt = loadLatestAssessmentSubmittedAt(userRef, snapshotAt)
                val reasons = collectAbnormalReasons(metricSnapshot.metrics, thresholds)
                val abnormal = reasons.isNotEmpty()
                if (query.abnormalOnly && !abnormal) return@mapNotNull null
                val birthDate = profileRow?.get(UserProfilesTable.birthDate)
                val latestAssessmentAtIso = latestAssessmentAt?.toIsoInstant()
                PatientListEntry(
                    item = DoctorPatientItem(
                        patientUserId = userRef.value,
                        phone = userRow[UsersTable.phone],
                        fullName = fullName,
                        gender = gender,
                        birthDate = birthDate?.toString(),
                        age = computeAgeYears(birthDate, snapshotAt.toLocalDatePlus8()),
                        severityGroup = severityGroup,
                        diagnosis = diagnosis,
                        latestScl90Score = latestScl90Score,
                        latestAssessmentAt = latestAssessmentAtIso,
                        lastScaleSubmittedAt = latestAssessmentAtIso,
                        metrics = metricSnapshot.metrics,
                        abnormal = abnormal,
                        abnormalReasons = reasons
                    ),
                    latestAssessmentAt = latestAssessmentAt,
                    latestScl90Score = latestScl90Score
                )
            }
            val sortedEntries = entries.sortedWith { left, right ->
                val primary = compareEntries(left, right, query.sortBy, query.sortOrder)
                if (primary != 0) primary else right.item.patientUserId.compareTo(left.item.patientUserId)
            }
            val startIndex = if (cursorPayload == null) {
                0
            } else {
                val index = sortedEntries.indexOfFirst { entry ->
                    entry.item.patientUserId == cursorPayload.lastPatientUserId &&
                        cursorSortValue(entry, query.sortBy) == cursorPayload.lastSortValue
                }
                if (index < 0) throw doctorCursorInvalid("Cursor points to unavailable data")
                index + 1
            }
            val pageWindow = sortedEntries.drop(startIndex)
            val hasMore = pageWindow.size > safeLimit
            val page = if (hasMore) pageWindow.take(safeLimit) else pageWindow
            val nextCursor = if (hasMore) {
                val last = page.last()
                encodeCursor(
                    PatientListCursorPayload(
                        v = CURSOR_VERSION,
                        snapshotAt = snapshotAt.toIsoInstant(),
                        sortBy = query.sortBy.name,
                        sortOrder = query.sortOrder.name,
                        filterHash = filterHash,
                        lastSortValue = cursorSortValue(last, query.sortBy),
                        lastPatientUserId = last.item.patientUserId
                    )
                )
            } else null
            DoctorPatientListResponse(items = page.map { it.item }, nextCursor = nextCursor)
        }
    }

    suspend fun updatePatientGrouping(
        doctorId: Long,
        patientUserId: Long,
        request: UpdatePatientGroupingRequest
    ): DoctorPatientGroupingStateResponse {
        val severityGroup = normalizeGroupValue(request.severityGroup)
        val reason = normalizeGroupReason(request.reason)
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val now = utcNow()
            val binding = requireActiveBindingForDoctor(doctorId, patientUserId)
            val oldSeverity = binding[DoctorPatientBindingsTable.severityGroup]
            DoctorPatientBindingsTable.update({ DoctorPatientBindingsTable.id eq binding[DoctorPatientBindingsTable.id] }) {
                it[DoctorPatientBindingsTable.severityGroup] = severityGroup
                it[DoctorPatientBindingsTable.updatedAt] = now
            }
            if (oldSeverity != severityGroup) {
                DoctorPatientGroupChangesTable.insert {
                    it[bindingId] = binding[DoctorPatientBindingsTable.id]
                    it[fieldName] = "severityGroup"
                    it[oldValue] = oldSeverity
                    it[newValue] = severityGroup
                    it[changedByDoctorId] = doctorRef
                    it[DoctorPatientGroupChangesTable.reason] = reason
                    it[changedAt] = now
                }
            }
            DoctorPatientGroupingStateResponse(
                patientUserId = patientUserId,
                severityGroup = severityGroup,
                updatedAt = now.toIsoInstant()
            )
        }
    }

    suspend fun updatePatientDiagnosis(
        doctorId: Long,
        patientUserId: Long,
        request: UpdatePatientDiagnosisRequest
    ): DoctorPatientDiagnosisStateResponse {
        val diagnosis = normalizeDiagnosis(request.diagnosis)
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val binding = requireActiveBindingForDoctor(doctorId, patientUserId)
            DoctorPatientBindingsTable.update({ DoctorPatientBindingsTable.id eq binding[DoctorPatientBindingsTable.id] }) {
                it[DoctorPatientBindingsTable.diagnosis] = diagnosis
                it[DoctorPatientBindingsTable.updatedAt] = now
            }
            DoctorPatientDiagnosisStateResponse(
                patientUserId = patientUserId,
                diagnosis = diagnosis,
                updatedAt = now.toIsoInstant()
            )
        }
    }

    suspend fun listGroupingChanges(
        doctorId: Long,
        patientUserId: Long,
        limit: Int,
        cursor: Long?
    ): GroupingChangeHistoryResponse {
        val safeLimit = limit.coerceIn(1, 100)
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val patientRef = EntityID(patientUserId, UsersTable)
            requireDoctor(doctorRef)
            requireUser(patientRef)
            val bindingIds = DoctorPatientBindingsTable.selectAll().where {
                (DoctorPatientBindingsTable.doctorId eq doctorRef) and
                    (DoctorPatientBindingsTable.patientUserId eq patientRef)
            }.map { it[DoctorPatientBindingsTable.id] }
            if (bindingIds.isEmpty()) {
                throw doctorPatientNotBound()
            }
            var condition: Op<Boolean> = DoctorPatientGroupChangesTable.bindingId inList bindingIds
            if (cursor != null) {
                condition = condition and (DoctorPatientGroupChangesTable.id less cursor)
            }
            val rows = DoctorPatientGroupChangesTable.selectAll().where {
                condition
            }.orderBy(DoctorPatientGroupChangesTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val fallbackBindingRefs = page.mapNotNull { row ->
                if (row[DoctorPatientGroupChangesTable.changedByDoctorId] == null) {
                    row[DoctorPatientGroupChangesTable.bindingId]
                } else {
                    null
                }
            }.distinct()
            val fallbackDoctorRefByBindingId = if (fallbackBindingRefs.isEmpty()) {
                emptyMap()
            } else {
                DoctorPatientBindingsTable.selectAll().where {
                    DoctorPatientBindingsTable.id inList fallbackBindingRefs
                }.associate { it[DoctorPatientBindingsTable.id] to it[DoctorPatientBindingsTable.doctorId] }
            }
            val operatorRefs = page.mapNotNull { row ->
                row[DoctorPatientGroupChangesTable.changedByDoctorId]
                    ?: fallbackDoctorRefByBindingId[row[DoctorPatientGroupChangesTable.bindingId]]
            }.distinct()
            val operatorById = if (operatorRefs.isEmpty()) {
                emptyMap()
            } else {
                DoctorsTable.selectAll().where {
                    DoctorsTable.id inList operatorRefs
                }.associateBy { it[DoctorsTable.id].value }
            }
            GroupingChangeHistoryResponse(
                items = page.map { row ->
                    val operatorRef = row[DoctorPatientGroupChangesTable.changedByDoctorId]
                        ?: fallbackDoctorRefByBindingId[row[DoctorPatientGroupChangesTable.bindingId]]
                        ?: throw doctorNotFound("Doctor not found")
                    val operator = operatorById[operatorRef.value]
                        ?: throw doctorNotFound("Doctor not found")
                    GroupingChangeItem(
                        changeId = row[DoctorPatientGroupChangesTable.id].value,
                        fieldName = row[DoctorPatientGroupChangesTable.fieldName],
                        oldValue = row[DoctorPatientGroupChangesTable.oldValue],
                        newValue = row[DoctorPatientGroupChangesTable.newValue],
                        operatorDoctorId = operatorRef.value,
                        operatorDoctorName = operator[DoctorsTable.fullName],
                        reason = row[DoctorPatientGroupChangesTable.reason],
                        changedAt = row[DoctorPatientGroupChangesTable.changedAt].toIsoInstant()
                    )
                },
                nextCursor = if (hasMore) page.last()[DoctorPatientGroupChangesTable.id].value.toString() else null
            )
        }
    }

    suspend fun getPatientScaleTrends(
        doctorId: Long,
        patientUserId: Long,
        days: Int?
    ): PatientScaleTrendsResponse {
        val safeDays = (days ?: 180).coerceIn(1, 3650)
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val patientRef = EntityID(patientUserId, UsersTable)
            val startAt = utcNow().minusDays(safeDays.toLong())
            val sessions = UserScaleSessionsTable.selectAll().where {
                (UserScaleSessionsTable.userId eq patientRef) and
                    (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED) and
                    (UserScaleSessionsTable.submittedAt greaterEq startAt)
            }.orderBy(UserScaleSessionsTable.submittedAt, SortOrder.ASC).toList()
            if (sessions.isEmpty()) {
                return@dbQuery PatientScaleTrendsResponse(patientUserId = patientUserId, series = emptyList())
            }
            val sessionRefs = sessions.map { it[UserScaleSessionsTable.id] }
            val resultBySessionId = UserScaleResultsTable.selectAll().where {
                UserScaleResultsTable.sessionId inList sessionRefs
            }.associateBy { it[UserScaleResultsTable.sessionId].value }
            val scaleRefs = sessions.map { it[UserScaleSessionsTable.scaleId] }.distinct()
            val scaleById = ScalesTable.selectAll().where {
                ScalesTable.id inList scaleRefs
            }.associateBy { it[ScalesTable.id].value }
            val series = linkedMapOf<String, MutableList<PatientScaleTrendPoint>>()
            val nameByCode = linkedMapOf<String, String>()
            sessions.forEach { session ->
                val submittedAt = session[UserScaleSessionsTable.submittedAt] ?: return@forEach
                val scale = scaleById[session[UserScaleSessionsTable.scaleId].value] ?: return@forEach
                val scaleCode = scale[ScalesTable.code]
                val scaleName = scale[ScalesTable.name]
                val result = resultBySessionId[session[UserScaleSessionsTable.id].value]
                series.computeIfAbsent(scaleCode) { mutableListOf() }.add(
                    PatientScaleTrendPoint(
                        submittedAt = submittedAt.toIsoInstant(),
                        totalScore = result?.get(UserScaleResultsTable.totalScore)?.toDouble()
                    )
                )
                nameByCode[scaleCode] = scaleName
            }
            PatientScaleTrendsResponse(
                patientUserId = patientUserId,
                series = series.entries.map { (scaleCode, points) ->
                    PatientScaleTrendSeries(
                        scaleCode = scaleCode,
                        scaleName = nameByCode[scaleCode] ?: scaleCode,
                        points = points
                    )
                }
            )
        }
    }

    suspend fun generateAssessmentReport(
        doctorId: Long,
        patientUserId: Long,
        request: GenerateAssessmentReportRequest
    ): AssessmentReportResponse {
        val safeDays = (request.days ?: 90).coerceIn(1, 3650)
        val reportContext = DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val patientRef = EntityID(patientUserId, UsersTable)
            val user = requireUser(patientRef)
            val profile = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq patientRef }.firstOrNull()
            val scaleCodeById = loadTrackedScaleCodeByScaleId()
            val metrics = loadLatestMetricSnapshot(patientRef, scaleCodeById, utcNow()).metrics
            val trends = getRecentTrendSnapshot(patientRef, safeDays)
            ReportContext(
                patientUserId = patientUserId,
                patientPhone = user[UsersTable.phone],
                patientName = profile?.get(UserProfilesTable.fullName),
                metrics = metrics,
                trendSnapshot = trends
            )
        }
        val generatedAt = utcNow()
        val template = buildTemplateReport(reportContext, safeDays, generatedAt)
        var polished = false
        var report = template
        var model: String? = null
        runCatching {
            val (output, _) = deps.deepSeekClient.completeTextChat(
                messages = listOf(
                    ChatMessage(role = "system", content = REPORT_POLISH_SYSTEM_PROMPT.trimIndent()),
                    ChatMessage(role = "user", content = buildReportPolishPrompt(template))
                ),
                temperature = 0.2,
                maxTokens = 1200
            )
            val normalized = output.trim()
            if (normalized.isNotEmpty()) {
                report = normalized
                polished = true
                model = deps.llmConfig.model
            }
        }
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val patientRef = EntityID(patientUserId, UsersTable)
            val now = utcNow()
            val reportId = DoctorPatientAssessmentReportsTable.insert {
                it[DoctorPatientAssessmentReportsTable.doctorId] = doctorRef
                it[DoctorPatientAssessmentReportsTable.patientUserId] = patientRef
                it[DoctorPatientAssessmentReportsTable.templateReport] = template
                it[DoctorPatientAssessmentReportsTable.report] = report
                it[DoctorPatientAssessmentReportsTable.polished] = polished
                it[DoctorPatientAssessmentReportsTable.model] = model
                it[DoctorPatientAssessmentReportsTable.days] = safeDays
                it[DoctorPatientAssessmentReportsTable.generatedAt] = generatedAt
                it[DoctorPatientAssessmentReportsTable.createdAt] = now
            }[DoctorPatientAssessmentReportsTable.id]
            val row = DoctorPatientAssessmentReportsTable.selectAll().where {
                DoctorPatientAssessmentReportsTable.id eq reportId
            }.first()
            row.toAssessmentReportResponse()
        }
    }

    suspend fun getLatestAssessmentReport(
        doctorId: Long,
        patientUserId: Long
    ): AssessmentReportResponse {
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val patientRef = EntityID(patientUserId, UsersTable)
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val row = DoctorPatientAssessmentReportsTable.selectAll().where {
                (DoctorPatientAssessmentReportsTable.doctorId eq doctorRef) and
                    (DoctorPatientAssessmentReportsTable.patientUserId eq patientRef)
            }.orderBy(
                DoctorPatientAssessmentReportsTable.generatedAt to SortOrder.DESC,
                DoctorPatientAssessmentReportsTable.id to SortOrder.DESC
            )
                .limit(1)
                .firstOrNull()
                ?: throw doctorReportNotFound()
            row.toAssessmentReportResponse()
        }
    }

    suspend fun listAssessmentReports(
        doctorId: Long,
        patientUserId: Long,
        limit: Int,
        cursor: Long?
    ): AssessmentReportListResponse {
        val safeLimit = limit.coerceIn(1, 100)
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val patientRef = EntityID(patientUserId, UsersTable)
            requireActiveBindingForDoctor(doctorId, patientUserId)
            var condition: Op<Boolean> =
                (DoctorPatientAssessmentReportsTable.doctorId eq doctorRef) and
                    (DoctorPatientAssessmentReportsTable.patientUserId eq patientRef)
            if (cursor != null) {
                condition = condition and (DoctorPatientAssessmentReportsTable.id less cursor)
            }
            val rows = DoctorPatientAssessmentReportsTable.selectAll().where {
                condition
            }.orderBy(DoctorPatientAssessmentReportsTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            AssessmentReportListResponse(
                items = page.map { it.toAssessmentReportSummaryItem() },
                nextCursor = if (hasMore) page.last()[DoctorPatientAssessmentReportsTable.id].value.toString() else null
            )
        }
    }

    suspend fun getAssessmentReportDetail(
        doctorId: Long,
        patientUserId: Long,
        reportId: Long
    ): AssessmentReportResponse {
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val patientRef = EntityID(patientUserId, UsersTable)
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val reportRef = EntityID(reportId, DoctorPatientAssessmentReportsTable)
            val row = DoctorPatientAssessmentReportsTable.selectAll().where {
                DoctorPatientAssessmentReportsTable.id eq reportRef
            }.firstOrNull() ?: throw doctorReportNotFound()
            if (row[DoctorPatientAssessmentReportsTable.doctorId].value != doctorRef.value) {
                throw doctorForbidden("No permission to access assessment report")
            }
            if (row[DoctorPatientAssessmentReportsTable.patientUserId].value != patientRef.value) {
                throw doctorReportNotFound()
            }
            row.toAssessmentReportResponse()
        }
    }

    private data class MetricSnapshotResult(
        val metrics: PatientMetricSnapshot,
        val lastSubmittedAt: LocalDateTime?
    )

    private data class DoctorThresholdSnapshot(
        val scl90: Double? = null,
        val phq9: Double? = null,
        val gad7: Double? = null,
        val psqi: Double? = null
    )

    private data class ReportContext(
        val patientUserId: Long,
        val patientPhone: String,
        val patientName: String?,
        val metrics: PatientMetricSnapshot,
        val trendSnapshot: Map<String, List<Pair<String, Double?>>>
    )

    private data class PatientListEntry(
        val item: DoctorPatientItem,
        val latestAssessmentAt: LocalDateTime?,
        val latestScl90Score: Double?
    )

    @Serializable
    private data class PatientListCursorPayload(
        val v: Int,
        val snapshotAt: String,
        val sortBy: String,
        val sortOrder: String,
        val filterHash: String,
        val lastSortValue: String? = null,
        val lastPatientUserId: Long
    )

    private fun org.jetbrains.exposed.sql.Transaction.loadTrackedScaleCodeByScaleId(): Map<Long, String> {
        return ScalesTable.selectAll().where {
            ScalesTable.code inList TRACKED_SCALE_CODES
        }.associate { row -> row[ScalesTable.id].value to row[ScalesTable.code] }
    }

    private fun org.jetbrains.exposed.sql.Transaction.loadLatestMetricSnapshot(
        patientRef: EntityID<Long>,
        scaleCodeByScaleId: Map<Long, String>,
        snapshotAt: LocalDateTime
    ): MetricSnapshotResult {
        if (scaleCodeByScaleId.isEmpty()) {
            return MetricSnapshotResult(metrics = PatientMetricSnapshot(adherence = null), lastSubmittedAt = null)
        }
        val scaleRefs = scaleCodeByScaleId.keys.map { EntityID(it, ScalesTable) }
        val sessions = UserScaleSessionsTable.selectAll().where {
            (UserScaleSessionsTable.userId eq patientRef) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED) and
                (UserScaleSessionsTable.scaleId inList scaleRefs) and
                (UserScaleSessionsTable.submittedAt lessEq snapshotAt)
        }.orderBy(UserScaleSessionsTable.submittedAt, SortOrder.DESC).toList()
        if (sessions.isEmpty()) {
            return MetricSnapshotResult(metrics = PatientMetricSnapshot(adherence = null), lastSubmittedAt = null)
        }
        val latestSessionByScaleId = linkedMapOf<Long, ResultRow>()
        sessions.forEach { session ->
            latestSessionByScaleId.putIfAbsent(session[UserScaleSessionsTable.scaleId].value, session)
        }
        val sessionRefs = latestSessionByScaleId.values.map { it[UserScaleSessionsTable.id] }
        val resultBySessionId = UserScaleResultsTable.selectAll().where {
            UserScaleResultsTable.sessionId inList sessionRefs
        }.associateBy { it[UserScaleResultsTable.sessionId].value }
        var scl90: Double? = null
        var phq9: Double? = null
        var gad7: Double? = null
        var psqi: Double? = null
        latestSessionByScaleId.forEach { (scaleId, session) ->
            val code = scaleCodeByScaleId[scaleId] ?: return@forEach
            val score = resultBySessionId[session[UserScaleSessionsTable.id].value]?.get(UserScaleResultsTable.totalScore)?.toDouble()
            when (code) {
                "SCL90" -> scl90 = score
                "PHQ9" -> phq9 = score
                "GAD7" -> gad7 = score
                "PSQI" -> psqi = score
            }
        }
        val lastSubmittedAt = sessions.mapNotNull { it[UserScaleSessionsTable.submittedAt] }.maxOrNull()
        return MetricSnapshotResult(
            metrics = PatientMetricSnapshot(
                scl90Total = scl90,
                phq9Total = phq9,
                gad7Total = gad7,
                psqiTotal = psqi,
                adherence = null
            ),
            lastSubmittedAt = lastSubmittedAt
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.loadLatestAssessmentSubmittedAt(
        patientRef: EntityID<Long>,
        snapshotAt: LocalDateTime
    ): LocalDateTime? {
        return UserScaleSessionsTable.selectAll().where {
            (UserScaleSessionsTable.userId eq patientRef) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED) and
                (UserScaleSessionsTable.submittedAt lessEq snapshotAt)
        }.orderBy(UserScaleSessionsTable.submittedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(UserScaleSessionsTable.submittedAt)
    }

    private fun org.jetbrains.exposed.sql.Transaction.loadDoctorThresholds(doctorRef: EntityID<Long>): DoctorThresholdSnapshot {
        val row = DoctorThresholdSettingsTable.selectAll().where {
            DoctorThresholdSettingsTable.doctorId eq doctorRef
        }.firstOrNull() ?: return DoctorThresholdSnapshot()
        return DoctorThresholdSnapshot(
            scl90 = row[DoctorThresholdSettingsTable.scl90Threshold]?.toDouble(),
            phq9 = row[DoctorThresholdSettingsTable.phq9Threshold]?.toDouble(),
            gad7 = row[DoctorThresholdSettingsTable.gad7Threshold]?.toDouble(),
            psqi = row[DoctorThresholdSettingsTable.psqiThreshold]?.toDouble()
        )
    }

    private fun collectAbnormalReasons(
        metrics: PatientMetricSnapshot,
        thresholds: DoctorThresholdSnapshot
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (thresholds.scl90 != null && metrics.scl90Total != null && metrics.scl90Total >= thresholds.scl90) reasons += "SCL90"
        if (thresholds.phq9 != null && metrics.phq9Total != null && metrics.phq9Total >= thresholds.phq9) reasons += "PHQ9"
        if (thresholds.gad7 != null && metrics.gad7Total != null && metrics.gad7Total >= thresholds.gad7) reasons += "GAD7"
        if (thresholds.psqi != null && metrics.psqiTotal != null && metrics.psqiTotal >= thresholds.psqi) reasons += "PSQI"
        return reasons
    }

    private fun keywordMatched(keyword: String?, phone: String, fullName: String?): Boolean {
        if (keyword == null) return true
        return phone.contains(keyword, ignoreCase = true) || (fullName?.contains(keyword, ignoreCase = true) == true)
    }

    private fun org.jetbrains.exposed.sql.Transaction.getRecentTrendSnapshot(
        patientRef: EntityID<Long>,
        days: Int
    ): Map<String, List<Pair<String, Double?>>> {
        val startAt = utcNow().minusDays(days.toLong())
        val sessions = UserScaleSessionsTable.selectAll().where {
            (UserScaleSessionsTable.userId eq patientRef) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED) and
                (UserScaleSessionsTable.submittedAt greaterEq startAt)
        }.orderBy(UserScaleSessionsTable.submittedAt, SortOrder.ASC).toList()
        if (sessions.isEmpty()) {
            return emptyMap()
        }
        val sessionRefs = sessions.map { it[UserScaleSessionsTable.id] }
        val resultBySessionId = UserScaleResultsTable.selectAll().where {
            UserScaleResultsTable.sessionId inList sessionRefs
        }.associateBy { it[UserScaleResultsTable.sessionId].value }
        val scaleRefs = sessions.map { it[UserScaleSessionsTable.scaleId] }.distinct()
        val scaleById = ScalesTable.selectAll().where {
            ScalesTable.id inList scaleRefs
        }.associateBy { it[ScalesTable.id].value }
        val trends = linkedMapOf<String, MutableList<Pair<String, Double?>>>()
        sessions.forEach { session ->
            val submittedAt = session[UserScaleSessionsTable.submittedAt] ?: return@forEach
            val scale = scaleById[session[UserScaleSessionsTable.scaleId].value] ?: return@forEach
            val code = scale[ScalesTable.code]
            val score = resultBySessionId[session[UserScaleSessionsTable.id].value]?.get(UserScaleResultsTable.totalScore)?.toDouble()
            trends.computeIfAbsent(code) { mutableListOf() }.add(submittedAt.toIsoInstant() to score)
        }
        return trends
    }

    private fun buildTemplateReport(context: ReportContext, days: Int, generatedAt: LocalDateTime): String {
        val lines = mutableListOf<String>()
        lines += "报告生成时间：${generatedAt.toIsoOffsetPlus8()}"
        lines += "观察窗口：近 ${days} 天"
        lines += "患者：${context.patientName ?: "未填写姓名"}（userId=${context.patientUserId}，phone=${context.patientPhone}）"
        lines += ""
        lines += "一、最新量表快照"
        lines += "- SCL-90 总分：${context.metrics.scl90Total?.toString() ?: "无数据"}"
        lines += "- PHQ-9 总分：${context.metrics.phq9Total?.toString() ?: "无数据"}"
        lines += "- GAD-7 总分：${context.metrics.gad7Total?.toString() ?: "无数据"}"
        lines += "- PSQI 总分：${context.metrics.psqiTotal?.toString() ?: "无数据"}"
        lines += ""
        lines += "二、趋势摘要"
        if (context.trendSnapshot.isEmpty()) {
            lines += "- 观察窗口内无量表提交记录。"
        } else {
            context.trendSnapshot.forEach { (code, points) ->
                val first = points.firstOrNull()
                val last = points.lastOrNull()
                lines += "- $code：记录数=${points.size}，首末分数=${first?.second ?: "无"} -> ${last?.second ?: "无"}"
            }
        }
        lines += ""
        lines += "三、风险提示（模板）"
        lines += "- 本报告基于量表与历史数据自动生成，仅供医生临床评估参考。"
        lines += "- 若出现自伤/自杀风险信号，请立即线下评估与干预。"
        lines += ""
        lines += "四、随访建议（模板）"
        lines += "- 建议结合面访、病程、用药与不良反应记录综合判断。"
        lines += "- 建议在 2-4 周内安排复评，并关注症状变化与功能恢复。"
        return lines.joinToString("\n")
    }

    private fun buildReportPolishPrompt(templateReport: String): String {
        return """
请在不改变事实与数值的前提下润色下述报告草稿：

$templateReport
""".trimIndent()
    }

    private fun compareEntries(
        left: PatientListEntry,
        right: PatientListEntry,
        sortBy: DoctorPatientSortBy,
        sortOrder: DoctorPatientSortOrder
    ): Int {
        return when (sortBy) {
            DoctorPatientSortBy.LATEST_ASSESSMENT_AT -> compareNullable(left.latestAssessmentAt, right.latestAssessmentAt, sortOrder)
            DoctorPatientSortBy.SCL90_SCORE -> compareNullable(left.latestScl90Score, right.latestScl90Score, sortOrder)
        }
    }

    private fun <T : Comparable<T>> compareNullable(
        left: T?,
        right: T?,
        sortOrder: DoctorPatientSortOrder
    ): Int {
        if (left == null && right == null) return 0
        if (left == null) return 1
        if (right == null) return -1
        return when (sortOrder) {
            DoctorPatientSortOrder.ASC -> left.compareTo(right)
            DoctorPatientSortOrder.DESC -> right.compareTo(left)
        }
    }

    private fun cursorSortValue(entry: PatientListEntry, sortBy: DoctorPatientSortBy): String? {
        return when (sortBy) {
            DoctorPatientSortBy.LATEST_ASSESSMENT_AT -> entry.latestAssessmentAt?.toIsoInstant()
            DoctorPatientSortBy.SCL90_SCORE -> entry.latestScl90Score?.let { normalizeNumberString(it) }
        }
    }

    private fun encodeCursor(payload: PatientListCursorPayload): String {
        val json = deps.json.encodeToString(payload)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(raw: String): PatientListCursorPayload {
        val decoded = runCatching {
            Base64.getUrlDecoder().decode(raw.trim())
        }.getOrElse {
            throw doctorCursorInvalid("Invalid cursor encoding")
        }
        val payloadJson = decoded.toString(StandardCharsets.UTF_8)
        return runCatching {
            deps.json.decodeFromString<PatientListCursorPayload>(payloadJson)
        }.getOrElse {
            throw doctorCursorInvalid("Invalid cursor payload")
        }
    }

    private fun parseCursorSnapshotAt(raw: String): LocalDateTime {
        return runCatching { parseInstantToUtcDateTime(raw) }
            .getOrElse { throw doctorCursorInvalid("Invalid cursor snapshotAt") }
    }

    private fun computeFilterHash(
        keyword: String?,
        gender: Gender?,
        severityGroup: String?,
        abnormalOnly: Boolean,
        scl90ScoreMin: Double?,
        scl90ScoreMax: Double?
    ): String {
        val normalized = listOf(
            "keyword=${keyword ?: ""}",
            "gender=${gender?.name ?: ""}",
            "severityGroup=${severityGroup ?: ""}",
            "abnormalOnly=$abnormalOnly",
            "scl90ScoreMin=${scl90ScoreMin?.let { normalizeNumberString(it) } ?: ""}",
            "scl90ScoreMax=${scl90ScoreMax?.let { normalizeNumberString(it) } ?: ""}"
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }

    private fun normalizeNumberString(value: Double): String {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    private fun computeAgeYears(birthDate: LocalDate?, asOfDate: LocalDate): Int? {
        if (birthDate == null || birthDate.isAfter(asOfDate)) {
            return null
        }
        return Period.between(birthDate, asOfDate).years
    }

    private fun normalizeGroupValue(value: String?): String? {
        if (value == null) {
            return null
        }
        val normalized = value.trim().takeIf { it.isNotEmpty() }
        validateTextLength("group value", normalized, GROUP_VALUE_MAX_LENGTH)
        return normalized
    }

    private fun normalizeGroupReason(value: String?): String? {
        if (value == null) {
            return null
        }
        val normalized = value.trim().takeIf { it.isNotEmpty() }
        validateTextLength("group reason", normalized, GROUP_REASON_MAX_LENGTH)
        return normalized
    }

    private fun normalizeDiagnosis(value: String?): String? {
        if (value == null) {
            return null
        }
        val normalized = value.trim().takeIf { it.isNotEmpty() }
        validateTextLength("diagnosis", normalized, DIAGNOSIS_MAX_LENGTH)
        return normalized
    }

    private fun normalizeGroupingFilter(fieldName: String, value: String?): String? {
        if (value == null) {
            return null
        }
        val normalized = value.trim().takeIf { it.isNotEmpty() }
        if (normalized == null) {
            return null
        }
        if (normalized.any { it.isISOControl() }) {
            throw doctorFilterInvalid("$fieldName contains control characters")
        }
        if (normalized.length > GROUP_VALUE_MAX_LENGTH) {
            throw doctorFilterInvalid("$fieldName exceeds $GROUP_VALUE_MAX_LENGTH characters")
        }
        return normalized
    }

    private fun validateTextLength(fieldName: String, value: String?, maxLength: Int) {
        if (value == null) {
            return
        }
        if (value.any { it.isISOControl() }) {
            throw doctorInvalidArg("$fieldName contains control characters")
        }
        if (value.length > maxLength) {
            throw doctorInvalidArg("$fieldName exceeds $maxLength characters")
        }
    }

    private fun ResultRow.toAssessmentReportResponse(): AssessmentReportResponse {
        return AssessmentReportResponse(
            reportId = this[DoctorPatientAssessmentReportsTable.id].value,
            days = this[DoctorPatientAssessmentReportsTable.days],
            patientUserId = this[DoctorPatientAssessmentReportsTable.patientUserId].value,
            generatedAt = this[DoctorPatientAssessmentReportsTable.generatedAt].toIsoInstant(),
            polished = this[DoctorPatientAssessmentReportsTable.polished],
            model = this[DoctorPatientAssessmentReportsTable.model],
            templateReport = this[DoctorPatientAssessmentReportsTable.templateReport],
            report = this[DoctorPatientAssessmentReportsTable.report]
        )
    }

    private fun ResultRow.toAssessmentReportSummaryItem(): AssessmentReportSummaryItem {
        return AssessmentReportSummaryItem(
            reportId = this[DoctorPatientAssessmentReportsTable.id].value,
            days = this[DoctorPatientAssessmentReportsTable.days],
            patientUserId = this[DoctorPatientAssessmentReportsTable.patientUserId].value,
            generatedAt = this[DoctorPatientAssessmentReportsTable.generatedAt].toIsoInstant(),
            polished = this[DoctorPatientAssessmentReportsTable.polished],
            model = this[DoctorPatientAssessmentReportsTable.model]
        )
    }
}
