package me.hztcm.mindisle.util

import java.security.MessageDigest

fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

fun sha256Hex(value: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value)
    return digest.joinToString("") { "%02x".format(it) }
}
