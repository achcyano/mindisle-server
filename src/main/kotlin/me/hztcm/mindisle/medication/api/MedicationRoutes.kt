package me.hztcm.mindisle.medication.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.medication.service.MedicationService
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.CreateMedicationRequest
import me.hztcm.mindisle.model.UpdateMedicationRequest
import me.hztcm.mindisle.security.UserPrincipal

fun Route.registerMedicationRoutes(service: MedicationService) {
    authenticate("auth-jwt") {
        route("/users/me/medications") {
            post {
                val request = call.receive<CreateMedicationRequest>()
                val data = service.createMedication(call.requireUserId(), request)
                call.respond(HttpStatusCode.Created, ApiResponse(data = data))
            }

            get {
                val limit = parseIntQuery(call, "limit", defaultValue = 50)
                val cursor = parseOptionalLongQuery(call, "cursor")
                val onlyActive = parseBooleanQuery(call, "onlyActive", defaultValue = false)
                val data = service.listMedications(
                    userId = call.requireUserId(),
                    limit = limit,
                    cursor = cursor,
                    onlyActive = onlyActive
                )
                call.respond(ApiResponse(data = data))
            }

            put("/{medicationId}") {
                val medicationId = call.requirePathLong("medicationId")
                val request = call.receive<UpdateMedicationRequest>()
                val data = service.updateMedication(call.requireUserId(), medicationId, request)
                call.respond(ApiResponse(data = data))
            }

            delete("/{medicationId}") {
                val medicationId = call.requirePathLong("medicationId")
                service.deleteMedication(call.requireUserId(), medicationId)
                call.respond(ApiResponse<Unit>())
            }
        }
    }
}

private fun ApplicationCall.requireUserId(): Long {
    return principal<UserPrincipal>()?.userId ?: throw AppException(
        code = ErrorCodes.UNAUTHORIZED,
        message = "Unauthorized",
        status = HttpStatusCode.Unauthorized
    )
}

private fun ApplicationCall.requirePathLong(name: String): Long {
    val value = parameters[name]?.toLongOrNull()
    if (value == null || value <= 0) {
        throw AppException(
            code = ErrorCodes.MEDICATION_INVALID_ARGUMENT,
            message = "Invalid path parameter: $name",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

private fun parseIntQuery(call: ApplicationCall, name: String, defaultValue: Int): Int {
    val raw = call.request.queryParameters[name] ?: return defaultValue
    val value = raw.toIntOrNull() ?: throw AppException(
        code = ErrorCodes.MEDICATION_INVALID_ARGUMENT,
        message = "$name must be integer",
        status = HttpStatusCode.BadRequest
    )
    if (value <= 0) {
        throw AppException(
            code = ErrorCodes.MEDICATION_INVALID_ARGUMENT,
            message = "$name must be positive",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

private fun parseOptionalLongQuery(call: ApplicationCall, name: String): Long? {
    val raw = call.request.queryParameters[name] ?: return null
    val value = raw.toLongOrNull() ?: throw AppException(
        code = ErrorCodes.MEDICATION_INVALID_ARGUMENT,
        message = "$name must be integer",
        status = HttpStatusCode.BadRequest
    )
    if (value <= 0L) {
        throw AppException(
            code = ErrorCodes.MEDICATION_INVALID_ARGUMENT,
            message = "$name must be positive",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

private fun parseBooleanQuery(call: ApplicationCall, name: String, defaultValue: Boolean): Boolean {
    val raw = call.request.queryParameters[name] ?: return defaultValue
    return when (raw.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> throw AppException(
            code = ErrorCodes.MEDICATION_INVALID_ARGUMENT,
            message = "$name must be boolean",
            status = HttpStatusCode.BadRequest
        )
    }
}
