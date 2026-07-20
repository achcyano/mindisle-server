package me.hztcm.mindisle.state

import me.hztcm.mindisle.db.RiskLevel
import me.hztcm.mindisle.db.SeverityLevel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Documents clinically distinct risk rules used by PatientStateService.
 * Keep in sync with recompute() risk when-block.
 */
class RiskClassificationTest {
    private fun classify(
        suicideFlag: Boolean,
        riskHits: Int,
        medDistress: SeverityLevel,
        phq9: Double?,
        dims: List<SeverityLevel>,
        hasObservation: Boolean
    ): RiskLevel {
        return when {
            suicideFlag || riskHits > 0 || medDistress == SeverityLevel.SEVERE ||
                (phq9 != null && phq9 >= 20) -> RiskLevel.HIGH
            !hasObservation -> RiskLevel.LOW
            dims.any { it == SeverityLevel.SEVERE || it == SeverityLevel.MODERATE } ||
                (phq9 != null && phq9 >= 10) -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }

    @Test
    fun suicideFlagIsHighEvenWithLowScores() {
        val risk = classify(
            suicideFlag = true,
            riskHits = 0,
            medDistress = SeverityLevel.NONE,
            phq9 = 4.0,
            dims = listOf(SeverityLevel.NONE),
            hasObservation = true
        )
        assertEquals(RiskLevel.HIGH, risk)
    }

    @Test
    fun sleepSevereAloneIsMediumNotHigh() {
        val risk = classify(
            suicideFlag = false,
            riskHits = 0,
            medDistress = SeverityLevel.NONE,
            phq9 = 8.0,
            dims = listOf(SeverityLevel.SEVERE),
            hasObservation = true
        )
        assertEquals(RiskLevel.MEDIUM, risk)
    }

    @Test
    fun unobservedIsLow() {
        val risk = classify(
            suicideFlag = false,
            riskHits = 0,
            medDistress = SeverityLevel.NONE,
            phq9 = null,
            dims = listOf(SeverityLevel.NONE),
            hasObservation = false
        )
        assertEquals(RiskLevel.LOW, risk)
    }
}
