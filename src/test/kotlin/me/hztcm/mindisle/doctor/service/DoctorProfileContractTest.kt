package me.hztcm.mindisle.doctor.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.hztcm.mindisle.common.AppException
import me.hztcm.mindisle.common.ErrorCodes
import me.hztcm.mindisle.model.BindingHistoryItem
import me.hztcm.mindisle.model.DoctorBindingInfoResponse
import me.hztcm.mindisle.model.DoctorProfileResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DoctorProfileContractTest {
    @Test
    fun `normalizeDoctorProfileField trims value`() {
        assertEquals("Example Hospital", normalizeDoctorProfileField("hospital", "  Example Hospital  ", 200))
    }

    @Test
    fun `normalizeDoctorProfileField keeps null as no update`() {
        assertEquals(null, normalizeDoctorProfileField("hospital", null, 200))
    }

    @Test
    fun `normalizeDoctorProfileField rejects blank string`() {
        val ex = assertFailsWith<AppException> {
            normalizeDoctorProfileField("fullName", "   ", 200)
        }
        assertEquals(ErrorCodes.INVALID_REQUEST, ex.code)
    }

    @Test
    fun `DoctorProfileResponse exposes phone full name and hospital`() {
        val json = Json.encodeToString(
            DoctorProfileResponse(
                doctorId = 1,
                phone = "13800138000",
                fullName = "Dr Zhang",
                hospital = "Example Hospital"
            )
        )
        assertEquals(
            """{"doctorId":1,"phone":"13800138000","fullName":"Dr Zhang","hospital":"Example Hospital"}""",
            json
        )
    }

    @Test
    fun `binding responses only expose doctor name and hospital`() {
        val bindingInfoJson = Json.encodeToString(
            DoctorBindingInfoResponse(
                bindingId = 1,
                doctorId = 2,
                doctorName = "Dr Zhang",
                doctorHospital = "Example Hospital",
                boundAt = "2026-03-11T08:10:00Z"
            )
        )
        val historyJson = Json.encodeToString(
            BindingHistoryItem(
                bindingId = 1,
                doctorId = 2,
                doctorName = "Dr Zhang",
                doctorHospital = "Example Hospital",
                status = "ACTIVE",
                boundAt = "2026-03-11T08:10:00Z"
            )
        )
        assertEquals(
            """{"bindingId":1,"doctorId":2,"doctorName":"Dr Zhang","doctorHospital":"Example Hospital","boundAt":"2026-03-11T08:10:00Z"}""",
            bindingInfoJson
        )
        assertEquals(
            """{"bindingId":1,"doctorId":2,"doctorName":"Dr Zhang","doctorHospital":"Example Hospital","status":"ACTIVE","boundAt":"2026-03-11T08:10:00Z"}""",
            historyJson
        )
    }
}
