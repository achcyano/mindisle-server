package me.hztcm.mindisle.safety

import me.hztcm.mindisle.safety.service.SafetyScanner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyScannerTest {
    @Test
    fun detectsChineseCrisisKeywords() {
        val result = SafetyScanner.scan("我真的不想活了")
        assertTrue(result.highRisk)
        assertTrue(result.reasons.contains("SUICIDE_SELF_HARM_KEYWORD"))
    }

    @Test
    fun ignoresNormalMoodText() {
        val result = SafetyScanner.scan("今天心情一般，睡得还行")
        assertFalse(result.highRisk)
    }
}
