package me.hztcm.mindisle.doctor.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.config.AuthConfig
import me.hztcm.mindisle.config.LlmConfig
import me.hztcm.mindisle.db.DoctorPatientBindingStatus
import me.hztcm.mindisle.db.DoctorPatientBindingsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.UserMedicationsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.DoctorBindingInfoResponse
import me.hztcm.mindisle.model.DoctorSmsPurpose
import me.hztcm.mindisle.model.SmsPurpose
import me.hztcm.mindisle.security.JwtService
import me.hztcm.mindisle.sms.SmsGateway
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.math.BigDecimal
import java.math.RoundingMode

internal data class DoctorServiceDeps(
    val authConfig: AuthConfig,
    val llmConfig: LlmConfig,
    val jwtService: JwtService,
    val smsGateway: SmsGateway?,
    val deepSeekClient: DeepSeekAliyunClient,
    val json: Json = Json
)

internal fun DoctorSmsPurpose.toSmsPurpose(): SmsPurpose = when (this) {
    DoctorSmsPurpose.REGISTER -> SmsPurpose.DOCTOR_REGISTER
    DoctorSmsPurpose.RESET_PASSWORD -> SmsPurpose.DOCTOR_RESET_PASSWORD
}

internal fun Double.round(scale: Int): Double = BigDecimal.valueOf(this).setScale(scale, RoundingMode.HALF_UP).toDouble()

internal fun medicationNotFound(): AppException {
    return AppException(
        code = ErrorCodes.MEDICATION_NOT_FOUND,
        message = "Medication not found",
        status = HttpStatusCode.NotFound
    )
}

internal fun doctorNotFound(message: String): AppException {
    return AppException(
        code = ErrorCodes.DOCTOR_NOT_FOUND,
        message = message,
        status = HttpStatusCode.NotFound
    )
}

internal fun doctorPatientNotBound(): AppException {
    return AppException(
        code = ErrorCodes.DOCTOR_PATIENT_NOT_BOUND,
        message = "Patient is not bound to current doctor",
        status = HttpStatusCode.NotFound
    )
}

internal fun doctorInvalidArg(message: String): AppException {
    return AppException(
        code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
        message = message,
        status = HttpStatusCode.BadRequest
    )
}

internal fun org.jetbrains.exposed.sql.Transaction.requireUser(userId: EntityID<Long>): ResultRow {
    return UsersTable.selectAll().where { UsersTable.id eq userId }.firstOrNull()
        ?: throw AppException(
            code = ErrorCodes.UNAUTHORIZED,
            message = "User not found",
            status = HttpStatusCode.Unauthorized
        )
}

internal fun org.jetbrains.exposed.sql.Transaction.requireDoctor(doctorId: EntityID<Long>): ResultRow {
    return DoctorsTable.selectAll().where { DoctorsTable.id eq doctorId }.firstOrNull()
        ?: throw doctorNotFound("Doctor not found")
}

internal fun org.jetbrains.exposed.sql.Transaction.findActiveBindingByPatient(patientRef: EntityID<Long>): ResultRow? {
    return DoctorPatientBindingsTable.selectAll().where {
        (DoctorPatientBindingsTable.patientUserId eq patientRef) and
            (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE) and
            DoctorPatientBindingsTable.unboundAt.isNull()
    }.orderBy(DoctorPatientBindingsTable.updatedAt, org.jetbrains.exposed.sql.SortOrder.DESC).limit(1).firstOrNull()
}

internal fun org.jetbrains.exposed.sql.Transaction.requireActiveBindingForDoctor(
    doctorId: Long,
    patientUserId: Long
): ResultRow {
    val doctorRef = EntityID(doctorId, DoctorsTable)
    val patientRef = EntityID(patientUserId, UsersTable)
    requireDoctor(doctorRef)
    requireUser(patientRef)
    return DoctorPatientBindingsTable.selectAll().where {
        (DoctorPatientBindingsTable.doctorId eq doctorRef) and
            (DoctorPatientBindingsTable.patientUserId eq patientRef) and
            (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE) and
            DoctorPatientBindingsTable.unboundAt.isNull()
    }.orderBy(DoctorPatientBindingsTable.updatedAt, org.jetbrains.exposed.sql.SortOrder.DESC).limit(1).firstOrNull()
        ?: throw doctorPatientNotBound()
}

internal fun toBindingInfo(binding: ResultRow, doctor: ResultRow): DoctorBindingInfoResponse {
    return DoctorBindingInfoResponse(
        bindingId = binding[DoctorPatientBindingsTable.id].value,
        doctorId = doctor[DoctorsTable.id].value,
        doctorName = doctor[DoctorsTable.fullName],
        doctorTitle = doctor[DoctorsTable.title],
        doctorHospital = doctor[DoctorsTable.hospital],
        boundAt = binding[DoctorPatientBindingsTable.boundAt].toIsoInstant(),
        severityGroup = binding[DoctorPatientBindingsTable.severityGroup],
        treatmentPhase = binding[DoctorPatientBindingsTable.treatmentPhase]
    )
}

internal fun buildMedicationListCondition(
    patientUserId: EntityID<Long>,
    cursor: Long?,
    onlyActive: Boolean,
    todayPlus8: java.time.LocalDate
): org.jetbrains.exposed.sql.Op<Boolean> {
    val cursorCondition = if (cursor != null) {
        UserMedicationsTable.id less cursor
    } else {
        UserMedicationsTable.id greater 0L
    }
    var condition: org.jetbrains.exposed.sql.Op<Boolean> = (UserMedicationsTable.userId eq patientUserId) and
        UserMedicationsTable.deletedAt.isNull() and
        cursorCondition
    if (onlyActive) {
        condition = condition and
            (UserMedicationsTable.recordedDateLocal lessEq todayPlus8) and
            (UserMedicationsTable.endDateLocal greaterEq todayPlus8)
    }
    return condition
}
