package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable

@Serializable
data class UpsertProfileRequest(
    val fullName: String? = null,
    val gender: Gender? = null,
    val birthDate: String? = null,
    val weightKg: Double? = null,
    val familyHistory: List<String>? = null,
    val medicalHistory: List<String>? = null,
    val medicationHistory: List<String>? = null
)

@Serializable
data class UserProfileResponse(
    val userId: Long,
    val phone: String,
    val fullName: String? = null,
    val gender: Gender = Gender.UNKNOWN,
    val birthDate: String? = null,
    val weightKg: Double? = null,
    val familyHistory: List<String> = emptyList(),
    val medicalHistory: List<String> = emptyList(),
    val medicationHistory: List<String> = emptyList()
)
