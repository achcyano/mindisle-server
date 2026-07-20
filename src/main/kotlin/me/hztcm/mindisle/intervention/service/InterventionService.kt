package me.hztcm.mindisle.intervention.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.InterventionDeliveriesTable
import me.hztcm.mindisle.db.InterventionDeliveryStatus
import me.hztcm.mindisle.db.InterventionFeedbackTable
import me.hztcm.mindisle.db.InterventionModulesTable
import me.hztcm.mindisle.db.PatientStateSnapshotsTable
import me.hztcm.mindisle.db.SeverityLevel
import me.hztcm.mindisle.db.UiTaskType
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.model.InterventionDeliveryResponse
import me.hztcm.mindisle.model.InterventionFeedbackRequest
import me.hztcm.mindisle.model.InterventionModuleResponse
import me.hztcm.mindisle.model.InterventionPendingResponse
import me.hztcm.mindisle.model.InterventionStepDto
import me.hztcm.mindisle.model.PatientStateResponse
import me.hztcm.mindisle.task.service.UiTaskService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class InterventionService(
    private val uiTaskService: UiTaskService
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listModules(): List<InterventionModuleResponse> {
        return DatabaseFactory.dbQuery {
            InterventionModulesTable.selectAll().where {
                InterventionModulesTable.active eq true
            }.map { it.toModule() }
        }
    }

    suspend fun getModule(code: String): InterventionModuleResponse {
        return DatabaseFactory.dbQuery {
            InterventionModulesTable.selectAll().where {
                InterventionModulesTable.code eq code
            }.firstOrNull()?.toModule()
                ?: throw AppException(ErrorCodes.INVALID_REQUEST, "Module not found", HttpStatusCode.NotFound)
        }
    }

    suspend fun pending(userId: Long): InterventionPendingResponse {
        return DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val rows = InterventionDeliveriesTable.selectAll().where {
                (InterventionDeliveriesTable.userId eq userRef) and (
                    (InterventionDeliveriesTable.status eq InterventionDeliveryStatus.PENDING) or
                        (InterventionDeliveriesTable.status eq InterventionDeliveryStatus.SHOWN) or
                        (InterventionDeliveriesTable.status eq InterventionDeliveryStatus.STARTED)
                    )
            }.orderBy(InterventionDeliveriesTable.createdAt, SortOrder.DESC).limit(20).toList()
            InterventionPendingResponse(
                items = rows.mapNotNull { row ->
                    val module = InterventionModulesTable.selectAll().where {
                        InterventionModulesTable.code eq row[InterventionDeliveriesTable.moduleCode]
                    }.firstOrNull()?.toModule() ?: return@mapNotNull null
                    InterventionDeliveryResponse(
                        deliveryId = row[InterventionDeliveriesTable.id].value,
                        module = module,
                        status = row[InterventionDeliveriesTable.status].name,
                        triggerType = row[InterventionDeliveriesTable.triggerType],
                        createdAt = row[InterventionDeliveriesTable.createdAt].toIsoOffsetUtc()
                    )
                }
            )
        }
    }

    suspend fun startModule(
        userId: Long,
        moduleCode: String,
        triggerType: String,
        conversationId: Long? = null,
        stateDims: List<String> = emptyList()
    ): InterventionDeliveryResponse {
        val now = utcNow()
        val cooldownFrom = now.minusHours(4)
        val deliveryId = DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val module = InterventionModulesTable.selectAll().where {
                (InterventionModulesTable.code eq moduleCode) and (InterventionModulesTable.active eq true)
            }.firstOrNull() ?: throw AppException(
                ErrorCodes.INVALID_REQUEST,
                "Unknown intervention module",
                HttpStatusCode.BadRequest
            )
            val recent = InterventionDeliveriesTable.selectAll().where {
                (InterventionDeliveriesTable.userId eq userRef) and
                    (InterventionDeliveriesTable.moduleCode eq moduleCode) and
                    (InterventionDeliveriesTable.createdAt greaterEq cooldownFrom)
            }.any()
            if (recent) {
                throw AppException(
                    ErrorCodes.INVALID_REQUEST,
                    "Same module was delivered within 4 hours",
                    HttpStatusCode.TooManyRequests
                )
            }
            InterventionDeliveriesTable.insert {
                it[InterventionDeliveriesTable.userId] = userRef
                it[InterventionDeliveriesTable.moduleCode] = module[InterventionModulesTable.code]
                it[InterventionDeliveriesTable.triggerType] = triggerType
                it[stateDimsJson] = json.encodeToString(stateDims)
                it[status] = InterventionDeliveryStatus.PENDING
                it[InterventionDeliveriesTable.conversationId] = conversationId
                it[createdAt] = now
                it[updatedAt] = now
            }[InterventionDeliveriesTable.id].value to module.toModule()
        }
        uiTaskService.create(
            userId = userId,
            type = UiTaskType.INTERVENTION,
            title = "建议练习：${deliveryId.second.title}",
            payload = mapOf(
                "deliveryId" to deliveryId.first.toString(),
                "moduleCode" to moduleCode
            ),
            source = triggerType
        )
        return InterventionDeliveryResponse(
            deliveryId = deliveryId.first,
            module = deliveryId.second,
            status = InterventionDeliveryStatus.PENDING.name,
            triggerType = triggerType,
            createdAt = now.toIsoOffsetUtc()
        )
    }

    suspend fun updateStatus(userId: Long, deliveryId: Long, status: InterventionDeliveryStatus) {
        DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val updated = InterventionDeliveriesTable.update({
                (InterventionDeliveriesTable.id eq deliveryId) and
                    (InterventionDeliveriesTable.userId eq userRef)
            }) {
                it[InterventionDeliveriesTable.status] = status
                it[updatedAt] = utcNow()
            }
            if (updated == 0) {
                throw AppException(ErrorCodes.INVALID_REQUEST, "Delivery not found", HttpStatusCode.NotFound)
            }
        }
    }

    suspend fun feedback(userId: Long, deliveryId: Long, request: InterventionFeedbackRequest) {
        DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val row = InterventionDeliveriesTable.selectAll().where {
                (InterventionDeliveriesTable.id eq deliveryId) and
                    (InterventionDeliveriesTable.userId eq userRef)
            }.firstOrNull() ?: throw AppException(
                ErrorCodes.INVALID_REQUEST,
                "Delivery not found",
                HttpStatusCode.NotFound
            )
            val now = utcNow()
            val existing = InterventionFeedbackTable.selectAll().where {
                InterventionFeedbackTable.deliveryId eq row[InterventionDeliveriesTable.id]
            }.firstOrNull()
            if (existing == null) {
                InterventionFeedbackTable.insert {
                    it[InterventionFeedbackTable.deliveryId] = row[InterventionDeliveriesTable.id]
                    it[adopted] = request.adopted
                    it[completed] = request.completed
                    it[durationSec] = request.durationSec
                    it[moodBefore] = request.moodBefore
                    it[moodAfter] = request.moodAfter
                    it[createdAt] = now
                }
            }
            InterventionDeliveriesTable.update({ InterventionDeliveriesTable.id eq deliveryId }) {
                it[status] = if (request.completed) {
                    InterventionDeliveryStatus.COMPLETED
                } else if (request.adopted) {
                    InterventionDeliveryStatus.STARTED
                } else {
                    InterventionDeliveryStatus.DISMISSED
                }
                it[updatedAt] = now
            }
        }
    }

    suspend fun matchFromState(userId: Long, state: PatientStateResponse, triggerType: String): List<InterventionDeliveryResponse> {
        val candidates = mutableListOf<String>()
        fun addIf(level: String, module: String) {
            if (level == SeverityLevel.MODERATE.name || level == SeverityLevel.SEVERE.name) {
                candidates += module
            }
        }
        // Priority roughly matches feasibility table 4
        if (state.riskLevel == "HIGH") return emptyList()
        addIf(state.medicationDistress, "med_comm_list")
        addIf(state.anxiety, "breathing_5min")
        addIf(state.lowMood, "ba_one_step")
        addIf(state.reducedActivity, "ba_one_step")
        addIf(state.rumination, "mindfulness_5min")
        addIf(state.sleepDisturbance, "sleep_hygiene")
        addIf(state.socialWithdrawal, "ba_one_step")
        if (state.anxiety == SeverityLevel.SEVERE.name) {
            candidates.add(0, "pmr_10min")
        }
        val unique = candidates.distinct().take(2)
        val dims = listOfNotNull(
            state.lowMood.takeIf { it != "NONE" }?.let { "lowMood" },
            state.anxiety.takeIf { it != "NONE" }?.let { "anxiety" },
            state.sleepDisturbance.takeIf { it != "NONE" }?.let { "sleep" },
            state.medicationDistress.takeIf { it != "NONE" }?.let { "med" }
        )
        return unique.mapNotNull { code ->
            runCatching {
                startModule(userId, code, triggerType, stateDims = dims)
            }.getOrNull()
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toModule(): InterventionModuleResponse {
        val steps = runCatching {
            json.decodeFromString<List<InterventionStepDto>>(this[InterventionModulesTable.contentJson])
        }.getOrDefault(emptyList())
        return InterventionModuleResponse(
            code = this[InterventionModulesTable.code],
            title = this[InterventionModulesTable.title],
            category = this[InterventionModulesTable.category],
            summary = this[InterventionModulesTable.summary],
            durationMinutes = this[InterventionModulesTable.durationMinutes],
            steps = steps
        )
    }
}
