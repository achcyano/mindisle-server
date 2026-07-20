package me.hztcm.mindisle

import io.github.cdimascio.dotenv.dotenv
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.ai.agent.AiAgentOrchestrator
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.ai.context.PatientContextAssembler
import me.hztcm.mindisle.ai.service.AiChatService
import me.hztcm.mindisle.ai.service.AiNlpService
import me.hztcm.mindisle.ai.tools.buildDefaultToolRegistry
import me.hztcm.mindisle.common.configureStatusPages
import me.hztcm.mindisle.config.AppConfig
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.doctor.service.DoctorService
import me.hztcm.mindisle.ema.service.EmaService
import me.hztcm.mindisle.event.service.EventService
import me.hztcm.mindisle.intervention.service.InterventionService
import me.hztcm.mindisle.medication.service.DoseLogService
import me.hztcm.mindisle.medication.service.MedicationService
import me.hztcm.mindisle.safety.service.SafetyAlertService
import me.hztcm.mindisle.scale.service.ScaleService
import me.hztcm.mindisle.security.JwtService
import me.hztcm.mindisle.security.configureAuth
import me.hztcm.mindisle.sms.createSmsGateway
import me.hztcm.mindisle.state.service.PatientStateService
import me.hztcm.mindisle.task.service.UiTaskService
import me.hztcm.mindisle.user.service.UserManagementService

val dotenv = dotenv()
val DEBUG = dotenv["DEBUG"]?.toBoolean() ?: true

fun main() {
    embeddedServer(
        factory = Netty,
        port = dotenv["KTOR_HTTP_PORT"]?.toInt() ?: 8808,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
            }
        )
    }
    DatabaseFactory.init(AppConfig.db)
    val jwtService = JwtService(AppConfig.auth)
    val smsGateway = createSmsGateway(AppConfig.sms)
    val userService = UserManagementService(AppConfig.auth, jwtService, smsGateway)
    val deepSeekClient = DeepSeekAliyunClient(AppConfig.llm)

    val safetyAlertService = SafetyAlertService()
    val stateService = PatientStateService(safetyAlertService)
    val doseLogService = DoseLogService(stateService)
    val uiTaskService = UiTaskService()
    val interventionService = InterventionService(uiTaskService)
    val emaService = EmaService(stateService, interventionService)
    val contextAssembler = PatientContextAssembler(stateService, doseLogService)
    val toolRegistry = buildDefaultToolRegistry(
        stateService = stateService,
        interventionService = interventionService,
        uiTaskService = uiTaskService,
        doseLogService = doseLogService
    )
    val agentOrchestrator = AiAgentOrchestrator(AppConfig.llm, deepSeekClient, toolRegistry)
    val nlpService = AiNlpService(deepSeekClient)
    val aiChatService = AiChatService(
        config = AppConfig.llm,
        deepSeekClient = deepSeekClient,
        contextAssembler = contextAssembler,
        agentOrchestrator = agentOrchestrator,
        nlpService = nlpService,
        safetyAlertService = safetyAlertService,
        uiTaskService = uiTaskService
    )
    val scaleService = ScaleService(AppConfig.llm, deepSeekClient, AppConfig.scale)
    val medicationService = MedicationService()
    val doctorService = DoctorService(AppConfig.auth, AppConfig.llm, jwtService, smsGateway, deepSeekClient)
    val eventService = EventService()

    configureStatusPages()
    configureAuth(jwtService)
    configureRouting(
        userService = userService,
        aiChatService = aiChatService,
        scaleService = scaleService,
        medicationService = medicationService,
        doseLogService = doseLogService,
        doctorService = doctorService,
        eventService = eventService,
        emaService = emaService,
        stateService = stateService,
        uiTaskService = uiTaskService,
        interventionService = interventionService,
        safetyAlertService = safetyAlertService,
        nlpService = nlpService
    )

    monitor.subscribe(ApplicationStopping) {
        runCatching { aiChatService.close() }
        runCatching { deepSeekClient.close() }
    }
}
