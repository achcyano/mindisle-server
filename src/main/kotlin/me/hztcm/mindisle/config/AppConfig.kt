package me.hztcm.mindisle.config

import me.hztcm.mindisle.dotenv

data class DbConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val driver: String,
    val maxPoolSize: Int
)

data class AuthConfig(
    val jwtSecret: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlSeconds: Long,
    val loginTicketTtlSeconds: Long,
    val smsCodeTtlSeconds: Long,
    val smsCooldownSeconds: Long,
    val smsDailyLimit: Int,
    val smsVerifyWindowSeconds: Long,
    val smsVerifyMaxAttempts: Int
)

data class SmsProviderConfig(
    val provider: String,
    val countryCode: String,
    val aliyunRegion: String,
    val aliyunEndpoint: String,
    val aliyunAccessKeyId: String?,
    val aliyunAccessKeySecret: String?,
    val aliyunSignName: String?,
    val aliyunTemplateCode: String?,
    val aliyunSchemeName: String?,
    val codeType: Int,
    val codeLength: Int,
    val caseAuthPolicy: Int,
    val duplicatePolicy: Int
)

object AppConfig {
    val db = DbConfig(
        jdbcUrl = dotenv["DB_URL"] ?: "jdbc:mysql://localhost:3306/mindisle?useSSL=false&serverTimezone=UTC&verifyServerCertificate=false&useUnicode=true&characterEncoding=utf8",
        user = dotenv["DB_USER"] ?: "root",
        password = dotenv["DB_PASSWORD"] ?: "root",
        driver = dotenv["DB_DRIVER"] ?: "com.mysql.cj.jdbc.Driver",
        maxPoolSize = dotenv["DB_MAX_POOL_SIZE"]?.toIntOrNull() ?: 10
    )

    val auth = AuthConfig(
        jwtSecret = dotenv["JWT_SECRET"] ?: "mindisle-dev-secret-change-me",
        jwtIssuer = dotenv["JWT_ISSUER"] ?: "mindisle-server",
        jwtAudience = dotenv["JWT_AUDIENCE"] ?: "mindisle-app",
        accessTokenTtlSeconds = dotenv["ACCESS_TOKEN_TTL_SECONDS"]?.toLongOrNull() ?: 1800L,
        refreshTokenTtlSeconds = dotenv["REFRESH_TOKEN_TTL_SECONDS"]?.toLongOrNull() ?: 15_552_000L,
        loginTicketTtlSeconds = dotenv["LOGIN_TICKET_TTL_SECONDS"]?.toLongOrNull() ?: 120L,
        smsCodeTtlSeconds = dotenv["SMS_CODE_TTL_SECONDS"]?.toLongOrNull() ?: 300L,
        smsCooldownSeconds = dotenv["SMS_COOLDOWN_SECONDS"]?.toLongOrNull() ?: 60L,
        smsDailyLimit = dotenv["SMS_DAILY_LIMIT"]?.toIntOrNull() ?: 10,
        smsVerifyWindowSeconds = dotenv["SMS_VERIFY_WINDOW_SECONDS"]?.toLongOrNull() ?: 600L,
        smsVerifyMaxAttempts = dotenv["SMS_VERIFY_MAX_ATTEMPTS"]?.toIntOrNull() ?: 5
    )

    val sms = SmsProviderConfig(
        provider = dotenv["SMS_PROVIDER"] ?: "local",
        countryCode = dotenv["SMS_COUNTRY_CODE"] ?: "86",
        aliyunRegion = dotenv["ALIYUN_SMS_REGION"] ?: "cn-hangzhou",
        aliyunEndpoint = dotenv["ALIYUN_SMS_ENDPOINT"] ?: "dypnsapi.aliyuncs.com",
        aliyunAccessKeyId = dotenv["ALIBABA_CLOUD_ACCESS_KEY_ID"],
        aliyunAccessKeySecret = dotenv["ALIBABA_CLOUD_ACCESS_KEY_SECRET"],
        aliyunSignName = dotenv["ALIYUN_SMS_SIGN_NAME"],
        aliyunTemplateCode = dotenv["ALIYUN_SMS_TEMPLATE_CODE"],
        aliyunSchemeName = dotenv["ALIYUN_SMS_SCHEME_NAME"],
        codeType = dotenv["ALIYUN_SMS_CODE_TYPE"]?.toIntOrNull() ?: 1,
        codeLength = dotenv["ALIYUN_SMS_CODE_LENGTH"]?.toIntOrNull() ?: 6,
        caseAuthPolicy = dotenv["ALIYUN_SMS_CASE_AUTH_POLICY"]?.toIntOrNull() ?: 1,
        duplicatePolicy = dotenv["ALIYUN_SMS_DUPLICATE_POLICY"]?.toIntOrNull() ?: 1
    )
}
