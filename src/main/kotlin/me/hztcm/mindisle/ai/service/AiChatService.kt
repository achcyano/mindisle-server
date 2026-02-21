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
import kotlinx.serialization.Serializable
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
import me.hztcm.mindisle.model.AssistantOptionDto
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
import me.hztcm.mindisle.model.StreamOptionsEvent
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
private const val EVENT_OPTIONS = "options"
private const val EVENT_DONE = "done"
private const val EVENT_ERROR = "error"

private const val OPTIONS_START_MARKER = "<OPTIONS_JSON>"
private const val OPTIONS_END_MARKER = "</OPTIONS_JSON>"
private const val OPTIONS_REQUIRED_COUNT = 3
private const val OPTION_LABEL_MAX_CHARS = 24
private const val OPTION_PAYLOAD_MAX_CHARS = 80

private const val SYSTEM_PROMPT = """
You are a patient-care assistant. Respond in Simplified Chinese.
After your normal answer, you MUST append one options block with this exact format:
<OPTIONS_JSON>
{"items":[{"label":"...","payload":"..."},{"label":"...","payload":"..."},{"label":"...","payload":"..."}]}
</OPTIONS_JSON>
Rules:
1) Exactly 3 items.
2) label should be short and clickable.
3) payload should be a complete user follow-up sentence.
4) Do not output anything after </OPTIONS_JSON>.
"""

private const val FALLBACK_OPTIONS_SYSTEM_PROMPT = """
You only generate clickable options in JSON.
Return ONLY this JSON object and nothing else:
{"items":[{"label":"...","payload":"..."},{"label":"...","payload":"..."},{"label":"...","payload":"..."}]}
Rules:
1) Exactly 3 items.
2) label max 24 chars.
3) payload max 80 chars.
4) Output in Simplified Chinese.
"""

@Serializable
private data class OptionDraft(
    val label: String = "",
    val payload: String = ""
)

@Serializable
private data class OptionBlock(
    val items: List<OptionDraft> = emptyList()
)

private data class ParsedAssistantOutput(
    val answerText: String,
    val options: List<AssistantOptionDto>? = null
)

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
    val currentUserMessage: String,
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
                    options = it[AiMessagesTable.optionsJson]?.let(::parseStoredOptionsJson),
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
                    it[optionsJson] = null
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
        val rawAssistant = StringBuilder()
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
                    rawAssistant.append(delta)
                }
                if (!chunk.finishReason.isNullOrBlank()) {
                    finishReason = chunk.finishReason
                }
                if (chunk.usage != null) {
                    usage = chunk.usage
                }
            }

            val parsed = parseAssistantOutput(rawAssistant.toString())
            val answerText = parsed.answerText.ifBlank { rawAssistant.toString().trim() }
            val (options, source) = resolveOptions(
                userMessage = context.currentUserMessage,
                assistantAnswer = answerText,
                primary = parsed.options
            )

            if (answerText.isNotBlank()) {
                appendAndPublishEvent(
                    generationId = generationId,
                    eventType = EVENT_DELTA,
                    eventJson = json.encodeToString(StreamDeltaEvent(text = answerText))
                )
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

            appendAndPublishEvent(
                generationId = generationId,
                eventType = EVENT_OPTIONS,
                eventJson = json.encodeToString(
                    StreamOptionsEvent(
                        items = options,
                        source = source
                    )
                )
            )

            val assistantMessageId = persistAssistantMessage(
                generationId = generationId,
                content = answerText,
                options = options,
                completionTokens = usage?.completionTokens
            )

            appendAndPublishEvent(
                generationId = generationId,
                eventType = EVENT_DONE,
                eventJson = json.encodeToString(
                    StreamDoneEvent(
                        assistantMessageId = assistantMessageId,
                        finishReason = finishReason ?: "stop",
                        hasOptions = true
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

    private suspend fun resolveOptions(
        userMessage: String,
        assistantAnswer: String,
        primary: List<AssistantOptionDto>?
    ): Pair<List<AssistantOptionDto>, String> {
        if (!primary.isNullOrEmpty()) {
            return primary to "primary"
        }
        val fallback = runCatching {
            generateOptionsWithFallback(userMessage, assistantAnswer)
        }.getOrNull()
        if (!fallback.isNullOrEmpty()) {
            return fallback to "fallback"
        }
        return defaultOptions() to "default"
    }

    private suspend fun generateOptionsWithFallback(
        userMessage: String,
        assistantAnswer: String
    ): List<AssistantOptionDto> {
        val fallbackMessages = listOf(
            ChatMessage(role = "system", content = FALLBACK_OPTIONS_SYSTEM_PROMPT),
            ChatMessage(
                role = "user",
                content = buildString {
                    appendLine("User message:")
                    appendLine(userMessage.trim())
                    appendLine()
                    appendLine("Assistant answer:")
                    appendLine(assistantAnswer.trim())
                    appendLine()
                    appendLine("Now return options JSON only.")
                }
            )
        )
        val (raw, _) = deepSeekClient.completeTextChat(
            messages = fallbackMessages,
            temperature = 0.2,
            maxTokens = 256
        )
        val parsed = parseOptionsFromAnyText(raw)
        return parsed ?: throw AppException(
            code = ErrorCodes.AI_OPTIONS_FALLBACK_FAILED,
            message = "Fallback options generation failed",
            status = HttpStatusCode.BadGateway
        )
    }

    private fun parseAssistantOutput(raw: String): ParsedAssistantOutput {
        val trimmed = raw.trim()
        val start = trimmed.indexOf(OPTIONS_START_MARKER)
        val end = trimmed.indexOf(OPTIONS_END_MARKER)
        if (start < 0 || end < 0 || end <= start) {
            return ParsedAssistantOutput(answerText = trimmed)
        }
        val jsonBlock = trimmed.substring(start + OPTIONS_START_MARKER.length, end).trim()
        val options = parseOptionsJson(jsonBlock)
        val answer = (trimmed.substring(0, start) + trimmed.substring(end + OPTIONS_END_MARKER.length)).trim()
        return ParsedAssistantOutput(
            answerText = answer,
            options = options
        )
    }

    private fun parseOptionsFromAnyText(raw: String): List<AssistantOptionDto>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val fromMarkers = parseAssistantOutput(trimmed).options
        if (!fromMarkers.isNullOrEmpty()) {
            return fromMarkers
        }
        parseOptionsJson(trimmed)?.let { return it }
        val firstBrace = trimmed.indexOf("{")
        val lastBrace = trimmed.lastIndexOf("}")
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return parseOptionsJson(trimmed.substring(firstBrace, lastBrace + 1))
        }
        return null
    }

    private fun parseOptionsJson(rawJson: String): List<AssistantOptionDto>? {
        val parsed = runCatching {
            json.decodeFromString<OptionBlock>(rawJson)
        }.getOrNull() ?: return null
        return normalizeOptions(parsed.items)
    }

    private fun normalizeOptions(items: List<OptionDraft>): List<AssistantOptionDto>? {
        if (items.isEmpty()) {
            return null
        }
        val distinct = linkedSetOf<String>()
        val normalized = mutableListOf<OptionDraft>()
        for (item in items) {
            val label = item.label.trim()
            val payload = item.payload.trim()
            if (label.isEmpty() || payload.isEmpty()) {
                continue
            }
            if (label.any { it.isISOControl() } || payload.any { it.isISOControl() }) {
                continue
            }
            if (label.codePointCount(0, label.length) > OPTION_LABEL_MAX_CHARS) {
                continue
            }
            if (payload.codePointCount(0, payload.length) > OPTION_PAYLOAD_MAX_CHARS) {
                continue
            }
            if (!distinct.add(payload)) {
                continue
            }
            normalized += OptionDraft(label = label, payload = payload)
            if (normalized.size == OPTIONS_REQUIRED_COUNT) {
                break
            }
        }
        if (normalized.size < OPTIONS_REQUIRED_COUNT) {
            return null
        }
        return normalized.mapIndexed { index, option ->
            AssistantOptionDto(
                id = "opt_${index + 1}",
                label = option.label,
                payload = option.payload
            )
        }
    }

    private fun defaultOptions(): List<AssistantOptionDto> {
        return listOf(
            AssistantOptionDto(
                id = "opt_1",
                label = "请继续解释",
                payload = "请继续解释，并给我更具体一点的建议。"
            ),
            AssistantOptionDto(
                id = "opt_2",
                label = "帮我做总结",
                payload = "请把刚才的回答总结成三点重点。"
            ),
            AssistantOptionDto(
                id = "opt_3",
                label = "下一步怎么做",
                payload = "结合我现在的情况，我下一步具体该怎么做？"
            )
        )
    }

    private fun parseStoredOptionsJson(raw: String): List<AssistantOptionDto>? {
        val parsed = runCatching {
            json.decodeFromString<List<AssistantOptionDto>>(raw)
        }.getOrNull() ?: return null
        if (parsed.isEmpty()) {
            return null
        }
        return parsed
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
            val requestPayload = json.decodeFromString<StreamChatRequest>(generation[AiGenerationsTable.requestPayloadJson])
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
            val summaryToUse = if (olderMessages.isNotEmpty()) generateSummary(olderMessages) else existingSummary
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
                currentUserMessage = requestPayload.userMessage.trim(),
                temperature = requestPayload.temperature,
                maxTokens = requestPayload.maxTokens,
                messages = messages
            )
        }
    }

    private suspend fun persistAssistantMessage(
        generationId: String,
        content: String,
        options: List<AssistantOptionDto>,
        completionTokens: Int?
    ): Long {
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
                it[optionsJson] = json.encodeToString(options)
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
            it[requestPayloadJson] = json.encodeToString(request)
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
