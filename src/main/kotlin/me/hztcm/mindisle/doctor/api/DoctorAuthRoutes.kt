package me.hztcm.mindisle.doctor.api

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
import me.hztcm.mindisle.doctor.service.DoctorService
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.DoctorChangePasswordRequest
import me.hztcm.mindisle.model.DoctorLogoutRequest
import me.hztcm.mindisle.model.DoctorPasswordLoginRequest
import me.hztcm.mindisle.model.DoctorRegisterRequest
import me.hztcm.mindisle.model.DoctorResetPasswordRequest
import me.hztcm.mindisle.model.DoctorTokenRefreshRequest
import me.hztcm.mindisle.model.SendDoctorSmsCodeRequest
import me.hztcm.mindisle.security.DoctorPrincipal

private const val DEVICE_ID_HEADER = "X-Device-Id"

fun Route.registerDoctorAuthRoutes(service: DoctorService) {
    route("/doctor/auth") {
        post("/sms-codes") {
            val request = call.receive<SendDoctorSmsCodeRequest>()
            val requestIp = call.request.headers["X-Forwarded-For"]?.substringBefore(",")?.trim()
            service.sendSmsCode(request, requestIp)
            call.respond(HttpStatusCode.Accepted, ApiResponse<Unit>(message = "Accepted"))
        }

        post("/register") {
            val request = call.receive<DoctorRegisterRequest>()
            val deviceId = call.requireDeviceId(service)
            val data = service.register(request, deviceId)
            call.respond(HttpStatusCode.Created, ApiResponse(data = data))
        }

        post("/login/password") {
            val request = call.receive<DoctorPasswordLoginRequest>()
            val deviceId = call.requireDeviceId(service)
            val data = service.loginWithPassword(request, deviceId)
            call.respond(ApiResponse(data = data))
        }

        post("/token/refresh") {
            val request = call.receive<DoctorTokenRefreshRequest>()
            val deviceId = call.requireDeviceId(service)
            val data = service.refreshToken(request, deviceId)
            call.respond(ApiResponse(data = data))
        }

        post("/password/reset") {
            val request = call.receive<DoctorResetPasswordRequest>()
            service.resetPassword(request)
            call.respond(ApiResponse<Unit>(message = "Password reset success"))
        }

        authenticate("doctor-auth-jwt") {
            post("/password/change") {
                val principal = call.principal<DoctorPrincipal>() ?: throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "Unauthorized",
                    status = HttpStatusCode.Unauthorized
                )
                val request = call.receive<DoctorChangePasswordRequest>()
                service.changePassword(principal.doctorId, request)
                call.respond(ApiResponse<Unit>(message = "Password changed"))
            }

            post("/logout") {
                val principal = call.principal<DoctorPrincipal>() ?: throw AppException(
                    code = ErrorCodes.UNAUTHORIZED,
                    message = "Unauthorized",
                    status = HttpStatusCode.Unauthorized
                )
                val deviceId = call.requireDeviceId(service)
                val request = runCatching { call.receive<DoctorLogoutRequest>() }.getOrDefault(DoctorLogoutRequest())
                service.logout(principal.doctorId, deviceId, request)
                call.respond(ApiResponse<Unit>(message = "Logged out"))
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireDeviceId(service: DoctorService): String {
    val value = request.headers[DEVICE_ID_HEADER].orEmpty()
    return service.validateDeviceId(value)
}
