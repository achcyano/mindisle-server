package me.hztcm.mindisle.medication.service

import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.CreateMedicationRequest
import me.hztcm.mindisle.model.MedicationDoseUnit
import me.hztcm.mindisle.model.MedicationStrengthUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.LocalDate

class MedicationValidatorsTest {
    @Test
    fun `create request accepts tablet with strength`() {
        val payload = MedicationValidators.validateCreateRequest(
            request = CreateMedicationRequest(
                drugName = "阿司匹林",
                doseTimes = listOf("12:30", "08:00", "08:00"),
                endDate = "2026-03-10",
                doseAmount = 1.0,
                doseUnit = MedicationDoseUnit.TABLET,
                tabletStrengthAmount = 500.0,
                tabletStrengthUnit = MedicationStrengthUnit.MG
            ),
            minEndDateInclusive = LocalDate.parse("2026-03-01")
        )

        assertEquals(listOf("08:00", "12:30"), payload.doseTimes)
        assertEquals(MedicationDoseUnit.TABLET, payload.doseUnit)
        assertEquals(MedicationStrengthUnit.MG, payload.tabletStrengthUnit)
    }

    @Test
    fun `create request rejects non strict hh mm`() {
        val ex = assertFailsWith<AppException> {
            MedicationValidators.validateCreateRequest(
                request = CreateMedicationRequest(
                    drugName = "维生素C",
                    doseTimes = listOf("8:00"),
                    endDate = "2026-03-10",
                    doseAmount = 100.0,
                    doseUnit = MedicationDoseUnit.MG
                ),
                minEndDateInclusive = LocalDate.parse("2026-03-01")
            )
        }

        assertEquals(ErrorCodes.MEDICATION_INVALID_ARGUMENT, ex.code)
    }

    @Test
    fun `create request rejects tablet without strength`() {
        val ex = assertFailsWith<AppException> {
            MedicationValidators.validateCreateRequest(
                request = CreateMedicationRequest(
                    drugName = "布洛芬",
                    doseTimes = listOf("08:00"),
                    endDate = "2026-03-10",
                    doseAmount = 1.0,
                    doseUnit = MedicationDoseUnit.TABLET
                ),
                minEndDateInclusive = LocalDate.parse("2026-03-01")
            )
        }

        assertEquals(ErrorCodes.MEDICATION_INVALID_ARGUMENT, ex.code)
    }

    @Test
    fun `create request rejects end date earlier than minimum`() {
        val ex = assertFailsWith<AppException> {
            MedicationValidators.validateCreateRequest(
                request = CreateMedicationRequest(
                    drugName = "二甲双胍",
                    doseTimes = listOf("08:00"),
                    endDate = "2026-02-28",
                    doseAmount = 500.0,
                    doseUnit = MedicationDoseUnit.MG
                ),
                minEndDateInclusive = LocalDate.parse("2026-03-01")
            )
        }

        assertEquals(ErrorCodes.MEDICATION_INVALID_ARGUMENT, ex.code)
    }
}
