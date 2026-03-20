package me.hztcm.mindisle.doctor.service

import me.hztcm.mindisle.config.AuthConfig
import me.hztcm.mindisle.config.LlmConfig
import me.hztcm.mindisle.model.AssessmentReportResponse
import me.hztcm.mindisle.model.AssessmentReportListResponse
import me.hztcm.mindisle.model.CreateMedicationRequest
import me.hztcm.mindisle.model.CreateSideEffectRequest
import me.hztcm.mindisle.model.DirectLoginRequest
import me.hztcm.mindisle.model.DoctorAuthResponse
import me.hztcm.mindisle.model.DoctorBindingHistoryResponse
import me.hztcm.mindisle.model.DoctorChangePasswordRequest
import me.hztcm.mindisle.model.DoctorPatientDiagnosisStateResponse
import me.hztcm.mindisle.model.DoctorLogoutRequest
import me.hztcm.mindisle.model.DoctorPasswordLoginRequest
import me.hztcm.mindisle.model.DoctorPatientGroupingStateResponse
import me.hztcm.mindisle.model.DoctorPatientListResponse
import me.hztcm.mindisle.model.DoctorProfileResponse
import me.hztcm.mindisle.model.DoctorRegisterRequest
import me.hztcm.mindisle.model.DoctorResetPasswordRequest
import me.hztcm.mindisle.model.DoctorThresholdSettingsRequest
import me.hztcm.mindisle.model.DoctorThresholdSettingsResponse
import me.hztcm.mindisle.model.DoctorTokenRefreshRequest
import me.hztcm.mindisle.model.GenerateAssessmentReportRequest
import me.hztcm.mindisle.model.GenerateBindingCodeResponse
import me.hztcm.mindisle.model.GroupingChangeHistoryResponse
import me.hztcm.mindisle.model.ListDoctorPatientsQuery
import me.hztcm.mindisle.model.LoginCheckRequest
import me.hztcm.mindisle.model.LoginCheckResponse
import me.hztcm.mindisle.model.ListBindingHistoryResponse
import me.hztcm.mindisle.model.MedicationItemResponse
import me.hztcm.mindisle.model.MedicationListResponse
import me.hztcm.mindisle.model.PatientBindDoctorRequest
import me.hztcm.mindisle.model.PatientDoctorBindingStatusResponse
import me.hztcm.mindisle.model.PatientScaleTrendsResponse
import me.hztcm.mindisle.model.SendDoctorSmsCodeRequest
import me.hztcm.mindisle.model.SideEffectItemResponse
import me.hztcm.mindisle.model.SideEffectSummaryResponse
import me.hztcm.mindisle.model.UpdateMedicationRequest
import me.hztcm.mindisle.model.UpdatePatientDiagnosisRequest
import me.hztcm.mindisle.model.UpdatePatientGroupingRequest
import me.hztcm.mindisle.model.UpsertDoctorProfileRequest
import me.hztcm.mindisle.model.WeightTrendResponse
import me.hztcm.mindisle.security.JwtService
import me.hztcm.mindisle.sms.SmsGateway
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient

class DoctorService(
    authConfig: AuthConfig,
    llmConfig: LlmConfig,
    jwtService: JwtService,
    smsGateway: SmsGateway?,
    deepSeekClient: DeepSeekAliyunClient
) {
    private val deps = DoctorServiceDeps(
        authConfig = authConfig,
        llmConfig = llmConfig,
        jwtService = jwtService,
        smsGateway = smsGateway,
        deepSeekClient = deepSeekClient
    )

    private val authService = DoctorAuthDomainService(deps)
    private val bindingCodeService = DoctorBindingCodeDomainService()
    private val bindingService = DoctorBindingDomainService(bindingCodeService)
    private val patientService = DoctorPatientDomainService(deps)
    private val monitoringService = DoctorMonitoringDomainService(deps)

    suspend fun sendSmsCode(request: SendDoctorSmsCodeRequest, requestIp: String?) =
        authService.sendSmsCode(request, requestIp)

    suspend fun register(request: DoctorRegisterRequest, deviceId: String): DoctorAuthResponse =
        authService.register(request, deviceId)

    suspend fun loginCheck(request: LoginCheckRequest, deviceId: String): LoginCheckResponse =
        authService.loginCheck(request, deviceId)

    suspend fun loginDirect(request: DirectLoginRequest, deviceId: String): DoctorAuthResponse =
        authService.loginDirect(request, deviceId)

    suspend fun loginWithPassword(request: DoctorPasswordLoginRequest, deviceId: String): DoctorAuthResponse =
        authService.loginWithPassword(request, deviceId)

    suspend fun refreshToken(request: DoctorTokenRefreshRequest, deviceId: String): DoctorAuthResponse =
        authService.refreshToken(request, deviceId)

    suspend fun resetPassword(request: DoctorResetPasswordRequest) =
        authService.resetPassword(request)

    suspend fun changePassword(doctorId: Long, request: DoctorChangePasswordRequest) =
        authService.changePassword(doctorId, request)

    suspend fun logout(doctorId: Long, deviceId: String, request: DoctorLogoutRequest) =
        authService.logout(doctorId, deviceId, request)

    suspend fun getProfile(doctorId: Long): DoctorProfileResponse =
        authService.getProfile(doctorId)

    suspend fun upsertProfile(doctorId: Long, request: UpsertDoctorProfileRequest): DoctorProfileResponse =
        authService.upsertProfile(doctorId, request)

    suspend fun getThresholdSettings(doctorId: Long): DoctorThresholdSettingsResponse =
        authService.getThresholdSettings(doctorId)

    suspend fun upsertThresholdSettings(doctorId: Long, request: DoctorThresholdSettingsRequest): DoctorThresholdSettingsResponse =
        authService.upsertThresholdSettings(doctorId, request)

    suspend fun generateBindingCode(doctorId: Long): GenerateBindingCodeResponse =
        bindingCodeService.generateBindingCode(doctorId)

    suspend fun getPatientBindingStatus(userId: Long): PatientDoctorBindingStatusResponse =
        bindingService.getPatientBindingStatus(userId)

    suspend fun bindPatientToDoctor(userId: Long, request: PatientBindDoctorRequest): PatientDoctorBindingStatusResponse =
        bindingService.bindPatientToDoctor(userId, request)

    suspend fun unbindPatientDoctor(userId: Long): PatientDoctorBindingStatusResponse =
        bindingService.unbindPatientDoctor(userId)

    suspend fun listPatientBindingHistory(userId: Long, limit: Int, cursor: Long?): ListBindingHistoryResponse =
        bindingService.listPatientBindingHistory(userId, limit, cursor)

    suspend fun listDoctorBindingHistory(
        doctorId: Long,
        limit: Int,
        cursor: Long?,
        patientUserId: Long?
    ): DoctorBindingHistoryResponse = bindingService.listDoctorBindingHistory(doctorId, limit, cursor, patientUserId)

    suspend fun listDoctorPatients(
        doctorId: Long,
        query: ListDoctorPatientsQuery
    ): DoctorPatientListResponse = patientService.listDoctorPatients(doctorId, query)

    suspend fun updatePatientGrouping(
        doctorId: Long,
        patientUserId: Long,
        request: UpdatePatientGroupingRequest
    ): DoctorPatientGroupingStateResponse = patientService.updatePatientGrouping(doctorId, patientUserId, request)

    suspend fun updatePatientDiagnosis(
        doctorId: Long,
        patientUserId: Long,
        request: UpdatePatientDiagnosisRequest
    ): DoctorPatientDiagnosisStateResponse = patientService.updatePatientDiagnosis(doctorId, patientUserId, request)

    suspend fun listGroupingChanges(
        doctorId: Long,
        patientUserId: Long,
        limit: Int,
        cursor: Long?
    ): GroupingChangeHistoryResponse = patientService.listGroupingChanges(doctorId, patientUserId, limit, cursor)

    suspend fun getPatientScaleTrends(
        doctorId: Long,
        patientUserId: Long,
        days: Int?
    ): PatientScaleTrendsResponse = patientService.getPatientScaleTrends(doctorId, patientUserId, days)

    suspend fun generateAssessmentReport(
        doctorId: Long,
        patientUserId: Long,
        request: GenerateAssessmentReportRequest
    ): AssessmentReportResponse = patientService.generateAssessmentReport(doctorId, patientUserId, request)

    suspend fun getLatestAssessmentReport(
        doctorId: Long,
        patientUserId: Long
    ): AssessmentReportResponse = patientService.getLatestAssessmentReport(doctorId, patientUserId)

    suspend fun listAssessmentReports(
        doctorId: Long,
        patientUserId: Long,
        limit: Int,
        cursor: Long?
    ): AssessmentReportListResponse = patientService.listAssessmentReports(doctorId, patientUserId, limit, cursor)

    suspend fun getAssessmentReportDetail(
        doctorId: Long,
        patientUserId: Long,
        reportId: Long
    ): AssessmentReportResponse = patientService.getAssessmentReportDetail(doctorId, patientUserId, reportId)

    suspend fun createPatientMedication(
        doctorId: Long,
        patientUserId: Long,
        request: CreateMedicationRequest
    ): MedicationItemResponse = monitoringService.createPatientMedication(doctorId, patientUserId, request)

    suspend fun listPatientMedications(
        doctorId: Long,
        patientUserId: Long,
        limit: Int,
        cursor: Long?,
        onlyActive: Boolean
    ): MedicationListResponse = monitoringService.listPatientMedications(doctorId, patientUserId, limit, cursor, onlyActive)

    suspend fun updatePatientMedication(
        doctorId: Long,
        patientUserId: Long,
        medicationId: Long,
        request: UpdateMedicationRequest
    ): MedicationItemResponse = monitoringService.updatePatientMedication(doctorId, patientUserId, medicationId, request)

    suspend fun deletePatientMedication(doctorId: Long, patientUserId: Long, medicationId: Long) =
        monitoringService.deletePatientMedication(doctorId, patientUserId, medicationId)

    suspend fun createSideEffect(userId: Long, request: CreateSideEffectRequest): SideEffectItemResponse =
        monitoringService.createSideEffect(userId, request)

    suspend fun listUserSideEffects(userId: Long, limit: Int, cursor: Long?): List<SideEffectItemResponse> =
        monitoringService.listUserSideEffects(userId, limit, cursor)

    suspend fun summarizePatientSideEffects(doctorId: Long, patientUserId: Long, days: Int?): SideEffectSummaryResponse =
        monitoringService.summarizePatientSideEffects(doctorId, patientUserId, days)

    suspend fun getPatientWeightTrend(doctorId: Long, patientUserId: Long, days: Int?): WeightTrendResponse =
        monitoringService.getPatientWeightTrend(doctorId, patientUserId, days)
}
