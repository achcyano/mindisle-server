package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable

@Serializable
enum class SmsPurpose {
    REGISTER,
    RESET_PASSWORD
}

@Serializable
enum class LoginDecision {
    REGISTER_REQUIRED,
    DIRECT_LOGIN_ALLOWED,
    PASSWORD_REQUIRED
}

@Serializable
enum class Gender {
    UNKNOWN,
    MALE,
    FEMALE,
    OTHER
}
