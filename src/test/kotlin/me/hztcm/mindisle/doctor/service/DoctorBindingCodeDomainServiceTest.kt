package me.hztcm.mindisle.doctor.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.GenerateBindingCodeResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DoctorBindingCodeDomainServiceTest {
    @Test
    fun `formatDoctorBindingCode keeps five digits and leading zeros`() {
        assertEquals("00007", formatDoctorBindingCode(7))
        assertEquals("99999", formatDoctorBindingCode(99_999))
    }

    @Test
    fun `generateDoctorBindingCode always returns five digit number`() {
        repeat(200) {
            val code = generateDoctorBindingCode()
            assertTrue(isDoctorBindingCodeFormatValid(code))
        }
    }

    @Test
    fun `normalizeAndValidateDoctorBindingCode trims whitespace`() {
        assertEquals("01234", normalizeAndValidateDoctorBindingCode(" 01234 "))
    }

    @Test
    fun `normalizeAndValidateDoctorBindingCode rejects non five digit values`() {
        val invalidValues = listOf("1234", "123456", "12a45")
        invalidValues.forEach { raw ->
            val ex = assertFailsWith<AppException> { normalizeAndValidateDoctorBindingCode(raw) }
            assertEquals(ErrorCodes.DOCTOR_BINDING_CODE_INVALID, ex.code)
        }
    }

    @Test
    fun `GenerateBindingCodeResponse does not expose qrPayload field`() {
        val payload = GenerateBindingCodeResponse(
            code = "01234",
            expiresAt = "2026-03-11T08:10:00Z"
        )
        val json = Json.encodeToString(payload)
        assertTrue(json.contains("\"code\""))
        assertTrue(json.contains("\"expiresAt\""))
        assertTrue(!json.contains("qrPayload"))
    }
}
