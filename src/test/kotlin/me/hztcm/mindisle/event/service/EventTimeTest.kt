package me.hztcm.mindisle.event.service

import java.time.LocalDateTime
import me.hztcm.mindisle.model.ScaleDeliveryModeDto
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
    fun `nextRecurringDueByDays keeps missed due date after overdue`() {
        val anchor = LocalDateTime.parse("2026-01-01T08:00:00")
        val now = LocalDateTime.parse("2026-04-20T09:00:00")

        val due = nextRecurringDueByDays(anchor, intervalDays = 30, now = now)

        assertEquals(LocalDateTime.parse("2026-01-31T08:00:00"), due)
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

    @Test
    fun `parseEventScaleDelivery returns webview path from config`() {
        val delivery = parseEventScaleDelivery(
            """
                {
                  "delivery": {
                    "mode": "WEBVIEW",
                    "webPath": "/web/scales/TESS"
                  }
                }
            """.trimIndent()
        )

        assertEquals(ScaleDeliveryModeDto.WEBVIEW, delivery.mode)
        assertEquals("/web/scales/TESS", delivery.webPath)
        assertEquals("/web/scales/TESS?sessionId=42", delivery.historyWebPath(42L))
    }

    @Test
    fun `parseEventScaleDelivery falls back to native for invalid config`() {
        val delivery = parseEventScaleDelivery("""{"delivery":{"mode":"WEBVIEW","webPath":"https://example.com"}}""")

        assertEquals(ScaleDeliveryModeDto.NATIVE, delivery.mode)
        assertEquals(null, delivery.webPath)
    }

    @Test
    fun `normalizeImmediateDueAt keeps past anchor unchanged`() {
        val anchor = LocalDateTime.parse("2026-03-31T13:09:06")
        val now = LocalDateTime.parse("2026-03-31T13:09:50")

        val due = normalizeImmediateDueAt(anchor, now)

        assertEquals(anchor, due)
    }

    @Test
    fun `normalizeImmediateDueAt clamps future anchor to now`() {
        val anchor = LocalDateTime.parse("2026-03-31T21:09:06")
        val now = LocalDateTime.parse("2026-03-31T13:09:50")

        val due = normalizeImmediateDueAt(anchor, now)

        assertEquals(now, due)
    }
}
