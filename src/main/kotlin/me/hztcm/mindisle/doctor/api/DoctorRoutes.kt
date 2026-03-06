package me.hztcm.mindisle.doctor.api

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
import me.hztcm.mindisle.doctor.service.DoctorService
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.CreateMedicationRequest
import me.hztcm.mindisle.model.CreateSideEffectRequest
import me.hztcm.mindisle.model.GenerateAssessmentReportRequest
import me.hztcm.mindisle.model.PatientBindDoctorRequest
import me.hztcm.mindisle.model.UpdateMedicationRequest
import me.hztcm.mindisle.model.UpdatePatientGroupingRequest
import me.hztcm.mindisle.model.DoctorThresholdSettingsRequest
import me.hztcm.mindisle.security.DoctorPrincipal
import me.hztcm.mindisle.security.UserPrincipal

fun Route.registerDoctorRoutes(service: DoctorService) {
    authenticate("auth-jwt") {
        route("/users/me") {
            get("/doctor-binding") {
                val data = service.getPatientBindingStatus(call.requireUserId())
                call.respond(ApiResponse(data = data))
            }

            post("/doctor-binding/bind") {
                val request = call.receive<PatientBindDoctorRequest>()
                val data = service.bindPatientToDoctor(call.requireUserId(), request)
                call.respond(ApiResponse(data = data))
            }

            post("/doctor-binding/unbind") {
                val data = service.unbindPatientDoctor(call.requireUserId())
                call.respond(ApiResponse(data = data))
            }

            get("/doctor-binding/history") {
                val limit = parseIntQuery(call, "limit", 20, 1, 100)
                val cursor = parseOptionalLongQuery(call, "cursor")
                val data = service.listPatientBindingHistory(call.requireUserId(), limit, cursor)
                call.respond(ApiResponse(data = data))
            }

            route("/side-effects") {
                post {
                    val request = call.receive<CreateSideEffectRequest>()
                    val data = service.createSideEffect(call.requireUserId(), request)
                    call.respond(HttpStatusCode.Created, ApiResponse(data = data))
                }

                get {
                    val limit = parseIntQuery(call, "limit", 20, 1, 100)
                    val cursor = parseOptionalLongQuery(call, "cursor")
                    val data = service.listUserSideEffects(call.requireUserId(), limit, cursor)
                    call.respond(ApiResponse(data = data))
                }
            }
        }
    }

    authenticate("doctor-auth-jwt") {
        route("/doctors/me") {
            get("/profile") {
                val data = service.getProfile(call.requireDoctorId())
                call.respond(ApiResponse(data = data))
            }

            get("/thresholds") {
                val data = service.getThresholdSettings(call.requireDoctorId())
                call.respond(ApiResponse(data = data))
            }

            put("/thresholds") {
                val request = call.receive<DoctorThresholdSettingsRequest>()
                val data = service.upsertThresholdSettings(call.requireDoctorId(), request)
                call.respond(ApiResponse(data = data))
            }

            post("/binding-codes") {
                val data = service.generateBindingCode(call.requireDoctorId())
                call.respond(HttpStatusCode.Created, ApiResponse(data = data))
            }

            get("/binding-history") {
                val limit = parseIntQuery(call, "limit", 20, 1, 100)
                val cursor = parseOptionalLongQuery(call, "cursor")
                val patientUserId = parseOptionalLongQuery(call, "patientUserId")
                val data = service.listDoctorBindingHistory(call.requireDoctorId(), limit, cursor, patientUserId)
                call.respond(ApiResponse(data = data))
            }

            get("/patients") {
                val limit = parseIntQuery(call, "limit", 20, 1, 50)
                val cursor = parseOptionalLongQuery(call, "cursor")
                val keyword = call.request.queryParameters["keyword"]?.trim()?.takeIf { it.isNotEmpty() }
                val abnormalOnly = parseBooleanQuery(call, "abnormalOnly", false)
                val data = service.listDoctorPatients(
                    doctorId = call.requireDoctorId(),
                    limit = limit,
                    cursor = cursor,
                    keyword = keyword,
                    abnormalOnly = abnormalOnly
                )
                call.respond(ApiResponse(data = data))
            }

            put("/patients/{patientUserId}/grouping") {
                val patientUserId = call.requirePathLong("patientUserId")
                val request = call.receive<UpdatePatientGroupingRequest>()
                val data = service.updatePatientGrouping(call.requireDoctorId(), patientUserId, request)
                call.respond(ApiResponse(data = data))
            }

            get("/patients/{patientUserId}/grouping-history") {
                val patientUserId = call.requirePathLong("patientUserId")
                val limit = parseIntQuery(call, "limit", 20, 1, 100)
                val cursor = parseOptionalLongQuery(call, "cursor")
                val data = service.listGroupingChanges(call.requireDoctorId(), patientUserId, limit, cursor)
                call.respond(ApiResponse(data = data))
            }

            get("/patients/{patientUserId}/scale-trends") {
                val patientUserId = call.requirePathLong("patientUserId")
                val days = parseOptionalIntQuery(call, "days")
                val data = service.getPatientScaleTrends(call.requireDoctorId(), patientUserId, days)
                call.respond(ApiResponse(data = data))
            }

            post("/patients/{patientUserId}/assessment-report") {
                val patientUserId = call.requirePathLong("patientUserId")
                val request = runCatching { call.receive<GenerateAssessmentReportRequest>() }
                    .getOrDefault(GenerateAssessmentReportRequest())
                val data = service.generateAssessmentReport(call.requireDoctorId(), patientUserId, request)
                call.respond(ApiResponse(data = data))
            }

            route("/patients/{patientUserId}/medications") {
                post {
                    val patientUserId = call.requirePathLong("patientUserId")
                    val request = call.receive<CreateMedicationRequest>()
                    val data = service.createPatientMedication(call.requireDoctorId(), patientUserId, request)
                    call.respond(HttpStatusCode.Created, ApiResponse(data = data))
                }

                get {
                    val patientUserId = call.requirePathLong("patientUserId")
                    val limit = parseIntQuery(call, "limit", 50, 1, 200)
                    val cursor = parseOptionalLongQuery(call, "cursor")
                    val onlyActive = parseBooleanQuery(call, "onlyActive", false)
                    val data = service.listPatientMedications(
                        doctorId = call.requireDoctorId(),
                        patientUserId = patientUserId,
                        limit = limit,
                        cursor = cursor,
                        onlyActive = onlyActive
                    )
                    call.respond(ApiResponse(data = data))
                }

                put("/{medicationId}") {
                    val patientUserId = call.requirePathLong("patientUserId")
                    val medicationId = call.requirePathLong("medicationId")
                    val request = call.receive<UpdateMedicationRequest>()
                    val data = service.updatePatientMedication(call.requireDoctorId(), patientUserId, medicationId, request)
                    call.respond(ApiResponse(data = data))
                }

                delete("/{medicationId}") {
                    val patientUserId = call.requirePathLong("patientUserId")
                    val medicationId = call.requirePathLong("medicationId")
                    service.deletePatientMedication(call.requireDoctorId(), patientUserId, medicationId)
                    call.respond(ApiResponse<Unit>())
                }
            }

            get("/patients/{patientUserId}/side-effects/summary") {
                val patientUserId = call.requirePathLong("patientUserId")
                val days = parseOptionalIntQuery(call, "days")
                val data = service.summarizePatientSideEffects(call.requireDoctorId(), patientUserId, days)
                call.respond(ApiResponse(data = data))
            }

            get("/patients/{patientUserId}/weight-trend") {
                val patientUserId = call.requirePathLong("patientUserId")
                val days = parseOptionalIntQuery(call, "days")
                val data = service.getPatientWeightTrend(call.requireDoctorId(), patientUserId, days)
                call.respond(ApiResponse(data = data))
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

private fun ApplicationCall.requireDoctorId(): Long {
    return principal<DoctorPrincipal>()?.doctorId ?: throw AppException(
        code = ErrorCodes.UNAUTHORIZED,
        message = "Unauthorized",
        status = HttpStatusCode.Unauthorized
    )
}

private fun ApplicationCall.requirePathLong(name: String): Long {
    val value = parameters[name]?.toLongOrNull()
    if (value == null || value <= 0L) {
        throw AppException(
            code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
            message = "Invalid path parameter: $name",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

private fun parseIntQuery(call: ApplicationCall, name: String, defaultValue: Int, min: Int, max: Int): Int {
    val raw = call.request.queryParameters[name] ?: return defaultValue
    val value = raw.toIntOrNull() ?: throw AppException(
        code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
        message = "$name must be integer",
        status = HttpStatusCode.BadRequest
    )
    if (value !in min..max) {
        throw AppException(
            code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
            message = "$name must be in range $min..$max",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

private fun parseOptionalIntQuery(call: ApplicationCall, name: String): Int? {
    val raw = call.request.queryParameters[name] ?: return null
    return raw.toIntOrNull() ?: throw AppException(
        code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
        message = "$name must be integer",
        status = HttpStatusCode.BadRequest
    )
}

private fun parseOptionalLongQuery(call: ApplicationCall, name: String): Long? {
    val raw = call.request.queryParameters[name] ?: return null
    val value = raw.toLongOrNull() ?: throw AppException(
        code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
        message = "$name must be integer",
        status = HttpStatusCode.BadRequest
    )
    if (value <= 0L) {
        throw AppException(
            code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
            message = "$name must be positive",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

private fun parseBooleanQuery(call: ApplicationCall, name: String, defaultValue: Boolean): Boolean {
    val raw = call.request.queryParameters[name] ?: return defaultValue
    return when (raw.trim().lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> throw AppException(
            code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
            message = "$name must be boolean",
            status = HttpStatusCode.BadRequest
        )
    }
}
