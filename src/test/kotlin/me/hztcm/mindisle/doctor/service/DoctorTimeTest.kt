package me.hztcm.mindisle.doctor.service

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.LocalDateTime

class DoctorTimeTest {
    @Test
    fun `toIsoOffsetPlus8 converts utc timestamp to plus8`() {
        val utc = LocalDateTime.of(2026, 2, 24, 7, 45, 51)
        assertEquals("2026-02-24T15:45:51+08:00", utc.toIsoOffsetPlus8())
    }

    @Test
    fun `parseInstantToUtcDateTime parses iso instant to utc local datetime`() {
        val parsed = parseInstantToUtcDateTime("2026-02-24T07:45:51Z")
        assertEquals(LocalDateTime.of(2026, 2, 24, 7, 45, 51), parsed)
    }
}
