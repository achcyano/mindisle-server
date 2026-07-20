package me.hztcm.mindisle.ai.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.config.LlmConfig
import me.hztcm.mindisle.config.LlmModelTier
import java.io.Closeable
import java.util.concurrent.CancellationException

data class ChatMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class UsageMetrics(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)

data class DeepSeekChunk(
    val contentDelta: String? = null,
    val finishReason: String? = null,
    val usage: UsageMetrics? = null,
    val toolCallDeltas: List<ToolCallDelta> = emptyList()
)

data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsDelta: String? = null
)

data class LlmToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

data class ChatCompletionResult(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val usage: UsageMetrics?
)

class DeepSeekAliyunClient(
    private val config: LlmConfig
) : Closeable {
    private val upstreamSocketTimeoutMillis: Long? = config.requestTimeoutSeconds
        .takeIf { it > 0L }
        ?.let { it * 1_000L }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 0L
        }
        install(HttpTimeout)
        install(ContentNegotiation) {
            json(json)
        }
    }

    init {
        if (config.apiKey.isNullOrBlank()) {
            throw IllegalStateException("LLM_API_KEY is missing")
        }
    }

    suspend fun streamChat(
        messages: List<ChatMessage>,
        temperature: Double?,
        maxTokens: Int?,
        modelTier: LlmModelTier = LlmModelTier.FLASH,
        tools: List<LlmToolSpec> = emptyList(),
        onChunk: suspend (DeepSeekChunk) -> Unit
    ) {
        val requestBody = buildRequest(
            model = config.resolveModel(modelTier),
            messages = messages,
            stream = true,
            temperature = temperature,
            maxTokens = maxTokens,
            tools = tools
        )
        try {
            client.preparePost("${config.baseUrl.trimEnd('/')}/chat/completions") {
                timeout {
                    requestTimeoutMillis = null
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = upstreamSocketTimeoutMillis
                }
                accept(ContentType.Text.EventStream)
                headers {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                }
                setBody(requestBody)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val text = runCatching { response.bodyAsText() }.getOrNull()
                    throw mapUpstreamStatus(response.status, text)
                }

                val channel = response.bodyAsChannel()
                val dataLines = mutableListOf<String>()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.startsWith("data:")) {
                        dataLines += line.removePrefix("data:").trimStart()
                        continue
                    }
                    if (line.isBlank()) {
                        if (dataLines.isEmpty()) {
                            continue
                        }
                        val dataPayload = dataLines.joinToString("\n")
                        dataLines.clear()
                        if (dataPayload == "[DONE]") {
                            break
                        }
                        onChunk(parseStreamChunk(dataPayload))
                    }
                }
                if (dataLines.isNotEmpty()) {
                    val dataPayload = dataLines.joinToString("\n")
                    if (dataPayload != "[DONE]") {
                        onChunk(parseStreamChunk(dataPayload))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            throw e
        } catch (e: Throwable) {
            val timeoutHint = if (isTimeoutError(e)) {
                val timeoutText = upstreamSocketTimeoutMillis?.toString() ?: "disabled"
                " (upstream stream timeout=${timeoutText}ms; configure LLM_REQUEST_TIMEOUT_SECONDS)"
            } else {
                ""
            }
            throw AppException(
                code = ErrorCodes.AI_UPSTREAM_ERROR,
                message = "Failed to stream DeepSeek response: ${e.message}$timeoutHint",
                status = HttpStatusCode.BadGateway
            )
        }
    }

    suspend fun completeChat(
        messages: List<ChatMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        modelTier: LlmModelTier = LlmModelTier.FLASH,
        tools: List<LlmToolSpec> = emptyList()
    ): ChatCompletionResult {
        val requestBody = buildRequest(
            model = config.resolveModel(modelTier),
            messages = messages,
            stream = false,
            temperature = temperature,
            maxTokens = maxTokens,
            tools = tools
        )
        try {
            val response = client.post("${config.baseUrl.trimEnd('/')}/chat/completions") {
                timeout {
                    requestTimeoutMillis = upstreamSocketTimeoutMillis ?: 120_000L
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    socketTimeoutMillis = upstreamSocketTimeoutMillis
                }
                headers {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                }
                setBody(requestBody)
            }
            if (!response.status.isSuccess()) {
                throw mapUpstreamStatus(response.status, runCatching { response.bodyAsText() }.getOrNull())
            }
            val payload = response.body<DeepSeekCompletionResponse>()
            val choice = payload.choices.firstOrNull()
            val message = choice?.message
            val toolCalls = message?.toolCalls.orEmpty().mapNotNull { call ->
                val id = call.id ?: return@mapNotNull null
                val name = call.function?.name ?: return@mapNotNull null
                ToolCall(
                    id = id,
                    name = name,
                    argumentsJson = call.function.arguments.orEmpty()
                )
            }
            return ChatCompletionResult(
                content = message?.content,
                toolCalls = toolCalls,
                finishReason = choice?.finishReason,
                usage = payload.usage?.toMetrics()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            throw e
        } catch (e: Throwable) {
            throw AppException(
                code = ErrorCodes.AI_UPSTREAM_ERROR,
                message = "Failed to complete DeepSeek request: ${e.message}",
                status = HttpStatusCode.BadGateway
            )
        }
    }

    suspend fun completeTextChat(
        messages: List<ChatMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        modelTier: LlmModelTier = LlmModelTier.FLASH
    ): Pair<String, UsageMetrics?> {
        val result = completeChat(
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            modelTier = modelTier
        )
        return (result.content.orEmpty()) to result.usage
    }

    private fun buildRequest(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Double?,
        maxTokens: Int?,
        tools: List<LlmToolSpec>
    ): DeepSeekChatRequest {
        return DeepSeekChatRequest(
            model = model,
            messages = messages.map { it.toWire() },
            stream = stream,
            temperature = temperature,
            maxTokens = maxTokens,
            streamOptions = if (stream) StreamOptions(includeUsage = true) else null,
            tools = tools.takeIf { it.isNotEmpty() }?.map { tool ->
                DeepSeekTool(
                    type = "function",
                    function = DeepSeekToolFunction(
                        name = tool.name,
                        description = tool.description,
                        parameters = tool.parameters
                    )
                )
            }
        )
    }

    private fun ChatMessage.toWire(): DeepSeekMessage {
        return DeepSeekMessage(
            role = role,
            content = content,
            name = name,
            toolCallId = toolCallId,
            toolCalls = toolCalls?.map {
                DeepSeekToolCall(
                    id = it.id,
                    type = "function",
                    function = DeepSeekFunctionCall(
                        name = it.name,
                        arguments = it.argumentsJson
                    )
                )
            }
        )
    }

    private fun parseStreamChunk(dataPayload: String): DeepSeekChunk {
        val chunk = try {
            json.decodeFromString<DeepSeekStreamChunk>(dataPayload)
        } catch (_: SerializationException) {
            throw AppException(
                code = ErrorCodes.AI_UPSTREAM_ERROR,
                message = "Invalid upstream stream chunk",
                status = HttpStatusCode.BadGateway
            )
        }
        val choice = chunk.choices.firstOrNull()
        val toolDeltas = choice?.delta?.toolCalls.orEmpty().map { delta ->
            ToolCallDelta(
                index = delta.index,
                id = delta.id,
                name = delta.function?.name,
                argumentsDelta = delta.function?.arguments
            )
        }
        return DeepSeekChunk(
            contentDelta = choice?.delta?.content,
            finishReason = choice?.finishReason,
            usage = chunk.usage?.toMetrics(),
            toolCallDeltas = toolDeltas
        )
    }

    private fun mapUpstreamStatus(status: HttpStatusCode, text: String?): AppException {
        return when (status) {
            HttpStatusCode.TooManyRequests -> AppException(
                code = ErrorCodes.AI_RATE_LIMITED,
                message = text ?: "Upstream rate limited",
                status = HttpStatusCode.TooManyRequests
            )

            else -> AppException(
                code = ErrorCodes.AI_UPSTREAM_ERROR,
                message = text ?: "Upstream request failed with status=${status.value}",
                status = HttpStatusCode.BadGateway
            )
        }
    }

    override fun close() {
        client.close()
    }

    private fun isTimeoutError(error: Throwable): Boolean {
        var cursor: Throwable? = error
        while (cursor != null) {
            if (cursor is java.net.SocketTimeoutException) {
                return true
            }
            val name = cursor::class.qualifiedName.orEmpty()
            if (name.contains("Timeout", ignoreCase = true)) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }

    companion object {
        fun emptyObjectSchema(): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        }
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 15_000L

@Serializable
private data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    val stream: Boolean,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("stream_options")
    val streamOptions: StreamOptions? = null,
    val tools: List<DeepSeekTool>? = null
)

@Serializable
private data class StreamOptions(
    @SerialName("include_usage")
    val includeUsage: Boolean
)

@Serializable
private data class DeepSeekMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<DeepSeekToolCall>? = null
)

@Serializable
private data class DeepSeekTool(
    val type: String,
    val function: DeepSeekToolFunction
)

@Serializable
private data class DeepSeekToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement
)

@Serializable
private data class DeepSeekToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: DeepSeekFunctionCall? = null
)

@Serializable
private data class DeepSeekFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
private data class DeepSeekStreamChunk(
    val choices: List<DeepSeekStreamChoice> = emptyList(),
    val usage: DeepSeekUsage? = null
)

@Serializable
private data class DeepSeekStreamChoice(
    val delta: DeepSeekDelta = DeepSeekDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
private data class DeepSeekDelta(
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    val role: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<DeepSeekToolCallDelta> = emptyList()
)

@Serializable
private data class DeepSeekToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: DeepSeekFunctionCall? = null
)

@Serializable
private data class DeepSeekCompletionResponse(
    val choices: List<DeepSeekCompletionChoice> = emptyList(),
    val usage: DeepSeekUsage? = null
)

@Serializable
private data class DeepSeekCompletionChoice(
    val message: DeepSeekMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
private data class DeepSeekUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null
)

private fun DeepSeekUsage.toMetrics(): UsageMetrics = UsageMetrics(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens
)
