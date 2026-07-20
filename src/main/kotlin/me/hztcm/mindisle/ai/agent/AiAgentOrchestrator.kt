package me.hztcm.mindisle.ai.agent

import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.ai.client.ToolCall
import me.hztcm.mindisle.ai.client.UsageMetrics
import me.hztcm.mindisle.ai.tools.MindIsleToolRegistry
import me.hztcm.mindisle.ai.tools.ToolContext
import me.hztcm.mindisle.ai.tools.ToolExecutionResult
import me.hztcm.mindisle.config.LlmConfig
import me.hztcm.mindisle.config.LlmModelTier
import me.hztcm.mindisle.model.StreamUiActionEvent

data class AgentOutcome(
    val assistantText: String,
    val toolTrace: List<AgentToolTrace>,
    val uiActions: List<StreamUiActionEvent>,
    val usage: UsageMetrics?
)

data class AgentToolTrace(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val ok: Boolean,
    val summary: String
)

class AiAgentOrchestrator(
    private val config: LlmConfig,
    private val client: DeepSeekAliyunClient,
    private val toolRegistry: MindIsleToolRegistry
) {
    suspend fun run(
        seedMessages: List<ChatMessage>,
        userId: Long,
        conversationId: Long?,
        temperature: Double?,
        maxTokens: Int?,
        modelTier: LlmModelTier = LlmModelTier.FLASH,
        onToolCall: suspend (ToolCall) -> Unit = {},
        onToolResult: suspend (AgentToolTrace) -> Unit = {}
    ): AgentOutcome {
        val messages = seedMessages.toMutableList()
        val traces = mutableListOf<AgentToolTrace>()
        val uiActions = mutableListOf<StreamUiActionEvent>()
        var usage: UsageMetrics? = null
        val tools = toolRegistry.specs()
        val maxRounds = config.maxToolRounds.coerceIn(1, 8)

        repeat(maxRounds) {
            val completion = client.completeChat(
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens,
                modelTier = modelTier,
                tools = tools
            )
            usage = mergeUsage(usage, completion.usage)
            if (completion.toolCalls.isEmpty()) {
                return AgentOutcome(
                    assistantText = completion.content.orEmpty().trim(),
                    toolTrace = traces,
                    uiActions = uiActions,
                    usage = usage
                )
            }

            messages += ChatMessage(
                role = "assistant",
                content = completion.content,
                toolCalls = completion.toolCalls
            )

            for (call in completion.toolCalls) {
                onToolCall(call)
                val result = toolRegistry.execute(
                    name = call.name,
                    ctx = ToolContext(userId = userId, conversationId = conversationId),
                    argumentsJson = call.argumentsJson
                )
                val trace = AgentToolTrace(
                    id = call.id,
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    ok = result.ok,
                    summary = result.summary
                )
                traces += trace
                result.uiAction?.let { uiActions += it }
                onToolResult(trace)
                messages += ChatMessage(
                    role = "tool",
                    content = result.toToolContent(),
                    toolCallId = call.id,
                    name = call.name
                )
            }
        }

        // Final text-only pass without tools if still looping.
        val final = client.completeChat(
            messages = messages + ChatMessage(
                role = "system",
                content = "请基于工具结果给出简洁中文回复，不要再调用工具。"
            ),
            temperature = temperature,
            maxTokens = maxTokens,
            modelTier = modelTier,
            tools = emptyList()
        )
        usage = mergeUsage(usage, final.usage)
        return AgentOutcome(
            assistantText = final.content.orEmpty().ifBlank {
                traces.lastOrNull()?.summary ?: "我已记录你的情况。"
            },
            toolTrace = traces,
            uiActions = uiActions,
            usage = usage
        )
    }

    private fun ToolExecutionResult.toToolContent(): String {
        val extra = if (data.isEmpty()) "" else " data=$data"
        return "ok=$ok; summary=$summary$extra"
    }

    private fun mergeUsage(a: UsageMetrics?, b: UsageMetrics?): UsageMetrics? {
        if (a == null) return b
        if (b == null) return a
        return UsageMetrics(
            promptTokens = (a.promptTokens ?: 0) + (b.promptTokens ?: 0),
            completionTokens = (a.completionTokens ?: 0) + (b.completionTokens ?: 0),
            totalTokens = (a.totalTokens ?: 0) + (b.totalTokens ?: 0)
        )
    }
}
