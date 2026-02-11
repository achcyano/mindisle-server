package me.hztcm.mindisle.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import me.hztcm.mindisle.config.AuthConfig
import java.time.Instant
import java.util.Date

class JwtService(private val config: AuthConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    val verifier: JWTVerifier = JWT
        .require(algorithm)
        .withIssuer(config.jwtIssuer)
        .withAudience(config.jwtAudience)
        .build()

    fun generateAccessToken(userId: Long, deviceId: String): Pair<String, Long> {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(config.accessTokenTtlSeconds)
        val token = JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expiresAt))
            .withClaim("uid", userId)
            .withClaim("did", deviceId)
            .sign(algorithm)
        return token to config.accessTokenTtlSeconds
    }
}
