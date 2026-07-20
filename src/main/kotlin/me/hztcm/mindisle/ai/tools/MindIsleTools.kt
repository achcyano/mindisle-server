package me.hztcm.mindisle.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.ai.client.LlmToolSpec
import me.hztcm.mindisle.db.UiTaskType
import me.hztcm.mindisle.intervention.service.InterventionService
import me.hztcm.mindisle.medication.service.DoseLogService
import me.hztcm.mindisle.model.StreamUiActionEvent
import me.hztcm.mindisle.state.service.PatientStateService
import me.hztcm.mindisle.task.service.UiTaskService

data class ToolContext(
    val userId: Long,
    val conversationId: Long?
)

data class ToolExecutionResult(
    val ok: Boolean,
    val summary: String,
    val uiAction: StreamUiActionEvent? = null,
    val data: Map<String, String> = emptyMap()
)

interface MindIsleTool {
    val name: String
    val description: String
    val parameters: JsonObject
    suspend fun execute(ctx: ToolContext, args: JsonObject): ToolExecutionResult

    fun toSpec(): LlmToolSpec = LlmToolSpec(name, description, parameters)
}

class MindIsleToolRegistry(
    private val tools: List<MindIsleTool>
) {
    private val byName = tools.associateBy { it.name }

    fun specs(): List<LlmToolSpec> = tools.map { it.toSpec() }

    suspend fun execute(name: String, ctx: ToolContext, argumentsJson: String): ToolExecutionResult {
        val tool = byName[name]
            ?: return ToolExecutionResult(false, "Unknown tool: $name")
        val args = runCatching {
            Json.parseToJsonElement(argumentsJson).let { it as? JsonObject } ?: buildJsonObject { }
        }.getOrDefault(buildJsonObject { })
        return runCatching { tool.execute(ctx, args) }.getOrElse {
            ToolExecutionResult(false, it.message ?: "tool failed")
        }
    }
}

fun buildDefaultToolRegistry(
    stateService: PatientStateService,
    interventionService: InterventionService,
    uiTaskService: UiTaskService,
    doseLogService: DoseLogService
): MindIsleToolRegistry {
    val tools = listOf(
        object : MindIsleTool {
            override val name = "get_patient_snapshot"
            override val description = "读取患者当前状态摘要（心境/焦虑/睡眠/用药困扰/风险等）"
            override val parameters = DeepSeekAliyunClient.emptyObjectSchema()
            override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolExecutionResult {
                val state = stateService.current(ctx.userId)
                return ToolExecutionResult(true, state.summary, data = mapOf("riskLevel" to state.riskLevel))
            }
        },
        object : MindIsleTool {
            override val name = "list_available_scales"
            override val description = "列出可推荐的常用量表代码"
            override val parameters = DeepSeekAliyunClient.emptyObjectSchema()
            override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolExecutionResult {
                return ToolExecutionResult(
                    true,
                    "PHQ9(抑郁), GAD7(焦虑), PSQI(睡眠), TESS(副作用)",
                    data = mapOf("codes" to "PHQ9,GAD7,PSQI,TESS")
                )
            }
        },
        object : MindIsleTool {
            override val name = "recommend_scale"
            override val description = "向患者推荐并创建待填量表任务。参数 scaleCode: PHQ9|GAD7|PSQI|TESS"
            override val parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("scaleCode") {
                        put("type", "string")
                        put("description", "量表代码")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                    }
                }
                put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("scaleCode"))))
            }
            override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolExecutionResult {
                val code = args["scaleCode"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "PHQ9"
                val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "根据当前状态建议评估"
                uiTaskService.create(
                    userId = ctx.userId,
                    type = UiTaskType.SCALE,
                    title = "建议完成量表 $code",
                    payload = mapOf("scaleCode" to code, "reason" to reason),
                    source = "AI_TOOL"
                )
                return ToolExecutionResult(
                    ok = true,
                    summary = "已推荐量表 $code：$reason",
                    uiAction = StreamUiActionEvent(
                        type = "OPEN_SCALE",
                        title = "去完成 $code",
                        payload = mapOf("scaleCode" to code, "reason" to reason)
                    )
                )
            }
        },
        object : MindIsleTool {
            override val name = "start_intervention"
            override val description =
                "启动结构化干预模块。moduleCode: breathing_5min|pmr_10min|mindfulness_5min|ba_one_step|sleep_hygiene|med_comm_list"
            override val parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("moduleCode") { put("type", "string") }
                }
                put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("moduleCode"))))
            }
            override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolExecutionResult {
                val code = args["moduleCode"]?.jsonPrimitive?.contentOrNull ?: "mindfulness_5min"
                val delivery = interventionService.startModule(
                    userId = ctx.userId,
                    moduleCode = code,
                    triggerType = "AI_TOOL",
                    conversationId = ctx.conversationId
                )
                return ToolExecutionResult(
                    ok = true,
                    summary = "已启动干预：${delivery.module.title}",
                    uiAction = StreamUiActionEvent(
                        type = "OPEN_INTERVENTION",
                        title = delivery.module.title,
                        payload = mapOf(
                            "moduleCode" to delivery.module.code,
                            "deliveryId" to delivery.deliveryId.toString()
                        )
                    )
                )
            }
        },
        object : MindIsleTool {
            override val name = "log_ema_prompt"
            override val description = "提醒患者完成今日 EMA 情绪/睡眠简短自评"
            override val parameters = DeepSeekAliyunClient.emptyObjectSchema()
            override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolExecutionResult {
                uiTaskService.create(
                    userId = ctx.userId,
                    type = UiTaskType.EMA,
                    title = "完成今日心情与睡眠简评",
                    payload = mapOf("slot" to "ADHOC"),
                    source = "AI_TOOL"
                )
                return ToolExecutionResult(
                    true,
                    "已创建今日 EMA 提醒",
                    uiAction = StreamUiActionEvent(
                        type = "OPEN_EMA",
                        title = "记录今日心情",
                        payload = mapOf("slot" to "ADHOC")
                    )
                )
            }
        },
        object : MindIsleTool {
            override val name = "record_side_effect_intent"
            override val description = "引导患者记录药物副作用"
            override val parameters = DeepSeekAliyunClient.emptyObjectSchema()
            override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolExecutionResult {
                uiTaskService.create(
                    userId = ctx.userId,
                    type = UiTaskType.SIDE_EFFECT,
                    title = "记录用药副作用",
                    source = "AI_TOOL"
                )
                return ToolExecutionResult(
                    true,
                    "已引导记录副作用",
                    uiAction = StreamUiActionEvent(
                        type = "OPEN_SIDE_EFFECT",
                        title = "记录副作用",
                        payload = emptyMap()
                    )
                )
            }
        },
        object : MindIsleTool {
            override val name = "get_medication_today"
            override val description = "查看今日用药计划与打卡状态"
            override val parameters = DeepSeekAliyunClient.emptyObjectSchema()
            override suspend fun execute(ctx: ToolContext, args: JsonObject): ToolExecutionResult {
                val plan = doseLogService.todayPlan(ctx.userId)
                val text = if (plan.items.isEmpty()) {
                    "今日无用药计划"
                } else {
                    plan.items.joinToString("; ") {
                        "${it.drugName}@${it.plannedTime}:${it.status ?: "PENDING"}"
                    }
                }
                return ToolExecutionResult(true, text)
            }
        }
    )
    return MindIsleToolRegistry(tools)
}
