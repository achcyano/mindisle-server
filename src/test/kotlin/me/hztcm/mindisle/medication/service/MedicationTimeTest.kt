package me.hztcm.mindisle.medication.service

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.LocalDate
import java.time.LocalDateTime

class MedicationTimeTest {
    @Test
    fun `toLocalDatePlus8 keeps same day when utc morning`() {
        val utc = LocalDateTime.parse("2026-03-01T03:00:00")
        assertEquals(LocalDate.parse("2026-03-01"), utc.toLocalDatePlus8())
    }

    @Test
    fun `toLocalDatePlus8 crosses day when utc evening`() {
        val utc = LocalDateTime.parse("2026-03-01T20:30:00")
        assertEquals(LocalDate.parse("2026-03-02"), utc.toLocalDatePlus8())
    }
}
