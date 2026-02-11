package me.hztcm.mindisle.common

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import me.hztcm.mindisle.model.ApiResponse

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<AppException> { call, cause ->
            call.respond(
                cause.status,
                ApiResponse<Unit>(code = cause.code, message = cause.message)
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse<Unit>(code = ErrorCodes.INVALID_REQUEST, message = "Invalid request body")
            )
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception on path=${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse<Unit>(code = 50000, message = "Internal server error")
            )
        }
    }
}
