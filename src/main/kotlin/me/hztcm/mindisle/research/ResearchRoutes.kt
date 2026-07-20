package me.hztcm.mindisle.research

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.ApiResponse

fun Route.registerResearchRoutes(researchService: ResearchService) {
    authenticate("doctor-auth-jwt") {
        route("/research") {
            post("/enrollments") {
                val data = researchService.enroll(call.receive())
                call.respond(HttpStatusCode.Created, ApiResponse(data = data))
            }
            post("/enrollments/{id}/randomize") {
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid id", HttpStatusCode.BadRequest)
                call.respond(ApiResponse(data = researchService.randomize(id)))
            }
            post("/enrollments/{id}/visits") {
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid id", HttpStatusCode.BadRequest)
                call.respond(ApiResponse(data = researchService.upsertVisit(id, call.receive())))
            }
            post("/enrollments/{id}/ae") {
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid id", HttpStatusCode.BadRequest)
                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse(data = researchService.createAe(id, call.receive()))
                )
            }
            post("/enrollments/{id}/qc") {
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid id", HttpStatusCode.BadRequest)
                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse(data = researchService.createQc(id, call.receive()))
                )
            }
            get("/export") {
                call.respond(ApiResponse(data = researchService.export()))
            }
        }
    }
}
