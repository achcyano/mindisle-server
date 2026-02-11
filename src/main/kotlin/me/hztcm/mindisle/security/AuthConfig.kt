package me.hztcm.mindisle.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.ApiResponse

data class UserPrincipal(
    val userId: Long,
    val deviceId: String?
)

fun Application.configureAuth(jwtService: JwtService) {
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(jwtService.verifier)
            validate { credential ->
                val userId = credential.payload.getClaim("uid").asLong()
                val deviceId = credential.payload.getClaim("did").asString()
                if (userId != null) {
                    UserPrincipal(userId = userId, deviceId = deviceId)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse<Unit>(
                        code = ErrorCodes.UNAUTHORIZED,
                        message = "Unauthorized"
                    )
                )
            }
        }
    }
}
