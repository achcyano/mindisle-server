package me.hztcm.mindisle.doctor.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoctorExportDomainServiceTest {
    @Test
    fun `normalizeScaleCodeForExport converts common scale codes`() {
        assertEquals("SCL-90", normalizeScaleCodeForExport("SCL90"))
        assertEquals("PHQ-9", normalizeScaleCodeForExport("PHQ9"))
        assertEquals("EPQ-88", normalizeScaleCodeForExport("EPQ88"))
        assertEquals("PSQI", normalizeScaleCodeForExport("PSQI"))
    }

    @Test
    fun `escapeCsvCell escapes comma quote and newline`() {
        assertEquals("\"a,b\"", escapeCsvCell("a,b"))
        assertEquals("\"a\"\"b\"", escapeCsvCell("a\"b"))
        assertEquals("\"a\nb\"", escapeCsvCell("a\nb"))
        assertEquals("plain", escapeCsvCell("plain"))
    }

    @Test
    fun `buildCsvBytes keeps utf8 bom and escaped rows`() {
        val bytes = buildCsvBytes(
            headers = listOf("A", "B"),
            rows = listOf(
                listOf("v1", "x,y"),
                listOf("line1\nline2", "z\"q")
            )
        )

        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])

        val text = bytes.copyOfRange(3, bytes.size).toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("A,B\r\n"), text)
        assertTrue(text.contains("v1,\"x,y\"\r\n"), text)
        assertTrue(text.contains("\"line1\nline2\",\"z\"\"q\"\r\n"), text)
    }

    @Test
    fun `enrichRawAnswerJsonForExport appends option labels`() {
        val enriched = enrichRawAnswerJsonForExport(
            rawAnswerJson = """{"optionId":2}""",
            optionLabelById = mapOf(2L to "中度"),
            optionLabelByKey = emptyMap()
        )
        val parsed = Json.parseToJsonElement(enriched).jsonObject
        assertEquals("2", parsed["optionId"]?.jsonPrimitive?.content)
        assertEquals("中度", parsed["optionLabel"]?.jsonPrimitive?.content)
    }

    @Test
    fun `enrichRawAnswerJsonForExport appends optionLabels for array answers`() {
        val enriched = enrichRawAnswerJsonForExport(
            rawAnswerJson = """{"optionIds":[1,3]}""",
            optionLabelById = mapOf(1L to "几乎没有", 3L to "明显"),
            optionLabelByKey = emptyMap()
        )
        val parsed = Json.parseToJsonElement(enriched).jsonObject
        val labels = parsed["optionLabels"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertEquals(listOf("几乎没有", "明显"), labels)
    }
}
