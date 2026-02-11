package me.hztcm.mindisle.user.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.UpsertProfileRequest
import me.hztcm.mindisle.security.UserPrincipal
import me.hztcm.mindisle.user.service.UserManagementService

fun Route.registerUserRoutes(service: UserManagementService) {
    authenticate("auth-jwt") {
        route("/users/me") {
            get {
                val principal = call.principal<UserPrincipal>()
                    ?: throw AppException(
                        code = ErrorCodes.UNAUTHORIZED,
                        message = "Unauthorized",
                        status = HttpStatusCode.Unauthorized
                    )
                val data = service.getProfile(principal.userId)
                call.respond(ApiResponse(data = data))
            }

            put("/profile") {
                val principal = call.principal<UserPrincipal>()
                    ?: throw AppException(
                        code = ErrorCodes.UNAUTHORIZED,
                        message = "Unauthorized",
                        status = HttpStatusCode.Unauthorized
                    )
                val request = call.receive<UpsertProfileRequest>()
                val data = service.upsertProfile(principal.userId, request)
                call.respond(ApiResponse(data = data))
            }
        }
    }
}
