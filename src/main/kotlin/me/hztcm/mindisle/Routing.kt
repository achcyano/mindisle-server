package me.hztcm.mindisle

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import me.hztcm.mindisle.ai.api.registerAiRoutes
import me.hztcm.mindisle.ai.service.AiChatService
import me.hztcm.mindisle.ai.service.AiNlpService
import me.hztcm.mindisle.doctor.api.registerDoctorAdaptiveRoutes
import me.hztcm.mindisle.doctor.api.registerDoctorAuthRoutes
import me.hztcm.mindisle.doctor.api.registerDoctorRoutes
import me.hztcm.mindisle.doctor.service.DoctorService
import me.hztcm.mindisle.ema.api.registerEmaRoutes
import me.hztcm.mindisle.ema.service.EmaService
import me.hztcm.mindisle.event.api.registerEventRoutes
import me.hztcm.mindisle.event.service.EventService
import me.hztcm.mindisle.intervention.api.registerInterventionRoutes
import me.hztcm.mindisle.intervention.service.InterventionService
import me.hztcm.mindisle.medication.api.registerMedicationRoutes
import me.hztcm.mindisle.medication.service.DoseLogService
import me.hztcm.mindisle.medication.service.MedicationService
import me.hztcm.mindisle.safety.service.SafetyAlertService
import me.hztcm.mindisle.scale.api.registerScaleRoutes
import me.hztcm.mindisle.scale.api.registerScaleWebRoutes
import me.hztcm.mindisle.scale.service.ScaleService
import me.hztcm.mindisle.state.service.PatientStateService
import me.hztcm.mindisle.task.service.UiTaskService
import me.hztcm.mindisle.research.ResearchService
import me.hztcm.mindisle.research.registerResearchRoutes
import me.hztcm.mindisle.user.api.registerAuthRoutes
import me.hztcm.mindisle.user.api.registerUserRoutes
import me.hztcm.mindisle.user.service.UserManagementService

fun Application.configureRouting(
    userService: UserManagementService,
    aiChatService: AiChatService,
    scaleService: ScaleService,
    medicationService: MedicationService,
    doseLogService: DoseLogService,
    doctorService: DoctorService,
    eventService: EventService,
    emaService: EmaService,
    stateService: PatientStateService,
    uiTaskService: UiTaskService,
    interventionService: InterventionService,
    safetyAlertService: SafetyAlertService,
    nlpService: AiNlpService,
    researchService: ResearchService
) {
    routing {
        get("/") {
            call.respondText("MindIsle server is running.")
        }

        post("/test") {
            call.respondText("This is a test endpoint.")
        }

        registerScaleWebRoutes()

        route("/api/v1") {
            registerAuthRoutes(userService)
            registerUserRoutes(userService)
            registerMedicationRoutes(medicationService, doseLogService)
            registerEmaRoutes(emaService, stateService, uiTaskService)
            registerInterventionRoutes(interventionService, stateService)
            registerAiRoutes(aiChatService)
            registerScaleRoutes(scaleService)
            registerDoctorAuthRoutes(doctorService)
            registerDoctorRoutes(doctorService)
            registerDoctorAdaptiveRoutes(stateService, safetyAlertService, nlpService)
            registerEventRoutes(eventService)
            registerResearchRoutes(researchService)
        }
    }
}
