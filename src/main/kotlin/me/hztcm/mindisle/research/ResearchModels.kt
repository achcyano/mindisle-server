package me.hztcm.mindisle.research

import kotlinx.serialization.Serializable

@Serializable
data class ResearchEnrollRequest(
    val userId: Long,
    val baselinePhq9: Double? = null,
    val inclusionNotes: String? = null,
    val exclusionHit: Boolean = false,
    val consent: Boolean = false
)

@Serializable
data class ResearchEnrollmentResponse(
    val enrollmentId: Long,
    val userId: Long,
    val arm: String? = null,
    val status: String,
    val consentAt: String? = null,
    val randomizedAt: String? = null,
    val baselinePhq9: Double? = null
)

@Serializable
data class ResearchRandomizeResponse(
    val enrollmentId: Long,
    val arm: String,
    val randomizedAt: String
)

@Serializable
data class ResearchVisitUpsertRequest(
    val visitCode: String,
    val instruments: List<String> = emptyList(),
    val markCompleted: Boolean = false
)

@Serializable
data class ResearchVisitResponse(
    val visitId: Long,
    val visitCode: String,
    val completedAt: String? = null,
    val instruments: List<String> = emptyList()
)

@Serializable
data class ResearchAeCreateRequest(
    val title: String,
    val severity: String,
    val description: String? = null,
    val onsetAt: String? = null
)

@Serializable
data class ResearchAeResponse(
    val aeId: Long,
    val title: String,
    val severity: String,
    val onsetAt: String,
    val resolvedAt: String? = null,
    val reportedToEthicsAt: String? = null
)

@Serializable
data class ResearchQcCreateRequest(
    val conversationId: Long? = null,
    val reviewer: String,
    val appropriatenessScore: Int? = null,
    val notes: String? = null
)

@Serializable
data class ResearchQcResponse(
    val reviewId: Long,
    val reviewer: String,
    val appropriatenessScore: Int? = null,
    val createdAt: String
)

@Serializable
data class ResearchExportRow(
    val enrollmentId: Long,
    val userId: Long,
    val arm: String? = null,
    val status: String,
    val baselinePhq9: Double? = null,
    val aeCount: Int = 0,
    val qcCount: Int = 0
)

@Serializable
data class ResearchExportResponse(
    val exportedAt: String,
    val rows: List<ResearchExportRow>
)
