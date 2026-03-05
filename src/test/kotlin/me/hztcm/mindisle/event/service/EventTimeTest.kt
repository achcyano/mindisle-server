package me.hztcm.mindisle.event.service

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTimeTest {
    @Test
    fun `nextRecurringDueByDays returns first due when not reached`() {
        val anchor = LocalDateTime.parse("2026-03-01T08:00:00")
        val now = LocalDateTime.parse("2026-03-15T07:59:59")

        val due = nextRecurringDueByDays(anchor, intervalDays = 30, now = now)

        assertEquals(LocalDateTime.parse("2026-03-31T08:00:00"), due)
    }

    @Test
    fun `nextRecurringDueByDays rolls to next cycle after overdue`() {
        val anchor = LocalDateTime.parse("2026-01-01T08:00:00")
        val now = LocalDateTime.parse("2026-04-20T09:00:00")

        val due = nextRecurringDueByDays(anchor, intervalDays = 30, now = now)

        assertEquals(LocalDateTime.parse("2026-05-01T08:00:00"), due)
    }

    @Test
    fun `nextRecurringDueByMonths handles end-of-month correctly`() {
        val anchor = LocalDateTime.parse("2026-01-31T10:00:00")
        val now = LocalDateTime.parse("2026-02-28T09:00:00")

        val due = nextRecurringDueByMonths(anchor, intervalMonths = 1, now = now)

        assertEquals(LocalDateTime.parse("2026-02-28T10:00:00"), due)
    }

    @Test
    fun `parseRedoIntervalDays falls back to default when missing or invalid`() {
        val defaultValue = 30

        assertEquals(defaultValue, parseRedoIntervalDays(null, defaultValue))
        assertEquals(defaultValue, parseRedoIntervalDays("""{"redoIntervalDays":0}""", defaultValue))
        assertEquals(defaultValue, parseRedoIntervalDays("""{"redoIntervalDays":"abc"}""", defaultValue))
        assertEquals(14, parseRedoIntervalDays("""{"redoIntervalDays":"14"}""", defaultValue))
        assertEquals(21, parseRedoIntervalDays("""{"redoIntervalDays":21}""", defaultValue))
    }
}
