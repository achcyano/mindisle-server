package me.hztcm.mindisle.medication.service

import io.ktor.http.HttpStatusCode
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.CreateMedicationRequest
import me.hztcm.mindisle.model.MedicationDoseUnit
import me.hztcm.mindisle.model.MedicationStrengthUnit
import me.hztcm.mindisle.model.UpdateMedicationRequest
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

internal data class ValidatedMedicationPayload(
    val drugName: String,
    val doseTimes: List<String>,
    val endDate: LocalDate,
    val doseAmount: BigDecimal,
    val doseUnit: MedicationDoseUnit,
    val tabletStrengthAmount: BigDecimal?,
    val tabletStrengthUnit: MedicationStrengthUnit?
)

internal object MedicationValidators {
    private const val DRUG_NAME_MAX_LENGTH = 200
    private const val DOSE_TIMES_MAX_SIZE = 16
    private const val DECIMAL_SCALE = 3
    private val DOSE_AMOUNT_MIN = BigDecimal("0")
    private val DOSE_AMOUNT_MAX = BigDecimal("100000")
    private val STRICT_HH_MM_REGEX = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    fun validateCreateRequest(
        request: CreateMedicationRequest,
        minEndDateInclusive: LocalDate
    ): ValidatedMedicationPayload {
        return validateRequest(
            drugName = request.drugName,
            doseTimes = request.doseTimes,
            endDateRaw = request.endDate,
            doseAmountRaw = request.doseAmount,
            doseUnit = request.doseUnit,
            tabletStrengthAmountRaw = request.tabletStrengthAmount,
            tabletStrengthUnit = request.tabletStrengthUnit,
            minEndDateInclusive = minEndDateInclusive
        )
    }

    fun validateUpdateRequest(
        request: UpdateMedicationRequest,
        minEndDateInclusive: LocalDate
    ): ValidatedMedicationPayload {
        return validateRequest(
            drugName = request.drugName,
            doseTimes = request.doseTimes,
            endDateRaw = request.endDate,
            doseAmountRaw = request.doseAmount,
            doseUnit = request.doseUnit,
            tabletStrengthAmountRaw = request.tabletStrengthAmount,
            tabletStrengthUnit = request.tabletStrengthUnit,
            minEndDateInclusive = minEndDateInclusive
        )
    }

    private fun validateRequest(
        drugName: String,
        doseTimes: List<String>,
        endDateRaw: String,
        doseAmountRaw: Double,
        doseUnit: MedicationDoseUnit,
        tabletStrengthAmountRaw: Double?,
        tabletStrengthUnit: MedicationStrengthUnit?,
        minEndDateInclusive: LocalDate
    ): ValidatedMedicationPayload {
        val normalizedDrugName = normalizeDrugName(drugName)
        val normalizedDoseTimes = normalizeDoseTimes(doseTimes)
        val endDate = parseEndDate(endDateRaw, minEndDateInclusive)
        val doseAmount = parsePositiveAmount("doseAmount", doseAmountRaw)
        val tabletStrengthAmount = tabletStrengthAmountRaw?.let { parsePositiveAmount("tabletStrengthAmount", it) }

        when (doseUnit) {
            MedicationDoseUnit.TABLET -> {
                if (tabletStrengthAmount == null || tabletStrengthUnit == null) {
                    throw invalid("tabletStrengthAmount and tabletStrengthUnit are required when doseUnit=TABLET")
                }
            }

            MedicationDoseUnit.MG, MedicationDoseUnit.G -> {
                if (tabletStrengthAmount != null || tabletStrengthUnit != null) {
                    throw invalid("tabletStrengthAmount and tabletStrengthUnit must be null unless doseUnit=TABLET")
                }
            }
        }

        return ValidatedMedicationPayload(
            drugName = normalizedDrugName,
            doseTimes = normalizedDoseTimes,
            endDate = endDate,
            doseAmount = doseAmount,
            doseUnit = doseUnit,
            tabletStrengthAmount = tabletStrengthAmount,
            tabletStrengthUnit = tabletStrengthUnit
        )
    }

    private fun normalizeDrugName(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) {
            throw invalid("drugName cannot be blank")
        }
        ensureNoControlChars("drugName", value)
        val charCount = value.codePointCount(0, value.length)
        if (charCount > DRUG_NAME_MAX_LENGTH) {
            throw invalid("drugName exceeds $DRUG_NAME_MAX_LENGTH characters")
        }
        return value
    }

    private fun normalizeDoseTimes(rawTimes: List<String>): List<String> {
        if (rawTimes.isEmpty()) {
            throw invalid("doseTimes cannot be empty")
        }
        if (rawTimes.size > DOSE_TIMES_MAX_SIZE) {
            throw invalid("doseTimes exceeds $DOSE_TIMES_MAX_SIZE items")
        }

        val normalized = LinkedHashSet<String>()
        rawTimes.forEachIndexed { index, raw ->
            val value = raw.trim()
            if (value.isEmpty()) {
                throw invalid("doseTimes[$index] cannot be blank")
            }
            if (!STRICT_HH_MM_REGEX.matches(value)) {
                throw invalid("doseTimes[$index] must be strict HH:mm format")
            }
            normalized.add(value)
        }
        if (normalized.isEmpty()) {
            throw invalid("doseTimes cannot be empty")
        }
        return normalized.sorted()
    }

    private fun parseEndDate(raw: String, minEndDateInclusive: LocalDate): LocalDate {
        val endDate = runCatching { LocalDate.parse(raw.trim()) }.getOrElse {
            throw invalid("endDate must be ISO-8601 format (yyyy-MM-dd)")
        }
        if (endDate < minEndDateInclusive) {
            throw invalid("endDate must be on or after $minEndDateInclusive")
        }
        return endDate
    }

    private fun parsePositiveAmount(field: String, raw: Double): BigDecimal {
        if (!raw.isFinite()) {
            throw invalid("$field must be a finite number")
        }
        val value = BigDecimal.valueOf(raw).setScale(DECIMAL_SCALE, RoundingMode.HALF_UP)
        if (value <= DOSE_AMOUNT_MIN || value > DOSE_AMOUNT_MAX) {
            throw invalid("$field must be > 0 and <= $DOSE_AMOUNT_MAX")
        }
        return value
    }

    private fun ensureNoControlChars(field: String, value: String) {
        if (value.any { it.isISOControl() }) {
            throw invalid("$field contains control characters")
        }
    }

    private fun invalid(message: String): AppException {
        return AppException(
            code = ErrorCodes.MEDICATION_INVALID_ARGUMENT,
            message = message,
            status = HttpStatusCode.BadRequest
        )
    }
}
