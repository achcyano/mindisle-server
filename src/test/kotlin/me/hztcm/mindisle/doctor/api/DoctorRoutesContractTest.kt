package me.hztcm.mindisle.doctor.api

import io.ktor.client.request.get
import io.ktor.client.request.post
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
import me.hztcm.mindisle.model.DoctorPatientGroupItem
import me.hztcm.mindisle.model.DoctorPatientGroupListResponse
import me.hztcm.mindisle.model.DoctorPatientListResponse
import me.hztcm.mindisle.model.DoctorPatientProfileResponse
import me.hztcm.mindisle.model.DoctorPatientScaleAnswerRecordItem
import me.hztcm.mindisle.model.DoctorPatientScaleAnswerRecordListResponse
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
    fun `GET doctors me patient groups returns list`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery { service.listPatientGroups(doctorId = 1L) } returns DoctorPatientGroupListResponse(
            items = listOf(
                DoctorPatientGroupItem(
                    severityGroup = "HIGH",
                    patientCount = 3,
                    createdAt = "2026-03-20T10:00:00Z",
                    updatedAt = "2026-03-20T10:00:00Z"
                )
            )
        )

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.get("/doctors/me/patient-groups") {
            bearerAuth(doctorAccessToken)
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"severityGroup\":\"HIGH\""), body)
    }

    @Test
    fun `POST doctors me patient groups creates group`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery { service.createPatientGroup(doctorId = 1L, request = any()) } returns DoctorPatientGroupItem(
            severityGroup = "MID",
            patientCount = 0,
            createdAt = "2026-03-20T10:00:00Z",
            updatedAt = "2026-03-20T10:00:00Z"
        )

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.post("/doctors/me/patient-groups") {
            bearerAuth(doctorAccessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"severityGroup":"MID"}""")
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(body.contains("\"severityGroup\":\"MID\""), body)
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
    fun `PUT doctors patient grouping rejects reason in request body`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.put("/doctors/me/patients/2/grouping") {
            bearerAuth(doctorAccessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"severityGroup":"HIGH","reason":"manual"}""")
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

    @Test
    fun `GET doctors patient profile returns profile for bound patient`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery { service.getPatientProfile(doctorId = 1L, patientUserId = 2L) } returns DoctorPatientProfileResponse(
            patientUserId = 2L,
            phone = "13800138001",
            fullName = "张三",
            gender = me.hztcm.mindisle.model.Gender.MALE,
            birthDate = "1990-01-01",
            heightCm = 175.0,
            weightKg = 68.5,
            waistCm = 82.0,
            usesTcm = false,
            diseaseHistory = listOf("焦虑障碍"),
            updatedAt = "2026-03-20T10:00:00Z"
        )

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.get("/doctors/me/patients/2/profile") {
            bearerAuth(doctorAccessToken)
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"patientUserId\":2"), body)
        assertTrue(body.contains("\"fullName\":\"张三\""), body)
        assertTrue(body.contains("\"weightKg\":68.5"), body)
    }

    @Test
    fun `GET doctors patient scale answer records returns paged result`() = testApplication {
        val service = mockk<DoctorService>()
        val jwtService = JwtService(AppConfig.auth)
        val (doctorAccessToken, _) = jwtService.generateDoctorAccessToken(doctorId = 1L, deviceId = "test-device")
        coEvery {
            service.listPatientScaleAnswerRecords(
                doctorId = 1L,
                patientUserId = 2L,
                limit = 20,
                cursor = null
            )
        } returns DoctorPatientScaleAnswerRecordListResponse(
            patientUserId = 2L,
            items = listOf(
                DoctorPatientScaleAnswerRecordItem(
                    recordId = 1001L,
                    sessionId = 201L,
                    scaleId = 3L,
                    scaleCode = "SCL90",
                    scaleName = "症状自评量表（SCL-90）",
                    versionId = 301L,
                    sessionStatus = "SUBMITTED",
                    sessionSubmittedAt = "2026-03-20T10:00:00Z",
                    questionId = 401L,
                    questionKey = "Q1",
                    questionOrderNo = 1,
                    questionStem = "头痛",
                    rawAnswer = kotlinx.serialization.json.JsonObject(
                        mapOf("optionId" to kotlinx.serialization.json.JsonPrimitive(1))
                    ),
                    normalizedAnswer = kotlinx.serialization.json.JsonObject(
                        mapOf(
                            "optionId" to kotlinx.serialization.json.JsonPrimitive(1),
                            "optionKey" to kotlinx.serialization.json.JsonPrimitive("opt_1")
                        )
                    ),
                    numericScore = 1.0,
                    answeredAt = "2026-03-20T09:50:00Z"
                )
            ),
            nextCursor = "1000"
        )

        application {
            installDoctorRouteTestApp(service, jwtService)
        }

        val response = client.get("/doctors/me/patients/2/scale-answer-records") {
            bearerAuth(doctorAccessToken)
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"recordId\":1001"), body)
        assertTrue(body.contains("\"nextCursor\":\"1000\""), body)
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
