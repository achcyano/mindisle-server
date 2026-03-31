package me.hztcm.mindisle.doctor.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.doctor.service.DoctorService
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.CreateMedicationRequest
import me.hztcm.mindisle.model.CreateSideEffectRequest
import me.hztcm.mindisle.model.CreateDoctorPatientGroupRequest
import me.hztcm.mindisle.model.DoctorPatientSortBy
import me.hztcm.mindisle.model.DoctorPatientSortOrder
import me.hztcm.mindisle.model.GenerateAssessmentReportRequest
import me.hztcm.mindisle.model.Gender
import me.hztcm.mindisle.model.ListDoctorPatientsQuery
import me.hztcm.mindisle.model.PatientBindDoctorRequest
import me.hztcm.mindisle.model.UpdateMedicationRequest
import me.hztcm.mindisle.model.UpdatePatientDiagnosisRequest
import me.hztcm.mindisle.model.UpdatePatientGroupingRequest
import me.hztcm.mindisle.model.DoctorThresholdSettingsRequest
import me.hztcm.mindisle.model.UpsertDoctorProfileRequest
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

            put("/profile") {
                val request = call.receive<UpsertDoctorProfileRequest>()
                val data = service.upsertProfile(call.requireDoctorId(), request)
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

            get("/patient-groups") {
                val data = service.listPatientGroups(call.requireDoctorId())
                call.respond(ApiResponse(data = data))
            }

            post("/patient-groups") {
                val request = call.receive<CreateDoctorPatientGroupRequest>()
                val data = service.createPatientGroup(call.requireDoctorId(), request)
                call.respond(HttpStatusCode.Created, ApiResponse(data = data))
            }

            get("/patients/export") {
                val data = service.exportDoctorPatients(call.requireDoctorId())
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${data.fileName}\"")
                call.respondBytes(
                    bytes = data.zipBytes,
                    contentType = ContentType.Application.Zip,
                    status = HttpStatusCode.OK
                )
            }

            get("/patients") {
                val limit = parseIntQuery(call, "limit", 20, 1, 50)
                val cursor = parseOpaqueCursorQuery(call, "cursor")
                val keyword = call.request.queryParameters["keyword"]?.trim()?.takeIf { it.isNotEmpty() }
                val gender = parseOptionalGenderQuery(call, "gender")
                val severityGroup = call.request.queryParameters["severityGroup"]?.trim()?.takeIf { it.isNotEmpty() }
                val abnormalOnly = parseBooleanQuery(call, "abnormalOnly", false)
                val scl90ScoreMin = parseOptionalDoubleQuery(call, "scl90ScoreMin")
                val scl90ScoreMax = parseOptionalDoubleQuery(call, "scl90ScoreMax")
                validateUnsupportedAdherenceQueries(call)
                validateUnsupportedTreatmentPhaseQuery(call)
                val sortBy = parsePatientSortByQuery(call, "sortBy", DoctorPatientSortBy.LATEST_ASSESSMENT_AT)
                val sortOrder = parsePatientSortOrderQuery(call, "sortOrder", DoctorPatientSortOrder.DESC)
                if (scl90ScoreMin != null && scl90ScoreMax != null && scl90ScoreMin > scl90ScoreMax) {
                    throw AppException(
                        code = ErrorCodes.DOCTOR_FILTER_INVALID,
                        message = "scl90ScoreMin cannot be greater than scl90ScoreMax",
                        status = HttpStatusCode.BadRequest
                    )
                }
                val data = service.listDoctorPatients(
                    doctorId = call.requireDoctorId(),
                    query = ListDoctorPatientsQuery(
                        limit = limit,
                        cursor = cursor,
                        keyword = keyword,
                        gender = gender,
                        severityGroup = severityGroup,
                        abnormalOnly = abnormalOnly,
                        scl90ScoreMin = scl90ScoreMin,
                        scl90ScoreMax = scl90ScoreMax,
                        sortBy = sortBy,
                        sortOrder = sortOrder
                    )
                )
                call.respond(ApiResponse(data = data))
            }

            put("/patients/{patientUserId}/grouping") {
                val patientUserId = call.requirePathLong("patientUserId")
                val request = parseUpdatePatientGroupingRequest(call)
                val data = service.updatePatientGrouping(call.requireDoctorId(), patientUserId, request)
                call.respond(ApiResponse(data = data))
            }

            put("/patients/{patientUserId}/diagnosis") {
                val patientUserId = call.requirePathLong("patientUserId")
                val request = call.receive<UpdatePatientDiagnosisRequest>()
                val data = service.updatePatientDiagnosis(call.requireDoctorId(), patientUserId, request)
                call.respond(ApiResponse(data = data))
            }

            get("/patients/{patientUserId}/grouping-history") {
                val patientUserId = call.requirePathLong("patientUserId")
                val limit = parseIntQuery(call, "limit", 20, 1, 100)
                val cursor = parseOptionalLongQuery(call, "cursor")
                val data = service.listGroupingChanges(call.requireDoctorId(), patientUserId, limit, cursor)
                call.respond(ApiResponse(data = data))
            }

            get("/patients/{patientUserId}/profile") {
                val patientUserId = call.requirePathLong("patientUserId")
                val data = service.getPatientProfile(call.requireDoctorId(), patientUserId)
                call.respond(ApiResponse(data = data))
            }

            get("/patients/{patientUserId}/scale-history") {
                val patientUserId = call.requirePathLong("patientUserId")
                val limit = parseIntQuery(call, "limit", 20, 1, 100)
                val cursor = call.request.queryParameters["cursor"]?.trim()?.takeIf { it.isNotEmpty() }
                val data = service.listPatientScaleHistory(call.requireDoctorId(), patientUserId, limit, cursor)
                call.respond(ApiResponse(data = data))
            }

            get("/patients/{patientUserId}/scales/sessions/{sessionId}/result") {
                val patientUserId = call.requirePathLong("patientUserId")
                val sessionId = call.requirePathLong("sessionId")
                val data = service.getPatientScaleSessionResult(
                    doctorId = call.requireDoctorId(),
                    patientUserId = patientUserId,
                    sessionId = sessionId
                )
                call.respond(ApiResponse(data = data))
            }

            post("/patients/{patientUserId}/assessment-report") {
                val patientUserId = call.requirePathLong("patientUserId")
                val request = runCatching { call.receive<GenerateAssessmentReportRequest>() }
                    .getOrDefault(GenerateAssessmentReportRequest())
                val data = service.generateAssessmentReport(call.requireDoctorId(), patientUserId, request)
                call.respond(ApiResponse(data = data))
            }

            route("/patients/{patientUserId}/assessment-reports") {
                get("/latest") {
                    val patientUserId = call.requirePathLong("patientUserId")
                    val data = service.getLatestAssessmentReport(call.requireDoctorId(), patientUserId)
                    call.respond(ApiResponse(data = data))
                }

                get {
                    val patientUserId = call.requirePathLong("patientUserId")
                    val limit = parseIntQuery(call, "limit", 20, 1, 100)
                    val cursor = parseOptionalLongQuery(call, "cursor")
                    val data = service.listAssessmentReports(call.requireDoctorId(), patientUserId, limit, cursor)
                    call.respond(ApiResponse(data = data))
                }

                get("/{reportId}") {
                    val patientUserId = call.requirePathLong("patientUserId")
                    val reportId = call.requirePathLong("reportId")
                    val data = service.getAssessmentReportDetail(call.requireDoctorId(), patientUserId, reportId)
                    call.respond(ApiResponse(data = data))
                }
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

private fun parseOpaqueCursorQuery(call: ApplicationCall, name: String): String? {
    val raw = call.request.queryParameters[name] ?: return null
    val value = raw.trim()
    if (value.isEmpty()) {
        throw AppException(
            code = ErrorCodes.DOCTOR_CURSOR_INVALID,
            message = "$name cannot be blank",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

private fun parseOptionalDoubleQuery(call: ApplicationCall, name: String): Double? {
    val raw = call.request.queryParameters[name] ?: return null
    val value = raw.toDoubleOrNull() ?: throw AppException(
        code = ErrorCodes.DOCTOR_FILTER_INVALID,
        message = "$name must be number",
        status = HttpStatusCode.BadRequest
    )
    if (!value.isFinite()) {
        throw AppException(
            code = ErrorCodes.DOCTOR_FILTER_INVALID,
            message = "$name must be finite",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

private fun parseOptionalGenderQuery(call: ApplicationCall, name: String): Gender? {
    val raw = call.request.queryParameters[name] ?: return null
    return runCatching { Gender.valueOf(raw.trim().uppercase()) }.getOrElse {
        throw AppException(
            code = ErrorCodes.DOCTOR_FILTER_INVALID,
            message = "$name must be one of UNKNOWN|MALE|FEMALE|OTHER",
            status = HttpStatusCode.BadRequest
        )
    }
}

private fun parsePatientSortByQuery(call: ApplicationCall, name: String, defaultValue: DoctorPatientSortBy): DoctorPatientSortBy {
    val raw = call.request.queryParameters[name] ?: return defaultValue
    return when (raw.trim()) {
        "latestAssessmentAt" -> DoctorPatientSortBy.LATEST_ASSESSMENT_AT
        "scl90Score" -> DoctorPatientSortBy.SCL90_SCORE
        "adherenceRate", "missedDoseRate" -> throw AppException(
            code = ErrorCodes.DOCTOR_FEATURE_NOT_SUPPORTED,
            message = "sortBy=$raw is not supported in current version",
            status = HttpStatusCode.BadRequest
        )
        else -> throw AppException(
            code = ErrorCodes.DOCTOR_SORT_INVALID,
            message = "Unsupported sortBy value: $raw",
            status = HttpStatusCode.BadRequest
        )
    }
}

private fun parsePatientSortOrderQuery(call: ApplicationCall, name: String, defaultValue: DoctorPatientSortOrder): DoctorPatientSortOrder {
    val raw = call.request.queryParameters[name] ?: return defaultValue
    return when (raw.trim().lowercase()) {
        "asc" -> DoctorPatientSortOrder.ASC
        "desc" -> DoctorPatientSortOrder.DESC
        else -> throw AppException(
            code = ErrorCodes.DOCTOR_SORT_INVALID,
            message = "Unsupported sortOrder value: $raw",
            status = HttpStatusCode.BadRequest
        )
    }
}

private fun validateUnsupportedAdherenceQueries(call: ApplicationCall) {
    val unsupportedNames = listOf("adherenceRateMin", "adherenceRateMax", "missedDoseRateMin", "missedDoseRateMax")
    val hit = unsupportedNames.firstOrNull { call.request.queryParameters[it] != null } ?: return
    throw AppException(
        code = ErrorCodes.DOCTOR_FEATURE_NOT_SUPPORTED,
        message = "$hit is not supported in current version",
        status = HttpStatusCode.BadRequest
    )
}

private fun validateUnsupportedTreatmentPhaseQuery(call: ApplicationCall) {
    if (call.request.queryParameters["treatmentPhase"] == null) {
        return
    }
    throw AppException(
        code = ErrorCodes.DOCTOR_FEATURE_NOT_SUPPORTED,
        message = "treatmentPhase is not supported in current version",
        status = HttpStatusCode.BadRequest
    )
}

private suspend fun parseUpdatePatientGroupingRequest(call: ApplicationCall): UpdatePatientGroupingRequest {
    val payload = call.receive<JsonObject>()
    if (payload.containsKey("treatmentPhase")) {
        throw AppException(
            code = ErrorCodes.DOCTOR_FEATURE_NOT_SUPPORTED,
            message = "treatmentPhase is not supported in current version",
            status = HttpStatusCode.BadRequest
        )
    }
    if (payload.containsKey("reason")) {
        throw AppException(
            code = ErrorCodes.DOCTOR_FEATURE_NOT_SUPPORTED,
            message = "reason is not supported in current version",
            status = HttpStatusCode.BadRequest
        )
    }
    return UpdatePatientGroupingRequest(
        severityGroup = parseNullableStringField(payload, "severityGroup")
    )
}

private fun parseNullableStringField(payload: JsonObject, fieldName: String): String? {
    val element = payload[fieldName] ?: return null
    if (element is JsonNull) return null
    if (element !is JsonPrimitive || !element.isString) {
        throw AppException(
            code = ErrorCodes.DOCTOR_INVALID_ARGUMENT,
            message = "$fieldName must be string or null",
            status = HttpStatusCode.BadRequest
        )
    }
    return element.content
}
