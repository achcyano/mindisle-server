package me.hztcm.mindisle

import io.github.cdimascio.dotenv.dotenv
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import me.hztcm.mindisle.ai.client.DeepSeekAliyunClient
import me.hztcm.mindisle.ai.service.AiChatService
import me.hztcm.mindisle.common.configureStatusPages
import me.hztcm.mindisle.config.AppConfig
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.medication.service.MedicationService
import me.hztcm.mindisle.scale.service.ScaleService
import me.hztcm.mindisle.security.JwtService
import me.hztcm.mindisle.security.configureAuth
import me.hztcm.mindisle.sms.createSmsGateway
import me.hztcm.mindisle.user.service.UserManagementService
import kotlinx.serialization.json.Json

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
                prettyPrint = DEBUG
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
    val aiChatService = AiChatService(AppConfig.llm, deepSeekClient)
    val scaleService = ScaleService(AppConfig.llm, deepSeekClient, AppConfig.scale)
    val medicationService = MedicationService()

    configureStatusPages()
    configureAuth(jwtService)
    configureRouting(userService, aiChatService, scaleService, medicationService)

    monitor.subscribe(ApplicationStopping) {
        runCatching { aiChatService.close() }
        runCatching { deepSeekClient.close() }
    }
}
