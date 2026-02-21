package me.hztcm.mindisle.ai.service

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.ai.client.UsageMetrics
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.config.LlmConfig
import me.hztcm.mindisle.db.AiConversationsTable
import me.hztcm.mindisle.db.AiGenerationStatus
import me.hztcm.mindisle.db.AiGenerationsTable
import me.hztcm.mindisle.db.AiMessageRole
import me.hztcm.mindisle.db.AiMessagesTable
import me.hztcm.mindisle.db.AiStreamEventsTable
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.AiMessageRoleDto
import me.hztcm.mindisle.model.ConversationListItem
import me.hztcm.mindisle.model.CreateConversationResponse
import me.hztcm.mindisle.model.ListConversationsResponse
import me.hztcm.mindisle.model.ListMessagesResponse
import me.hztcm.mindisle.model.MessageListItem
import me.hztcm.mindisle.model.StreamChatRequest
import me.hztcm.mindisle.model.StreamDeltaEvent
import me.hztcm.mindisle.model.StreamDoneEvent
import me.hztcm.mindisle.model.StreamErrorEvent
import me.hztcm.mindisle.model.StreamMetaEvent
import me.hztcm.mindisle.model.StreamUsageEvent
import me.hztcm.mindisle.util.generateSecureToken
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

private const val EVENT_META = "meta"
private const val EVENT_DELTA = "delta"
private const val EVENT_USAGE = "usage"
private const val EVENT_DONE = "done"
private const val EVENT_ERROR = "error"
private const val SYSTEM_PROMPT = """
你是DeepSeek，由深度求索公司创造的AI助手，用于安抚病人情绪、为病人在量表填写和用药等方面提供帮助。
知识截止日期：2025年5月
提供准确、有帮助的回答
保持友好、耐心的语气
对于不确定的信息要明确说明
拒绝回答有害、违法或不当的请求
尊重用户隐私
不参与任何可能造成伤害的对话
当用户情绪激动时，提供安抚和支持
若用户询问医疗建议，提供一般信息并建议咨询专业医生
支持多轮对话
暂时不支持文件上传（图像、txt、pdf、ppt、word、excel）并从中读取文字信息
暂时不支持联网搜索
支持阅读链接内容
支持上下文长度128k
使用汉语（简体中文）回复
对于无法确认的信息要诚实说明
"""

data class StreamEventRecord(
    val generationId: String,
    val seq: Long,
    val eventType: String,
    val eventJson: String
) {
    val eventId: String get() = "$generationId:$seq"

    fun isTerminal(): Boolean = eventType == EVENT_DONE || eventType == EVENT_ERROR
}

data class ReplayEventsResult(
    val generationId: String,
    val events: List<StreamEventRecord>,
    val terminalStatus: Boolean
)

data class GenerationOwnership(
    val generationId: String,
    val conversationId: Long,
    val status: AiGenerationStatus
)

private data class GenerationContext(
    val conversationId: Long,
    val temperature: Double?,
    val maxTokens: Int?,
    val messages: List<ChatMessage>
)

class AiChatService(
    private val config: LlmConfig,
    private val deepSeekClient: DeepSeekAliyunClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generationJobs = ConcurrentHashMap<String, Job>()
    private val generationLock = Mutex()
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArraySet<Channel<StreamEventRecord>>>()

    suspend fun createConversation(userId: Long, title: String?): CreateConversationResponse {
        validateTitle(title)
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            val conversationId = AiConversationsTable.insert {
                it[AiConversationsTable.userId] = EntityID(userId, UsersTable)
                it[AiConversationsTable.title] = title?.trim()
                it[summary] = null
                it[createdAt] = now
                it[updatedAt] = now
                it[lastMessageAt] = now
            }[AiConversationsTable.id].value

            CreateConversationResponse(
                conversationId = conversationId,
                title = title?.trim(),
                createdAt = now.toIsoInstant()
            )
        }
    }

    suspend fun listConversations(userId: Long, limit: Int, cursor: String?): ListConversationsResponse {
        val safeLimit = limit.coerceIn(1, 50)
        val cursorId = parseCursor(cursor, "cursor")

        return DatabaseFactory.dbQuery {
            val condition = (AiConversationsTable.userId eq EntityID(userId, UsersTable)) and
                if (cursorId != null) (AiConversationsTable.id less cursorId) else (AiConversationsTable.id greater 0L)

            val rows = AiConversationsTable.selectAll().where {
                condition
            }.orderBy(AiConversationsTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val items = page.map {
                ConversationListItem(
                    conversationId = it[AiConversationsTable.id].value,
                    title = it[AiConversationsTable.title],
                    summary = it[AiConversationsTable.summary],
                    lastMessageAt = it[AiConversationsTable.lastMessageAt].toIsoInstant(),
                    createdAt = it[AiConversationsTable.createdAt].toIsoInstant()
                )
            }
            val nextCursor = if (hasMore) page.last()[AiConversationsTable.id].value.toString() else null
            ListConversationsResponse(items = items, nextCursor = nextCursor)
        }
    }

    suspend fun listMessages(userId: Long, conversationId: Long, limit: Int, before: String?): ListMessagesResponse {
        val safeLimit = limit.coerceIn(1, 100)
        val beforeId = parseCursor(before, "before")

        DatabaseFactory.dbQuery {
            getOwnedConversation(userId, conversationId)
        }

        return DatabaseFactory.dbQuery {
            val condition = (AiMessagesTable.userId eq EntityID(userId, UsersTable)) and
                (AiMessagesTable.conversationId eq EntityID(conversationId, AiConversationsTable)) and
                if (beforeId != null) (AiMessagesTable.id less beforeId) else (AiMessagesTable.id greater 0L)

            val rows = AiMessagesTable.selectAll().where {
                condition
            }.orderBy(AiMessagesTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val items = page.reversed().map {
                MessageListItem(
                    messageId = it[AiMessagesTable.id].value,
                    role = it[AiMessagesTable.role].toDto(),
                    content = it[AiMessagesTable.content],
                    generationId = it[AiMessagesTable.generationId],
                    createdAt = it[AiMessagesTable.createdAt].toIsoInstant()
                )
            }
            val nextBefore = if (hasMore) page.last()[AiMessagesTable.id].value.toString() else null
            ListMessagesResponse(items = items, nextBefore = nextBefore)
        }
    }

    suspend fun startOrReuseGeneration(userId: Long, conversationId: Long, request: StreamChatRequest): String {
        validateStreamChatRequest(request)
        val now = utcNow()

        val result = DatabaseFactory.dbQuery {
            getOwnedConversation(userId, conversationId)
            val conversationRef = EntityID(conversationId, AiConversationsTable)
            val userRef = EntityID(userId, UsersTable)
            val userMessage = request.userMessage.trim()
            val clientMessageId = request.clientMessageId.trim()

            val existing = AiMessagesTable.selectAll().where {
                (AiMessagesTable.userId eq userRef) and
                    (AiMessagesTable.conversationId eq conversationRef) and
                    (AiMessagesTable.clientMessageId eq clientMessageId) and
                    (AiMessagesTable.role eq AiMessageRole.USER)
            }.firstOrNull()

            if (existing != null) {
                if (existing[AiMessagesTable.content] != userMessage) {
                    throw AppException(
                        code = ErrorCodes.AI_IDEMPOTENCY_CONFLICT,
                        message = "clientMessageId already exists with different content",
                        status = HttpStatusCode.Conflict
                    )
                }
                val generationId = existing[AiMessagesTable.generationId]
                    ?: createGenerationForMessage(userRef, conversationRef, existing[AiMessagesTable.id], request, now)
                generationId to false
            } else {
                val userMessageId = AiMessagesTable.insert {
                    it[AiMessagesTable.userId] = userRef
                    it[AiMessagesTable.conversationId] = conversationRef
                    it[role] = AiMessageRole.USER
                    it[content] = userMessage
                    it[AiMessagesTable.clientMessageId] = clientMessageId
                    it[generationId] = null
                    it[tokenCount] = null
                    it[createdAt] = now
                }[AiMessagesTable.id]
                val generationId = createGenerationForMessage(userRef, conversationRef, userMessageId, request, now)
                generationId to true
            }
        }

        val generationId = result.first
        if (result.second) {
            ensureGenerationRunning(generationId)
        }
        return generationId
    }

    suspend fun parseLastEventSeq(generationId: String, lastEventId: String?): Long {
        if (lastEventId.isNullOrBlank()) {
            return 0L
        }
        val trimmed = lastEventId.trim()
        val seq = if (trimmed.contains(":")) {
            val generationPart = trimmed.substringBefore(":")
            val seqPart = trimmed.substringAfterLast(":")
            if (generationPart != generationId) {
                throw AppException(
                    code = ErrorCodes.AI_INVALID_ARGUMENT,
                    message = "Last-Event-ID does not match generationId",
                    status = HttpStatusCode.BadRequest
                )
            }
            seqPart.toLongOrNull()
        } else {
            trimmed.toLongOrNull()
        } ?: throw AppException(
            code = ErrorCodes.AI_INVALID_ARGUMENT,
            message = "Invalid Last-Event-ID",
            status = HttpStatusCode.BadRequest
        )
        if (seq < 0) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "Last-Event-ID must be non-negative",
                status = HttpStatusCode.BadRequest
            )
        }
        return seq
    }

    suspend fun loadGenerationOwnership(userId: Long, generationId: String): GenerationOwnership {
        return DatabaseFactory.dbQuery {
            val row = AiGenerationsTable.selectAll().where {
                AiGenerationsTable.generationId eq generationId
            }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.AI_GENERATION_NOT_FOUND,
                    message = "Generation not found",
                    status = HttpStatusCode.NotFound
                )
            if (row[AiGenerationsTable.userId].value != userId) {
                throw AppException(
                    code = ErrorCodes.AI_CONVERSATION_FORBIDDEN,
                    message = "Generation does not belong to current user",
                    status = HttpStatusCode.Forbidden
                )
            }
            GenerationOwnership(
                generationId = generationId,
                conversationId = row[AiGenerationsTable.conversationId].value,
                status = row[AiGenerationsTable.status]
            )
        }
    }

    suspend fun replayEvents(userId: Long, generationId: String, lastSeq: Long): ReplayEventsResult {
        val ownership = loadGenerationOwnership(userId, generationId)
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            val latestEvent = AiStreamEventsTable.selectAll().where {
                AiStreamEventsTable.generationId eq generationId
            }.orderBy(AiStreamEventsTable.seq, SortOrder.DESC).limit(1).firstOrNull()

            if (lastSeq > 0 && ownership.status != AiGenerationStatus.RUNNING) {
                val latestAt = latestEvent?.get(AiStreamEventsTable.createdAt)
                if (latestAt != null && latestAt.plusSeconds(config.replayTtlSeconds) < now) {
                    throw AppException(
                        code = ErrorCodes.AI_REPLAY_WINDOW_EXPIRED,
                        message = "Replay window expired. Please re-send the message.",
                        status = HttpStatusCode.Conflict
                    )
                }
            }

            val events = AiStreamEventsTable.selectAll().where {
                (AiStreamEventsTable.generationId eq generationId) and
                    (AiStreamEventsTable.seq greater lastSeq)
            }.orderBy(AiStreamEventsTable.seq, SortOrder.ASC).map {
                StreamEventRecord(
                    generationId = generationId,
                    seq = it[AiStreamEventsTable.seq],
                    eventType = it[AiStreamEventsTable.eventType],
                    eventJson = it[AiStreamEventsTable.eventJson]
                )
            }

            if (lastSeq > 0 && events.isNotEmpty() && events.first().seq > lastSeq + 1) {
                throw AppException(
                    code = ErrorCodes.AI_REPLAY_WINDOW_EXPIRED,
                    message = "Replay gap detected. Please re-send the message.",
                    status = HttpStatusCode.Conflict
                )
            }

            ReplayEventsResult(
                generationId = generationId,
                events = events,
                terminalStatus = ownership.status != AiGenerationStatus.RUNNING
            )
        }
    }

    suspend fun refreshEventsAfter(generationId: String, seq: Long): List<StreamEventRecord> {
        return DatabaseFactory.dbQuery {
            AiStreamEventsTable.selectAll().where {
                (AiStreamEventsTable.generationId eq generationId) and
                    (AiStreamEventsTable.seq greater seq)
            }.orderBy(AiStreamEventsTable.seq, SortOrder.ASC).map {
                StreamEventRecord(
                    generationId = generationId,
                    seq = it[AiStreamEventsTable.seq],
                    eventType = it[AiStreamEventsTable.eventType],
                    eventJson = it[AiStreamEventsTable.eventJson]
                )
            }
        }
    }

    suspend fun isGenerationTerminal(generationId: String): Boolean {
        return DatabaseFactory.dbQuery {
            val row = AiGenerationsTable.selectAll().where {
                AiGenerationsTable.generationId eq generationId
            }.firstOrNull() ?: return@dbQuery true
            row[AiGenerationsTable.status] != AiGenerationStatus.RUNNING
        }
    }

    fun subscribe(generationId: String): Channel<StreamEventRecord> {
        val channel = Channel<StreamEventRecord>(capacity = Channel.BUFFERED)
        subscribers.computeIfAbsent(generationId) { CopyOnWriteArraySet() }.add(channel)
        return channel
    }

    fun unsubscribe(generationId: String, channel: Channel<StreamEventRecord>) {
        subscribers[generationId]?.remove(channel)
        channel.close()
    }

    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun ensureGenerationRunning(generationId: String) {
        generationLock.withLock {
            val existing = generationJobs[generationId]
            if (existing != null && existing.isActive) {
                return
            }
            val job = scope.launch {
                runGeneration(generationId)
            }
            generationJobs[generationId] = job
        }
    }

    private suspend fun runGeneration(generationId: String) {
        val assistantText = StringBuilder()
        var finishReason: String? = null
        var usage: UsageMetrics? = null

        try {
            val context = buildGenerationContext(generationId)
            appendAndPublishEvent(
                generationId = generationId,
                eventType = EVENT_META,
                eventJson = json.encodeToString(
                    StreamMetaEvent(
                        generationId = generationId,
                        conversationId = context.conversationId,
                        model = config.model,
                        createdAt = utcNow().toIsoInstant()
                    )
                )
            )

            deepSeekClient.streamChat(
                messages = context.messages,
                temperature = context.temperature,
                maxTokens = context.maxTokens
            ) { chunk ->
                chunk.contentDelta?.takeIf { it.isNotEmpty() }?.let { delta ->
                    assistantText.append(delta)
                    appendAndPublishEvent(
                        generationId = generationId,
                        eventType = EVENT_DELTA,
                        eventJson = json.encodeToString(StreamDeltaEvent(text = delta))
                    )
                }
                if (!chunk.finishReason.isNullOrBlank()) {
                    finishReason = chunk.finishReason
                }
                if (chunk.usage != null) {
                    usage = chunk.usage
                }
            }

            usage?.let {
                appendAndPublishEvent(
                    generationId = generationId,
                    eventType = EVENT_USAGE,
                    eventJson = json.encodeToString(
                        StreamUsageEvent(
                            promptTokens = it.promptTokens,
                            completionTokens = it.completionTokens,
                            totalTokens = it.totalTokens
                        )
                    )
                )
            }

            val assistantMessageId = persistAssistantMessage(
                generationId = generationId,
                content = assistantText.toString(),
                completionTokens = usage?.completionTokens
            )

            appendAndPublishEvent(
                generationId = generationId,
                eventType = EVENT_DONE,
                eventJson = json.encodeToString(
                    StreamDoneEvent(
                        assistantMessageId = assistantMessageId,
                        finishReason = finishReason ?: "stop"
                    )
                )
            )
            markGenerationFinished(generationId, AiGenerationStatus.COMPLETED, null, null)
        } catch (e: CancellationException) {
            markGenerationFinished(
                generationId = generationId,
                status = AiGenerationStatus.CANCELLED,
                errorCode = ErrorCodes.AI_STREAM_INTERNAL_ERROR,
                errorMessage = "Generation cancelled"
            )
        } catch (e: AppException) {
            markGenerationFinished(generationId, AiGenerationStatus.FAILED, e.code, e.message)
            safeEmitErrorEvent(generationId, e.code, e.message)
        } catch (e: Throwable) {
            markGenerationFinished(
                generationId = generationId,
                status = AiGenerationStatus.FAILED,
                errorCode = ErrorCodes.AI_STREAM_INTERNAL_ERROR,
                errorMessage = e.message ?: "Unexpected stream failure"
            )
            safeEmitErrorEvent(generationId, ErrorCodes.AI_STREAM_INTERNAL_ERROR, "Unexpected stream failure")
        } finally {
            generationJobs.remove(generationId)
        }
    }

    private suspend fun safeEmitErrorEvent(generationId: String, code: Int, message: String) {
        runCatching {
            appendAndPublishEvent(
                generationId = generationId,
                eventType = EVENT_ERROR,
                eventJson = json.encodeToString(StreamErrorEvent(code = code, message = message))
            )
        }
    }

    private suspend fun buildGenerationContext(generationId: String): GenerationContext {
        return DatabaseFactory.dbQuery {
            val generation = AiGenerationsTable.selectAll().where {
                AiGenerationsTable.generationId eq generationId
            }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.AI_GENERATION_NOT_FOUND,
                    message = "Generation not found",
                    status = HttpStatusCode.NotFound
                )

            val conversationRef = generation[AiGenerationsTable.conversationId]
            val requestPayload = json.decodeFromString(StreamChatRequest.serializer(), generation[AiGenerationsTable.requestPayloadJson])
            val conversation = AiConversationsTable.selectAll().where {
                AiConversationsTable.id eq conversationRef
            }.first()

            val allMessages = AiMessagesTable.selectAll().where {
                AiMessagesTable.conversationId eq conversationRef
            }.orderBy(AiMessagesTable.id, SortOrder.ASC).toList()

            val olderCount = (allMessages.size - config.contextRecentMessages).coerceAtLeast(0)
            val olderMessages = if (olderCount > 0) allMessages.take(olderCount) else emptyList()
            val recentMessages = if (olderCount > 0) allMessages.drop(olderCount) else allMessages

            val existingSummary = conversation[AiConversationsTable.summary]
            val summaryToUse = if (olderMessages.isNotEmpty()) {
                generateSummary(olderMessages)
            } else {
                existingSummary
            }
            if (summaryToUse != existingSummary) {
                AiConversationsTable.update({ AiConversationsTable.id eq conversationRef }) {
                    it[summary] = summaryToUse
                    it[updatedAt] = utcNow()
                }
            }

            val messages = mutableListOf<ChatMessage>()
            messages += ChatMessage(role = "system", content = SYSTEM_PROMPT)
            if (!summaryToUse.isNullOrBlank()) {
                messages += ChatMessage(role = "system", content = "Conversation summary:\n$summaryToUse")
            }
            recentMessages.forEach { row ->
                when (row[AiMessagesTable.role]) {
                    AiMessageRole.SYSTEM -> messages += ChatMessage(role = "system", content = row[AiMessagesTable.content])
                    AiMessageRole.USER -> messages += ChatMessage(role = "user", content = row[AiMessagesTable.content])
                    AiMessageRole.ASSISTANT -> messages += ChatMessage(role = "assistant", content = row[AiMessagesTable.content])
                }
            }

            GenerationContext(
                conversationId = conversationRef.value,
                temperature = requestPayload.temperature,
                maxTokens = requestPayload.maxTokens,
                messages = messages
            )
        }
    }

    private suspend fun persistAssistantMessage(generationId: String, content: String, completionTokens: Int?): Long {
        return DatabaseFactory.dbQuery {
            val generation = AiGenerationsTable.selectAll().where {
                AiGenerationsTable.generationId eq generationId
            }.firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.AI_GENERATION_NOT_FOUND,
                    message = "Generation not found",
                    status = HttpStatusCode.NotFound
                )
            val now = utcNow()
            val conversationRef = generation[AiGenerationsTable.conversationId]
            val userRef = generation[AiGenerationsTable.userId]
            val messageId = AiMessagesTable.insert {
                it[AiMessagesTable.conversationId] = conversationRef
                it[AiMessagesTable.userId] = userRef
                it[role] = AiMessageRole.ASSISTANT
                it[AiMessagesTable.content] = content
                it[AiMessagesTable.clientMessageId] = null
                it[AiMessagesTable.generationId] = generationId
                it[tokenCount] = completionTokens
                it[createdAt] = now
            }[AiMessagesTable.id].value

            AiConversationsTable.update({ AiConversationsTable.id eq conversationRef }) {
                it[updatedAt] = now
                it[lastMessageAt] = now
            }

            messageId
        }
    }

    private suspend fun markGenerationFinished(
        generationId: String,
        status: AiGenerationStatus,
        errorCode: Int?,
        errorMessage: String?
    ) {
        DatabaseFactory.dbQuery {
            AiGenerationsTable.update({ AiGenerationsTable.generationId eq generationId }) {
                it[AiGenerationsTable.status] = status
                it[AiGenerationsTable.errorCode] = errorCode
                it[AiGenerationsTable.errorMessage] = errorMessage?.take(1000)
                it[completedAt] = utcNow()
            }
        }
    }

    private suspend fun appendAndPublishEvent(generationId: String, eventType: String, eventJson: String): StreamEventRecord {
        val event = DatabaseFactory.dbQuery {
            val maxSeq = AiStreamEventsTable.selectAll().where {
                AiStreamEventsTable.generationId eq generationId
            }.orderBy(AiStreamEventsTable.seq, SortOrder.DESC).limit(1).firstOrNull()?.get(AiStreamEventsTable.seq) ?: 0L
            val seq = maxSeq + 1L
            AiStreamEventsTable.insert {
                it[AiStreamEventsTable.generationId] = generationId
                it[AiStreamEventsTable.seq] = seq
                it[AiStreamEventsTable.eventType] = eventType
                it[AiStreamEventsTable.eventJson] = eventJson
                it[createdAt] = utcNow()
            }
            StreamEventRecord(generationId = generationId, seq = seq, eventType = eventType, eventJson = eventJson)
        }
        publish(event)
        return event
    }

    private fun publish(event: StreamEventRecord) {
        val listeners = subscribers[event.generationId] ?: return
        listeners.forEach { channel ->
            if (channel.isClosedForSend) {
                listeners.remove(channel)
            } else {
                channel.trySend(event)
            }
        }
    }

    private fun validateStreamChatRequest(request: StreamChatRequest) {
        if (request.userMessage.isBlank()) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "userMessage is required",
                status = HttpStatusCode.BadRequest
            )
        }
        if (request.userMessage.length > config.maxUserMessageChars) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "userMessage exceeds ${config.maxUserMessageChars} characters",
                status = HttpStatusCode.BadRequest
            )
        }
        if (request.clientMessageId.isBlank()) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "clientMessageId is required",
                status = HttpStatusCode.BadRequest
            )
        }
        if (request.clientMessageId.length > config.maxClientMessageIdChars) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "clientMessageId exceeds ${config.maxClientMessageIdChars} characters",
                status = HttpStatusCode.BadRequest
            )
        }
        if (request.clientMessageId.any { it.isISOControl() } || request.userMessage.any { it.isISOControl() }) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "request contains control characters",
                status = HttpStatusCode.BadRequest
            )
        }
        if (request.temperature != null && (request.temperature < 0.0 || request.temperature > 2.0)) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "temperature must be in [0, 2]",
                status = HttpStatusCode.BadRequest
            )
        }
        if (request.maxTokens != null && (request.maxTokens <= 0 || request.maxTokens > 8192)) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "maxTokens must be in [1, 8192]",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun validateTitle(title: String?) {
        val value = title ?: return
        if (value.length > 100) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "title exceeds 100 characters",
                status = HttpStatusCode.BadRequest
            )
        }
        if (value.any { it.isISOControl() }) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "title contains control characters",
                status = HttpStatusCode.BadRequest
            )
        }
    }

    private fun parseCursor(raw: String?, name: String): Long? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return raw.toLongOrNull() ?: throw AppException(
            code = ErrorCodes.AI_INVALID_ARGUMENT,
            message = "$name must be a valid long value",
            status = HttpStatusCode.BadRequest
        )
    }

    private fun generateSummary(rows: List<ResultRow>): String {
        val summary = rows.takeLast(40).joinToString("\n") { row ->
            val role = row[AiMessagesTable.role].name.lowercase()
            val content = row[AiMessagesTable.content].replace('\n', ' ').trim()
            "$role: ${content.take(160)}"
        }
        return summary.take(2_000)
    }

    private fun org.jetbrains.exposed.sql.Transaction.createGenerationForMessage(
        userRef: EntityID<Long>,
        conversationRef: EntityID<Long>,
        messageId: EntityID<Long>,
        request: StreamChatRequest,
        now: LocalDateTime
    ): String {
        val generationId = generateSecureToken(24)
        AiGenerationsTable.insert {
            it[AiGenerationsTable.generationId] = generationId
            it[AiGenerationsTable.userId] = userRef
            it[AiGenerationsTable.conversationId] = conversationRef
            it[status] = AiGenerationStatus.RUNNING
            it[requestPayloadJson] = json.encodeToString(StreamChatRequest.serializer(), request)
            it[errorCode] = null
            it[errorMessage] = null
            it[startedAt] = now
            it[completedAt] = null
        }
        AiMessagesTable.update({ AiMessagesTable.id eq messageId }) {
            it[AiMessagesTable.generationId] = generationId
        }
        AiConversationsTable.update({ AiConversationsTable.id eq conversationRef }) {
            it[updatedAt] = now
            it[lastMessageAt] = now
        }
        return generationId
    }

    private fun org.jetbrains.exposed.sql.Transaction.getOwnedConversation(userId: Long, conversationId: Long): ResultRow {
        val row = AiConversationsTable.selectAll().where {
            AiConversationsTable.id eq EntityID(conversationId, AiConversationsTable)
        }.firstOrNull()
            ?: throw AppException(
                code = ErrorCodes.AI_CONVERSATION_NOT_FOUND,
                message = "Conversation not found",
                status = HttpStatusCode.NotFound
            )

        if (row[AiConversationsTable.userId].value != userId) {
            throw AppException(
                code = ErrorCodes.AI_CONVERSATION_FORBIDDEN,
                message = "Conversation does not belong to current user",
                status = HttpStatusCode.Forbidden
            )
        }
        return row
    }

    private fun AiMessageRole.toDto(): AiMessageRoleDto = when (this) {
        AiMessageRole.SYSTEM -> AiMessageRoleDto.SYSTEM
        AiMessageRole.USER -> AiMessageRoleDto.USER
        AiMessageRole.ASSISTANT -> AiMessageRoleDto.ASSISTANT
    }

    private fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
    private fun LocalDateTime.toIsoInstant(): String = atOffset(ZoneOffset.UTC).toInstant().toString()
}
