package me.hztcm.mindisle.ai.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.withTimeoutOrNull
import me.hztcm.mindisle.ai.service.AiChatService
import me.hztcm.mindisle.ai.service.StreamEventRecord
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.CreateConversationRequest
import me.hztcm.mindisle.model.StreamChatRequest
import me.hztcm.mindisle.security.UserPrincipal

fun Route.registerAiRoutes(service: AiChatService) {
    authenticate("auth-jwt") {
        route("/ai") {
            post("/conversations") {
                val principal = call.requirePrincipal()
                val request = call.receive<CreateConversationRequest>()
                val data = service.createConversation(principal.userId, request.title)
                call.respond(HttpStatusCode.Created, ApiResponse(data = data))
            }

            get("/conversations") {
                val principal = call.requirePrincipal()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val cursor = call.request.queryParameters["cursor"]
                val data = service.listConversations(principal.userId, limit, cursor)
                call.respond(ApiResponse(data = data))
            }

            get("/conversations/{conversationId}/messages") {
                val principal = call.requirePrincipal()
                val conversationId = call.requirePathLong("conversationId")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val before = call.request.queryParameters["before"]
                val data = service.listMessages(principal.userId, conversationId, limit, before)
                call.respond(ApiResponse(data = data))
            }

            post("/conversations/{conversationId}/stream") {
                val principal = call.requirePrincipal()
                val conversationId = call.requirePathLong("conversationId")
                val request = call.receive<StreamChatRequest>()
                val generationId = service.startOrReuseGeneration(principal.userId, conversationId, request)
                val lastEventId = call.request.headers["Last-Event-ID"]
                val lastSeq = service.parseLastEventSeq(generationId, lastEventId)
                streamGeneration(call, service, principal.userId, generationId, lastSeq)
            }

            get("/generations/{generationId}/stream") {
                val principal = call.requirePrincipal()
                val generationId = call.parameters["generationId"]?.trim().orEmpty()
                if (generationId.isEmpty()) {
                    throw AppException(
                        code = ErrorCodes.AI_INVALID_ARGUMENT,
                        message = "Missing generationId",
                        status = HttpStatusCode.BadRequest
                    )
                }
                val lastEventId = call.request.headers["Last-Event-ID"]
                val lastSeq = service.parseLastEventSeq(generationId, lastEventId)
                streamGeneration(call, service, principal.userId, generationId, lastSeq)
            }
        }
    }
}

private suspend fun streamGeneration(
    call: ApplicationCall,
    service: AiChatService,
    userId: Long,
    generationId: String,
    initialLastSeq: Long
) {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.response.header(HttpHeaders.Connection, "keep-alive")
    call.response.header("X-Accel-Buffering", "no")
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        var lastSeq = initialLastSeq
        val subscription = service.subscribe(generationId)
        try {
            val replay = service.replayEvents(userId, generationId, lastSeq)
            replay.events.forEach { event ->
                writeEvent(event)
                lastSeq = event.seq
                if (event.isTerminal()) {
                    return@respondTextWriter
                }
            }
            if (replay.terminalStatus) {
                return@respondTextWriter
            }

            while (true) {
                val event = withTimeoutOrNull(15_000) { subscription.receive() }
                if (event != null) {
                    if (event.seq <= lastSeq) {
                        continue
                    }
                    writeEvent(event)
                    lastSeq = event.seq
                    if (event.isTerminal()) {
                        break
                    }
                    continue
                }

                write(": ping\n\n")
                flush()

                val missed = service.refreshEventsAfter(generationId, lastSeq)
                missed.forEach { missedEvent ->
                    writeEvent(missedEvent)
                    lastSeq = missedEvent.seq
                }
                if (missed.any { it.isTerminal() }) {
                    break
                }

                if (service.isGenerationTerminal(generationId)) {
                    val finalMissed = service.refreshEventsAfter(generationId, lastSeq)
                    finalMissed.forEach { finalEvent ->
                        writeEvent(finalEvent)
                        lastSeq = finalEvent.seq
                    }
                    break
                }
            }
        } finally {
            service.unsubscribe(generationId, subscription)
        }
    }
}

private suspend fun java.io.Writer.writeEvent(event: StreamEventRecord) {
    write("id: ${event.eventId}\n")
    write("event: ${event.eventType}\n")
    write("data: ${event.eventJson}\n\n")
    flush()
}

private fun ApplicationCall.requirePrincipal(): UserPrincipal {
    return principal<UserPrincipal>() ?: throw AppException(
        code = ErrorCodes.UNAUTHORIZED,
        message = "Unauthorized",
        status = HttpStatusCode.Unauthorized
    )
}

private fun ApplicationCall.requirePathLong(name: String): Long {
    val value = parameters[name]?.toLongOrNull()
    if (value == null || value <= 0) {
        throw AppException(
            code = ErrorCodes.AI_INVALID_ARGUMENT,
            message = "Invalid path parameter: $name",
            status = HttpStatusCode.BadRequest
        )
    }
    return value
}
