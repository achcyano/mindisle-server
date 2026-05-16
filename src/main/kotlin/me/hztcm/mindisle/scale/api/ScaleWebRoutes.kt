package me.hztcm.mindisle.scale.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.registerScaleWebRoutes() {
    route("/web/scales") {
        get("/TESS") {
            val html = Thread.currentThread()
                .contextClassLoader
                .getResource("web/tess.html")
                ?.readText(Charsets.UTF_8)
            if (html == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(html, ContentType.Text.Html)
        }
    }
}
