package me.hztcm.mindisle.scale.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.config.LlmConfig
import me.hztcm.mindisle.config.ScaleConfig
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.ScaleOptionsTable
import me.hztcm.mindisle.db.ScaleQuestionType
import me.hztcm.mindisle.db.ScaleQuestionsTable
import me.hztcm.mindisle.db.ScaleResultBandsTable
import me.hztcm.mindisle.db.ScaleScoringMethod
import me.hztcm.mindisle.db.ScaleScoringRulesTable
import me.hztcm.mindisle.db.ScaleSessionStatus
import me.hztcm.mindisle.db.ScaleStatus
import me.hztcm.mindisle.db.ScaleVersionsTable
import me.hztcm.mindisle.db.ScalesTable
import me.hztcm.mindisle.db.UserScaleAnswersTable
import me.hztcm.mindisle.db.UserScaleResultsTable
import me.hztcm.mindisle.db.UserScaleSessionsTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.CreateScaleSessionResponse
import me.hztcm.mindisle.model.Gender
import me.hztcm.mindisle.model.ListScaleHistoryResponse
import me.hztcm.mindisle.model.ListScalesResponse
import me.hztcm.mindisle.model.SaveScaleAnswerRequest
import me.hztcm.mindisle.model.SaveScaleAnswerResponse
import me.hztcm.mindisle.model.ScaleAnswerItem
import me.hztcm.mindisle.model.ScaleAssistDeltaEvent
import me.hztcm.mindisle.model.ScaleAssistDoneEvent
import me.hztcm.mindisle.model.ScaleAssistErrorEvent
import me.hztcm.mindisle.model.ScaleAssistMetaEvent
import me.hztcm.mindisle.model.ScaleAssistStreamRequest
import me.hztcm.mindisle.model.ScaleDimensionDef
import me.hztcm.mindisle.model.ScaleDimensionResult
import me.hztcm.mindisle.model.ScaleDetailResponse
import me.hztcm.mindisle.model.ScaleHistoryItem
import me.hztcm.mindisle.model.ScaleListItem
import me.hztcm.mindisle.model.ScaleOptionItem
import me.hztcm.mindisle.model.ScaleQuestionItem
import me.hztcm.mindisle.model.ScaleQuestionTypeDto
import me.hztcm.mindisle.model.ScaleResultResponse
import me.hztcm.mindisle.model.ScaleScoreRange
import me.hztcm.mindisle.model.ScaleSessionDetailResponse
import me.hztcm.mindisle.model.ScaleSessionStatusDto
import me.hztcm.mindisle.model.ScaleSessionSummary
import me.hztcm.mindisle.model.ScaleStatusDto
import me.hztcm.mindisle.model.SubmitScaleSessionResponse
import me.hztcm.mindisle.util.generateSecureToken
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal

private const val SCALE_ASSIST_EVENT_META = "meta"
private const val SCALE_ASSIST_EVENT_DELTA = "delta"
private const val SCALE_ASSIST_EVENT_DONE = "done"
private const val SCALE_ASSIST_EVENT_ERROR = "error"

private const val SCALE_ASSIST_SYSTEM_PROMPT = """
你是心理量表答题助手。
任务边界：
1) 只解释题意、作答方式、选项差异，不替用户代答。
2) 不做医疗诊断，不下确定性结论。
3) 对于自伤自杀等高风险内容，提醒尽快联系医生或紧急求助。
4) 回复简洁，优先给可执行建议。
5) 永远使用简体中文。
"""

data class ScaleAssistStreamEventRecord(
    val eventId: String,
    val eventType: String,
    val eventJson: String
)

private data class ScaleAssistOption(
    val label: String,
    val scoreValue: BigDecimal?
)

private data class ScaleAssistContext(
    val sessionId: Long,
    val scaleId: Long,
    val scaleCode: String,
    val scaleName: String,
    val questionId: Long,
    val questionKey: String,
    val questionStem: String,
    val hint: String? = null,
    val note: String? = null,
    val options: List<ScaleAssistOption> = emptyList()
)

class ScaleService(
    private val config: LlmConfig,
    private val deepSeekClient: DeepSeekAliyunClient,
    private val scaleConfig: ScaleConfig
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listScales(limit: Int, cursor: String?, status: ScaleStatus?): ListScalesResponse {
        val safeLimit = limit.coerceIn(1, 50)
        val cursorId = parseCursor(cursor, "cursor")
        return DatabaseFactory.dbQuery {
            val latestVersionByScale = ScaleVersionsTable.selectAll().where {
                ScaleVersionsTable.status eq ScaleStatus.PUBLISHED
            }.orderBy(ScaleVersionsTable.scaleId, SortOrder.ASC)
                .orderBy(ScaleVersionsTable.version, SortOrder.DESC)
                .toList()
                .fold(mutableMapOf<Long, ResultRow>()) { acc, row ->
                    acc.putIfAbsent(row[ScaleVersionsTable.scaleId].value, row)
                    acc
                }

            val baseCondition = if (cursorId != null) {
                ScalesTable.id less cursorId
            } else {
                ScalesTable.id greater 0L
            }
            val statusCondition = if (status != null) {
                ScalesTable.status eq status
            } else {
                ScalesTable.id greater 0L
            }
            val allRows = ScalesTable.selectAll().where {
                baseCondition and statusCondition
            }.orderBy(ScalesTable.id, SortOrder.DESC).limit(safeLimit + 32).toList()

            val filtered = allRows.filter { latestVersionByScale.containsKey(it[ScalesTable.id].value) }
            val page = filtered.take(safeLimit)
            val hasMore = filtered.size > safeLimit
            val items = page.map { row ->
                val versionRow = latestVersionByScale[row[ScalesTable.id].value]
                    ?: throw scaleVersionNotFound("Published version not found for scale=${row[ScalesTable.id].value}")
                ScaleListItem(
                    scaleId = row[ScalesTable.id].value,
                    code = row[ScalesTable.code],
                    name = row[ScalesTable.name],
                    description = row[ScalesTable.description],
                    status = row[ScalesTable.status].toDto(),
                    latestVersion = versionRow[ScaleVersionsTable.version],
                    publishedAt = versionRow[ScaleVersionsTable.publishedAt]?.toIsoInstant()
                )
            }
            val nextCursor = if (hasMore) page.lastOrNull()?.get(ScalesTable.id)?.value?.toString() else null
            ListScalesResponse(items = items, nextCursor = nextCursor)
        }
    }

    suspend fun getScaleDetail(scaleRef: String): ScaleDetailResponse {
        return DatabaseFactory.dbQuery {
            val scaleRow = loadScaleByRef(scaleRef)
            val versionRow = latestPublishedVersion(scaleRow[ScalesTable.id])
                ?: throw scaleVersionNotFound("Published version not found")
            val questions = ScaleQuestionsTable.selectAll().where {
                ScaleQuestionsTable.versionId eq versionRow[ScaleVersionsTable.id]
            }.orderBy(ScaleQuestionsTable.orderNo, SortOrder.ASC).toList()
            val config = parseJsonOrNull(versionRow[ScaleVersionsTable.configJson])

            val questionIds = questions.map { it[ScaleQuestionsTable.id] }
            val optionsByQuestionId = if (questionIds.isEmpty()) {
                emptyMap<Long, List<ResultRow>>()
            } else {
                ScaleOptionsTable.selectAll().where {
                    ScaleOptionsTable.questionId inList questionIds
                }.orderBy(ScaleOptionsTable.orderNo, SortOrder.ASC)
                    .toList()
                    .groupBy { it[ScaleOptionsTable.questionId].value }
            }

            ScaleDetailResponse(
                scaleId = scaleRow[ScalesTable.id].value,
                code = scaleRow[ScalesTable.code],
                name = scaleRow[ScalesTable.name],
                description = scaleRow[ScalesTable.description],
                status = scaleRow[ScalesTable.status].toDto(),
                versionId = versionRow[ScaleVersionsTable.id].value,
                version = versionRow[ScaleVersionsTable.version],
                config = config,
                dimensions = parseDimensions(config),
                questions = questions.map { q ->
                    ScaleQuestionItem(
                        questionId = q[ScaleQuestionsTable.id].value,
                        questionKey = q[ScaleQuestionsTable.questionKey],
                        orderNo = q[ScaleQuestionsTable.orderNo],
                        type = q[ScaleQuestionsTable.type].toDto(),
                        dimension = q[ScaleQuestionsTable.dimension],
                        required = q[ScaleQuestionsTable.required],
                        scorable = q[ScaleQuestionsTable.scorable],
                        reverseScored = q[ScaleQuestionsTable.reverseScored],
                        stem = q[ScaleQuestionsTable.stem],
                        hint = q[ScaleQuestionsTable.hint],
                        note = q[ScaleQuestionsTable.note],
                        optionSetCode = q[ScaleQuestionsTable.optionSetCode],
                        meta = parseJsonOrNull(q[ScaleQuestionsTable.metaJson]),
                        options = optionsByQuestionId[q[ScaleQuestionsTable.id].value].orEmpty().map { option ->
                            ScaleOptionItem(
                                optionId = option[ScaleOptionsTable.id].value,
                                optionKey = option[ScaleOptionsTable.optionKey],
                                orderNo = option[ScaleOptionsTable.orderNo],
                                label = option[ScaleOptionsTable.label],
                                scoreValue = option[ScaleOptionsTable.scoreValue]?.toDouble(),
                                ext = parseJsonOrNull(option[ScaleOptionsTable.extJson])
                            )
                        }
                    )
                }
            )
        }
    }

    suspend fun createOrResumeSession(userId: Long, scaleId: Long): CreateScaleSessionResponse {
        return DatabaseFactory.dbQuery {
            val scaleRef = EntityID(scaleId, ScalesTable)
            val scaleRow = ScalesTable.selectAll().where {
                ScalesTable.id eq scaleRef
            }.firstOrNull() ?: throw scaleNotFound("Scale not found")
            val inProgress = UserScaleSessionsTable.selectAll().where {
                (UserScaleSessionsTable.userId eq EntityID(userId, UsersTable)) and
                    (UserScaleSessionsTable.scaleId eq scaleRef) and
                    (UserScaleSessionsTable.status eq ScaleSessionStatus.IN_PROGRESS)
            }.orderBy(UserScaleSessionsTable.updatedAt, SortOrder.DESC).limit(1).firstOrNull()
            if (inProgress != null) {
                val versionRow = ScaleVersionsTable.selectAll().where {
                    ScaleVersionsTable.id eq inProgress[UserScaleSessionsTable.versionId]
                }.firstOrNull() ?: throw scaleVersionNotFound("Session version not found")
                return@dbQuery CreateScaleSessionResponse(
                    created = false,
                    session = toSessionSummary(inProgress, scaleRow, versionRow)
                )
            }

            val versionRow = latestPublishedVersion(scaleRef)
                ?: throw scaleVersionNotFound("Published version not found")
            val now = utcNow()
            val sessionId = UserScaleSessionsTable.insert {
                it[UserScaleSessionsTable.userId] = EntityID(userId, UsersTable)
                it[UserScaleSessionsTable.scaleId] = scaleRef
                it[UserScaleSessionsTable.versionId] = versionRow[ScaleVersionsTable.id]
                it[UserScaleSessionsTable.status] = ScaleSessionStatus.IN_PROGRESS
                it[UserScaleSessionsTable.progress] = 0
                it[startedAt] = now
                it[updatedAt] = now
                it[submittedAt] = null
            }[UserScaleSessionsTable.id]
            val session = UserScaleSessionsTable.selectAll().where {
                UserScaleSessionsTable.id eq sessionId
            }.first()
            CreateScaleSessionResponse(
                created = true,
                session = toSessionSummary(session, scaleRow, versionRow)
            )
        }
    }

    suspend fun getSessionDetail(userId: Long, sessionId: Long): ScaleSessionDetailResponse {
        return DatabaseFactory.dbQuery {
            val sessionRow = getOwnedSession(userId, sessionId)
            val scaleRow = ScalesTable.selectAll().where {
                ScalesTable.id eq sessionRow[UserScaleSessionsTable.scaleId]
            }.first()
            val versionRow = ScaleVersionsTable.selectAll().where {
                ScaleVersionsTable.id eq sessionRow[UserScaleSessionsTable.versionId]
            }.first()
            val questionRows = ScaleQuestionsTable.selectAll().where {
                ScaleQuestionsTable.versionId eq versionRow[ScaleVersionsTable.id]
            }.orderBy(ScaleQuestionsTable.orderNo, SortOrder.ASC).toList()

            val answerRows = UserScaleAnswersTable.selectAll().where {
                UserScaleAnswersTable.sessionId eq sessionRow[UserScaleSessionsTable.id]
            }.orderBy(UserScaleAnswersTable.updatedAt, SortOrder.ASC).toList()

            val answeredQuestionIds = answerRows.map { it[UserScaleAnswersTable.questionId].value }.toSet()
            val unansweredRequired = questionRows
                .filter { it[ScaleQuestionsTable.required] && !answeredQuestionIds.contains(it[ScaleQuestionsTable.id].value) }
                .map { it[ScaleQuestionsTable.id].value }

            ScaleSessionDetailResponse(
                session = toSessionSummary(sessionRow, scaleRow, versionRow),
                answers = answerRows.map { row ->
                    ScaleAnswerItem(
                        questionId = row[UserScaleAnswersTable.questionId].value,
                        answer = parseJsonOrNull(row[UserScaleAnswersTable.answerJson]) ?: JsonObject(emptyMap()),
                        numericScore = row[UserScaleAnswersTable.numericScore]?.toDouble(),
                        updatedAt = row[UserScaleAnswersTable.updatedAt].toIsoInstant()
                    )
                },
                unansweredRequiredQuestionIds = unansweredRequired
            )
        }
    }

    suspend fun saveAnswer(
        userId: Long,
        sessionId: Long,
        questionId: Long,
        request: SaveScaleAnswerRequest
    ): SaveScaleAnswerResponse {
        return DatabaseFactory.dbQuery {
            val sessionRow = getOwnedSession(userId, sessionId)
            ensureSessionWritable(sessionRow[UserScaleSessionsTable.status])

            val questionRef = EntityID(questionId, ScaleQuestionsTable)
            val questionRow = ScaleQuestionsTable.selectAll().where {
                (ScaleQuestionsTable.id eq questionRef) and
                    (ScaleQuestionsTable.versionId eq sessionRow[UserScaleSessionsTable.versionId])
            }.firstOrNull() ?: throw invalidScaleArg("Question does not belong to this session version")

            val optionRows = ScaleOptionsTable.selectAll().where {
                ScaleOptionsTable.questionId eq questionRef
            }.orderBy(ScaleOptionsTable.orderNo, SortOrder.ASC).toList()
            val optionScores = optionRows.map { row ->
                ScaleOptionScore(
                    optionId = row[ScaleOptionsTable.id].value,
                    optionKey = row[ScaleOptionsTable.optionKey],
                    scoreValue = row[ScaleOptionsTable.scoreValue]
                )
            }
            val evaluation = ScaleAnswerEvaluator.evaluate(
                type = questionRow[ScaleQuestionsTable.type],
                scorable = questionRow[ScaleQuestionsTable.scorable],
                reverseScored = questionRow[ScaleQuestionsTable.reverseScored],
                options = optionScores,
                answer = request.answer
            )
            enforceOpenTextAnswerLength(
                questionType = questionRow[ScaleQuestionsTable.type],
                normalizedAnswer = evaluation.normalizedAnswer,
                minChars = scaleConfig.openTextAnswerMinChars,
                maxChars = scaleConfig.openTextAnswerMaxChars
            )
            val now = utcNow()
            val sessionRef = sessionRow[UserScaleSessionsTable.id]
            val existing = UserScaleAnswersTable.selectAll().where {
                (UserScaleAnswersTable.sessionId eq sessionRef) and
                    (UserScaleAnswersTable.questionId eq questionRef)
            }.firstOrNull()
            if (existing == null) {
                UserScaleAnswersTable.insert {
                    it[UserScaleAnswersTable.sessionId] = sessionRef
                    it[UserScaleAnswersTable.questionId] = questionRef
                    it[answerJson] = json.encodeToString(JsonElement.serializer(), evaluation.normalizedAnswer)
                    it[numericScore] = evaluation.numericScore
                    it[answeredAt] = now
                    it[updatedAt] = now
                }
            } else {
                UserScaleAnswersTable.update({
                    UserScaleAnswersTable.id eq existing[UserScaleAnswersTable.id]
                }) {
                    it[answerJson] = json.encodeToString(JsonElement.serializer(), evaluation.normalizedAnswer)
                    it[numericScore] = evaluation.numericScore
                    it[updatedAt] = now
                }
            }

            val progress = computeProgress(sessionRef, sessionRow[UserScaleSessionsTable.versionId])
            UserScaleSessionsTable.update({
                UserScaleSessionsTable.id eq sessionRef
            }) {
                it[UserScaleSessionsTable.progress] = progress
                it[updatedAt] = now
            }

            SaveScaleAnswerResponse(
                sessionId = sessionId,
                questionId = questionId,
                numericScore = evaluation.numericScore?.toDouble(),
                progress = progress,
                updatedAt = now.toIsoInstant()
            )
        }
    }

    suspend fun submitSession(userId: Long, sessionId: Long): SubmitScaleSessionResponse {
        return DatabaseFactory.dbQuery {
            val sessionRow = getOwnedSession(userId, sessionId)
            ensureSessionSubmittable(sessionRow[UserScaleSessionsTable.status])
            val versionRef = sessionRow[UserScaleSessionsTable.versionId]

            val questionRows = ScaleQuestionsTable.selectAll().where {
                ScaleQuestionsTable.versionId eq versionRef
            }.orderBy(ScaleQuestionsTable.orderNo, SortOrder.ASC).toList()
            val answerRows = UserScaleAnswersTable.selectAll().where {
                UserScaleAnswersTable.sessionId eq sessionRow[UserScaleSessionsTable.id]
            }.toList()
            val answerByQuestionId = answerRows.associateBy { it[UserScaleAnswersTable.questionId].value }
            val missingRequired = questionRows
                .filter { it[ScaleQuestionsTable.required] && !answerByQuestionId.containsKey(it[ScaleQuestionsTable.id].value) }
                .map { it[ScaleQuestionsTable.id].value }
            if (missingRequired.isNotEmpty()) {
                throw AppException(
                    code = ErrorCodes.SCALE_REQUIRED_ANSWER_MISSING,
                    message = "Required answers missing: ${missingRequired.joinToString(",")}",
                    status = HttpStatusCode.UnprocessableEntity
                )
            }

            val ruleRow = ScaleScoringRulesTable.selectAll().where {
                ScaleScoringRulesTable.versionId eq versionRef
            }.firstOrNull()
            val method = ruleRow?.get(ScaleScoringRulesTable.method) ?: ScaleScoringMethod.SIMPLE_SUM
            val ruleJson = ruleRow?.get(ScaleScoringRulesTable.ruleJson) ?: "{}"
            val bandRows = ScaleResultBandsTable.selectAll().where {
                ScaleResultBandsTable.versionId eq versionRef
            }.toList()

            val scoreResult = runCatching {
                val userGender = UserProfilesTable.selectAll().where {
                    UserProfilesTable.userId eq sessionRow[UserScaleSessionsTable.userId]
                }.firstOrNull()?.get(UserProfilesTable.gender) ?: Gender.UNKNOWN
                ScaleScoringEngine.compute(
                    method = method,
                    ruleJson = ruleJson,
                    questions = questionRows.map {
                        ScoreQuestionRow(
                            questionId = it[ScaleQuestionsTable.id].value,
                            questionKey = it[ScaleQuestionsTable.questionKey],
                            dimension = it[ScaleQuestionsTable.dimension],
                            scorable = it[ScaleQuestionsTable.scorable],
                            type = it[ScaleQuestionsTable.type]
                        )
                    },
                    answers = answerRows.map {
                        ScoreAnswerRow(
                            questionId = it[UserScaleAnswersTable.questionId].value,
                            numericScore = it[UserScaleAnswersTable.numericScore],
                            answerJson = it[UserScaleAnswersTable.answerJson]
                        )
                    },
                    bands = bandRows.map {
                        ScoreBandRow(
                            dimension = it[ScaleResultBandsTable.dimension],
                            minScore = it[ScaleResultBandsTable.minScore],
                            maxScore = it[ScaleResultBandsTable.maxScore],
                            levelCode = it[ScaleResultBandsTable.levelCode],
                            levelName = it[ScaleResultBandsTable.levelName],
                            interpretation = it[ScaleResultBandsTable.interpretation]
                        )
                    },
                    userGender = userGender
                )
            }.getOrElse { ex ->
                throw AppException(
                    code = ErrorCodes.SCALE_SCORING_INTERNAL_ERROR,
                    message = "Scoring failed: ${ex.message}",
                    status = HttpStatusCode.InternalServerError
                )
            }

            val now = utcNow()
            val sessionRef = sessionRow[UserScaleSessionsTable.id]
            val existingResult = UserScaleResultsTable.selectAll().where {
                UserScaleResultsTable.sessionId eq sessionRef
            }.firstOrNull()
            val dimensionJson = if (scoreResult.dimensionScores.isEmpty()) {
                null
            } else {
                json.encodeToString(scoreResult.dimensionScores.mapValues { (_, value) -> value.toDouble() })
            }
            val resultDetailJson = buildResultDetailJson(scoreResult)

            if (existingResult == null) {
                UserScaleResultsTable.insert {
                    it[UserScaleResultsTable.sessionId] = sessionRef
                    it[totalScore] = scoreResult.totalScore
                    it[dimensionScoresJson] = dimensionJson
                    it[UserScaleResultsTable.resultDetailJson] = resultDetailJson
                    it[bandLevelCode] = scoreResult.bandLevelCode
                    it[resultText] = scoreResult.resultText
                    it[computedAt] = now
                }
            } else {
                UserScaleResultsTable.update({
                    UserScaleResultsTable.id eq existingResult[UserScaleResultsTable.id]
                }) {
                    it[totalScore] = scoreResult.totalScore
                    it[dimensionScoresJson] = dimensionJson
                    it[UserScaleResultsTable.resultDetailJson] = resultDetailJson
                    it[bandLevelCode] = scoreResult.bandLevelCode
                    it[resultText] = scoreResult.resultText
                    it[computedAt] = now
                }
            }

            UserScaleSessionsTable.update({
                UserScaleSessionsTable.id eq sessionRef
            }) {
                it[status] = ScaleSessionStatus.SUBMITTED
                it[progress] = 100
                it[submittedAt] = now
                it[updatedAt] = now
            }

            SubmitScaleSessionResponse(
                sessionId = sessionId,
                status = ScaleSessionStatusDto.SUBMITTED,
                progress = 100,
                submittedAt = now.toIsoInstant()
            )
        }
    }

    suspend fun getResult(userId: Long, sessionId: Long): ScaleResultResponse {
        return DatabaseFactory.dbQuery {
            val sessionRow = getOwnedSession(userId, sessionId)
            val resultRow = UserScaleResultsTable.selectAll().where {
                UserScaleResultsTable.sessionId eq sessionRow[UserScaleSessionsTable.id]
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
            val dimensionScores = parseJsonOrNull(resultRow[UserScaleResultsTable.dimensionScoresJson])
                ?.let { parseDoubleMap(it) }
                ?: emptyMap()
            val resultDetail = parseJsonOrNull(resultRow[UserScaleResultsTable.resultDetailJson]) as? JsonObject
            val overallMetrics = resultDetail?.get("overallMetrics")?.let(::parseDoubleMap) ?: emptyMap()
            val dimensionResults = resultDetail?.get("dimensionResults")?.let(::parseDimensionResultList) ?: emptyList()
            val resultFlags = resultDetail?.get("resultFlags")?.let(::parseStringList) ?: emptyList()

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

    suspend fun listHistory(userId: Long, limit: Int, cursor: String?): ListScaleHistoryResponse {
        val safeLimit = limit.coerceIn(1, 50)
        val cursorId = parseCursor(cursor, "cursor")
        return DatabaseFactory.dbQuery {
            val condition = (UserScaleSessionsTable.userId eq EntityID(userId, UsersTable)) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED) and
                if (cursorId != null) (UserScaleSessionsTable.id less cursorId) else (UserScaleSessionsTable.id greater 0L)
            val rows = UserScaleSessionsTable.selectAll().where {
                condition
            }.orderBy(UserScaleSessionsTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val items = page.map { row ->
                val scaleRow = ScalesTable.selectAll().where {
                    ScalesTable.id eq row[UserScaleSessionsTable.scaleId]
                }.first()
                val versionRow = ScaleVersionsTable.selectAll().where {
                    ScaleVersionsTable.id eq row[UserScaleSessionsTable.versionId]
                }.first()
                val resultRow = UserScaleResultsTable.selectAll().where {
                    UserScaleResultsTable.sessionId eq row[UserScaleSessionsTable.id]
                }.firstOrNull()
                ScaleHistoryItem(
                    sessionId = row[UserScaleSessionsTable.id].value,
                    scaleId = scaleRow[ScalesTable.id].value,
                    scaleCode = scaleRow[ScalesTable.code],
                    scaleName = scaleRow[ScalesTable.name],
                    versionId = versionRow[ScaleVersionsTable.id].value,
                    version = versionRow[ScaleVersionsTable.version],
                    progress = row[UserScaleSessionsTable.progress],
                    totalScore = resultRow?.get(UserScaleResultsTable.totalScore)?.toDouble(),
                    submittedAt = row[UserScaleSessionsTable.submittedAt]?.toIsoInstant(),
                    updatedAt = row[UserScaleSessionsTable.updatedAt].toIsoInstant()
                )
            }
            val nextCursor = if (hasMore) page.last()[UserScaleSessionsTable.id].value.toString() else null
            ListScaleHistoryResponse(items = items, nextCursor = nextCursor)
        }
    }

    suspend fun deleteDraftSession(userId: Long, sessionId: Long) {
        DatabaseFactory.dbQuery {
            val sessionRow = getOwnedSession(userId, sessionId)
            if (sessionRow[UserScaleSessionsTable.status] == ScaleSessionStatus.SUBMITTED) {
                throw AppException(
                    code = ErrorCodes.SCALE_ALREADY_SUBMITTED,
                    message = "Submitted session cannot be deleted",
                    status = HttpStatusCode.Conflict
                )
            }
            UserScaleSessionsTable.deleteWhere {
                UserScaleSessionsTable.id eq sessionRow[UserScaleSessionsTable.id]
            }
        }
    }

    suspend fun streamAssist(
        userId: Long,
        request: ScaleAssistStreamRequest,
        onEvent: suspend (ScaleAssistStreamEventRecord) -> Unit
    ) {
        validateAssistRequest(request)
        val assistContext = DatabaseFactory.dbQuery {
            loadAssistContext(userId, request.sessionId, request.questionId)
        }
        val generationId = generateSecureToken(18)
        var seq = 1L
        suspend fun emit(eventType: String, eventJson: String) {
            val event = ScaleAssistStreamEventRecord(
                eventId = "$generationId:$seq",
                eventType = eventType,
                eventJson = eventJson
            )
            seq += 1L
            onEvent(event)
        }

        emit(
            SCALE_ASSIST_EVENT_META,
            json.encodeToString(
                ScaleAssistMetaEvent(
                    generationId = generationId,
                    model = config.model,
                    createdAt = utcNow().toIsoInstant()
                )
            )
        )

        val userPrompt = buildAssistPrompt(assistContext, request.userDraftAnswer)
        runCatching {
            deepSeekClient.streamChat(
                messages = listOf(
                    ChatMessage(role = "system", content = SCALE_ASSIST_SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = userPrompt)
                ),
                temperature = 0.3,
                maxTokens = 1024
            ) { chunk ->
                val text = chunk.contentDelta?.takeIf { it.isNotEmpty() } ?: return@streamChat
                emit(
                    SCALE_ASSIST_EVENT_DELTA,
                    json.encodeToString(ScaleAssistDeltaEvent(text = text))
                )
            }
            emit(
                SCALE_ASSIST_EVENT_DONE,
                json.encodeToString(
                    ScaleAssistDoneEvent(
                        finishReason = "stop",
                        createdAt = utcNow().toIsoInstant()
                    )
                )
            )
        }.getOrElse { throwable ->
            val error = when (throwable) {
                is AppException -> {
                    if (throwable.code == ErrorCodes.AI_RATE_LIMITED) {
                        ScaleAssistErrorEvent(
                            code = ErrorCodes.SCALE_AI_RATE_LIMITED,
                            message = throwable.message
                        )
                    } else {
                        ScaleAssistErrorEvent(
                            code = throwable.code,
                            message = throwable.message
                        )
                    }
                }

                else -> ScaleAssistErrorEvent(
                    code = ErrorCodes.AI_STREAM_INTERNAL_ERROR,
                    message = throwable.message ?: "Scale assist stream failed"
                )
            }
            emit(SCALE_ASSIST_EVENT_ERROR, json.encodeToString(error))
        }
    }

    private fun buildAssistPrompt(context: ScaleAssistContext, userDraftAnswer: String?): String {
        val optionsText = if (context.options.isEmpty()) {
            "（无选项）"
        } else {
            context.options.joinToString(separator = "\n") { option ->
                if (option.scoreValue == null) {
                    "- ${option.label}"
                } else {
                    "- ${option.label}（分值=${option.scoreValue}）"
                }
            }
        }
        val noteText = context.note?.takeIf { it.isNotBlank() } ?: "无"
        val hintText = context.hint?.takeIf { it.isNotBlank() } ?: "无"
        val draftText = userDraftAnswer?.takeIf { it.isNotBlank() } ?: "无"
        return """
量表上下文：scaleId=${context.scaleId}, scaleCode=${context.scaleCode}, scaleName=${context.scaleName}
会话：sessionId=${context.sessionId}
题目标识：questionId=${context.questionId}, questionKey=${context.questionKey}
题目内容：
${context.questionStem.trim()}

选项：
$optionsText

提示：
$hintText

注意事项：
$noteText

用户草稿答案（可能为空）：
$draftText

请先解释题意，再给出如何选择的建议，禁止替用户做最终选择。若该题属于高风险内容（如自伤），补充求助建议。
""".trimIndent()
    }

    private fun validateAssistRequest(request: ScaleAssistStreamRequest) {
        if (request.sessionId <= 0L) {
            throw invalidScaleArg("sessionId must be positive")
        }
        if (request.questionId <= 0L) {
            throw invalidScaleArg("questionId must be positive")
        }
        request.userDraftAnswer?.let { draft ->
            if (draft.length > 4000) {
                throw invalidScaleArg("userDraftAnswer exceeds 4000 characters")
            }
            if (draft.any { it.isISOControl() }) {
                throw invalidScaleArg("userDraftAnswer contains control characters")
            }
        }
    }

    private fun parseDoubleMap(element: JsonElement): Map<String, Double> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.entries.mapNotNull { (k, v) ->
            val primitive = v as? JsonPrimitive ?: return@mapNotNull null
            val value = primitive.doubleOrNull ?: return@mapNotNull null
            k to value
        }.toMap()
    }

    private fun parseStringList(element: JsonElement): List<String> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val primitive = item as? JsonPrimitive ?: return@mapNotNull null
            primitive.contentOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parseDimensionResultList(element: JsonElement): List<ScaleDimensionResult> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val dimensionKey = (obj["dimensionKey"] as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
            val extraMetrics = obj["extraMetrics"]?.let(::parseDoubleMap) ?: emptyMap()
            ScaleDimensionResult(
                dimensionKey = dimensionKey,
                dimensionName = (obj["dimensionName"] as? JsonPrimitive)?.contentOrNull(),
                rawScore = (obj["rawScore"] as? JsonPrimitive)?.doubleOrNull,
                averageScore = (obj["averageScore"] as? JsonPrimitive)?.doubleOrNull,
                standardScore = (obj["standardScore"] as? JsonPrimitive)?.doubleOrNull,
                levelCode = (obj["levelCode"] as? JsonPrimitive)?.contentOrNull(),
                levelName = (obj["levelName"] as? JsonPrimitive)?.contentOrNull(),
                interpretation = (obj["interpretation"] as? JsonPrimitive)?.contentOrNull(),
                extraMetrics = extraMetrics
            )
        }
    }

    private fun buildResultDetailJson(scoreResult: ScaleScoreComputationResult): String? {
        if (scoreResult.overallMetrics.isEmpty() && scoreResult.dimensionResults.isEmpty() && scoreResult.resultFlags.isEmpty()) {
            return null
        }
        val detail = buildJsonObject {
            putJsonObject("overallMetrics") {
                scoreResult.overallMetrics.forEach { (key, value) ->
                    put(key, value.toDouble())
                }
            }
            putJsonArray("dimensionResults") {
                scoreResult.dimensionResults.forEach { item ->
                    add(
                        buildJsonObject {
                            put("dimensionKey", item.dimensionKey)
                            item.dimensionName?.let { put("dimensionName", it) }
                            item.rawScore?.let { put("rawScore", it.toDouble()) }
                            item.averageScore?.let { put("averageScore", it.toDouble()) }
                            item.standardScore?.let { put("standardScore", it.toDouble()) }
                            item.levelCode?.let { put("levelCode", it) }
                            item.levelName?.let { put("levelName", it) }
                            item.interpretation?.let { put("interpretation", it) }
                            if (item.extraMetrics.isNotEmpty()) {
                                putJsonObject("extraMetrics") {
                                    item.extraMetrics.forEach { (k, v) ->
                                        put(k, v.toDouble())
                                    }
                                }
                            }
                        }
                    )
                }
            }
            putJsonArray("resultFlags") {
                scoreResult.resultFlags.forEach { add(JsonPrimitive(it)) }
            }
        }
        return json.encodeToString(JsonElement.serializer(), detail)
    }

    private fun parseDimensions(config: JsonElement?): List<ScaleDimensionDef> {
        val configObj = config as? JsonObject ?: return emptyList()
        val dimensions = configObj["dimensions"] as? JsonArray ?: return emptyList()
        return dimensions.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val key = (obj["key"] as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull() ?: key
            val min = (obj["min"] as? JsonPrimitive)?.doubleOrNull
            val max = (obj["max"] as? JsonPrimitive)?.doubleOrNull
            ScaleDimensionDef(
                key = key,
                name = name,
                description = (obj["description"] as? JsonPrimitive)?.contentOrNull(),
                scoreRange = if (min != null && max != null) ScaleScoreRange(min = min, max = max) else null,
                interpretationHint = (obj["interpretationHint"] as? JsonPrimitive)?.contentOrNull()
            )
        }
    }

    private fun loadAssistContext(userId: Long, sessionId: Long, questionId: Long): ScaleAssistContext {
        val sessionRow = getOwnedSession(userId, sessionId)
        val questionRef = EntityID(questionId, ScaleQuestionsTable)
        val questionRow = ScaleQuestionsTable.selectAll().where {
            (ScaleQuestionsTable.id eq questionRef) and
                (ScaleQuestionsTable.versionId eq sessionRow[UserScaleSessionsTable.versionId])
        }.firstOrNull() ?: throw invalidScaleArg("Question does not belong to this session")
        val scaleRow = ScalesTable.selectAll().where {
            ScalesTable.id eq sessionRow[UserScaleSessionsTable.scaleId]
        }.firstOrNull() ?: throw scaleNotFound("Scale not found")
        val optionRows = ScaleOptionsTable.selectAll().where {
            ScaleOptionsTable.questionId eq questionRef
        }.orderBy(ScaleOptionsTable.orderNo, SortOrder.ASC).toList()
        return ScaleAssistContext(
            sessionId = sessionId,
            scaleId = scaleRow[ScalesTable.id].value,
            scaleCode = scaleRow[ScalesTable.code],
            scaleName = scaleRow[ScalesTable.name],
            questionId = questionId,
            questionKey = questionRow[ScaleQuestionsTable.questionKey],
            questionStem = questionRow[ScaleQuestionsTable.stem],
            hint = questionRow[ScaleQuestionsTable.hint],
            note = questionRow[ScaleQuestionsTable.note],
            options = optionRows.map { row ->
                ScaleAssistOption(
                    label = row[ScaleOptionsTable.label],
                    scoreValue = row[ScaleOptionsTable.scoreValue]
                )
            }
        )
    }

    private fun parseCursor(raw: String?, name: String): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return raw.toLongOrNull() ?: throw invalidScaleArg("$name must be a valid long value")
    }

    private fun parseJsonOrNull(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }

    private fun toSessionSummary(
        sessionRow: ResultRow,
        scaleRow: ResultRow,
        versionRow: ResultRow
    ): ScaleSessionSummary {
        return ScaleSessionSummary(
            sessionId = sessionRow[UserScaleSessionsTable.id].value,
            scaleId = scaleRow[ScalesTable.id].value,
            scaleCode = scaleRow[ScalesTable.code],
            scaleName = scaleRow[ScalesTable.name],
            versionId = versionRow[ScaleVersionsTable.id].value,
            version = versionRow[ScaleVersionsTable.version],
            status = sessionRow[UserScaleSessionsTable.status].toDto(),
            progress = sessionRow[UserScaleSessionsTable.progress],
            startedAt = sessionRow[UserScaleSessionsTable.startedAt].toIsoInstant(),
            updatedAt = sessionRow[UserScaleSessionsTable.updatedAt].toIsoInstant(),
            submittedAt = sessionRow[UserScaleSessionsTable.submittedAt]?.toIsoInstant()
        )
    }

    private fun latestPublishedVersion(scaleId: EntityID<Long>): ResultRow? {
        return ScaleVersionsTable.selectAll().where {
            (ScaleVersionsTable.scaleId eq scaleId) and
                (ScaleVersionsTable.status eq ScaleStatus.PUBLISHED)
        }.orderBy(ScaleVersionsTable.version, SortOrder.DESC).limit(1).firstOrNull()
    }

    private fun loadScaleByRef(scaleRef: String): ResultRow {
        val byId = scaleRef.toLongOrNull()
        val row = if (byId != null && byId > 0L) {
            ScalesTable.selectAll().where {
                ScalesTable.id eq EntityID(byId, ScalesTable)
            }.firstOrNull()
        } else {
            val code = scaleRef.trim()
            if (code.isBlank()) {
                null
            } else {
                ScalesTable.selectAll().where {
                    ScalesTable.code eq code
                }.firstOrNull()
            }
        }
        return row ?: throw scaleNotFound("Scale not found")
    }

    private fun computeProgress(
        sessionRef: EntityID<Long>,
        versionRef: EntityID<Long>
    ): Int {
        val requiredQuestionRefs = ScaleQuestionsTable.selectAll().where {
            (ScaleQuestionsTable.versionId eq versionRef) and
                (ScaleQuestionsTable.required eq true)
        }.map { it[ScaleQuestionsTable.id] }
        if (requiredQuestionRefs.isEmpty()) {
            return 0
        }
        val answeredRequired = UserScaleAnswersTable.selectAll().where {
            (UserScaleAnswersTable.sessionId eq sessionRef) and
                (UserScaleAnswersTable.questionId inList requiredQuestionRefs)
        }.count()
        return ((answeredRequired * 100.0) / requiredQuestionRefs.size).toInt().coerceIn(0, 100)
    }

    private fun getOwnedSession(userId: Long, sessionId: Long): ResultRow {
        val sessionRef = EntityID(sessionId, UserScaleSessionsTable)
        val row = UserScaleSessionsTable.selectAll().where {
            UserScaleSessionsTable.id eq sessionRef
        }.firstOrNull() ?: throw AppException(
            code = ErrorCodes.SCALE_SESSION_NOT_FOUND,
            message = "Scale session not found",
            status = HttpStatusCode.NotFound
        )
        if (row[UserScaleSessionsTable.userId].value != userId) {
            throw AppException(
                code = ErrorCodes.SCALE_SESSION_FORBIDDEN,
                message = "Scale session does not belong to current user",
                status = HttpStatusCode.Forbidden
            )
        }
        return row
    }

    private fun ensureSessionWritable(status: ScaleSessionStatus) {
        if (status == ScaleSessionStatus.SUBMITTED) {
            throw AppException(
                code = ErrorCodes.SCALE_ALREADY_SUBMITTED,
                message = "Scale session already submitted",
                status = HttpStatusCode.Conflict
            )
        }
    }

    private fun ensureSessionSubmittable(status: ScaleSessionStatus) {
        if (status == ScaleSessionStatus.SUBMITTED) {
            throw AppException(
                code = ErrorCodes.SCALE_ALREADY_SUBMITTED,
                message = "Scale session already submitted",
                status = HttpStatusCode.Conflict
            )
        }
    }

    private fun invalidScaleArg(message: String): AppException {
        return AppException(
            code = ErrorCodes.SCALE_INVALID_ARGUMENT,
            message = message,
            status = HttpStatusCode.BadRequest
        )
    }

    private fun scaleNotFound(message: String): AppException {
        return AppException(
            code = ErrorCodes.SCALE_NOT_FOUND,
            message = message,
            status = HttpStatusCode.NotFound
        )
    }

    private fun scaleVersionNotFound(message: String): AppException {
        return AppException(
            code = ErrorCodes.SCALE_VERSION_NOT_FOUND,
            message = message,
            status = HttpStatusCode.NotFound
        )
    }
}

private fun ScaleStatus.toDto(): ScaleStatusDto = when (this) {
    ScaleStatus.DRAFT -> ScaleStatusDto.DRAFT
    ScaleStatus.PUBLISHED -> ScaleStatusDto.PUBLISHED
    ScaleStatus.ARCHIVED -> ScaleStatusDto.ARCHIVED
}

private fun ScaleQuestionType.toDto(): ScaleQuestionTypeDto = when (this) {
    ScaleQuestionType.SINGLE_CHOICE -> ScaleQuestionTypeDto.SINGLE_CHOICE
    ScaleQuestionType.MULTI_CHOICE -> ScaleQuestionTypeDto.MULTI_CHOICE
    ScaleQuestionType.TEXT -> ScaleQuestionTypeDto.TEXT
    ScaleQuestionType.TIME -> ScaleQuestionTypeDto.TIME
    ScaleQuestionType.DURATION -> ScaleQuestionTypeDto.DURATION
    ScaleQuestionType.YES_NO -> ScaleQuestionTypeDto.YES_NO
}

private fun ScaleSessionStatus.toDto(): ScaleSessionStatusDto = when (this) {
    ScaleSessionStatus.IN_PROGRESS -> ScaleSessionStatusDto.IN_PROGRESS
    ScaleSessionStatus.SUBMITTED -> ScaleSessionStatusDto.SUBMITTED
    ScaleSessionStatus.ABANDONED -> ScaleSessionStatusDto.ABANDONED
}

private fun JsonPrimitive.contentOrNull(): String? {
    val value = content.trim()
    return value.takeIf { it.isNotEmpty() }
}
