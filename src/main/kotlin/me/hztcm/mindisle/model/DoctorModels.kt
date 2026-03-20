package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable

@Serializable
enum class DoctorSmsPurpose {
    REGISTER,
    RESET_PASSWORD
}

@Serializable
data class SendDoctorSmsCodeRequest(
    val phone: String,
    val purpose: DoctorSmsPurpose
)

@Serializable
data class DoctorRegisterRequest(
    val phone: String,
    val smsCode: String,
    val password: String,
    val fullName: String? = null,
    val hospital: String? = null
)

@Serializable
data class DoctorPasswordLoginRequest(
    val phone: String,
    val password: String
)

@Serializable
data class DoctorTokenRefreshRequest(
    val refreshToken: String
)

@Serializable
data class DoctorResetPasswordRequest(
    val phone: String,
    val smsCode: String,
    val newPassword: String
)

@Serializable
data class DoctorChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String
)

@Serializable
data class DoctorLogoutRequest(
    val refreshToken: String? = null
)

@Serializable
data class DoctorTokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresInSeconds: Long,
    val refreshTokenExpiresInSeconds: Long
)

@Serializable
data class DoctorAuthResponse(
    val doctorId: Long,
    val token: DoctorTokenPairResponse
)

@Serializable
data class DoctorProfileResponse(
    val doctorId: Long,
    val phone: String,
    val fullName: String,
    val hospital: String? = null
)

@Serializable
data class UpsertDoctorProfileRequest(
    val fullName: String? = null,
    val hospital: String? = null
)

@Serializable
data class DoctorThresholdSettingsRequest(
    val scl90Threshold: Double? = null,
    val phq9Threshold: Double? = null,
    val gad7Threshold: Double? = null,
    val psqiThreshold: Double? = null
)

@Serializable
data class DoctorThresholdSettingsResponse(
    val scl90Threshold: Double? = null,
    val phq9Threshold: Double? = null,
    val gad7Threshold: Double? = null,
    val psqiThreshold: Double? = null,
    val updatedAt: String
)

@Serializable
data class GenerateBindingCodeResponse(
    val code: String,
    val expiresAt: String
)

@Serializable
data class PatientBindDoctorRequest(
    val bindingCode: String
)

@Serializable
data class DoctorBindingInfoResponse(
    val bindingId: Long,
    val doctorId: Long,
    val doctorName: String,
    val doctorHospital: String? = null,
    val boundAt: String,
    val severityGroup: String? = null
)

@Serializable
data class PatientDoctorBindingStatusResponse(
    val isBound: Boolean,
    val current: DoctorBindingInfoResponse? = null,
    val updatedAt: String
)

@Serializable
data class BindingHistoryItem(
    val bindingId: Long,
    val doctorId: Long,
    val doctorName: String,
    val doctorHospital: String? = null,
    val status: String,
    val boundAt: String,
    val unboundAt: String? = null,
    val severityGroup: String? = null
)

@Serializable
data class ListBindingHistoryResponse(
    val items: List<BindingHistoryItem>,
    val nextCursor: String? = null
)

@Serializable
data class DoctorPatientBindingHistoryItem(
    val bindingId: Long,
    val patientUserId: Long,
    val patientPhone: String,
    val patientFullName: String? = null,
    val status: String,
    val boundAt: String,
    val unboundAt: String? = null,
    val severityGroup: String? = null
)

@Serializable
data class DoctorBindingHistoryResponse(
    val items: List<DoctorPatientBindingHistoryItem>,
    val nextCursor: String? = null
)

enum class DoctorPatientSortBy {
    LATEST_ASSESSMENT_AT,
    SCL90_SCORE
}

enum class DoctorPatientSortOrder {
    ASC,
    DESC
}

data class ListDoctorPatientsQuery(
    val limit: Int,
    val cursor: String?,
    val keyword: String?,
    val gender: Gender?,
    val severityGroup: String?,
    val abnormalOnly: Boolean,
    val scl90ScoreMin: Double?,
    val scl90ScoreMax: Double?,
    val sortBy: DoctorPatientSortBy,
    val sortOrder: DoctorPatientSortOrder
)

@Serializable
data class UpdatePatientGroupingRequest(
    val severityGroup: String? = null,
    val reason: String? = null
)

@Serializable
data class DoctorPatientGroupingStateResponse(
    val patientUserId: Long,
    val severityGroup: String? = null,
    val updatedAt: String
)

@Serializable
data class UpdatePatientDiagnosisRequest(
    val diagnosis: String? = null
)

@Serializable
data class DoctorPatientDiagnosisStateResponse(
    val patientUserId: Long,
    val diagnosis: String? = null,
    val updatedAt: String
)

@Serializable
data class GroupingChangeItem(
    val changeId: Long,
    val fieldName: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val operatorDoctorId: Long,
    val operatorDoctorName: String,
    val reason: String? = null,
    val changedAt: String
)

@Serializable
data class GroupingChangeHistoryResponse(
    val items: List<GroupingChangeItem>,
    val nextCursor: String? = null
)

@Serializable
data class PatientMetricSnapshot(
    val scl90Total: Double? = null,
    val phq9Total: Double? = null,
    val gad7Total: Double? = null,
    val psqiTotal: Double? = null,
    val adherence: Double? = null
)

@Serializable
data class DoctorPatientItem(
    val patientUserId: Long,
    val phone: String,
    val fullName: String? = null,
    val gender: Gender = Gender.UNKNOWN,
    val birthDate: String? = null,
    val age: Int? = null,
    val severityGroup: String? = null,
    val diagnosis: String? = null,
    val latestScl90Score: Double? = null,
    val latestAssessmentAt: String? = null,
    val lastScaleSubmittedAt: String? = null,
    val metrics: PatientMetricSnapshot,
    val abnormal: Boolean,
    val abnormalReasons: List<String> = emptyList()
)

@Serializable
data class DoctorPatientListResponse(
    val items: List<DoctorPatientItem>,
    val nextCursor: String? = null
)

@Serializable
data class PatientScaleTrendPoint(
    val submittedAt: String,
    val totalScore: Double? = null
)

@Serializable
data class PatientScaleTrendSeries(
    val scaleCode: String,
    val scaleName: String,
    val points: List<PatientScaleTrendPoint>
)

@Serializable
data class PatientScaleTrendsResponse(
    val patientUserId: Long,
    val series: List<PatientScaleTrendSeries>
)

@Serializable
data class GenerateAssessmentReportRequest(
    val days: Int? = null
)

@Serializable
data class AssessmentReportResponse(
    val reportId: Long,
    val days: Int,
    val patientUserId: Long,
    val generatedAt: String,
    val polished: Boolean,
    val model: String? = null,
    val templateReport: String,
    val report: String
)

@Serializable
data class AssessmentReportSummaryItem(
    val reportId: Long,
    val days: Int,
    val patientUserId: Long,
    val generatedAt: String,
    val polished: Boolean,
    val model: String? = null
)

@Serializable
data class AssessmentReportListResponse(
    val items: List<AssessmentReportSummaryItem>,
    val nextCursor: String? = null
)

@Serializable
data class CreateSideEffectRequest(
    val symptom: String,
    val severity: Int,
    val note: String? = null,
    val recordedAt: String? = null
)

@Serializable
data class SideEffectItemResponse(
    val sideEffectId: Long,
    val symptom: String,
    val severity: Int,
    val note: String? = null,
    val recordedAt: String,
    val createdAt: String
)

@Serializable
data class SideEffectSummaryItem(
    val symptom: String,
    val count: Int,
    val averageSeverity: Double,
    val maxSeverity: Int
)

@Serializable
data class SideEffectSummaryResponse(
    val totalCount: Int,
    val items: List<SideEffectSummaryItem>
)

@Serializable
data class WeightTrendPoint(
    val recordedAt: String,
    val weightKg: Double,
    val source: String
)

@Serializable
data class WeightTrendResponse(
    val patientUserId: Long,
    val points: List<WeightTrendPoint>
)
