package me.hztcm.mindisle.ema.api

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
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.ema.service.EmaService
import me.hztcm.mindisle.model.AnalyticsBatchRequest
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.CreateEmaRequest
import me.hztcm.mindisle.security.UserPrincipal
import me.hztcm.mindisle.state.service.PatientStateService
import me.hztcm.mindisle.task.service.UiTaskService
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.AppUsageEventsTable
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.UsersTable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insert

fun Route.registerEmaRoutes(
    emaService: EmaService,
    stateService: PatientStateService,
    uiTaskService: UiTaskService
) {
    authenticate("auth-jwt") {
        route("/users/me") {
            route("/ema") {
                post {
                    val data = emaService.submit(call.requireUserId(), call.receive())
                    call.respond(HttpStatusCode.Created, ApiResponse(data = data))
                }
                get {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                    val data = emaService.list(
                        userId = call.requireUserId(),
                        from = call.request.queryParameters["from"],
                        to = call.request.queryParameters["to"],
                        limit = limit
                    )
                    call.respond(ApiResponse(data = data))
                }
                get("/today") {
                    call.respond(ApiResponse(data = emaService.today(call.requireUserId())))
                }
            }
            get("/state/current") {
                call.respond(ApiResponse(data = stateService.current(call.requireUserId())))
            }
            post("/state/recompute") {
                call.respond(ApiResponse(data = stateService.recompute(call.requireUserId(), "MANUAL")))
            }
            get("/tasks") {
                call.respond(ApiResponse(data = uiTaskService.listPending(call.requireUserId())))
            }
            post("/tasks/{taskId}/done") {
                val taskId = call.parameters["taskId"]?.toLongOrNull()
                    ?: throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid taskId", HttpStatusCode.BadRequest)
                uiTaskService.markDone(call.requireUserId(), taskId)
                call.respond(ApiResponse<Unit>())
            }
            post("/analytics/events") {
                val body = call.receive<AnalyticsBatchRequest>()
                val userId = call.requireUserId()
                DatabaseFactory.dbQuery {
                    val userRef = EntityID(userId, UsersTable)
                    val now = utcNow()
                    val json = Json { encodeDefaults = true }
                    body.events.take(50).forEach { event ->
                        AppUsageEventsTable.insert {
                            it[AppUsageEventsTable.userId] = userRef
                            it[eventType] = event.eventType.take(64)
                            it[payloadJson] = json.encodeToString(event.payload)
                            it[createdAt] = now
                        }
                    }
                }
                call.respond(ApiResponse<Unit>(message = "Accepted"))
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
