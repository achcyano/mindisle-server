package me.hztcm.mindisle.intervention

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure ranking logic mirror used by InterventionService.matchFromState.
 * Keeps priority (lower first) then weight (higher first).
 */
class InterventionMatchRankingTest {
    @Test
    fun ranksByPriorityThenWeight() {
        data class Row(val module: String, val priority: Int, val weight: Double)

        val rows = listOf(
            Row("ba_one_step", 3, 1.2),
            Row("breathing_5min", 2, 1.0),
            Row("sleep_hygiene", 5, 2.5),
            Row("med_comm_list", 1, 0.9),
        )
        val ranked = rows
            .sortedWith(compareBy<Row> { it.priority }.thenByDescending { it.weight })
            .map { it.module }
            .distinct()
            .take(2)

        assertEquals(listOf("med_comm_list", "breathing_5min"), ranked)
    }
}
