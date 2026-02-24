package me.hztcm.mindisle.scale.service

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaleTimeTest {
    @Test
    fun shouldConvertUtcToUtcPlus8IsoOffset() {
        val utcTime = LocalDateTime.of(2026, 2, 24, 13, 45, 0)
        val formatted = utcTime.toIsoOffsetPlus8()

        assertEquals("2026-02-24T21:45:00+08:00", formatted)
    }
}
