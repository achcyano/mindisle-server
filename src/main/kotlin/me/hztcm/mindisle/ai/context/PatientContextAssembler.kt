package me.hztcm.mindisle.ai.context

import me.hztcm.mindisle.common.toLocalDatePlus8
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.EmaEntriesTable
import me.hztcm.mindisle.db.UserMedicationsTable
import me.hztcm.mindisle.db.UserProfilesTable
import me.hztcm.mindisle.db.UserSideEffectsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.medication.service.DoseLogService
import me.hztcm.mindisle.model.PatientStateResponse
import me.hztcm.mindisle.state.service.PatientStateService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDate
import java.time.Period

class PatientContextAssembler(
    private val stateService: PatientStateService,
    private val doseLogService: DoseLogService
) {
    suspend fun assemble(userId: Long, conversationSummary: String?): String {
        val state = runCatching { stateService.current(userId) }.getOrNull()
        val adherence = runCatching { doseLogService.adherenceRate(userId, 7) }.getOrNull()
        val profileBlock = DatabaseFactory.dbQuery {
            val userRef = EntityID(userId, UsersTable)
            val profile = UserProfilesTable.selectAll().where {
                UserProfilesTable.userId eq userRef
            }.firstOrNull()
            val age = profile?.get(UserProfilesTable.birthDate)?.let {
                Period.between(it, LocalDate.now()).years
            }
            val gender = profile?.get(UserProfilesTable.gender)?.name ?: "UNKNOWN"
            val meds = UserMedicationsTable.selectAll().where {
                (UserMedicationsTable.userId eq userRef) and UserMedicationsTable.deletedAt.isNull()
            }.limit(8).map { it[UserMedicationsTable.drugName] }
            val seCount = UserSideEffectsTable.selectAll().where {
                UserSideEffectsTable.userId eq userRef
            }.count()
            val from = utcNow().toLocalDatePlus8().minusDays(7)
            val ema = EmaEntriesTable.selectAll().where {
                (EmaEntriesTable.userId eq userRef) and (EmaEntriesTable.localDate greaterEq from)
            }.toList()
            val moodAvg = ema.map { it[EmaEntriesTable.mood] }.average().takeIf { ema.isNotEmpty() }
            buildString {
                appendLine("gender=$gender; ageBand=${age?.let { "${it / 10 * 10}s" } ?: "unknown"}")
                appendLine("activeMeds=${meds.joinToString(",").ifBlank { "none" }}")
                appendLine("sideEffectRecords=$seCount")
                appendLine("emaCount7d=${ema.size}; moodAvg7d=${moodAvg?.let { "%.1f".format(it) } ?: "n/a"}")
            }
        }
        return buildString {
            appendLine("【患者只读上下文，勿复述隐私标识】")
            append(profileBlock)
            appendLine("adherence7d=${adherence?.let { "%.0f%%".format(it * 100) } ?: "n/a"}")
            if (state != null) {
                appendLine("state=${formatState(state)}")
            }
            if (!conversationSummary.isNullOrBlank()) {
                appendLine("conversationSummary=$conversationSummary")
            }
            appendLine("说明：你是辅助支持工具，非诊断；可调用工具推荐量表/干预模块；危机时优先安全。")
        }.trim()
    }

    private fun formatState(state: PatientStateResponse): String {
        return "risk=${state.riskLevel}; lowMood=${state.lowMood}; anxiety=${state.anxiety}; " +
            "rumination=${state.rumination}; sleep=${state.sleepDisturbance}; " +
            "activity=${state.reducedActivity}; social=${state.socialWithdrawal}; med=${state.medicationDistress}"
    }
}
