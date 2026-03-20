package me.hztcm.mindisle.doctor.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorBindingCodesTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.model.GenerateBindingCodeResponse
import me.hztcm.mindisle.util.sha256Hex
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.LocalDateTime

private const val BINDING_CODE_TTL_SECONDS = 10 * 60L
private const val BINDING_CODE_LENGTH = 5
private const val BINDING_CODE_MAX_VALUE = 100_000
private val BINDING_CODE_REGEX = Regex("^\\d{5}$")
private val bindingCodeRandom = SecureRandom()

internal fun formatDoctorBindingCode(value: Int): String {
    require(value in 0 until BINDING_CODE_MAX_VALUE) { "value out of range for binding code" }
    return value.toString().padStart(BINDING_CODE_LENGTH, '0')
}

internal fun generateDoctorBindingCode(): String {
    val value = bindingCodeRandom.nextInt(BINDING_CODE_MAX_VALUE)
    return formatDoctorBindingCode(value)
}

internal fun isDoctorBindingCodeFormatValid(value: String): Boolean = BINDING_CODE_REGEX.matches(value)

internal fun normalizeAndValidateDoctorBindingCode(value: String): String {
    val normalized = value.trim()
    if (!isDoctorBindingCodeFormatValid(normalized)) {
        throw AppException(
            code = ErrorCodes.DOCTOR_BINDING_CODE_INVALID,
            message = "bindingCode must be 5 digits",
            status = HttpStatusCode.BadRequest
        )
    }
    return normalized
}

internal class DoctorBindingCodeDomainService {
    suspend fun generateBindingCode(doctorId: Long): GenerateBindingCodeResponse {
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val doctorRef = EntityID(doctorId, DoctorsTable)
            requireDoctor(doctorRef)
            val code = generateDoctorBindingCode()
            val expiresAt = now.plusSeconds(BINDING_CODE_TTL_SECONDS)
            DoctorBindingCodesTable.insert {
                it[DoctorBindingCodesTable.doctorId] = doctorRef
                it[DoctorBindingCodesTable.codeHash] = sha256Hex(code)
                it[DoctorBindingCodesTable.expiresAt] = expiresAt
                it[DoctorBindingCodesTable.consumedAt] = null
                it[DoctorBindingCodesTable.createdAt] = now
                it[DoctorBindingCodesTable.qrPayload] = ""
            }
            GenerateBindingCodeResponse(
                code = code,
                expiresAt = expiresAt.toIsoInstant()
            )
        }
    }

    fun findActiveCodeRowOrThrow(
        tx: org.jetbrains.exposed.sql.Transaction,
        code: String,
        now: LocalDateTime
    ): ResultRow {
        val codeHash = sha256Hex(code)
        return with(tx) {
            DoctorBindingCodesTable.selectAll().where {
                (DoctorBindingCodesTable.codeHash eq codeHash) and
                    DoctorBindingCodesTable.consumedAt.isNull() and
                    (DoctorBindingCodesTable.expiresAt greaterEq now)
            }.orderBy(DoctorBindingCodesTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
                ?: throw invalidBindingCode()
        }
    }

    fun consumeCodeRowOrThrow(
        tx: org.jetbrains.exposed.sql.Transaction,
        rowId: EntityID<Long>,
        consumedAt: LocalDateTime
    ) {
        val affected = with(tx) {
            DoctorBindingCodesTable.update({
                (DoctorBindingCodesTable.id eq rowId) and DoctorBindingCodesTable.consumedAt.isNull()
            }) {
                it[DoctorBindingCodesTable.consumedAt] = consumedAt
            }
        }
        if (affected <= 0) {
            throw invalidBindingCode()
        }
    }

    private fun invalidBindingCode(): AppException {
        return AppException(
            code = ErrorCodes.DOCTOR_BINDING_CODE_INVALID,
            message = "Binding code invalid or expired",
            status = HttpStatusCode.BadRequest
        )
    }
}
