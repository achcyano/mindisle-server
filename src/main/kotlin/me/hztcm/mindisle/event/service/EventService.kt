package me.hztcm.mindisle.event.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoctorPatientBindingStatus
import me.hztcm.mindisle.db.DoctorPatientBindingsTable
import me.hztcm.mindisle.db.ScaleSessionStatus
import me.hztcm.mindisle.db.ScaleStatus
import me.hztcm.mindisle.db.ScaleVersionsTable
import me.hztcm.mindisle.db.ScalesTable
import me.hztcm.mindisle.db.UserMedicationsTable
import me.hztcm.mindisle.db.UserScaleSessionsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.UserEventItem
import me.hztcm.mindisle.model.UserEventListResponse
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDateTime

class EventService {
    private companion object {
        const val DEFAULT_SCALE_REDO_INTERVAL_DAYS = 30
        const val PROFILE_UPDATE_INTERVAL_MONTHS = 1

        const val EVENT_SCALE_REDO_DUE = "SCALE_REDO_DUE"
        const val EVENT_SCALE_SESSION_IN_PROGRESS = "SCALE_SESSION_IN_PROGRESS"
        const val EVENT_DOCTOR_BIND_REQUIRED = "DOCTOR_BIND_REQUIRED"
        const val EVENT_MEDICATION_PLAN_EMPTY = "MEDICATION_PLAN_EMPTY"
        const val EVENT_PROFILE_UPDATE_MONTHLY = "PROFILE_UPDATE_MONTHLY"

        const val EVENT_TYPE_OPEN_SCALE = "OPEN_SCALE"
        const val EVENT_TYPE_CONTINUE_SCALE_SESSION = "CONTINUE_SCALE_SESSION"
        const val EVENT_TYPE_BIND_DOCTOR = "BIND_DOCTOR"
        const val EVENT_TYPE_IMPORT_MEDICATION_PLAN = "IMPORT_MEDICATION_PLAN"
        const val EVENT_TYPE_UPDATE_BASIC_PROFILE = "UPDATE_BASIC_PROFILE"
    }

    suspend fun listEvents(userId: Long): UserEventListResponse {
        return DatabaseFactory.dbQuery {
            val now = utcNow()
            val userRef = EntityID(userId, UsersTable)
            val user = requireUser(userRef)
            val userCreatedAt = user[UsersTable.createdAt]

            val drafts = mutableListOf<EventDraft>()
            appendScaleRedoEvents(userRef, userCreatedAt, now, drafts)
            appendInProgressScaleEvents(userRef, drafts)
            appendDoctorBindingEvent(userRef, userCreatedAt, drafts)
            appendMedicationPlanEmptyEvent(userRef, userCreatedAt, drafts)
            appendMonthlyProfileUpdateEvent(userCreatedAt, now, drafts)

            val items = drafts
                .sortedWith(compareBy<EventDraft> { it.dueAt }.thenBy { it.eventName })
                .map { draft ->
                    UserEventItem(
                        eventName = draft.eventName,
                        eventType = draft.eventType,
                        dueAt = draft.dueAt.toIsoOffsetPlus8(),
                        payload = draft.payload
                    )
                }

            UserEventListResponse(
                generatedAt = now.toIsoOffsetPlus8(),
                items = items
            )
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.appendScaleRedoEvents(
        userId: EntityID<Long>,
        userCreatedAt: LocalDateTime,
        now: LocalDateTime,
        output: MutableList<EventDraft>
    ) {
        val latestVersionRows = ScaleVersionsTable.selectAll().where {
            ScaleVersionsTable.status eq ScaleStatus.PUBLISHED
        }.orderBy(ScaleVersionsTable.scaleId, SortOrder.ASC)
            .orderBy(ScaleVersionsTable.version, SortOrder.DESC)
            .toList()

        val latestVersionByScaleId = linkedMapOf<Long, ResultRow>()
        latestVersionRows.forEach { row ->
            latestVersionByScaleId.putIfAbsent(row[ScaleVersionsTable.scaleId].value, row)
        }
        if (latestVersionByScaleId.isEmpty()) {
            return
        }

        val scaleRefs = latestVersionByScaleId.keys.map { scaleId -> EntityID(scaleId, ScalesTable) }
        val scalesById = ScalesTable.selectAll().where {
            ScalesTable.id inList scaleRefs
        }.toList().associateBy { it[ScalesTable.id].value }

        val lastSubmittedByScaleId = UserScaleSessionsTable.selectAll().where {
            (UserScaleSessionsTable.userId eq userId) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED) and
                (UserScaleSessionsTable.scaleId inList scaleRefs)
        }.toList()
            .groupBy { it[UserScaleSessionsTable.scaleId].value }
            .mapValues { (_, rows) ->
                rows.mapNotNull { row -> row[UserScaleSessionsTable.submittedAt] }.maxOrNull()
            }

        latestVersionByScaleId.keys.sorted().forEach { scaleId ->
            val scaleRow = scalesById[scaleId] ?: return@forEach
            val versionRow = latestVersionByScaleId.getValue(scaleId)
            val intervalDays = parseRedoIntervalDays(
                configJson = versionRow[ScaleVersionsTable.configJson],
                defaultValue = DEFAULT_SCALE_REDO_INTERVAL_DAYS
            )
            val anchor = lastSubmittedByScaleId[scaleId] ?: userCreatedAt
            val dueAt = nextRecurringDueByDays(anchor, intervalDays, now)
            output += EventDraft(
                eventName = EVENT_SCALE_REDO_DUE,
                eventType = EVENT_TYPE_OPEN_SCALE,
                dueAt = dueAt,
                payload = buildJsonObject {
                    put("scaleId", scaleId)
                    put("scaleCode", scaleRow[ScalesTable.code])
                    put("scaleName", scaleRow[ScalesTable.name])
                    put("intervalDays", intervalDays)
                }
            )
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.appendInProgressScaleEvents(
        userId: EntityID<Long>,
        output: MutableList<EventDraft>
    ) {
        val sessionRows = UserScaleSessionsTable.selectAll().where {
            (UserScaleSessionsTable.userId eq userId) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.IN_PROGRESS)
        }.orderBy(UserScaleSessionsTable.updatedAt, SortOrder.DESC).toList()
        if (sessionRows.isEmpty()) {
            return
        }

        val latestByScaleId = linkedMapOf<Long, ResultRow>()
        sessionRows.forEach { row ->
            latestByScaleId.putIfAbsent(row[UserScaleSessionsTable.scaleId].value, row)
        }

        val scaleRefs = latestByScaleId.keys.map { scaleId -> EntityID(scaleId, ScalesTable) }
        val scalesById = ScalesTable.selectAll().where {
            ScalesTable.id inList scaleRefs
        }.toList().associateBy { it[ScalesTable.id].value }

        latestByScaleId.keys.sorted().forEach { scaleId ->
            val session = latestByScaleId.getValue(scaleId)
            val scale = scalesById[scaleId] ?: return@forEach
            output += EventDraft(
                eventName = EVENT_SCALE_SESSION_IN_PROGRESS,
                eventType = EVENT_TYPE_CONTINUE_SCALE_SESSION,
                dueAt = session[UserScaleSessionsTable.updatedAt],
                payload = buildJsonObject {
                    put("sessionId", session[UserScaleSessionsTable.id].value)
                    put("scaleId", scaleId)
                    put("scaleCode", scale[ScalesTable.code])
                    put("scaleName", scale[ScalesTable.name])
                    put("progress", session[UserScaleSessionsTable.progress])
                }
            )
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.appendDoctorBindingEvent(
        userId: EntityID<Long>,
        userCreatedAt: LocalDateTime,
        output: MutableList<EventDraft>
    ) {
        val hasActiveBinding = DoctorPatientBindingsTable.selectAll().where {
            (DoctorPatientBindingsTable.patientUserId eq userId) and
                (DoctorPatientBindingsTable.status eq DoctorPatientBindingStatus.ACTIVE) and
                DoctorPatientBindingsTable.unboundAt.isNull()
        }.any()
        if (!hasActiveBinding) {
            output += EventDraft(
                eventName = EVENT_DOCTOR_BIND_REQUIRED,
                eventType = EVENT_TYPE_BIND_DOCTOR,
                dueAt = userCreatedAt,
                payload = buildJsonObject {}
            )
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.appendMedicationPlanEmptyEvent(
        userId: EntityID<Long>,
        userCreatedAt: LocalDateTime,
        output: MutableList<EventDraft>
    ) {
        val activeCount = UserMedicationsTable.selectAll().where {
            (UserMedicationsTable.userId eq userId) and UserMedicationsTable.deletedAt.isNull()
        }.count()
        if (activeCount == 0L) {
            output += EventDraft(
                eventName = EVENT_MEDICATION_PLAN_EMPTY,
                eventType = EVENT_TYPE_IMPORT_MEDICATION_PLAN,
                dueAt = userCreatedAt,
                payload = buildJsonObject {
                    put("activeMedicationCount", 0)
                }
            )
        }
    }

    private fun appendMonthlyProfileUpdateEvent(
        userCreatedAt: LocalDateTime,
        now: LocalDateTime,
        output: MutableList<EventDraft>
    ) {
        output += EventDraft(
            eventName = EVENT_PROFILE_UPDATE_MONTHLY,
            eventType = EVENT_TYPE_UPDATE_BASIC_PROFILE,
            dueAt = nextRecurringDueByMonths(
                anchor = userCreatedAt,
                intervalMonths = PROFILE_UPDATE_INTERVAL_MONTHS,
                now = now
            ),
            payload = buildJsonObject {
                put("anchor", "REGISTERED_AT")
            }
        )
    }

    private fun org.jetbrains.exposed.sql.Transaction.requireUser(userId: EntityID<Long>): ResultRow {
        return UsersTable.selectAll().where { UsersTable.id eq userId }.firstOrNull()
            ?: throw AppException(
                code = ErrorCodes.UNAUTHORIZED,
                message = "User not found",
                status = HttpStatusCode.Unauthorized
            )
    }
}

private data class EventDraft(
    val eventName: String,
    val eventType: String,
    val dueAt: LocalDateTime,
    val payload: JsonObject
)
