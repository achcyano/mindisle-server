package me.hztcm.mindisle.model

import kotlinx.serialization.Serializable

@Serializable
enum class MedicationDoseUnit {
    MG,
    G,
    TABLET
}

@Serializable
enum class MedicationStrengthUnit {
    MG,
    G
}

@Serializable
data class CreateMedicationRequest(
    val drugName: String,
    val doseTimes: List<String>,
    val endDate: String,
    val doseAmount: Double,
    val doseUnit: MedicationDoseUnit,
    val tabletStrengthAmount: Double? = null,
    val tabletStrengthUnit: MedicationStrengthUnit? = null
)

@Serializable
data class UpdateMedicationRequest(
    val drugName: String,
    val doseTimes: List<String>,
    val endDate: String,
    val doseAmount: Double,
    val doseUnit: MedicationDoseUnit,
    val tabletStrengthAmount: Double? = null,
    val tabletStrengthUnit: MedicationStrengthUnit? = null
)

@Serializable
data class MedicationItemResponse(
    val medicationId: Long,
    val drugName: String,
    val doseTimes: List<String>,
    val recordedDate: String,
    val endDate: String,
    val doseAmount: Double,
    val doseUnit: MedicationDoseUnit,
    val tabletStrengthAmount: Double? = null,
    val tabletStrengthUnit: MedicationStrengthUnit? = null,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class MedicationListResponse(
    val items: List<MedicationItemResponse>,
    val activeCount: Int,
    val nextCursor: String? = null
)
