package me.hztcm.mindisle.scale.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.ScaleStatus
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.SaveScaleAnswerRequest
import me.hztcm.mindisle.model.ScaleAssistStreamRequest
import me.hztcm.mindisle.scale.service.ScaleAssistStreamEventRecord
import me.hztcm.mindisle.scale.service.ScaleService
import me.hztcm.mindisle.security.UserPrincipal

fun Route.registerScaleRoutes(service: ScaleService) {
    authenticate("auth-jwt") {
        route("/scales") {
            get {
                val principal = call.requirePrincipal()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val cursor = call.request.queryParameters["cursor"]
                val status = call.request.queryParameters["status"]?.let(::parseScaleStatus)
                val data = service.listScales(userId = principal.userId, limit = limit, cursor = cursor, status = status)
                call.respond(ApiResponse(data = data))
            }

            get("/history") {
                val principal = call.requirePrincipal()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val cursor = call.request.queryParameters["cursor"]
                val data = service.listHistory(principal.userId, limit, cursor)
                call.respond(ApiResponse(data = data))
            }

            post("/assist/stream") {
                val principal = call.requirePrincipal()
                val request = call.receive<ScaleAssistStreamRequest>()
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.response.header(HttpHeaders.Connection, "keep-alive")
                call.response.header("X-Accel-Buffering", "no")
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    service.streamAssist(principal.userId, request) { event ->
                        writeEvent(event)
                    }
                }
            }

            post("/{scaleId}/sessions") {
                val principal = call.requirePrincipal()
                val scaleId = call.requirePathLong("scaleId")
                val data = service.createOrResumeSession(principal.userId, scaleId)
                val status = if (data.created) HttpStatusCode.Created else HttpStatusCode.OK
                call.respond(status, ApiResponse(data = data))
            }

            route("/sessions/{sessionId}") {
                get {
                    val principal = call.requirePrincipal()
                    val sessionId = call.requirePathLong("sessionId")
                    val data = service.getSessionDetail(principal.userId, sessionId)
                    call.respond(ApiResponse(data = data))
                }

                put("/answers/{questionId}") {
                    val principal = call.requirePrincipal()
                    val sessionId = call.requirePathLong("sessionId")
                    val questionId = call.requirePathLong("questionId")
                    val request = call.receive<SaveScaleAnswerRequest>()
                    val data = service.saveAnswer(
                        userId = principal.userId,
                        sessionId = sessionId,
                        questionId = questionId,
                        request = request
                    )
                    call.respond(ApiResponse(data = data))
                }

                post("/submit") {
                    val principal = call.requirePrincipal()
                    val sessionId = call.requirePathLong("sessionId")
                    val data = service.submitSession(principal.userId, sessionId)
                    call.respond(ApiResponse(data = data))
                }

                get("/result") {
                    val principal = call.requirePrincipal()
                    val sessionId = call.requirePathLong("sessionId")
                    val data = service.getResult(principal.userId, sessionId)
                    call.respond(ApiResponse(data = data))
                }

                delete {
                    val principal = call.requirePrincipal()
                    val sessionId = call.requirePathLong("sessionId")
                    service.deleteDraftSession(principal.userId, sessionId)
                    call.respond(ApiResponse<Unit>())
                }
            }

            get("/{scaleRef}") {
                call.requirePrincipal()
                val scaleRef = call.parameters["scaleRef"]?.trim().orEmpty()
                if (scaleRef.isEmpty()) {
                    throw AppException(
                        code = ErrorCodes.SCALE_INVALID_ARGUMENT,
                        message = "Invalid path parameter: scaleRef",
                        status = HttpStatusCode.BadRequest
                    )
                }
                val data = service.getScaleDetail(scaleRef)
                call.respond(ApiResponse(data = data))
            }
        }
    }
}

private suspend fun java.io.Writer.writeEvent(event: ScaleAssistStreamEventRecord) {
    write("id: ${event.eventId}\n")
    write("event: ${event.eventType}\n")
    write("data: ${event.eventJson}\n\n")
    flush()
}

private fun parseScaleStatus(raw: String): ScaleStatus {
    return runCatching { ScaleStatus.valueOf(raw.trim().uppercase()) }.getOrElse {
        throw AppException(
            code = ErrorCodes.SCALE_INVALID_ARGUMENT,
            message = "status must be one of DRAFT/PUBLISHED/ARCHIVED",
            status = HttpStatusCode.BadRequest
        )
    }
}

private fun ApplicationCall.requirePrincipal(): UserPrincipal {
    return principal<UserPrincipal>() ?: throw AppException(
        code = ErrorCodes.UNAUTHORIZED,
        message = "Unauthorized",
        status = HttpStatusCode.Unauthorized
    )
}

private fun ApplicationCall.requirePathLong(name: String): Long {
    val value = parameters[name]?.toLongOrNull()
    if (value == null || value <= 0) {
        throw AppException(
            code = ErrorCodes.SCALE_INVALID_ARGUMENT,
            message = "Invalid path parameter: $name",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}
