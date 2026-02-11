package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable

@Serializable
data class SendSmsCodeRequest(
    val phone: String,
    val purpose: SmsPurpose
)

@Serializable
data class RegisterRequest(
    val phone: String,
    val smsCode: String,
    val password: String,
    val profile: UpsertProfileRequest? = null
)

@Serializable
data class LoginCheckRequest(
    val phone: String
)

@Serializable
data class LoginCheckResponse(
    val decision: LoginDecision,
    val ticket: String? = null
)

@Serializable
data class DirectLoginRequest(
    val phone: String,
    val ticket: String
)

@Serializable
data class PasswordLoginRequest(
    val phone: String,
    val password: String
)

@Serializable
data class TokenRefreshRequest(
    val refreshToken: String
)

@Serializable
data class ResetPasswordRequest(
    val phone: String,
    val smsCode: String,
    val newPassword: String
)

@Serializable
data class LogoutRequest(
    val refreshToken: String? = null
)

@Serializable
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresInSeconds: Long,
    val refreshTokenExpiresInSeconds: Long
)

@Serializable
data class AuthResponse(
    val userId: Long,
    val token: TokenPairResponse
)
