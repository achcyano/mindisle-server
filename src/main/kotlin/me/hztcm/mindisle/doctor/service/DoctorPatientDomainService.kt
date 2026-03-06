package me.hztcm.mindisle.doctor.service

import kotlinx.serialization.json.JsonObject
import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.db.DatabaseFactory
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
import me.hztcm.mindisle.model.AssessmentReportResponse
import me.hztcm.mindisle.model.DoctorPatientGroupingStateResponse
import me.hztcm.mindisle.model.DoctorPatientItem
import me.hztcm.mindisle.model.DoctorPatientListResponse
import me.hztcm.mindisle.model.GenerateAssessmentReportRequest
import me.hztcm.mindisle.model.GroupingChangeHistoryResponse
import me.hztcm.mindisle.model.GroupingChangeItem
import me.hztcm.mindisle.model.PatientMetricSnapshot
import me.hztcm.mindisle.model.PatientScaleTrendPoint
import me.hztcm.mindisle.model.PatientScaleTrendSeries
import me.hztcm.mindisle.model.PatientScaleTrendsResponse
import me.hztcm.mindisle.model.SideEffectSummaryItem
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

private const val GROUP_VALUE_MAX_LENGTH = 64
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
        limit: Int,
        cursor: Long?,
        keyword: String?,
        abnormalOnly: Boolean
    ): DoctorPatientListResponse {
        val safeLimit = limit.coerceIn(1, 50)
        val normalizedKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            requireDoctor(doctorRef)
            val scaleCodeById = loadTrackedScaleCodeByScaleId()
            val thresholds = loadDoctorThresholds(doctorRef)
            val items = mutableListOf<Pair<Long, DoctorPatientItem>>()
            var scanCursor = cursor
            var exhausted = false
            val fetchSize = 100

            while (!exhausted && items.size < safeLimit + 1) {
                var condition: Op<Boolean> = (DoctorPatientBindingsTable.doctorId eq doctorRef) and
                    (DoctorPatientBindingsTable.status eq me.hztcm.mindisle.db.DoctorPatientBindingStatus.ACTIVE) and
                    DoctorPatientBindingsTable.unboundAt.isNull()
                if (scanCursor != null) {
                    condition = condition and (DoctorPatientBindingsTable.id less scanCursor)
                }
                val chunk = DoctorPatientBindingsTable.selectAll().where {
                    condition
                }.orderBy(DoctorPatientBindingsTable.id, SortOrder.DESC).limit(fetchSize).toList()
                if (chunk.isEmpty()) {
                    exhausted = true
                    break
                }
                scanCursor = chunk.last()[DoctorPatientBindingsTable.id].value

                chunk.forEach { binding ->
                    if (items.size >= safeLimit + 1) {
                        return@forEach
                    }
                    val userRef = binding[DoctorPatientBindingsTable.patientUserId]
                    val userRow = UsersTable.selectAll().where { UsersTable.id eq userRef }.firstOrNull() ?: return@forEach
                    val profileRow = UserProfilesTable.selectAll().where { UserProfilesTable.userId eq userRef }.firstOrNull()
                    val fullName = profileRow?.get(UserProfilesTable.fullName)
                    if (!keywordMatched(normalizedKeyword, userRow[UsersTable.phone], fullName)) {
                        return@forEach
                    }
                    val metricsAndLastAt = loadLatestMetricSnapshot(userRef, scaleCodeById)
                    val reasons = collectAbnormalReasons(metricsAndLastAt.metrics, thresholds)
                    val abnormal = reasons.isNotEmpty()
                    if (abnormalOnly && !abnormal) {
                        return@forEach
                    }
                    items += binding[DoctorPatientBindingsTable.id].value to DoctorPatientItem(
                        patientUserId = userRef.value,
                        phone = userRow[UsersTable.phone],
                        fullName = fullName,
                        severityGroup = binding[DoctorPatientBindingsTable.severityGroup],
                        treatmentPhase = binding[DoctorPatientBindingsTable.treatmentPhase],
                        lastScaleSubmittedAt = metricsAndLastAt.lastSubmittedAt?.toIsoInstant(),
                        metrics = metricsAndLastAt.metrics,
                        abnormal = abnormal,
                        abnormalReasons = reasons
                    )
                }
                if (chunk.size < fetchSize) {
                    exhausted = true
                }
            }
            val hasMore = items.size > safeLimit
            val page = if (hasMore) items.take(safeLimit) else items
            DoctorPatientListResponse(
                items = page.map { it.second },
                nextCursor = if (hasMore) page.last().first.toString() else null
            )
        }
    }

    suspend fun updatePatientGrouping(
        doctorId: Long,
        patientUserId: Long,
        request: UpdatePatientGroupingRequest
    ): DoctorPatientGroupingStateResponse {
        val severityGroup = normalizeGroupValue(request.severityGroup)
        val treatmentPhase = normalizeGroupValue(request.treatmentPhase)
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val binding = requireActiveBindingForDoctor(doctorId, patientUserId)
            val oldSeverity = binding[DoctorPatientBindingsTable.severityGroup]
            val oldPhase = binding[DoctorPatientBindingsTable.treatmentPhase]
            DoctorPatientBindingsTable.update({ DoctorPatientBindingsTable.id eq binding[DoctorPatientBindingsTable.id] }) {
                it[DoctorPatientBindingsTable.severityGroup] = severityGroup
                it[DoctorPatientBindingsTable.treatmentPhase] = treatmentPhase
                it[DoctorPatientBindingsTable.updatedAt] = now
            }
            if (oldSeverity != severityGroup) {
                DoctorPatientGroupChangesTable.insert {
                    it[bindingId] = binding[DoctorPatientBindingsTable.id]
                    it[fieldName] = "severityGroup"
                    it[oldValue] = oldSeverity
                    it[newValue] = severityGroup
                    it[changedAt] = now
                }
            }
            if (oldPhase != treatmentPhase) {
                DoctorPatientGroupChangesTable.insert {
                    it[bindingId] = binding[DoctorPatientBindingsTable.id]
                    it[fieldName] = "treatmentPhase"
                    it[oldValue] = oldPhase
                    it[newValue] = treatmentPhase
                    it[changedAt] = now
                }
            }
            DoctorPatientGroupingStateResponse(
                patientUserId = patientUserId,
                severityGroup = severityGroup,
                treatmentPhase = treatmentPhase,
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
            GroupingChangeHistoryResponse(
                items = page.map { row ->
                    GroupingChangeItem(
                        changeId = row[DoctorPatientGroupChangesTable.id].value,
                        fieldName = row[DoctorPatientGroupChangesTable.fieldName],
                        oldValue = row[DoctorPatientGroupChangesTable.oldValue],
                        newValue = row[DoctorPatientGroupChangesTable.newValue],
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
            val metrics = loadLatestMetricSnapshot(patientRef, scaleCodeById).metrics
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
        return AssessmentReportResponse(
            patientUserId = patientUserId,
            generatedAt = generatedAt.toIsoInstant(),
            polished = polished,
            model = model,
            templateReport = template,
            report = report
        )
    }

    private data class MetricSnapshotResult(
        val metrics: PatientMetricSnapshot,
        val lastSubmittedAt: java.time.LocalDateTime?
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

    private fun org.jetbrains.exposed.sql.Transaction.loadTrackedScaleCodeByScaleId(): Map<Long, String> {
        return ScalesTable.selectAll().where {
            ScalesTable.code inList TRACKED_SCALE_CODES
        }.associate { row -> row[ScalesTable.id].value to row[ScalesTable.code] }
    }

    private fun org.jetbrains.exposed.sql.Transaction.loadLatestMetricSnapshot(
        patientRef: EntityID<Long>,
        scaleCodeByScaleId: Map<Long, String>
    ): MetricSnapshotResult {
        if (scaleCodeByScaleId.isEmpty()) {
            return MetricSnapshotResult(metrics = PatientMetricSnapshot(adherence = null), lastSubmittedAt = null)
        }
        val scaleRefs = scaleCodeByScaleId.keys.map { EntityID(it, ScalesTable) }
        val sessions = UserScaleSessionsTable.selectAll().where {
            (UserScaleSessionsTable.userId eq patientRef) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED) and
                (UserScaleSessionsTable.scaleId inList scaleRefs)
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

    private fun buildTemplateReport(context: ReportContext, days: Int, generatedAt: java.time.LocalDateTime): String {
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

    private fun normalizeGroupValue(value: String?): String? {
        if (value == null) {
            return null
        }
        val normalized = value.trim().takeIf { it.isNotEmpty() }
        validateTextLength("group value", normalized, GROUP_VALUE_MAX_LENGTH)
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
}
