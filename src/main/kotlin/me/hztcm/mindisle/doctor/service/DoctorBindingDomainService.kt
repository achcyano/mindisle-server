package me.hztcm.mindisle.doctor.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorBindingCodesTable
import me.hztcm.mindisle.db.DoctorPatientBindingStatus
import me.hztcm.mindisle.db.DoctorPatientBindingsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.BindingHistoryItem
import me.hztcm.mindisle.model.DoctorBindingHistoryResponse
import me.hztcm.mindisle.model.DoctorPatientBindingHistoryItem
import me.hztcm.mindisle.model.ListBindingHistoryResponse
import me.hztcm.mindisle.model.PatientBindDoctorRequest
import me.hztcm.mindisle.model.PatientDoctorBindingStatusResponse
import me.hztcm.mindisle.util.sha256Hex
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

private val SMS_CODE_REGEX = Regex("^\\d{6}$")

internal class DoctorBindingDomainService(private val deps: DoctorServiceDeps) {
    suspend fun getPatientBindingStatus(userId: Long): PatientDoctorBindingStatusResponse {
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val userRef = EntityID(userId, UsersTable)
            requireUser(userRef)
            val active = findActiveBindingByPatient(userRef)
            if (active == null) {
                return@dbQuery PatientDoctorBindingStatusResponse(
                    isBound = false,
                    current = null,
                    updatedAt = now.toIsoInstant()
                )
            }
            val doctor = requireDoctor(active[DoctorPatientBindingsTable.doctorId])
            PatientDoctorBindingStatusResponse(
                isBound = true,
                current = toBindingInfo(active, doctor),
                updatedAt = active[DoctorPatientBindingsTable.updatedAt].toIsoInstant()
            )
        }
    }

    suspend fun bindPatientToDoctor(userId: Long, request: PatientBindDoctorRequest): PatientDoctorBindingStatusResponse {
        val code = request.bindingCode.trim()
        if (!SMS_CODE_REGEX.matches(code)) {
            throw AppException(
                code = ErrorCodes.DOCTOR_BINDING_CODE_INVALID,
                message = "bindingCode must be 6 digits",
                status = HttpStatusCode.BadRequest
            )
        }
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val userRef = EntityID(userId, UsersTable)
            requireUser(userRef)
            val codeHash = sha256Hex(code)
            val codeRow = DoctorBindingCodesTable.selectAll().where {
                (DoctorBindingCodesTable.codeHash eq codeHash) and
                    DoctorBindingCodesTable.consumedAt.isNull() and
                    (DoctorBindingCodesTable.expiresAt greaterEq now)
            }.orderBy(DoctorBindingCodesTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
                ?: throw AppException(
                    code = ErrorCodes.DOCTOR_BINDING_CODE_INVALID,
                    message = "Binding code invalid or expired",
                    status = HttpStatusCode.BadRequest
                )
            val active = findActiveBindingByPatient(userRef)
            if (active != null) {
                val activeDoctorId = active[DoctorPatientBindingsTable.doctorId].value
                val targetDoctorId = codeRow[DoctorBindingCodesTable.doctorId].value
                if (activeDoctorId != targetDoctorId) {
                    throw AppException(
                        code = ErrorCodes.DOCTOR_BINDING_CONFLICT,
                        message = "Patient is already bound to another doctor",
                        status = HttpStatusCode.Conflict
                    )
                }
                consumeBindingCodeRow(codeRow[DoctorBindingCodesTable.id], now)
                val doctor = requireDoctor(active[DoctorPatientBindingsTable.doctorId])
                return@dbQuery PatientDoctorBindingStatusResponse(
                    isBound = true,
                    current = toBindingInfo(active, doctor),
                    updatedAt = active[DoctorPatientBindingsTable.updatedAt].toIsoInstant()
                )
            }

            consumeBindingCodeRow(codeRow[DoctorBindingCodesTable.id], now)
            val bindingId = DoctorPatientBindingsTable.insert {
                it[patientUserId] = userRef
                it[doctorId] = codeRow[DoctorBindingCodesTable.doctorId]
                it[status] = DoctorPatientBindingStatus.ACTIVE
                it[severityGroup] = null
                it[treatmentPhase] = null
                it[boundAt] = now
                it[unboundAt] = null
                it[createdAt] = now
                it[updatedAt] = now
            }[DoctorPatientBindingsTable.id]
            val binding = DoctorPatientBindingsTable.selectAll().where {
                DoctorPatientBindingsTable.id eq bindingId
            }.first()
            val doctor = requireDoctor(binding[DoctorPatientBindingsTable.doctorId])
            PatientDoctorBindingStatusResponse(
                isBound = true,
                current = toBindingInfo(binding, doctor),
                updatedAt = now.toIsoInstant()
            )
        }
    }

    suspend fun unbindPatientDoctor(userId: Long): PatientDoctorBindingStatusResponse {
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val userRef = EntityID(userId, UsersTable)
            requireUser(userRef)
            val active = findActiveBindingByPatient(userRef)
            if (active == null) {
                return@dbQuery PatientDoctorBindingStatusResponse(
                    isBound = false,
                    current = null,
                    updatedAt = now.toIsoInstant()
                )
            }
            DoctorPatientBindingsTable.update({
                (DoctorPatientBindingsTable.id eq active[DoctorPatientBindingsTable.id]) and
                    (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE)
            }) {
                it[status] = DoctorPatientBindingStatus.UNBOUND
                it[unboundAt] = now
                it[updatedAt] = now
            }
            PatientDoctorBindingStatusResponse(
                isBound = false,
                current = null,
                updatedAt = now.toIsoInstant()
            )
        }
    }

    suspend fun listPatientBindingHistory(userId: Long, limit: Int, cursor: Long?): ListBindingHistoryResponse {
        val safeLimit = limit.coerceIn(1, 100)
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            requireUser(userRef)
            val condition = if (cursor != null) {
                (DoctorPatientBindingsTable.patientUserId eq userRef) and (DoctorPatientBindingsTable.id less cursor)
            } else {
                (DoctorPatientBindingsTable.patientUserId eq userRef) and (DoctorPatientBindingsTable.id greater 0L)
            }
            val rows = DoctorPatientBindingsTable.selectAll().where {
                condition
            }.orderBy(DoctorPatientBindingsTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val doctorRefs = page.map { it[DoctorPatientBindingsTable.doctorId] }.distinct()
            val doctorById = DoctorsTable.selectAll().where {
                DoctorsTable.id inList doctorRefs
            }.associateBy { it[DoctorsTable.id].value }
            val items = page.map { row ->
                val doctor = doctorById[row[DoctorPatientBindingsTable.doctorId].value]
                    ?: throw doctorNotFound("Doctor not found")
                BindingHistoryItem(
                    bindingId = row[DoctorPatientBindingsTable.id].value,
                    doctorId = doctor[DoctorsTable.id].value,
                    doctorName = doctor[DoctorsTable.fullName],
                    doctorTitle = doctor[DoctorsTable.title],
                    doctorHospital = doctor[DoctorsTable.hospital],
                    status = row[DoctorPatientBindingsTable.status].name,
                    boundAt = row[DoctorPatientBindingsTable.boundAt].toIsoInstant(),
                    unboundAt = row[DoctorPatientBindingsTable.unboundAt]?.toIsoInstant(),
                    severityGroup = row[DoctorPatientBindingsTable.severityGroup],
                    treatmentPhase = row[DoctorPatientBindingsTable.treatmentPhase]
                )
            }
            ListBindingHistoryResponse(
                items = items,
                nextCursor = if (hasMore) page.last()[DoctorPatientBindingsTable.id].value.toString() else null
            )
        }
    }

    suspend fun listDoctorBindingHistory(
        doctorId: Long,
        limit: Int,
        cursor: Long?,
        patientUserId: Long?
    ): DoctorBindingHistoryResponse {
        val safeLimit = limit.coerceIn(1, 100)
        return DatabaseFactory.dbQuery {
            val doctorRef = EntityID(doctorId, DoctorsTable)
            requireDoctor(doctorRef)
            val patientRef = patientUserId?.let { EntityID(it, UsersTable) }
            var condition: Op<Boolean> = DoctorPatientBindingsTable.doctorId eq doctorRef
            if (cursor != null) {
                condition = condition and (DoctorPatientBindingsTable.id less cursor)
            }
            if (patientRef != null) {
                condition = condition and (DoctorPatientBindingsTable.patientUserId eq patientRef)
            }
            val rows = DoctorPatientBindingsTable.selectAll().where {
                condition
            }.orderBy(DoctorPatientBindingsTable.id, SortOrder.DESC).limit(safeLimit + 1).toList()
            val hasMore = rows.size > safeLimit
            val page = if (hasMore) rows.take(safeLimit) else rows
            val userRefs = page.map { it[DoctorPatientBindingsTable.patientUserId] }.distinct()
            val userById = UsersTable.selectAll().where { UsersTable.id inList userRefs }
                .associateBy { it[UsersTable.id].value }
            val profileByUserId = UserProfilesTable.selectAll().where { UserProfilesTable.userId inList userRefs }
                .associateBy { it[UserProfilesTable.userId].value }
            val items = page.map { row ->
                val userRow = userById[row[DoctorPatientBindingsTable.patientUserId].value]
                    ?: throw doctorNotFound("Patient not found")
                DoctorPatientBindingHistoryItem(
                    bindingId = row[DoctorPatientBindingsTable.id].value,
                    patientUserId = row[DoctorPatientBindingsTable.patientUserId].value,
                    patientPhone = userRow[UsersTable.phone],
                    patientFullName = profileByUserId[row[DoctorPatientBindingsTable.patientUserId].value]?.get(UserProfilesTable.fullName),
                    status = row[DoctorPatientBindingsTable.status].name,
                    boundAt = row[DoctorPatientBindingsTable.boundAt].toIsoInstant(),
                    unboundAt = row[DoctorPatientBindingsTable.unboundAt]?.toIsoInstant(),
                    severityGroup = row[DoctorPatientBindingsTable.severityGroup],
                    treatmentPhase = row[DoctorPatientBindingsTable.treatmentPhase]
                )
            }
            DoctorBindingHistoryResponse(
                items = items,
                nextCursor = if (hasMore) page.last()[DoctorPatientBindingsTable.id].value.toString() else null
            )
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.consumeBindingCodeRow(
        rowId: EntityID<Long>,
        consumedAt: java.time.LocalDateTime
    ) {
        val affected = DoctorBindingCodesTable.update({
            (DoctorBindingCodesTable.id eq rowId) and DoctorBindingCodesTable.consumedAt.isNull()
        }) {
            it[DoctorBindingCodesTable.consumedAt] = consumedAt
        }
        if (affected <= 0) {
            throw AppException(
                code = ErrorCodes.DOCTOR_BINDING_CODE_INVALID,
                message = "Binding code invalid or expired",
                status = HttpStatusCode.BadRequest
            )
        }
    }
}
