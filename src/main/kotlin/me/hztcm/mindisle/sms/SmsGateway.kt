package me.hztcm.mindisle.sms

import me.hztcm.mindisle.model.SmsPurpose

data class SmsSendResult(
    val outId: String?,
    val bizId: String?
)

interface SmsGateway {
    fun sendSmsCode(
        phone: String,
        purpose: SmsPurpose,
        ttlSeconds: Long,
        intervalSeconds: Long
    ): SmsSendResult

    fun verifySmsCode(
        phone: String,
        purpose: SmsPurpose,
        code: String
    ): Boolean
}
