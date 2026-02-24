package me.hztcm.mindisle

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import me.hztcm.mindisle.ai.api.registerAiRoutes
import me.hztcm.mindisle.ai.service.AiChatService
import me.hztcm.mindisle.scale.api.registerScaleRoutes
import me.hztcm.mindisle.scale.service.ScaleService
import me.hztcm.mindisle.user.api.registerAuthRoutes
import me.hztcm.mindisle.user.api.registerUserRoutes
import me.hztcm.mindisle.user.service.UserManagementService

fun Application.configureRouting(
    userService: UserManagementService,
    aiChatService: AiChatService,
    scaleService: ScaleService
) {
    routing {
        get("/") {
            call.respondText("MindIsle server is running.")
        }

        post("/test") {
            call.respondText("This is a test endpoint.")
        }

        route("/api/v1") {
            registerAuthRoutes(userService)
            registerUserRoutes(userService)
            registerAiRoutes(aiChatService)
            registerScaleRoutes(scaleService)
        }
    }
}
