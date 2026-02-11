package me.hztcm.mindisle.sms

import me.hztcm.mindisle.config.SmsProviderConfig

fun createSmsGateway(config: SmsProviderConfig): SmsGateway? {
    return when (config.provider.lowercase()) {
        "local", "none", "disabled" -> null
        "aliyun" -> AliyunSmsGateway(config)
        else -> error("Unsupported SMS provider: ${config.provider}")
    }
}
