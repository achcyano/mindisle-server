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
import me.hztcm.mindisle.model.UpdateConversationTitleResponse
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

private const val AUTO_TITLE_MAX_CHARS = 20

class AiChatService(
    private val config: LlmConfig,
    private val deepSeekClient: DeepSeekAliyunClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val optionResolver = AiOptionResolver(deepSeekClient, json)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generationJobs = ConcurrentHashMap<String, Job>()
    private val generationLock = Mutex()
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArraySet<Channel<StreamEventRecord>>>()

    suspend fun createConversation(userId: Long, title: String?): CreateConversationResponse {
        AiRequestValidators.validateTitle(title)
        val now = utcNow()
        val normalizedTitle = title?.trim()?.takeIf { it.isNotBlank() }
        return DatabaseFactory.dbQuery {
            val conversationId = AiConversationsTable.insert {
                it[AiConversationsTable.userId] = EntityID(userId, UsersTable)
                it[AiConversationsTable.title] = normalizedTitle
                it[summary] = null
                it[createdAt] = now
                it[updatedAt] = now
                it[lastMessageAt] = now
            }[AiConversationsTable.id].value

            CreateConversationResponse(
                conversationId = conversationId,
                title = normalizedTitle,
                createdAt = now.toIsoInstant()
            )
        }
    }

    suspend fun updateConversationTitle(
        userId: Long,
        conversationId: Long,
        title: String
    ): UpdateConversationTitleResponse {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) {
            throw AppException(
                code = ErrorCodes.AI_INVALID_ARGUMENT,
                message = "title is required",
                status = HttpStatusCode.BadRequest
            )
        }
        AiRequestValidators.validateTitle(normalizedTitle)
        val now = utcNow()
        return DatabaseFactory.dbQuery {
            getOwnedConversation(userId, conversationId)
            val conversationRef = EntityID(conversationId, AiConversationsTable)
            AiConversationsTable.update({ AiConversationsTable.id eq conversationRef }) {
                it[AiConversationsTable.title] = normalizedTitle
                it[updatedAt] = now
            }
            UpdateConversationTitleResponse(
                conversationId = conversationId,
                title = normalizedTitle,
                updatedAt = now.toIsoInstant()
            )
        }
    }

    suspend fun deleteConversation(userId: Long, conversationId: Long) {
        val generationIds = DatabaseFactory.dbQuery {
            getOwnedConversation(userId, conversationId)
            val conversationRef = EntityID(conversationId, AiConversationsTable)
            val ids = AiGenerationsTable.selectAll().where {
                AiGenerationsTable.conversationId eq conversationRef
            }.map { it[AiGenerationsTable.generationId] }

            ids.forEach { generationId ->
                AiStreamEventsTable.deleteWhere { AiStreamEventsTable.generationId eq generationId }
            }
            AiConversationsTable.deleteWhere { AiConversationsTable.id eq conversationRef }
            ids
        }
        generationIds.forEach { generationId ->
            generationJobs.remove(generationId)?.cancel()
            subscribers.remove(generationId)?.forEach { channel -> channel.close() }
        }
    }

    suspend fun listConversations(userId: Long, limit: Int, cursor: String?): ListConversationsResponse {
        val safeLimit = limit.coerceIn(1, 50)
        val cursorId = AiRequestValidators.parseCursor(cursor, "cursor")

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
        val beforeId = AiRequestValidators.parseCursor(before, "before")

        DatabaseFactory.dbQuery {
            getOwnedConversation(userId, conversationId)
        }

        return DatabaseFactory.dbQuery {
            val conversationRef = EntityID(conversationId, AiConversationsTable)
            val condition = (AiMessagesTable.userId eq EntityID(userId, UsersTable)) and
                (AiMessagesTable.conversationId eq conversationRef) and
                if (beforeId != null) (AiMessagesTable.id less beforeId) else (AiMessagesTable.id greater 0L)

            val latestOptionsMessageId = AiMessagesTable.selectAll().where {
                (AiMessagesTable.userId eq EntityID(userId, UsersTable)) and
                    (AiMessagesTable.conversationId eq conversationRef) and
                    (AiMessagesTable.role eq AiMessageRole.ASSISTANT) and
                    AiMessagesTable.optionsJson.isNotNull()
            }.orderBy(AiMessagesTable.id, SortOrder.DESC).limit(1).firstOrNull()?.get(AiMessagesTable.id)?.value

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
                    options = if (it[AiMessagesTable.id].value == latestOptionsMessageId) {
                        it[AiMessagesTable.optionsJson]?.let(optionResolver::parseStoredOptionsJson)
                    } else {
                        null
                    },
                    generationId = it[AiMessagesTable.generationId],
                    createdAt = it[AiMessagesTable.createdAt].toIsoInstant()
                )
            }
            val nextBefore = if (hasMore) page.last()[AiMessagesTable.id].value.toString() else null
            ListMessagesResponse(items = items, nextBefore = nextBefore)
        }
    }

    suspend fun startOrReuseGeneration(userId: Long, conversationId: Long, request: StreamChatRequest): String {
        AiRequestValidators.validateStreamChatRequest(request, config)
        val now = utcNow()
        val requestPayloadJson = json.encodeToString(request)

        val result = DatabaseFactory.dbQuery {
            val ownedConversation = getOwnedConversation(userId, conversationId)
            val conversationRef = EntityID(conversationId, AiConversationsTable)
            val userRef = EntityID(userId, UsersTable)
            val userMessage = request.userMessage.trim()
            val clientMessageId = request.clientMessageId.trim()
            val autoTitle = if (ownedConversation[AiConversationsTable.title].isNullOrBlank()) {
                deriveAutoConversationTitle(userMessage)
            } else {
                null
            }

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
                    ?: createGenerationForMessage(
                        userRef = userRef,
                        conversationRef = conversationRef,
                        messageId = existing[AiMessagesTable.id],
                        requestPayloadJson = requestPayloadJson,
                        now = now
                    )
                if (autoTitle != null) {
                    AiConversationsTable.update({ AiConversationsTable.id eq conversationRef }) {
                        it[AiConversationsTable.title] = autoTitle
                        it[updatedAt] = now
                    }
                }
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
                val generationId = createGenerationForMessage(
                    userRef = userRef,
                    conversationRef = conversationRef,
                    messageId = userMessageId,
                    requestPayloadJson = requestPayloadJson,
                    now = now
                )
                if (autoTitle != null) {
                    AiConversationsTable.update({ AiConversationsTable.id eq conversationRef }) {
                        it[AiConversationsTable.title] = autoTitle
                        it[updatedAt] = now
                    }
                }
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
        val pendingDelta = StringBuilder()
        val deltaFilter = AiDeltaOptionFilter()
        var finishReason: String? = null
        var usage: UsageMetrics? = null
        var lastDeltaEmitAt = 0L

        try {
            val context = buildGenerationContext(generationId)
            var nextSeq = loadLatestEventSeq(generationId) + 1L
            suspend fun emitEvent(eventType: String, eventJson: String) {
                appendAndPublishEvent(
                    generationId = generationId,
                    seq = nextSeq,
                    eventType = eventType,
                    eventJson = eventJson
                )
                nextSeq += 1L
            }

            suspend fun flushDelta(force: Boolean = false) {
                if (pendingDelta.isEmpty()) {
                    return
                }
                val now = System.currentTimeMillis()
                if (!force) {
                    val enoughTime = now - lastDeltaEmitAt >= DELTA_EMIT_INTERVAL_MS
                    val enoughChars = pendingDelta.length >= DELTA_EMIT_MAX_CHARS
                    if (!enoughTime && !enoughChars) {
                        return
                    }
                }
                val text = pendingDelta.toString()
                pendingDelta.setLength(0)
                lastDeltaEmitAt = now
                emitEvent(
                    eventType = EVENT_DELTA,
                    eventJson = json.encodeToString(StreamDeltaEvent(text = text))
                )
            }

            emitEvent(
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
                    val visible = deltaFilter.accept(delta)
                    if (visible.isNotEmpty()) {
                        pendingDelta.append(visible)
                        flushDelta()
                    }
                }
                if (!chunk.finishReason.isNullOrBlank()) {
                    finishReason = chunk.finishReason
                }
                if (chunk.usage != null) {
                    usage = chunk.usage
                }
            }
            val visibleTail = deltaFilter.flushRemainder()
            if (visibleTail.isNotEmpty()) {
                pendingDelta.append(visibleTail)
            }
            flushDelta(force = true)

            val rawAnswer = rawAssistant.toString()
            val (answerCandidate, primaryOptions) = optionResolver.extractAnswerAndPrimaryOptions(rawAnswer)
            val answerText = answerCandidate.ifBlank { rawAnswer.trim() }
            val (options, source) = optionResolver.resolveOptions(
                userMessage = context.currentUserMessage,
                assistantAnswer = answerText,
                primary = primaryOptions
            )

            usage?.let {
                emitEvent(
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

            emitEvent(
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

            emitEvent(
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

            val recentMessages = AiMessagesTable.selectAll().where {
                AiMessagesTable.conversationId eq conversationRef
            }.orderBy(AiMessagesTable.id, SortOrder.DESC).limit(config.contextRecentMessages).toList().asReversed()

            val summaryToUse = conversation[AiConversationsTable.summary]

            val messages = mutableListOf<ChatMessage>()
            messages += ChatMessage(role = "system", content = AI_SYSTEM_PROMPT)
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
            }[AiMessagesTable.id]

            // Keep only the newest assistant options for this conversation.
            AiMessagesTable.update({
                (AiMessagesTable.conversationId eq conversationRef) and
                    (AiMessagesTable.role eq AiMessageRole.ASSISTANT) and
                    (AiMessagesTable.id neq messageId) and
                    AiMessagesTable.optionsJson.isNotNull()
            }) {
                it[optionsJson] = null
            }

            AiConversationsTable.update({ AiConversationsTable.id eq conversationRef }) {
                it[updatedAt] = now
                it[lastMessageAt] = now
            }

            messageId.value
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
        val seq = loadLatestEventSeq(generationId) + 1L
        return appendAndPublishEvent(generationId, seq, eventType, eventJson)
    }

    private suspend fun appendAndPublishEvent(
        generationId: String,
        seq: Long,
        eventType: String,
        eventJson: String
    ): StreamEventRecord {
        val event = DatabaseFactory.dbQuery {
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

    private suspend fun loadLatestEventSeq(generationId: String): Long {
        return DatabaseFactory.dbQuery {
            AiStreamEventsTable.selectAll().where {
                AiStreamEventsTable.generationId eq generationId
            }.orderBy(AiStreamEventsTable.seq, SortOrder.DESC).limit(1).firstOrNull()?.get(AiStreamEventsTable.seq) ?: 0L
        }
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

    private fun deriveAutoConversationTitle(message: String): String? {
        val compact = message.trim().replace(Regex("\\s+"), " ")
        if (compact.isBlank()) {
            return null
        }
        val firstSentence = compact.split(Regex("[。！？!?\\n\\r]"), limit = 2).firstOrNull()?.trim().orEmpty()
        val base = if (firstSentence.isNotBlank()) firstSentence else compact
        return truncateCodePoints(base, AUTO_TITLE_MAX_CHARS).takeIf { it.isNotBlank() }
    }

    private fun truncateCodePoints(text: String, maxCodePoints: Int): String {
        if (text.codePointCount(0, text.length) <= maxCodePoints) {
            return text
        }
        val end = text.offsetByCodePoints(0, maxCodePoints)
        return text.substring(0, end).trimEnd()
    }

}
