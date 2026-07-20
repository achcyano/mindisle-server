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
import me.hztcm.mindisle.db.InterventionMatchWeightsTable
import me.hztcm.mindisle.db.InterventionModulesTable
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
        val moduleCodeAndDims = DatabaseFactory.dbQuery {
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
            val dims = runCatching {
                json.decodeFromString<List<String>>(row[InterventionDeliveriesTable.stateDimsJson] ?: "[]")
            }.getOrDefault(emptyList())
            Triple(
                row[InterventionDeliveriesTable.moduleCode],
                dims,
                when {
                    request.completed -> 0.15
                    request.adopted -> 0.05
                    else -> -0.12
                }
            )
        }
        adjustWeights(
            userId = userId,
            moduleCode = moduleCodeAndDims.first,
            dims = moduleCodeAndDims.second,
            delta = moduleCodeAndDims.third
        )
    }

    suspend fun matchFromState(userId: Long, state: PatientStateResponse, triggerType: String): List<InterventionDeliveryResponse> {
        data class Candidate(val dim: String, val module: String, val basePriority: Int)

        val candidates = mutableListOf<Candidate>()
        fun addIf(level: String, dim: String, module: String, priority: Int) {
            if (level == SeverityLevel.MODERATE.name || level == SeverityLevel.SEVERE.name) {
                candidates += Candidate(dim, module, priority)
            }
        }
        // Lower priority number = higher precedence (table 4).
        if (state.riskLevel == "HIGH") return emptyList()
        addIf(state.medicationDistress, "med", "med_comm_list", 1)
        addIf(state.anxiety, "anxiety", "breathing_5min", 2)
        if (state.anxiety == SeverityLevel.SEVERE.name) {
            candidates += Candidate("anxiety", "pmr_10min", 2)
        }
        addIf(state.lowMood, "lowMood", "ba_one_step", 3)
        addIf(state.reducedActivity, "activity", "ba_one_step", 3)
        addIf(state.rumination, "rumination", "mindfulness_5min", 4)
        addIf(state.sleepDisturbance, "sleep", "sleep_hygiene", 5)
        addIf(state.socialWithdrawal, "social", "ba_one_step", 6)

        val weights = DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            InterventionMatchWeightsTable.selectAll().where {
                InterventionMatchWeightsTable.userId eq userRef
            }.associate {
                (it[InterventionMatchWeightsTable.stateDim] to it[InterventionMatchWeightsTable.moduleCode]) to
                    it[InterventionMatchWeightsTable.weight]
            }
        }

        val ranked = candidates
            .groupBy { it.module }
            .map { (module, list) ->
                val best = list.minBy { it.basePriority }
                val w = list.maxOf { weights[it.dim to module] ?: 1.0 }
                Triple(module, best.basePriority, w)
            }
            .sortedWith(compareBy<Triple<String, Int, Double>> { it.second }.thenByDescending { it.third })
            .map { it.first }
            .distinct()
            .take(2)

        val dims = candidates.map { it.dim }.distinct()
        return ranked.mapNotNull { code ->
            runCatching {
                startModule(userId, code, triggerType, stateDims = dims)
            }.getOrNull()
        }
    }

    private suspend fun adjustWeights(
        userId: Long,
        moduleCode: String,
        dims: List<String>,
        delta: Double
    ) {
        if (dims.isEmpty()) return
        // Never down-weight crisis/med safety modules below floor via dismiss.
        val floor = if (moduleCode == "med_comm_list") 0.8 else 0.2
        val ceiling = 3.0
        DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val now = utcNow()
            dims.forEach { dim ->
                val existing = InterventionMatchWeightsTable.selectAll().where {
                    (InterventionMatchWeightsTable.userId eq userRef) and
                        (InterventionMatchWeightsTable.stateDim eq dim) and
                        (InterventionMatchWeightsTable.moduleCode eq moduleCode)
                }.firstOrNull()
                val next = ((existing?.get(InterventionMatchWeightsTable.weight) ?: 1.0) + delta)
                    .coerceIn(floor, ceiling)
                if (existing == null) {
                    InterventionMatchWeightsTable.insert {
                        it[InterventionMatchWeightsTable.userId] = userRef
                        it[stateDim] = dim
                        it[InterventionMatchWeightsTable.moduleCode] = moduleCode
                        it[weight] = next
                        it[updatedAt] = now
                    }
                } else {
                    InterventionMatchWeightsTable.update({
                        InterventionMatchWeightsTable.id eq existing[InterventionMatchWeightsTable.id]
                    }) {
                        it[weight] = next
                        it[updatedAt] = now
                    }
                }
            }
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
