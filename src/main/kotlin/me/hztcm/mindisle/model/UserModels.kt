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
data class UpsertBasicProfileRequest(
    val fullName: String? = null,
    val gender: Gender? = null,
    val birthDate: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val diseaseHistory: List<String>? = null
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

@Serializable
data class UserBasicProfileResponse(
    val userId: Long,
    val fullName: String? = null,
    val gender: Gender = Gender.UNKNOWN,
    val birthDate: String? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val diseaseHistory: List<String> = emptyList()
)

@Serializable
data class UserAvatarMetaResponse(
    val avatarUrl: String,
    val contentType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val updatedAt: String
)
