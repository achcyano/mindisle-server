package me.hztcm.mindisle.auth

import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthInputValidatorTest {
    @Test
    fun `requireDeviceIdHeader returns trimmed value`() {
        assertEquals("device-1", requireDeviceIdHeader("  device-1  "))
    }

    @Test
    fun `requireDeviceIdHeader throws for blank`() {
        val ex = assertFailsWith<AppException> { requireDeviceIdHeader("   ") }
        assertEquals(ErrorCodes.INVALID_REQUEST, ex.code)
    }

    @Test
    fun `validatePassword throws for short password`() {
        val ex = assertFailsWith<AppException> { validatePassword("123") }
        assertEquals(ErrorCodes.PASSWORD_TOO_SHORT, ex.code)
    }

    @Test
    fun `validateSmsCode throws for non digit code`() {
        val ex = assertFailsWith<AppException> { validateSmsCode("12ab56") }
        assertEquals(ErrorCodes.INVALID_SMS_CODE, ex.code)
    }
}
