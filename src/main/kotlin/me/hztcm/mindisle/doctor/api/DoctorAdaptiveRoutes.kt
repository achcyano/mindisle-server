package me.hztcm.mindisle.doctor.api

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
import me.hztcm.mindisle.ai.service.AiNlpService
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.AiConversationsTable
import me.hztcm.mindisle.db.AiMessagesTable
import me.hztcm.mindisle.db.AiNlpFeaturesTable
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorPatientBindingStatus
import me.hztcm.mindisle.db.DoctorPatientBindingsTable
import me.hztcm.mindisle.db.DoctorsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.AckSafetyAlertRequest
import me.hztcm.mindisle.model.ApiResponse
import me.hztcm.mindisle.model.ConversationExportItem
import me.hztcm.mindisle.model.ConversationExportMessage
import me.hztcm.mindisle.model.ConversationExportResponse
import me.hztcm.mindisle.safety.service.SafetyAlertService
import me.hztcm.mindisle.security.DoctorPrincipal
import me.hztcm.mindisle.state.service.PatientStateService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

fun Route.registerDoctorAdaptiveRoutes(
    stateService: PatientStateService,
    safetyAlertService: SafetyAlertService,
    nlpService: AiNlpService
) {
    authenticate("doctor-auth-jwt") {
        route("/doctors/me") {
            get("/alerts") {
                val onlyOpen = call.request.queryParameters["onlyOpen"]?.toBooleanStrictOrNull() ?: true
                call.respond(
                    ApiResponse(data = safetyAlertService.listForDoctor(call.requireDoctorId(), onlyOpen))
                )
            }
            post("/alerts/{alertId}/ack") {
                val alertId = call.parameters["alertId"]?.toLongOrNull()
                    ?: throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid alertId", HttpStatusCode.BadRequest)
                safetyAlertService.ack(call.requireDoctorId(), alertId, call.receive())
                call.respond(ApiResponse<Unit>())
            }
            get("/patients/{patientUserId}/state") {
                val patientUserId = call.requirePathLong("patientUserId")
                ensureBound(call.requireDoctorId(), patientUserId)
                call.respond(ApiResponse(data = stateService.current(patientUserId)))
            }
            get("/patients/{patientUserId}/nlp-summary") {
                val patientUserId = call.requirePathLong("patientUserId")
                ensureBound(call.requireDoctorId(), patientUserId)
                val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
                call.respond(ApiResponse(data = nlpService.summary(patientUserId, days.coerceIn(1, 90))))
            }
            get("/patients/{patientUserId}/conversations/export") {
                val patientUserId = call.requirePathLong("patientUserId")
                ensureBound(call.requireDoctorId(), patientUserId)
                val data = exportConversations(patientUserId)
                call.respond(ApiResponse(data = data))
            }
        }
    }
}

private suspend fun ensureBound(doctorId: Long, patientUserId: Long) {
    DatabaseFactory.dbQuery {
        val ok = DoctorPatientBindingsTable.selectAll().where {
            (DoctorPatientBindingsTable.doctorId eq EntityID(doctorId, DoctorsTable)) and
                (DoctorPatientBindingsTable.patientUserId eq EntityID(patientUserId, UsersTable)) and
                (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE)
        }.any()
        if (!ok) {
            throw AppException(
                ErrorCodes.DOCTOR_PATIENT_NOT_BOUND,
                "Patient is not bound to current doctor",
                HttpStatusCode.NotFound
            )
        }
    }
}

private suspend fun exportConversations(patientUserId: Long): ConversationExportResponse {
    return DatabaseFactory.dbQuery {
        val userRef = EntityID(patientUserId, UsersTable)
        val conversations = AiConversationsTable.selectAll().where {
            AiConversationsTable.userId eq userRef
        }.orderBy(AiConversationsTable.updatedAt, SortOrder.DESC).limit(50).toList()
        val items = conversations.map { conv ->
            val convId = conv[AiConversationsTable.id].value
            val messages = AiMessagesTable.selectAll().where {
                AiMessagesTable.conversationId eq conv[AiConversationsTable.id]
            }.orderBy(AiMessagesTable.id, SortOrder.ASC).limit(500).map { msg ->
                val nlp = AiNlpFeaturesTable.selectAll().where {
                    AiNlpFeaturesTable.messageId eq msg[AiMessagesTable.id].value
                }.firstOrNull()
                ConversationExportMessage(
                    messageId = msg[AiMessagesTable.id].value,
                    role = msg[AiMessagesTable.role].name,
                    content = msg[AiMessagesTable.content],
                    createdAt = msg[AiMessagesTable.createdAt].atOffset(java.time.ZoneOffset.UTC).toString(),
                    polarity = nlp?.get(AiNlpFeaturesTable.polarity),
                    riskHit = nlp?.get(AiNlpFeaturesTable.riskHit) ?: false
                )
            }
            ConversationExportItem(
                conversationId = convId,
                title = conv[AiConversationsTable.title],
                messages = messages
            )
        }
        ConversationExportResponse(
            patientUserId = patientUserId,
            exportedAt = utcNow().toIsoOffsetUtc(),
            conversations = items
        )
    }
}

private fun ApplicationCall.requireDoctorId(): Long {
    return principal<DoctorPrincipal>()?.doctorId ?: throw AppException(
        ErrorCodes.UNAUTHORIZED,
        "Unauthorized",
        HttpStatusCode.Unauthorized
    )
}

private fun ApplicationCall.requirePathLong(name: String): Long {
    return parameters[name]?.toLongOrNull()
        ?: throw AppException(ErrorCodes.DOCTOR_INVALID_ARGUMENT, "Invalid $name", HttpStatusCode.BadRequest)
}
