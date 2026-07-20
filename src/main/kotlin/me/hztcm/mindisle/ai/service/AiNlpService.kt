package me.hztcm.mindisle.ai.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.ai.client.ChatMessage
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.toLocalDatePlus8
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.config.LlmModelTier
import me.hztcm.mindisle.db.AiNlpFeaturesTable
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.NlpDailyPoint
import me.hztcm.mindisle.model.NlpSummaryResponse
import me.hztcm.mindisle.safety.service.SafetyScanner
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

class AiNlpService(
    private val client: DeepSeekAliyunClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class NlpExtract(
        val polarity: Double? = null,
        val negativeIntensity: Double? = null,
        val anxietyIntensity: Double? = null,
        val rumination: Boolean = false,
        val hopeless: Boolean = false,
        val anhedonia: Boolean = false,
        val topics: List<String> = emptyList()
    )

    suspend fun analyzeAndStore(
        userId: Long,
        conversationId: Long?,
        messageId: Long?,
        text: String
    ) {
        val hard = SafetyScanner.scan(text)
        val extracted = runCatching {
            val (raw, _) = client.completeTextChat(
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = """
                            你是中文心理对话特征抽取器。只输出 JSON：
                            {"polarity":-1到1,"negativeIntensity":0到1,"anxietyIntensity":0到1,
                             "rumination":bool,"hopeless":bool,"anhedonia":bool,"topics":[".."]}
                        """.trimIndent()
                    ),
                    ChatMessage(role = "user", content = text.take(2000))
                ),
                temperature = 0.0,
                maxTokens = 200,
                modelTier = LlmModelTier.FLASH
            )
            val body = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            json.decodeFromString<NlpExtract>(body)
        }.getOrElse {
            NlpExtract(
                polarity = if (hard.highRisk) -0.9 else null,
                rumination = text.contains("反复") || text.contains("一直想"),
                hopeless = text.contains("没希望") || text.contains("没有希望")
            )
        }

        DatabaseFactory.dbQuery {
            AiNlpFeaturesTable.insert {
                it[AiNlpFeaturesTable.userId] = EntityID(userId, UsersTable)
                it[AiNlpFeaturesTable.conversationId] = conversationId
                it[AiNlpFeaturesTable.messageId] = messageId
                it[polarity] = extracted.polarity
                it[negativeIntensity] = extracted.negativeIntensity
                it[anxietyIntensity] = extracted.anxietyIntensity
                it[ruminationHit] = extracted.rumination
                it[hopelessHit] = extracted.hopeless
                it[anhedoniaHit] = extracted.anhedonia
                it[riskHit] = hard.highRisk
                it[topicsJson] = json.encodeToString(extracted.topics)
                it[rawJson] = json.encodeToString(extracted)
                it[createdAt] = utcNow()
            }
        }
    }

    suspend fun summary(userId: Long, days: Int = 7): NlpSummaryResponse {
        val from = utcNow().minusDays(days.toLong())
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val rows = AiNlpFeaturesTable.selectAll().where {
                (AiNlpFeaturesTable.userId eq userRef) and (AiNlpFeaturesTable.createdAt greaterEq from)
            }.orderBy(AiNlpFeaturesTable.createdAt, SortOrder.ASC).toList()
            val polarities = rows.mapNotNull { it[AiNlpFeaturesTable.polarity] }
            val dailyMap = linkedMapOf<String, MutableList<Double>>()
            rows.forEach { row ->
                val day = row[AiNlpFeaturesTable.createdAt].toLocalDatePlus8().toString()
                val p = row[AiNlpFeaturesTable.polarity]
                dailyMap.getOrPut(day) { mutableListOf() }.also { list ->
                    if (p != null) list += p
                }
            }
            NlpSummaryResponse(
                windowDays = days,
                messageCount = rows.size,
                avgPolarity = polarities.average().takeIf { polarities.isNotEmpty() },
                negativeRatio = if (polarities.isEmpty()) null else {
                    polarities.count { it < 0 }.toDouble() / polarities.size
                },
                riskHitCount = rows.count { it[AiNlpFeaturesTable.riskHit] },
                ruminationHitCount = rows.count { it[AiNlpFeaturesTable.ruminationHit] },
                hopelessHitCount = rows.count { it[AiNlpFeaturesTable.hopelessHit] },
                daily = dailyMap.map { (date, values) ->
                    NlpDailyPoint(
                        date = date,
                        avgPolarity = values.average().takeIf { values.isNotEmpty() },
                        count = values.size
                    )
                }
            )
        }
    }
}
