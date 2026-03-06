package me.hztcm.mindisle.event.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.event.service.EventService
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.security.UserPrincipal

fun Route.registerEventRoutes(service: EventService) {
    authenticate("auth-jwt") {
        route("/users/me") {
            get("/events") {
                val data = service.listEvents(call.requireUserId())
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
