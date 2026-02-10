package me.hztcm

import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

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
    configureRouting()
}
