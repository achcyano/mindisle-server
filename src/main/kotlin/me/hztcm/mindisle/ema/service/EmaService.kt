package me.hztcm.mindisle.ema.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.common.parseLocalDateOrTodayPlus8
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.toLocalDatePlus8
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.EmaActivityLevel
import me.hztcm.mindisle.db.EmaEntriesTable
import me.hztcm.mindisle.db.EmaSlot
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.intervention.service.InterventionService
import me.hztcm.mindisle.model.CreateEmaRequest
import me.hztcm.mindisle.model.EmaEntryResponse
import me.hztcm.mindisle.model.EmaListResponse
import me.hztcm.mindisle.model.EmaTodayResponse
import me.hztcm.mindisle.state.service.PatientStateService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class EmaService(
    private val stateService: PatientStateService,
    private val interventionService: InterventionService? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun submit(userId: Long, request: CreateEmaRequest): EmaEntryResponse {
        if (request.mood !in 0..10) {
            throw AppException(ErrorCodes.INVALID_REQUEST, "mood must be 0-10", HttpStatusCode.BadRequest)
        }
        request.sleepQuality?.let {
            if (it !in 0..10) {
                throw AppException(ErrorCodes.INVALID_REQUEST, "sleepQuality must be 0-10", HttpStatusCode.BadRequest)
            }
        }
        request.socialContact?.let {
            if (it !in 0..10) {
                throw AppException(ErrorCodes.INVALID_REQUEST, "socialContact must be 0-10", HttpStatusCode.BadRequest)
            }
        }
        val slot = runCatching { EmaSlot.valueOf(request.slot.uppercase()) }.getOrElse {
            throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid EMA slot", HttpStatusCode.BadRequest)
        }
        val activity = request.activity?.let {
            runCatching { EmaActivityLevel.valueOf(it.uppercase()) }.getOrElse {
                throw AppException(ErrorCodes.INVALID_REQUEST, "Invalid activity", HttpStatusCode.BadRequest)
            }
        }
        val localDate = parseLocalDateOrTodayPlus8(request.localDate)
        val now = utcNow()
        val entry = DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val existing = EmaEntriesTable.selectAll().where {
                (EmaEntriesTable.userId eq userRef) and
                    (EmaEntriesTable.localDate eq localDate) and
                    (EmaEntriesTable.slot eq slot)
            }.firstOrNull()

            val id = if (existing == null) {
                EmaEntriesTable.insert {
                    it[EmaEntriesTable.userId] = userRef
                    it[EmaEntriesTable.localDate] = localDate
                    it[EmaEntriesTable.slot] = slot
                    it[mood] = request.mood
                    it[sleepQuality] = request.sleepQuality
                    it[EmaEntriesTable.activity] = activity
                    it[socialContact] = request.socialContact
                    it[stressText] = request.stressText?.trim()?.takeIf { t -> t.isNotEmpty() }
                    it[eventTagsJson] = json.encodeToString(request.eventTags)
                    it[bodyTagsJson] = json.encodeToString(request.bodyTags)
                    it[note] = request.note?.trim()?.takeIf { t -> t.isNotEmpty() }
                    it[responseLatencyMs] = request.responseLatencyMs
                    it[submittedAt] = now
                }[EmaEntriesTable.id]
            } else {
                EmaEntriesTable.update({ EmaEntriesTable.id eq existing[EmaEntriesTable.id] }) {
                    it[mood] = request.mood
                    it[sleepQuality] = request.sleepQuality
                    it[EmaEntriesTable.activity] = activity
                    it[socialContact] = request.socialContact
                    it[stressText] = request.stressText?.trim()?.takeIf { t -> t.isNotEmpty() }
                    it[eventTagsJson] = json.encodeToString(request.eventTags)
                    it[bodyTagsJson] = json.encodeToString(request.bodyTags)
                    it[note] = request.note?.trim()?.takeIf { t -> t.isNotEmpty() }
                    it[responseLatencyMs] = request.responseLatencyMs
                    it[submittedAt] = now
                }
                existing[EmaEntriesTable.id]
            }
            EmaEntriesTable.selectAll().where { EmaEntriesTable.id eq id }.first().toResponse()
        }
        try {
            val state = stateService.recompute(userId, source = "EMA_SUBMIT")
            if (state.riskLevel != "HIGH") {
                interventionService?.matchFromState(userId, state, triggerType = "EMA_SUBMIT")
            }
        } catch (ex: Exception) {
            org.slf4j.LoggerFactory.getLogger(EmaService::class.java)
                .error("Post-EMA state/intervention failed userId={}", userId, ex)
        }
        return entry
    }

    suspend fun list(userId: Long, from: String?, to: String?, limit: Int): EmaListResponse {
        val safeLimit = limit.coerceIn(1, 100)
        val fromDate = from?.let { parseLocalDateOrTodayPlus8(it) }
        val toDate = to?.let { parseLocalDateOrTodayPlus8(it) }
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val condition = buildList {
                add(EmaEntriesTable.userId eq userRef)
                if (fromDate != null) add(EmaEntriesTable.localDate greaterEq fromDate)
                if (toDate != null) add(EmaEntriesTable.localDate lessEq toDate)
            }.reduce { acc, op -> acc and op }
            val rows = EmaEntriesTable.selectAll().where { condition }
                .orderBy(EmaEntriesTable.localDate, SortOrder.DESC)
                .orderBy(EmaEntriesTable.submittedAt, SortOrder.DESC)
                .limit(safeLimit)
                .toList()
            val today = utcNow().toLocalDatePlus8()
            val todaySlots = EmaEntriesTable.selectAll().where {
                (EmaEntriesTable.userId eq userRef) and (EmaEntriesTable.localDate eq today)
            }.map { it[EmaEntriesTable.slot].name }
            EmaListResponse(
                items = rows.map { it.toResponse() },
                todaySlots = todaySlots
            )
        }
    }

    suspend fun today(userId: Long): EmaTodayResponse {
        val today = utcNow().toLocalDatePlus8()
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val rows = EmaEntriesTable.selectAll().where {
                (EmaEntriesTable.userId eq userRef) and (EmaEntriesTable.localDate eq today)
            }.orderBy(EmaEntriesTable.submittedAt, SortOrder.DESC).toList()
            val completed = rows.map { it[EmaEntriesTable.slot].name }.distinct()
            val expected = listOf(EmaSlot.MORNING.name, EmaSlot.EVENING.name)
            val pending = expected.filterNot { it in completed }
            EmaTodayResponse(
                localDate = today.toString(),
                completedSlots = completed,
                pendingSlots = pending,
                latest = rows.firstOrNull()?.toResponse()
            )
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toResponse(): EmaEntryResponse {
        val eventTags = runCatching {
            json.decodeFromString<List<String>>(this[EmaEntriesTable.eventTagsJson] ?: "[]")
        }.getOrDefault(emptyList())
        val bodyTags = runCatching {
            json.decodeFromString<List<String>>(this[EmaEntriesTable.bodyTagsJson] ?: "[]")
        }.getOrDefault(emptyList())
        return EmaEntryResponse(
            emaId = this[EmaEntriesTable.id].value,
            localDate = this[EmaEntriesTable.localDate].toString(),
            slot = this[EmaEntriesTable.slot].name,
            mood = this[EmaEntriesTable.mood],
            sleepQuality = this[EmaEntriesTable.sleepQuality],
            activity = this[EmaEntriesTable.activity]?.name,
            socialContact = this[EmaEntriesTable.socialContact],
            stressText = this[EmaEntriesTable.stressText],
            eventTags = eventTags,
            bodyTags = bodyTags,
            note = this[EmaEntriesTable.note],
            submittedAt = this[EmaEntriesTable.submittedAt].toIsoOffsetUtc()
        )
    }
}
