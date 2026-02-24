package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class ScaleStatusDto {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}

@Serializable
enum class ScaleQuestionTypeDto {
    SINGLE_CHOICE,
    MULTI_CHOICE,
    TEXT,
    TIME,
    DURATION,
    YES_NO
}

@Serializable
data class ScaleScoreRange(
    val min: Double,
    val max: Double
)

@Serializable
data class ScaleDimensionDef(
    val key: String,
    val name: String,
    val description: String? = null,
    val scoreRange: ScaleScoreRange? = null,
    val interpretationHint: String? = null
)

@Serializable
data class ScaleDimensionResult(
    val dimensionKey: String,
    val dimensionName: String? = null,
    val rawScore: Double? = null,
    val averageScore: Double? = null,
    val standardScore: Double? = null,
    val levelCode: String? = null,
    val levelName: String? = null,
    val interpretation: String? = null,
    val extraMetrics: Map<String, Double> = emptyMap()
)

@Serializable
enum class ScaleSessionStatusDto {
    IN_PROGRESS,
    SUBMITTED,
    ABANDONED
}

@Serializable
data class ScaleListItem(
    val scaleId: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    val status: ScaleStatusDto,
    val latestVersion: Int,
    val publishedAt: String? = null,
    val lastCompletedAt: String? = null
)

@Serializable
data class ListScalesResponse(
    val items: List<ScaleListItem>,
    val nextCursor: String? = null
)

@Serializable
data class ScaleDetailResponse(
    val scaleId: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    val status: ScaleStatusDto,
    val versionId: Long,
    val version: Int,
    val config: JsonElement? = null,
    val dimensions: List<ScaleDimensionDef> = emptyList(),
    val questions: List<ScaleQuestionItem>
)

@Serializable
data class ScaleQuestionItem(
    val questionId: Long,
    val questionKey: String,
    val orderNo: Int,
    val type: ScaleQuestionTypeDto,
    val dimension: String? = null,
    val required: Boolean,
    val scorable: Boolean,
    val reverseScored: Boolean,
    val stem: String,
    val hint: String? = null,
    val note: String? = null,
    val optionSetCode: String? = null,
    val meta: JsonElement? = null,
    val options: List<ScaleOptionItem> = emptyList()
)

@Serializable
data class ScaleOptionItem(
    val optionId: Long,
    val optionKey: String,
    val orderNo: Int,
    val label: String,
    val scoreValue: Double? = null,
    val ext: JsonElement? = null
)

@Serializable
data class CreateScaleSessionResponse(
    val created: Boolean,
    val session: ScaleSessionSummary
)

@Serializable
data class ScaleSessionSummary(
    val sessionId: Long,
    val scaleId: Long,
    val scaleCode: String,
    val scaleName: String,
    val versionId: Long,
    val version: Int,
    val status: ScaleSessionStatusDto,
    val progress: Int,
    val startedAt: String,
    val updatedAt: String,
    val submittedAt: String? = null
)

@Serializable
data class ScaleAnswerItem(
    val questionId: Long,
    val answer: JsonElement,
    val numericScore: Double? = null,
    val updatedAt: String
)

@Serializable
data class ScaleSessionDetailResponse(
    val session: ScaleSessionSummary,
    val answers: List<ScaleAnswerItem>,
    val unansweredRequiredQuestionIds: List<Long>
)

@Serializable
data class SaveScaleAnswerRequest(
    val answer: JsonElement
)

@Serializable
data class SaveScaleAnswerResponse(
    val sessionId: Long,
    val questionId: Long,
    val numericScore: Double? = null,
    val progress: Int,
    val updatedAt: String
)

@Serializable
data class SubmitScaleSessionResponse(
    val sessionId: Long,
    val status: ScaleSessionStatusDto,
    val progress: Int,
    val submittedAt: String
)

@Serializable
data class ScaleResultResponse(
    val sessionId: Long,
    val totalScore: Double? = null,
    val dimensionScores: Map<String, Double> = emptyMap(),
    val overallMetrics: Map<String, Double> = emptyMap(),
    val dimensionResults: List<ScaleDimensionResult> = emptyList(),
    val resultFlags: List<String> = emptyList(),
    val bandLevelCode: String? = null,
    val bandLevelName: String? = null,
    val resultText: String? = null,
    val computedAt: String
)

@Serializable
data class ScaleHistoryItem(
    val sessionId: Long,
    val scaleId: Long,
    val scaleCode: String,
    val scaleName: String,
    val versionId: Long,
    val version: Int,
    val progress: Int,
    val totalScore: Double? = null,
    val submittedAt: String? = null,
    val updatedAt: String
)

@Serializable
data class ListScaleHistoryResponse(
    val items: List<ScaleHistoryItem>,
    val nextCursor: String? = null
)

@Serializable
data class ScaleAssistStreamRequest(
    val sessionId: Long,
    val questionId: Long,
    val userDraftAnswer: String? = null
)

@Serializable
data class ScaleAssistMetaEvent(
    val generationId: String,
    val model: String,
    val createdAt: String
)

@Serializable
data class ScaleAssistDeltaEvent(
    val text: String
)

@Serializable
data class ScaleAssistDoneEvent(
    val finishReason: String = "stop",
    val createdAt: String
)

@Serializable
data class ScaleAssistErrorEvent(
    val code: Int,
    val message: String
)
