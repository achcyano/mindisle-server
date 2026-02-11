package me.hztcm.mindisle.util

import java.security.SecureRandom
import java.util.Base64

private val secureRandom = SecureRandom()

fun generateSecureToken(byteSize: Int = 48): String {
    val bytes = ByteArray(byteSize)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

fun generateSmsCode(): String = (secureRandom.nextInt(900_000) + 100_000).toString()
