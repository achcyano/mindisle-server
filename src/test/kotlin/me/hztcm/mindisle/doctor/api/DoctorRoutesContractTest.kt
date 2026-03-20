package me.hztcm.mindisle.doctor.api

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.bearerAuth
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import me.hztcm.mindisle.common.configureStatusPages
import me.hztcm.mindisle.config.AppConfig
import me.hztcm.mindisle.doctor.service.DoctorService
import me.hztcm.mindisle.model.DoctorPatientDiagnosisStateResponse
import me.hztcm.mindisle.model.DoctorPatientListResponse
import me.hztcm.mindisle.model.DoctorProfileResponse
import me.hztcm.mindisle.security.JwtService
import me.hztcm.mindisle.security.configureAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoctorRoutesContractTest {
    @Test
    fun `PUT doctors me profile is handled instead of 405`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery { service.upsertProfile(doctorId = 1L, request = any()) } returns DoctorProfileResponse(
            doctorId = 1L,
            phone = "13800138000",
            fullName = "Dr Zhang",
            hospital = "MindIsle Hospital"
        )

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.put("/doctors/me/profile") {
            bearerAuth(doctorAccessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"fullName":"Dr Zhang","hospital":"MindIsle Hospital"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"doctorId\":1"))
    }

    @Test
    fun `GET doctors me patients returns feature-not-supported code for deferred adherence filters`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery { service.listDoctorPatients(doctorId = 1L, query = any()) } returns DoctorPatientListResponse(emptyList())

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.get("/doctors/me/patients?adherenceRateMin=0.8") {
            bearerAuth(doctorAccessToken)
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(body.contains("\"code\":40046"), body)
    }

    @Test
    fun `GET doctors me patients returns sort-invalid code for unsupported sortBy`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery { service.listDoctorPatients(doctorId = 1L, query = any()) } returns DoctorPatientListResponse(emptyList())

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.get("/doctors/me/patients?sortBy=abc") {
            bearerAuth(doctorAccessToken)
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(body.contains("\"code\":40044"), body)
    }

    @Test
    fun `GET doctors me patients returns feature-not-supported code for treatmentPhase query`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery { service.listDoctorPatients(doctorId = 1L, query = any()) } returns DoctorPatientListResponse(emptyList())

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.get("/doctors/me/patients?treatmentPhase=ACUTE") {
            bearerAuth(doctorAccessToken)
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(body.contains("\"code\":40046"), body)
    }

    @Test
    fun `PUT doctors patient grouping rejects treatmentPhase in request body`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.put("/doctors/me/patients/2/grouping") {
            bearerAuth(doctorAccessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"severityGroup":"HIGH","treatmentPhase":"ACUTE"}""")
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(body.contains("\"code\":40046"), body)
    }

    @Test
    fun `PUT doctors patient diagnosis updates diagnosis`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery { service.updatePatientDiagnosis(doctorId = 1L, patientUserId = 2L, request = any()) } returns
            DoctorPatientDiagnosisStateResponse(
                patientUserId = 2L,
                diagnosis = "双相情感障碍",
                updatedAt = "2026-03-20T10:00:00Z"
            )

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.put("/doctors/me/patients/2/diagnosis") {
            bearerAuth(doctorAccessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"diagnosis":"双相情感障碍"}""")
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"diagnosis\":\"双相情感障碍\""), body)
    }
}

private fun Application.installDoctorRouteTestApp(service: DoctorService, jwtService: JwtService) {
    configureStatusPages()
    install(ContentNegotiation) {
        json()
    }
    configureAuth(jwtService)
    routing {
        registerDoctorRoutes(service)
    }
}
