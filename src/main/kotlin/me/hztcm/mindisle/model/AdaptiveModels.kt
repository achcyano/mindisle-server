package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateEmaRequest(
    val slot: String = "ADHOC",
    val mood: Int,
    val sleepQuality: Int? = null,
    val activity: String? = null,
    val socialContact: Int? = null,
    val stressText: String? = null,
    val eventTags: List<String> = emptyList(),
    val bodyTags: List<String> = emptyList(),
    val note: String? = null,
    val responseLatencyMs: Long? = null,
    val localDate: String? = null
)

@Serializable
data class EmaEntryResponse(
    val emaId: Long,
    val localDate: String,
    val slot: String,
    val mood: Int,
    val sleepQuality: Int? = null,
    val activity: String? = null,
    val socialContact: Int? = null,
    val stressText: String? = null,
    val eventTags: List<String> = emptyList(),
    val bodyTags: List<String> = emptyList(),
    val note: String? = null,
    val submittedAt: String
)

@Serializable
data class EmaListResponse(
    val items: List<EmaEntryResponse>,
    val todaySlots: List<String> = emptyList()
)

@Serializable
data class EmaTodayResponse(
    val localDate: String,
    val completedSlots: List<String>,
    val pendingSlots: List<String>,
    val latest: EmaEntryResponse? = null
)

@Serializable
data class DoseCheckInRequest(
    val medicationId: Long,
    val plannedTime: String,
    val status: String = "TAKEN",
    val localDate: String? = null,
    val note: String? = null
)

@Serializable
data class DoseLogItem(
    val doseLogId: Long,
    val medicationId: Long,
    val drugName: String,
    val localDate: String,
    val plannedTime: String,
    val status: String,
    val actedAt: String
)

@Serializable
data class TodayDosePlanResponse(
    val localDate: String,
    val items: List<TodayDosePlanItem>
)

@Serializable
data class TodayDosePlanItem(
    val medicationId: Long,
    val drugName: String,
    val plannedTime: String,
    val status: String? = null,
    val doseLogId: Long? = null
)

@Serializable
data class PatientStateResponse(
    val lowMood: String,
    val anxiety: String,
    val rumination: String,
    val sleepDisturbance: String,
    val reducedActivity: String,
    val socialWithdrawal: String,
    val medicationDistress: String,
    val riskLevel: String,
    val summary: String,
    val createdAt: String,
    val features: Map<String, String> = emptyMap()
)

@Serializable
data class UiTaskItem(
    val taskId: Long,
    val taskType: String,
    val title: String,
    val status: String,
    val payload: Map<String, String> = emptyMap(),
    val dueAt: String? = null,
    val createdAt: String
)

@Serializable
data class UiTaskListResponse(
    val items: List<UiTaskItem>
)

@Serializable
data class InterventionModuleResponse(
    val code: String,
    val title: String,
    val category: String,
    val summary: String? = null,
    val durationMinutes: Int,
    val steps: List<InterventionStepDto> = emptyList()
)

@Serializable
data class InterventionStepDto(
    val title: String,
    val body: String,
    val durationSec: Int? = null
)

@Serializable
data class InterventionDeliveryResponse(
    val deliveryId: Long,
    val module: InterventionModuleResponse,
    val status: String,
    val triggerType: String,
    val createdAt: String
)

@Serializable
data class InterventionPendingResponse(
    val items: List<InterventionDeliveryResponse>
)

@Serializable
data class InterventionFeedbackRequest(
    val adopted: Boolean = true,
    val completed: Boolean = false,
    val durationSec: Int? = null,
    val moodBefore: Int? = null,
    val moodAfter: Int? = null
)

@Serializable
data class SafetyAlertItem(
    val alertId: Long,
    val userId: Long,
    val riskLevel: String,
    val reasonCodes: List<String>,
    val evidence: String? = null,
    val status: String,
    val createdAt: String,
    val patientName: String? = null
)

@Serializable
data class SafetyAlertListResponse(
    val items: List<SafetyAlertItem>
)

@Serializable
data class AckSafetyAlertRequest(
    val note: String? = null,
    val resolve: Boolean = false
)

@Serializable
data class StreamToolCallEvent(
    val id: String,
    val name: String,
    val argumentsJson: String
)

@Serializable
data class StreamToolResultEvent(
    val id: String,
    val name: String,
    val ok: Boolean,
    val summary: String
)

@Serializable
data class StreamUiActionEvent(
    val type: String,
    val title: String,
    val payload: Map<String, String> = emptyMap()
)

@Serializable
data class NlpSummaryResponse(
    val windowDays: Int,
    val messageCount: Int,
    val avgPolarity: Double? = null,
    val negativeRatio: Double? = null,
    val riskHitCount: Int = 0,
    val ruminationHitCount: Int = 0,
    val hopelessHitCount: Int = 0,
    val daily: List<NlpDailyPoint> = emptyList()
)

@Serializable
data class NlpDailyPoint(
    val date: String,
    val avgPolarity: Double? = null,
    val count: Int
)

@Serializable
data class ConversationExportMessage(
    val messageId: Long,
    val role: String,
    val content: String,
    val createdAt: String,
    val polarity: Double? = null,
    val riskHit: Boolean = false
)

@Serializable
data class ConversationExportResponse(
    val patientUserId: Long,
    val exportedAt: String,
    val conversations: List<ConversationExportItem>
)

@Serializable
data class ConversationExportItem(
    val conversationId: Long,
    val title: String? = null,
    val messages: List<ConversationExportMessage>
)

@Serializable
data class AnalyticsEventRequest(
    val eventType: String,
    val payload: Map<String, String> = emptyMap()
)

@Serializable
data class AnalyticsBatchRequest(
    val events: List<AnalyticsEventRequest>
)
