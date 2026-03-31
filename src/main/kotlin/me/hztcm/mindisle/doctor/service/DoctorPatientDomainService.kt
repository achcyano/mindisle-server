package me.hztcm.mindisle.doctor.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorPatientAssessmentReportsTable
import me.hztcm.mindisle.db.DoctorPatientBindingStatus
import me.hztcm.mindisle.db.DoctorPatientBindingsTable
import me.hztcm.mindisle.db.DoctorPatientGroupChangesTable
import me.hztcm.mindisle.db.DoctorPatientGroupsTable
import me.hztcm.mindisle.db.DoctorThresholdSettingsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.ScaleResultBandsTable
import me.hztcm.mindisle.db.ScaleSessionStatus
import me.hztcm.mindisle.db.ScalesTable
import me.hztcm.mindisle.db.ScaleVersionsTable
import me.hztcm.mindisle.db.UserDiseaseHistoriesTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UserScaleResultsTable
import me.hztcm.mindisle.db.UserScaleSessionsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.AssessmentReportListResponse
import me.hztcm.mindisle.model.AssessmentReportResponse
import me.hztcm.mindisle.model.AssessmentReportSummaryItem
import me.hztcm.mindisle.model.CreateDoctorPatientGroupRequest
import me.hztcm.mindisle.model.DoctorPatientDiagnosisStateResponse
import me.hztcm.mindisle.model.DoctorPatientGroupItem
import me.hztcm.mindisle.model.DoctorPatientGroupingStateResponse
import me.hztcm.mindisle.model.DoctorPatientGroupListResponse
import me.hztcm.mindisle.model.DoctorPatientItem
import me.hztcm.mindisle.model.DoctorPatientListResponse
import me.hztcm.mindisle.model.DoctorPatientProfileResponse
import me.hztcm.mindisle.model.DoctorPatientSortBy
import me.hztcm.mindisle.model.DoctorPatientSortOrder
import me.hztcm.mindisle.model.Gender
import me.hztcm.mindisle.model.GenerateAssessmentReportRequest
import me.hztcm.mindisle.model.GroupingChangeHistoryResponse
import me.hztcm.mindisle.model.GroupingChangeItem
import me.hztcm.mindisle.model.ListScaleHistoryResponse
import me.hztcm.mindisle.model.ListDoctorPatientsQuery
import me.hztcm.mindisle.model.PatientMetricSnapshot
import me.hztcm.mindisle.model.ScaleHistoryItem
import me.hztcm.mindisle.model.ScaleDimensionResult
import me.hztcm.mindisle.model.ScaleResultResponse
import me.hztcm.mindisle.model.UpdatePatientDiagnosisRequest
import me.hztcm.mindisle.model.UpdatePatientGroupingRequest
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
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
private const val DIAGNOSIS_MAX_LENGTH = 512
private const val CURSOR_VERSION = 1
private val TRACKED_SCALE_CODES = listOf("SCL90", "PHQ9", "GAD7", "PSQI")

private const val REPORT_GENERATION_SYSTEM_PROMPT = """
你是精神心理科医生助理。请基于输入的结构化评估信息，直接生成简洁、专业、可执行的中文评估报告。
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

    suspend fun listPatientGroups(doctorId: Long): DoctorPatientGroupListResponse {
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            requireDoctor(doctorRef)
            val groupRows = DoctorPatientGroupsTable.selectAll().where {
                DoctorPatientGroupsTable.doctorId eq doctorRef
            }.orderBy(DoctorPatientGroupsTable.updatedAt, SortOrder.DESC).toList()
            val activeBindings = DoctorPatientBindingsTable.selectAll().where {
                (DoctorPatientBindingsTable.doctorId eq doctorRef) and
                    (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE) and
                    DoctorPatientBindingsTable.unboundAt.isNull()
            }.toList()
            val patientCountByGroup = mutableMapOf<String, Int>()
            activeBindings.forEach { row ->
                val name = row[DoctorPatientBindingsTable.severityGroup]?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                patientCountByGroup[name] = (patientCountByGroup[name] ?: 0) + 1
            }
            val groupRowByName = groupRows.associateBy { it[DoctorPatientGroupsTable.severityGroup] }
            val names = mutableListOf<String>()
            names += groupRows.map { it[DoctorPatientGroupsTable.severityGroup] }
            names += patientCountByGroup.keys.filterNot { groupRowByName.containsKey(it) }.sorted()
            DoctorPatientGroupListResponse(
                items = names.map { name ->
                    val row = groupRowByName[name]
                    DoctorPatientGroupItem(
                        severityGroup = name,
                        patientCount = patientCountByGroup[name] ?: 0,
                        createdAt = row?.get(DoctorPatientGroupsTable.createdAt)?.toIsoInstant(),
                        updatedAt = row?.get(DoctorPatientGroupsTable.updatedAt)?.toIsoInstant()
                    )
                }
            )
        }
    }

    suspend fun createPatientGroup(
        doctorId: Long,
        request: CreateDoctorPatientGroupRequest
    ): DoctorPatientGroupItem {
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            requireDoctor(doctorRef)
            val now = utcNow()
            val severityGroup = normalizeGroupValue(request.severityGroup)
                ?: throw doctorInvalidArg("severityGroup must not be blank")
            upsertGroupDefinition(doctorRef, severityGroup, now)
            val row = DoctorPatientGroupsTable.selectAll().where {
                (DoctorPatientGroupsTable.doctorId eq doctorRef) and
                    (DoctorPatientGroupsTable.severityGroup eq severityGroup)
            }.orderBy(DoctorPatientGroupsTable.id, SortOrder.DESC).limit(1).first()
            val patientCount = DoctorPatientBindingsTable.selectAll().where {
                (DoctorPatientBindingsTable.doctorId eq doctorRef) and
                    (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE) and
                    DoctorPatientBindingsTable.unboundAt.isNull() and
                    (DoctorPatientBindingsTable.severityGroup eq severityGroup)
            }.count().toInt()
            DoctorPatientGroupItem(
                severityGroup = severityGroup,
                patientCount = patientCount,
                createdAt = row[DoctorPatientGroupsTable.createdAt].toIsoInstant(),
                updatedAt = row[DoctorPatientGroupsTable.updatedAt].toIsoInstant()
            )
        }
    }

    suspend fun updatePatientGrouping(
        doctorId: Long,
        patientUserId: Long,
        request: UpdatePatientGroupingRequest
    ): DoctorPatientGroupingStateResponse {
        val severityGroup = normalizeGroupValue(request.severityGroup)
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            val now = utcNow()
            val binding = requireActiveBindingForDoctor(doctorId, patientUserId)
            val oldSeverity = binding[DoctorPatientBindingsTable.severityGroup]
            DoctorPatientBindingsTable.update({ DoctorPatientBindingsTable.id eq binding[DoctorPatientBindingsTable.id] }) {
                it[DoctorPatientBindingsTable.severityGroup] = severityGroup
                it[DoctorPatientBindingsTable.updatedAt] = now
            }
            upsertGroupDefinition(doctorRef, severityGroup, now)
            if (oldSeverity != severityGroup) {
                DoctorPatientGroupChangesTable.insert {
                    it[bindingId] = binding[DoctorPatientBindingsTable.id]
                    it[fieldName] = "severityGroup"
                    it[oldValue] = oldSeverity
                    it[newValue] = severityGroup
                    it[changedByDoctorId] = doctorRef
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
                        changedAt = row[DoctorPatientGroupChangesTable.changedAt].toIsoInstant()
                    )
                },
                nextCursor = if (hasMore) page.last()[DoctorPatientGroupChangesTable.id].value.toString() else null
            )
        }
    }

    suspend fun getPatientProfile(
        doctorId: Long,
        patientUserId: Long
    ): DoctorPatientProfileResponse {
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val patientRef = EntityID(patientUserId, UsersTable)
            val userRow = requireUser(patientRef)
            val profileRow = UserProfilesTable.selectAll().where {
                UserProfilesTable.userId eq patientRef
            }.firstOrNull()
            val diseaseHistory = UserDiseaseHistoriesTable.selectAll().where {
                UserDiseaseHistoriesTable.userId eq patientRef
            }.orderBy(UserDiseaseHistoriesTable.id, SortOrder.ASC).map { it[UserDiseaseHistoriesTable.item] }

            DoctorPatientProfileResponse(
                patientUserId = patientUserId,
                phone = userRow[UsersTable.phone],
                fullName = profileRow?.get(UserProfilesTable.fullName),
                gender = profileRow?.get(UserProfilesTable.gender) ?: Gender.UNKNOWN,
                birthDate = profileRow?.get(UserProfilesTable.birthDate)?.toString(),
                heightCm = profileRow?.get(UserProfilesTable.heightCm)?.toDouble(),
                weightKg = profileRow?.get(UserProfilesTable.weightKg)?.toDouble(),
                waistCm = profileRow?.get(UserProfilesTable.waistCm)?.toDouble(),
                usesTcm = profileRow?.get(UserProfilesTable.usesTcm) ?: false,
                diseaseHistory = diseaseHistory,
                updatedAt = profileRow?.get(UserProfilesTable.updatedAt)?.toIsoInstant()
            )
        }
    }

    suspend fun listPatientScaleHistory(
        doctorId: Long,
        patientUserId: Long,
        limit: Int,
        cursor: String?
    ): ListScaleHistoryResponse {
        val safeLimit = limit.coerceIn(1, 50)
        val cursorId = parseScaleHistoryCursor(cursor)
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val patientRef = EntityID(patientUserId, UsersTable)
            val condition = (UserScaleSessionsTable.userId eq patientRef) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED) and
                if (cursorId != null) (UserScaleSessionsTable.id less cursorId) else (UserScaleSessionsTable.id greater 0L)
            val rows = UserScaleSessionsTable.selectAll().where {
                condition
            }.orderBy(UserScaleSessionsTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val scaleById = if (page.isEmpty()) {
                emptyMap()
            } else {
                val scaleRefs = page.map { it[UserScaleSessionsTable.scaleId] }.distinct()
                ScalesTable.selectAll().where {
                    ScalesTable.id inList scaleRefs
                }.associateBy { it[ScalesTable.id].value }
            }
            val versionById = if (page.isEmpty()) {
                emptyMap()
            } else {
                val versionRefs = page.map { it[UserScaleSessionsTable.versionId] }.distinct()
                ScaleVersionsTable.selectAll().where {
                    ScaleVersionsTable.id inList versionRefs
                }.associateBy { it[ScaleVersionsTable.id].value }
            }
            val resultBySessionId = if (page.isEmpty()) {
                emptyMap()
            } else {
                UserScaleResultsTable.selectAll().where {
                    UserScaleResultsTable.sessionId inList page.map { it[UserScaleSessionsTable.id] }
                }.associateBy { it[UserScaleResultsTable.sessionId].value }
            }

            ListScaleHistoryResponse(
                items = page.map { row ->
                    val sessionId = row[UserScaleSessionsTable.id].value
                    val scale = scaleById[row[UserScaleSessionsTable.scaleId].value]
                        ?: throw doctorInvalidArg("Scale not found for session=$sessionId")
                    val version = versionById[row[UserScaleSessionsTable.versionId].value]
                        ?: throw doctorInvalidArg("Version not found for session=$sessionId")
                    val result = resultBySessionId[sessionId]
                    ScaleHistoryItem(
                        sessionId = sessionId,
                        scaleId = scale[ScalesTable.id].value,
                        scaleCode = scale[ScalesTable.code],
                        scaleName = scale[ScalesTable.name],
                        versionId = version[ScaleVersionsTable.id].value,
                        version = version[ScaleVersionsTable.version],
                        progress = row[UserScaleSessionsTable.progress],
                        totalScore = result?.get(UserScaleResultsTable.totalScore)?.toDouble(),
                        submittedAt = row[UserScaleSessionsTable.submittedAt]?.toIsoInstant(),
                        updatedAt = row[UserScaleSessionsTable.updatedAt].toIsoInstant()
                    )
                },
                nextCursor = if (hasMore) page.last()[UserScaleSessionsTable.id].value.toString() else null
            )
        }
    }

    suspend fun getPatientScaleSessionResult(
        doctorId: Long,
        patientUserId: Long,
        sessionId: Long
    ): ScaleResultResponse {
        return DatabaseFactory.dbQuery {
            requireActiveBindingForDoctor(doctorId, patientUserId)
            val sessionRef = EntityID(sessionId, UserScaleSessionsTable)
            val sessionRow = UserScaleSessionsTable.selectAll().where {
                UserScaleSessionsTable.id eq sessionRef
            }.firstOrNull() ?: throw AppException(
                code = ErrorCodes.SCALE_SESSION_NOT_FOUND,
                message = "Scale session not found",
                status = HttpStatusCode.NotFound
            )
            if (sessionRow[UserScaleSessionsTable.userId].value != patientUserId) {
                throw doctorForbidden("Scale session does not belong to current patient")
            }
            val resultRow = UserScaleResultsTable.selectAll().where {
                UserScaleResultsTable.sessionId eq sessionRef
            }.firstOrNull() ?: throw AppException(
                code = ErrorCodes.SCALE_INVALID_ARGUMENT,
                message = "Result not ready",
                status = HttpStatusCode.Conflict
            )
            val bandCode = resultRow[UserScaleResultsTable.bandLevelCode]
            val bandRow = if (bandCode.isNullOrBlank()) {
                null
            } else {
                ScaleResultBandsTable.selectAll().where {
                    (ScaleResultBandsTable.versionId eq sessionRow[UserScaleSessionsTable.versionId]) and
                        (ScaleResultBandsTable.levelCode eq bandCode)
                }.orderBy(ScaleResultBandsTable.minScore, SortOrder.ASC).limit(1).firstOrNull()
            }
            val dimensionScores = parseDoubleMap(parseDoctorJsonOrNull(resultRow[UserScaleResultsTable.dimensionScoresJson]))
            val resultDetail = parseDoctorJsonOrNull(resultRow[UserScaleResultsTable.resultDetailJson]) as? JsonObject
            val overallMetrics = parseDoubleMap(resultDetail?.get("overallMetrics"))
            val dimensionResults = parseDimensionResultList(resultDetail?.get("dimensionResults"))
            val resultFlags = parseStringList(resultDetail?.get("resultFlags"))
            ScaleResultResponse(
                sessionId = sessionId,
                totalScore = resultRow[UserScaleResultsTable.totalScore]?.toDouble(),
                dimensionScores = dimensionScores,
                overallMetrics = overallMetrics,
                dimensionResults = dimensionResults,
                resultFlags = resultFlags,
                bandLevelCode = bandCode,
                bandLevelName = bandRow?.get(ScaleResultBandsTable.levelName),
                resultText = resultRow[UserScaleResultsTable.resultText] ?: bandRow?.get(ScaleResultBandsTable.interpretation),
                computedAt = resultRow[UserScaleResultsTable.computedAt].toIsoInstant()
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
        val report = runCatching {
            val (output, _) = deps.deepSeekClient.completeTextChat(
                messages = listOf(
                    ChatMessage(role = "system", content = REPORT_GENERATION_SYSTEM_PROMPT.trimIndent()),
                    ChatMessage(role = "user", content = buildReportGenerationPrompt(template))
                ),
                temperature = 0.2,
                maxTokens = 1200
            )
            output.trim()
        }.getOrElse { ex ->
            throw AppException(
                code = ErrorCodes.AI_UPSTREAM_ERROR,
                message = "Assessment report generation failed: ${ex.message}",
                status = HttpStatusCode.BadGateway
            )
        }.takeIf { it.isNotEmpty() } ?: throw AppException(
            code = ErrorCodes.AI_UPSTREAM_ERROR,
            message = "Assessment report generation failed: empty model output",
            status = HttpStatusCode.BadGateway
        )
        val model: String = deps.llmConfig.model
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
                it[DoctorPatientAssessmentReportsTable.polished] = true
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

    private fun buildReportGenerationPrompt(templateReport: String): String {
        return """
请基于以下结构化信息直接生成医生可用的评估报告（中文）：

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

    private fun parseScaleHistoryCursor(raw: String?): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return raw.toLongOrNull()?.takeIf { it > 0L } ?: throw doctorInvalidArg("cursor must be a valid positive long value")
    }

    private fun parseDoctorJsonOrNull(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { deps.json.parseToJsonElement(raw) }.getOrNull()
    }

    private fun parseDoubleMap(element: JsonElement?): Map<String, Double> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            val primitive = v as? JsonPrimitive ?: return@mapNotNull null
            val value = primitive.doubleOrNull ?: return@mapNotNull null
            k to value
        }.toMap()
    }

    private fun parseStringList(element: JsonElement?): List<String> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val primitive = item as? JsonPrimitive ?: return@mapNotNull null
            primitive.content.trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun parseDimensionResultList(element: JsonElement?): List<ScaleDimensionResult> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val dimensionKey = (obj["dimensionKey"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val extraMetrics = parseDoubleMap(obj["extraMetrics"])
            ScaleDimensionResult(
                dimensionKey = dimensionKey,
                dimensionName = (obj["dimensionName"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() },
                rawScore = (obj["rawScore"] as? JsonPrimitive)?.doubleOrNull,
                averageScore = (obj["averageScore"] as? JsonPrimitive)?.doubleOrNull,
                standardScore = (obj["standardScore"] as? JsonPrimitive)?.doubleOrNull,
                levelCode = (obj["levelCode"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() },
                levelName = (obj["levelName"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() },
                interpretation = (obj["interpretation"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() },
                extraMetrics = extraMetrics
            )
        }
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

    private fun org.jetbrains.exposed.sql.Transaction.upsertGroupDefinition(
        doctorRef: EntityID<Long>,
        severityGroup: String?,
        now: LocalDateTime
    ) {
        if (severityGroup == null) {
            return
        }
        val existing = DoctorPatientGroupsTable.selectAll().where {
            (DoctorPatientGroupsTable.doctorId eq doctorRef) and
                (DoctorPatientGroupsTable.severityGroup eq severityGroup)
        }.orderBy(DoctorPatientGroupsTable.id, SortOrder.DESC).limit(1).firstOrNull()
        if (existing == null) {
            DoctorPatientGroupsTable.insert {
                it[DoctorPatientGroupsTable.doctorId] = doctorRef
                it[DoctorPatientGroupsTable.severityGroup] = severityGroup
                it[DoctorPatientGroupsTable.createdAt] = now
                it[DoctorPatientGroupsTable.updatedAt] = now
            }
            return
        }
        DoctorPatientGroupsTable.update({ DoctorPatientGroupsTable.id eq existing[DoctorPatientGroupsTable.id] }) {
            it[DoctorPatientGroupsTable.updatedAt] = now
        }
    }

    private fun normalizeGroupValue(value: String?): String? {
        if (value == null) {
            return null
        }
        val normalized = value.trim().takeIf { it.isNotEmpty() }
        validateTextLength("group value", normalized, GROUP_VALUE_MAX_LENGTH)
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
