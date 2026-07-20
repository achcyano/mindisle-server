package me.hztcm.mindisle.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDateTime
import java.time.ZoneOffset

object InterventionSeedData {
    private val json = Json { encodeDefaults = true }

    fun seedDefaultsIfEmpty() {
        if (InterventionModulesTable.selectAll().any()) return
        val now = LocalDateTime.now(ZoneOffset.UTC)
        modules().forEach { module ->
            InterventionModulesTable.insert {
                it[code] = module.code
                it[title] = module.title
                it[category] = module.category
                it[summary] = module.summary
                it[durationMinutes] = module.durationMinutes
                it[contentJson] = json.encodeToString(module.steps)
                it[active] = true
                it[createdAt] = now
            }
        }
    }

    private data class SeedModule(
        val code: String,
        val title: String,
        val category: String,
        val summary: String,
        val durationMinutes: Int,
        val steps: List<SeedStep>
    )

    @Serializable
    private data class SeedStep(
        val title: String,
        val body: String,
        val durationSec: Int? = null
    )

    private fun modules(): List<SeedModule> = listOf(
        SeedModule(
            code = "breathing_5min",
            title = "5分钟腹式呼吸",
            category = "RELAXATION",
            summary = "通过缓慢腹式呼吸降低焦虑唤醒。",
            durationMinutes = 5,
            steps = listOf(
                SeedStep("准备", "找一个舒适坐姿，双脚着地，轻轻闭上眼睛或柔和注视前方。", 30),
                SeedStep("吸气", "用鼻子缓慢吸气4秒，感受腹部轻轻鼓起。", 60),
                SeedStep("呼气", "用嘴巴缓慢呼气6秒，想象紧张随气息离开身体。", 90),
                SeedStep("循环", "继续吸4呼6的节奏，大约8–10轮。若走神，温柔地把注意带回呼吸。", 120)
            )
        ),
        SeedModule(
            code = "pmr_10min",
            title = "渐进性肌肉放松",
            category = "RELAXATION",
            summary = "轮流绷紧再放松肌群，缓解躯体紧张。",
            durationMinutes = 10,
            steps = listOf(
                SeedStep("手部", "双手握拳5秒，然后完全放松10秒，感受对比。", 40),
                SeedStep("肩颈", "耸肩靠近耳朵5秒，再放下放松。", 40),
                SeedStep("面部", "轻轻皱眉/咬牙5秒后放松。", 40),
                SeedStep("收尾", "全身扫描，留意哪里已经更松软，保持自然呼吸。", 60)
            )
        ),
        SeedModule(
            code = "mindfulness_5min",
            title = "5分钟正念觉察",
            category = "MINDFULNESS",
            summary = "把注意力安放在当下呼吸与身体感受。",
            durationMinutes = 5,
            steps = listOf(
                SeedStep("锚定", "注意鼻尖或腹部的呼吸起伏，不评判。", 60),
                SeedStep("身体", "从头到脚快速扫描，标记紧绷处但不强行改变。", 90),
                SeedStep("念头", "念头来去如云，标记“想”后回到呼吸。", 90)
            )
        ),
        SeedModule(
            code = "ba_one_step",
            title = "微型行为激活",
            category = "BEHAVIORAL_ACTIVATION",
            summary = "选一件小而可行的愉悦/掌控活动并完成。",
            durationMinutes = 10,
            steps = listOf(
                SeedStep("选择", "从清单选一件：散步5分钟、洗杯子、听一首歌、给信任的人发短讯。", 60),
                SeedStep("执行", "现在就做，做到“足够好”即可，不追求完美。", 300),
                SeedStep("记录", "完成后给自己一句肯定：我迈出了一步。", 30)
            )
        ),
        SeedModule(
            code = "sleep_hygiene",
            title = "睡眠卫生小练习",
            category = "SLEEP",
            summary = "睡前30分钟的稳定化习惯。",
            durationMinutes = 8,
            steps = listOf(
                SeedStep("环境", "调暗灯光，室温舒适，手机放到视线外。", 60),
                SeedStep("节律", "固定上床/起床时间，白天小憩不超过20分钟。", 60),
                SeedStep("放松", "做2分钟缓慢呼吸，或写下三件明天再处理的事。", 120)
            )
        ),
        SeedModule(
            code = "med_comm_list",
            title = "用药沟通清单",
            category = "MEDICATION",
            summary = "整理副作用与疑问，方便与医生沟通。",
            durationMinutes = 6,
            steps = listOf(
                SeedStep("症状", "写下最近不适：开始时间、严重度、是否影响日常。", 90),
                SeedStep("用药", "记录漏服/自行调整情况（如有）。", 60),
                SeedStep("问题", "准备1–3个想问医生的问题。", 60)
            )
        )
    )
}
