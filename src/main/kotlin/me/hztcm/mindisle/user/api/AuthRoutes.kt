package me.hztcm.mindisle.user.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.DirectLoginRequest
import me.hztcm.mindisle.model.LoginCheckRequest
import me.hztcm.mindisle.model.LogoutRequest
import me.hztcm.mindisle.model.PasswordLoginRequest
import me.hztcm.mindisle.model.RegisterRequest
import me.hztcm.mindisle.model.ResetPasswordRequest
import me.hztcm.mindisle.model.SendSmsCodeRequest
import me.hztcm.mindisle.model.TokenRefreshRequest
import me.hztcm.mindisle.security.UserPrincipal
import me.hztcm.mindisle.user.service.UserManagementService

private const val DEVICE_ID_HEADER = "X-Device-Id"

fun Route.registerAuthRoutes(service: UserManagementService) {
    route("/auth") {
        post("/sms-codes") {
            val request = call.receive<SendSmsCodeRequest>()
            val requestIp = call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
            service.sendSmsCode(request, requestIp)
            call.respond(HttpStatusCode.Accepted, ApiResponse<Unit>(message = "Accepted"))
        }

        post("/register") {
            val deviceId = call.requireDeviceId()
            val request = call.receive<RegisterRequest>()
            val data = service.register(request, deviceId)
            call.respond(HttpStatusCode.Created, ApiResponse(data = data))
        }

        post("/login/check") {
            val deviceId = call.requireDeviceId()
            val request = call.receive<LoginCheckRequest>()
            val data = service.loginCheck(request, deviceId)
            call.respond(ApiResponse(data = data))
        }

        post("/login/direct") {
            val deviceId = call.requireDeviceId()
            val request = call.receive<DirectLoginRequest>()
            val data = service.loginDirect(request, deviceId)
            call.respond(ApiResponse(data = data))
        }

        post("/login/password") {
            val deviceId = call.requireDeviceId()
            val request = call.receive<PasswordLoginRequest>()
            val data = service.loginWithPassword(request, deviceId)
            call.respond(ApiResponse(data = data))
        }

        post("/token/refresh") {
            val deviceId = call.requireDeviceId()
            val request = call.receive<TokenRefreshRequest>()
            val data = service.refreshToken(request, deviceId)
            call.respond(ApiResponse(data = data))
        }

        post("/password/reset") {
            val request = call.receive<ResetPasswordRequest>()
            service.resetPassword(request)
            call.respond(ApiResponse<Unit>(message = "Password reset success"))
        }

        authenticate("auth-jwt") {
            post("/logout") {
                val principal = call.principal<UserPrincipal>()
                    ?: throw AppException(
                        code = ErrorCodes.UNAUTHORIZED,
                        message = "Unauthorized",
                        status = HttpStatusCode.Unauthorized
                    )
                val deviceId = call.requireDeviceId()
                val request = runCatching { call.receive<LogoutRequest>() }.getOrDefault(LogoutRequest())
                service.logout(principal.userId, deviceId, request.refreshToken)
                call.respond(ApiResponse<Unit>(message = "Logged out"))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireDeviceId(): String {
    val value = request.headers[DEVICE_ID_HEADER]?.trim().orEmpty()
    if (value.isEmpty()) {
        throw AppException(
            code = ErrorCodes.INVALID_REQUEST,
            message = "Missing required header: $DEVICE_ID_HEADER",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}

