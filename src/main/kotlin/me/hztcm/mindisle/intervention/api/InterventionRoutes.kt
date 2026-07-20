package me.hztcm.mindisle.intervention.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.InterventionDeliveryStatus
import me.hztcm.mindisle.intervention.service.InterventionService
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.InterventionFeedbackRequest
import me.hztcm.mindisle.security.UserPrincipal
import me.hztcm.mindisle.state.service.PatientStateService

@Serializable
private data class StartInterventionRequest(
    val moduleCode: String,
    val triggerType: String = "USER"
)

fun Route.registerInterventionRoutes(
    interventionService: InterventionService,
    stateService: PatientStateService
) {
    authenticate("auth-jwt") {
        route("/users/me/interventions") {
            get("/modules") {
                call.respond(ApiResponse(data = interventionService.listModules()))
            }
            get("/modules/{code}") {
                val code = call.parameters["code"]
                    ?: throw AppException(ErrorCodes.INVALID_REQUEST, "code required", HttpStatusCode.BadRequest)
                call.respond(ApiResponse(data = interventionService.getModule(code)))
            }
            get("/pending") {
                call.respond(ApiResponse(data = interventionService.pending(call.requireUserId())))
            }
            post("/start") {
                val body = call.receive<StartInterventionRequest>()
                val data = interventionService.startModule(
                    userId = call.requireUserId(),
                    moduleCode = body.moduleCode,
                    triggerType = body.triggerType
                )
                call.respond(HttpStatusCode.Created, ApiResponse(data = data))
            }
            post("/match") {
                val state = stateService.recompute(call.requireUserId(), "INTERVENTION_MATCH")
                val items = interventionService.matchFromState(
                    userId = call.requireUserId(),
                    state = state,
                    triggerType = "STATE_MATCH"
                )
                call.respond(ApiResponse(data = items))
            }
            post("/{deliveryId}/start") {
                val id = call.requireDeliveryId()
                interventionService.updateStatus(call.requireUserId(), id, InterventionDeliveryStatus.STARTED)
                call.respond(ApiResponse<Unit>())
            }
            post("/{deliveryId}/feedback") {
                val id = call.requireDeliveryId()
                interventionService.feedback(call.requireUserId(), id, call.receive())
                call.respond(ApiResponse<Unit>())
            }
        }
    }
}

private fun ApplicationCall.requireUserId(): Long {
    return principal<UserPrincipal>()?.userId ?: throw AppException(
        ErrorCodes.UNAUTHORIZED,
        "Unauthorized",
        HttpStatusCode.Unauthorized
    )
}

private fun ApplicationCall.requireDeliveryId(): Long {
    return parameters["deliveryId"]?.toLongOrNull()
        ?: throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid deliveryId", HttpStatusCode.BadRequest)
}
