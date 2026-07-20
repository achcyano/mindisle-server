package me.hztcm.mindisle.state.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.toIsoOffsetUtc
import me.hztcm.mindisle.common.toLocalDatePlus8
import me.hztcm.mindisle.common.utcNow
import me.hztcm.mindisle.db.AiNlpFeaturesTable
import me.hztcm.mindisle.db.DatabaseFactory
import me.hztcm.mindisle.db.DoseLogStatus
import me.hztcm.mindisle.db.EmaActivityLevel
import me.hztcm.mindisle.db.EmaEntriesTable
import me.hztcm.mindisle.db.MedicationDoseLogsTable
import me.hztcm.mindisle.db.PatientStateSnapshotsTable
import me.hztcm.mindisle.db.RiskLevel
import me.hztcm.mindisle.db.ScaleSessionStatus
import me.hztcm.mindisle.db.ScalesTable
import me.hztcm.mindisle.db.SeverityLevel
import me.hztcm.mindisle.db.UserMedicationsTable
import me.hztcm.mindisle.db.UserScaleResultsTable
import me.hztcm.mindisle.db.UserScaleSessionsTable
import me.hztcm.mindisle.db.UserSideEffectsTable
import me.hztcm.mindisle.db.UserWeightLogsTable
import me.hztcm.mindisle.db.UsersTable
import me.hztcm.mindisle.db.UiTaskType
import me.hztcm.mindisle.model.PatientStateResponse
import me.hztcm.mindisle.safety.service.SafetyAlertService
import me.hztcm.mindisle.task.service.UiTaskService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory

class PatientStateService(
    private val safetyAlertService: SafetyAlertService? = null,
    private val uiTaskService: UiTaskService? = null
) {
    private val json = Json { encodeDefaults = true }
    private val log = LoggerFactory.getLogger(PatientStateService::class.java)

    suspend fun current(userId: Long, maxAgeHours: Long = 6): PatientStateResponse {
        val latest = DatabaseFactory.dbQuery {
            PatientStateSnapshotsTable.selectAll().where {
                PatientStateSnapshotsTable.userId eq EntityID(userId, UsersTable)
            }.orderBy(PatientStateSnapshotsTable.createdAt, SortOrder.DESC).limit(1).firstOrNull()
        }
        if (latest == null) {
            return recompute(userId, source = "LAZY")
        }
        val ageHours = java.time.Duration.between(
            latest[PatientStateSnapshotsTable.createdAt],
            utcNow()
        ).toHours()
        if (ageHours > maxAgeHours) {
            return recompute(userId, source = "STALE_REFRESH")
        }
        return latest.toResponse()
    }

    suspend fun recompute(userId: Long, source: String = "RULE_ENGINE"): PatientStateResponse {
        val snapshot = DatabaseFactory.dbQuery {
            val now = utcNow()
            val today = now.toLocalDatePlus8()
            val fromDate = today.minusDays(7)
            val userRef = EntityID(userId, UsersTable)

            val emaRows = EmaEntriesTable.selectAll().where {
                (EmaEntriesTable.userId eq userRef) and (EmaEntriesTable.localDate greaterEq fromDate)
            }.toList()
            val moodAvg = emaRows.map { it[EmaEntriesTable.mood].toDouble() }.takeIf { it.isNotEmpty() }?.average()
            val sleepAvg = emaRows.mapNotNull { it[EmaEntriesTable.sleepQuality]?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
            val socialAvg = emaRows.mapNotNull { it[EmaEntriesTable.socialContact]?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
            val lowActivityRatio = if (emaRows.isEmpty()) 0.0 else {
                emaRows.count { it[EmaEntriesTable.activity] == EmaActivityLevel.LOW }.toDouble() / emaRows.size
            }

            val nlpFrom = now.minusDays(7)
            val nlpRows = AiNlpFeaturesTable.selectAll().where {
                (AiNlpFeaturesTable.userId eq userRef) and (AiNlpFeaturesTable.createdAt greaterEq nlpFrom)
            }.toList()
            val ruminationHits = nlpRows.count { it[AiNlpFeaturesTable.ruminationHit] }
            val riskHits = nlpRows.count { it[AiNlpFeaturesTable.riskHit] }
            val avgPolarity = nlpRows.mapNotNull { it[AiNlpFeaturesTable.polarity] }.takeIf { it.isNotEmpty() }?.average()

            val scaleMeta = latestScaleMeta(userRef)
            val phq9 = scaleMeta.scores["PHQ9"]
            val gad7 = scaleMeta.scores["GAD7"]
            val psqi = scaleMeta.scores["PSQI"] ?: scaleMeta.scores["ISI"]
            val suicideFlag = scaleMeta.flags.contains("SUICIDE_RISK")

            val sideEffectFrom = now.minusDays(14)
            val missedDoses = MedicationDoseLogsTable.selectAll().where {
                (MedicationDoseLogsTable.userId eq userRef) and
                    (MedicationDoseLogsTable.localDate greaterEq fromDate) and
                    (MedicationDoseLogsTable.status eq DoseLogStatus.MISSED)
            }.count().toInt()
            val sideEffects = UserSideEffectsTable.selectAll().where {
                (UserSideEffectsTable.userId eq userRef) and
                    (UserSideEffectsTable.recordedAt greaterEq sideEffectFrom)
            }.count().toInt()

            val weights = UserWeightLogsTable.selectAll().where {
                (UserWeightLogsTable.userId eq userRef) and
                    (UserWeightLogsTable.recordedAt greaterEq now.minusDays(180))
            }.orderBy(UserWeightLogsTable.recordedAt, SortOrder.ASC).map {
                it[UserWeightLogsTable.weightKg].toDouble()
            }
            val weightGainPct = if (weights.size >= 2 && weights.first() > 0) {
                (weights.last() - weights.first()) / weights.first() * 100.0
            } else {
                0.0
            }

            val lowMood = levelByMood(moodAvg, phq9, avgPolarity)
            val anxiety = levelByGad(gad7)
            val rumination = when {
                ruminationHits >= 6 -> SeverityLevel.SEVERE
                ruminationHits >= 3 -> SeverityLevel.MODERATE
                ruminationHits >= 1 -> SeverityLevel.MILD
                else -> SeverityLevel.NONE
            }
            val sleep = levelBySleep(sleepAvg, psqi)
            val activity = when {
                lowActivityRatio >= 0.75 -> SeverityLevel.SEVERE
                lowActivityRatio >= 0.5 -> SeverityLevel.MODERATE
                lowActivityRatio >= 0.25 -> SeverityLevel.MILD
                else -> SeverityLevel.NONE
            }
            val social = when {
                socialAvg == null -> SeverityLevel.NONE
                socialAvg <= 0.5 -> SeverityLevel.SEVERE
                socialAvg <= 2.0 -> SeverityLevel.MODERATE
                socialAvg <= 4.0 -> SeverityLevel.MILD
                else -> SeverityLevel.NONE
            }
            val medDistress = when {
                weightGainPct >= 7.0 || sideEffects >= 5 || missedDoses >= 4 -> SeverityLevel.SEVERE
                weightGainPct >= 5.0 || sideEffects >= 2 || missedDoses >= 2 -> SeverityLevel.MODERATE
                sideEffects >= 1 || missedDoses >= 1 -> SeverityLevel.MILD
                else -> SeverityLevel.NONE
            }

            val dims = listOf(lowMood, anxiety, rumination, sleep, activity, social, medDistress)
            val hasObservation = emaRows.isNotEmpty() || nlpRows.isNotEmpty() || scaleMeta.scores.isNotEmpty() ||
                sideEffects > 0 || missedDoses > 0 || weights.isNotEmpty()
            // Crisis HIGH is reserved for self-harm/NLP risk, med safety severe, or severe PHQ-9.
            // Symptom SEVERE alone (e.g. sleep) maps to MEDIUM so it still drives intervention.
            val risk = when {
                suicideFlag || riskHits > 0 || medDistress == SeverityLevel.SEVERE ||
                    (phq9 != null && phq9 >= 20) -> RiskLevel.HIGH
                !hasObservation -> RiskLevel.LOW
                dims.any { it == SeverityLevel.SEVERE || it == SeverityLevel.MODERATE } ||
                    (phq9 != null && phq9 >= 10) -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            }

            val features = mapOf(
                "moodAvg7d" to (moodAvg?.let { "%.1f".format(it) } ?: "n/a"),
                "phq9" to (phq9?.toString() ?: "n/a"),
                "gad7" to (gad7?.toString() ?: "n/a"),
                "psqi" to (psqi?.toString() ?: "n/a"),
                "missedDoses7d" to missedDoses.toString(),
                "weightGainPct" to "%.1f".format(weightGainPct),
                "nlpRiskHits7d" to riskHits.toString(),
                "suicideFlag" to suicideFlag.toString(),
                "observed" to hasObservation.toString()
            )

            val id = PatientStateSnapshotsTable.insert {
                it[PatientStateSnapshotsTable.userId] = userRef
                it[PatientStateSnapshotsTable.lowMood] = lowMood
                it[PatientStateSnapshotsTable.anxiety] = anxiety
                it[PatientStateSnapshotsTable.rumination] = rumination
                it[sleepDisturbance] = sleep
                it[reducedActivity] = activity
                it[socialWithdrawal] = social
                it[medicationDistress] = medDistress
                it[riskLevel] = risk
                it[featureJson] = json.encodeToString(features)
                it[snapshotSource] = source
                it[createdAt] = now
            }[PatientStateSnapshotsTable.id]

            PatientStateSnapshotsTable.selectAll().where {
                PatientStateSnapshotsTable.id eq id
            }.first() to risk
        }

        val response = snapshot.first.toResponse()
        if (snapshot.second == RiskLevel.HIGH) {
            val reasons = buildList {
                if (response.features["suicideFlag"] == "true") add("PHQ9_SUICIDE_ITEM")
                if ((response.features["nlpRiskHits7d"]?.toIntOrNull() ?: 0) > 0) add("NLP_RISK")
                if (response.medicationDistress == SeverityLevel.SEVERE.name) add("MED_SAFETY")
                if (isEmpty()) add("STATE_HIGH_RISK")
            }
            try {
                safetyAlertService?.raise(
                    userId = userId,
                    riskLevel = RiskLevel.HIGH,
                    reasonCodes = reasons,
                    evidence = response.summary
                )
                uiTaskService?.create(
                    userId = userId,
                    type = UiTaskType.SAFETY,
                    title = "安全支持与求助资源",
                    payload = mapOf("source" to source),
                    source = "STATE_HIGH"
                )
                uiTaskService?.dismissPendingByTypes(
                    userId = userId,
                    types = setOf(UiTaskType.INTERVENTION)
                )
            } catch (ex: Exception) {
                log.error("Failed to escalate HIGH risk for userId={}", userId, ex)
            }
        }
        return response
    }

    private data class ScaleMeta(
        val scores: Map<String, Double>,
        val flags: Set<String>
    )

    private fun org.jetbrains.exposed.sql.Transaction.latestScaleMeta(
        userRef: EntityID<Long>
    ): ScaleMeta {
        val sessions = UserScaleSessionsTable.selectAll().where {
            (UserScaleSessionsTable.userId eq userRef) and
                (UserScaleSessionsTable.status eq ScaleSessionStatus.SUBMITTED)
        }.orderBy(UserScaleSessionsTable.submittedAt, SortOrder.DESC).limit(30).toList()

        val scores = linkedMapOf<String, Double>()
        val flags = linkedSetOf<String>()
        for (session in sessions) {
            val scaleCode = ScalesTable.selectAll().where {
                ScalesTable.id eq session[UserScaleSessionsTable.scaleId]
            }.firstOrNull()?.get(ScalesTable.code) ?: continue
            val resultRow = UserScaleResultsTable.selectAll().where {
                UserScaleResultsTable.sessionId eq session[UserScaleSessionsTable.id]
            }.firstOrNull() ?: continue
            if (!scores.containsKey(scaleCode)) {
                resultRow[UserScaleResultsTable.totalScore]?.toDouble()?.let { scores[scaleCode] = it }
            }
            val detail = resultRow[UserScaleResultsTable.resultDetailJson]
            if (!detail.isNullOrBlank() && detail.contains("SUICIDE_RISK")) {
                flags += "SUICIDE_RISK"
            }
        }
        return ScaleMeta(scores = scores, flags = flags)
    }

    private fun levelByMood(moodAvg: Double?, phq9: Double?, polarity: Double?): SeverityLevel {
        val byMood = when {
            moodAvg == null -> null
            moodAvg < 3 -> SeverityLevel.SEVERE
            moodAvg <= 4 -> SeverityLevel.MODERATE
            moodAvg <= 6 -> SeverityLevel.MILD
            else -> SeverityLevel.NONE
        }
        val byPhq = when {
            phq9 == null -> null
            phq9 >= 20 -> SeverityLevel.SEVERE
            phq9 >= 15 -> SeverityLevel.MODERATE
            phq9 >= 10 -> SeverityLevel.MILD
            phq9 >= 5 -> SeverityLevel.MILD
            else -> SeverityLevel.NONE
        }
        val byPol = when {
            polarity == null -> null
            polarity <= -0.6 -> SeverityLevel.MODERATE
            polarity <= -0.3 -> SeverityLevel.MILD
            else -> SeverityLevel.NONE
        }
        return maxOfLevels(listOfNotNull(byMood, byPhq, byPol))
    }

    private fun levelByGad(gad7: Double?): SeverityLevel = when {
        gad7 == null -> SeverityLevel.NONE
        gad7 >= 15 -> SeverityLevel.SEVERE
        gad7 >= 10 -> SeverityLevel.MODERATE
        gad7 >= 5 -> SeverityLevel.MILD
        else -> SeverityLevel.NONE
    }

    private fun levelBySleep(sleepAvg: Double?, psqi: Double?): SeverityLevel {
        val byEma = when {
            sleepAvg == null -> null
            sleepAvg < 3 -> SeverityLevel.SEVERE
            sleepAvg <= 5 -> SeverityLevel.MODERATE
            sleepAvg <= 7 -> SeverityLevel.MILD
            else -> SeverityLevel.NONE
        }
        val byPsqi = when {
            psqi == null -> null
            psqi >= 16 -> SeverityLevel.SEVERE
            psqi >= 11 -> SeverityLevel.MODERATE
            psqi >= 6 -> SeverityLevel.MILD
            else -> SeverityLevel.NONE
        }
        return maxOfLevels(listOfNotNull(byEma, byPsqi))
    }

    private fun maxOfLevels(levels: List<SeverityLevel>): SeverityLevel {
        if (levels.isEmpty()) return SeverityLevel.NONE
        return levels.maxBy { it.ordinal }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toResponse(): PatientStateResponse {
        val features = runCatching {
            json.decodeFromString<Map<String, String>>(this[PatientStateSnapshotsTable.featureJson] ?: "{}")
        }.getOrDefault(emptyMap())
        val risk = this[PatientStateSnapshotsTable.riskLevel]
        val lowMood = this[PatientStateSnapshotsTable.lowMood]
        val anxiety = this[PatientStateSnapshotsTable.anxiety]
        val rumination = this[PatientStateSnapshotsTable.rumination]
        val sleep = this[PatientStateSnapshotsTable.sleepDisturbance]
        val activity = this[PatientStateSnapshotsTable.reducedActivity]
        val social = this[PatientStateSnapshotsTable.socialWithdrawal]
        val med = this[PatientStateSnapshotsTable.medicationDistress]
        val summary =
            "风险=${risk.name}; 低心境=${lowMood.name}, 焦虑=${anxiety.name}, 睡眠=${sleep.name}, 用药困扰=${med.name}"
        return PatientStateResponse(
            lowMood = lowMood.name,
            anxiety = anxiety.name,
            rumination = rumination.name,
            sleepDisturbance = sleep.name,
            reducedActivity = activity.name,
            socialWithdrawal = social.name,
            medicationDistress = med.name,
            riskLevel = risk.name,
            summary = summary,
            createdAt = this[PatientStateSnapshotsTable.createdAt].toIsoOffsetUtc(),
            features = features
        )
    }
}
