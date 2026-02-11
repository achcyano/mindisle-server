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
    val smsDailyLimit: Int
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
        smsDailyLimit = dotenv["SMS_DAILY_LIMIT"]?.toIntOrNull() ?: 10
    )
}
