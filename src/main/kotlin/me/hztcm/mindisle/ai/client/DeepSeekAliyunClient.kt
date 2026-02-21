package me.hztcm.mindisle.ai.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
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
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.config.LlmConfig
import java.io.Closeable
import java.util.concurrent.CancellationException

data class ChatMessage(
    val role: String,
    val content: String
)

data class UsageMetrics(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)

data class DeepSeekChunk(
    val contentDelta: String? = null,
    val finishReason: String? = null,
    val usage: UsageMetrics? = null
)

class DeepSeekAliyunClient(
    private val config: LlmConfig
) : Closeable {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
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
        onChunk: suspend (DeepSeekChunk) -> Unit
    ) {
        val requestBody = DeepSeekChatRequest(
            model = config.model,
            messages = messages.map { DeepSeekMessage(role = it.role, content = it.content) },
            stream = true,
            temperature = temperature,
            maxTokens = maxTokens,
            streamOptions = StreamOptions(includeUsage = true)
        )
        try {
            client.preparePost("${config.baseUrl.trimEnd('/')}/chat/completions") {
                accept(ContentType.Text.EventStream)
                headers {
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                }
                setBody(requestBody)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val text = runCatching { response.body<String>() }.getOrNull()
                    throw when (response.status) {
                        HttpStatusCode.TooManyRequests -> AppException(
                            code = ErrorCodes.AI_RATE_LIMITED,
                            message = text ?: "Upstream rate limited",
                            status = HttpStatusCode.TooManyRequests
                        )

                        else -> AppException(
                            code = ErrorCodes.AI_UPSTREAM_ERROR,
                            message = text ?: "Upstream request failed with status=${response.status.value}",
                            status = HttpStatusCode.BadGateway
                        )
                    }
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
                        val parsed = parseChunk(dataPayload)
                        onChunk(parsed)
                    }
                }
                if (dataLines.isNotEmpty()) {
                    val dataPayload = dataLines.joinToString("\n")
                    if (dataPayload != "[DONE]") {
                        onChunk(parseChunk(dataPayload))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            throw e
        } catch (e: Throwable) {
            throw AppException(
                code = ErrorCodes.AI_UPSTREAM_ERROR,
                message = "Failed to stream DeepSeek response: ${e.message}",
                status = HttpStatusCode.BadGateway
            )
        }
    }

    private fun parseChunk(dataPayload: String): DeepSeekChunk {
        val chunk = try {
            json.decodeFromString<DeepSeekStreamChunk>(dataPayload)
        } catch (e: SerializationException) {
            throw AppException(
                code = ErrorCodes.AI_UPSTREAM_ERROR,
                message = "Invalid upstream stream chunk: $e ${e.stackTrace}",
                status = HttpStatusCode.BadGateway
            )
        }
        val choice = chunk.choices.firstOrNull()
        return DeepSeekChunk(
            contentDelta = choice?.delta?.content,
            finishReason = choice?.finishReason,
            usage = chunk.usage?.let {
                UsageMetrics(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens
                )
            }
        )
    }

    override fun close() {
        client.close()
    }
}

@Serializable
private data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    val stream: Boolean,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("stream_options")
    val streamOptions: StreamOptions? = null
)

@Serializable
private data class StreamOptions(
    @SerialName("include_usage")
    val includeUsage: Boolean
)

@Serializable
private data class DeepSeekMessage(
    val role: String,
    val content: String
)

@Serializable
private data class DeepSeekStreamChunk(
    val choices: List<DeepSeekChoice> = emptyList(),
    val usage: DeepSeekUsage? = null
)

@Serializable
private data class DeepSeekChoice(
    val delta: DeepSeekDelta = DeepSeekDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
private data class DeepSeekDelta(
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    val role: String? = null
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
