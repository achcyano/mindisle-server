package me.hztcm.mindisle.scale.api

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import me.hztcm.mindisle.model.ListScaleHistoryResponse
import me.hztcm.mindisle.model.ListScalesResponse
import me.hztcm.mindisle.model.ScaleDeliveryModeDto
import me.hztcm.mindisle.model.ScaleHistoryItem
import me.hztcm.mindisle.model.ScaleListItem
import me.hztcm.mindisle.model.ScaleStatusDto
import me.hztcm.mindisle.scale.service.ScaleService
import me.hztcm.mindisle.security.JwtService
import me.hztcm.mindisle.security.configureAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaleRoutesContractTest {
    @Test
    fun `GET scales returns webview delivery fields for TESS`() = testApplication {
        val service = mockScaleService()
        val jwtService = JwtService(AppConfig.auth)
        val (accessToken, _) = jwtService.generateAccessToken(userId = 1L, deviceId = "test-device")
        coEvery {
            service.listScales(
                userId = 1L,
                limit = 20,
                cursor = null,
                status = null
            )
        } returns ListScalesResponse(
            items = listOf(
                ScaleListItem(
                    scaleId = 10L,
                    code = "TESS",
                    name = "TESS 药物副反应自评",
                    description = "药物副反应自评",
                    status = ScaleStatusDto.PUBLISHED,
                    latestVersion = 1,
                    deliveryMode = ScaleDeliveryModeDto.WEBVIEW,
                    webPath = "/web/scales/TESS"
                )
            )
        )

        application {
            installScaleRouteTestApp(service, jwtService)
        }

        val response = client.get("/scales") {
            bearerAuth(accessToken)
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"code\":\"TESS\""), body)
        assertTrue(body.contains("\"deliveryMode\":\"WEBVIEW\""), body)
        assertTrue(body.contains("\"webPath\":\"/web/scales/TESS\""), body)
    }

    @Test
    fun `GET scales history returns webview record path with session id`() = testApplication {
        val service = mockScaleService()
        val jwtService = JwtService(AppConfig.auth)
        val (accessToken, _) = jwtService.generateAccessToken(userId = 1L, deviceId = "test-device")
        coEvery {
            service.listHistory(
                userId = 1L,
                limit = 20,
                cursor = null
            )
        } returns ListScaleHistoryResponse(
            items = listOf(
                ScaleHistoryItem(
                    sessionId = 99L,
                    scaleId = 10L,
                    scaleCode = "TESS",
                    scaleName = "TESS 药物副反应自评",
                    versionId = 100L,
                    version = 1,
                    progress = 100,
                    totalScore = 8.0,
                    submittedAt = "2026-05-15T10:00:00+08:00",
                    updatedAt = "2026-05-15T10:00:00+08:00",
                    deliveryMode = ScaleDeliveryModeDto.WEBVIEW,
                    webPath = "/web/scales/TESS?sessionId=99"
                )
            )
        )

        application {
            installScaleRouteTestApp(service, jwtService)
        }

        val response = client.get("/scales/history") {
            bearerAuth(accessToken)
        }
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"scaleCode\":\"TESS\""), body)
        assertTrue(body.contains("\"deliveryMode\":\"WEBVIEW\""), body)
        assertTrue(body.contains("\"webPath\":\"/web/scales/TESS?sessionId=99\""), body)
    }

    @Test
    fun `GET TESS web page returns html`() = testApplication {
        application {
            routing {
                registerScaleWebRoutes()
            }
        }

        val response = client.get("/web/scales/TESS")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue((response.headers["Content-Type"] ?: "").startsWith(ContentType.Text.Html.toString()))
        assertTrue(body.contains("TESS 药物副反应自评"), body)
        assertTrue(body.contains("accessToken"), body)
    }

    private fun mockScaleService(): ScaleService {
        return mockk(relaxed = true) {
            coEvery { listScales(any(), any(), any(), any()) } returns ListScalesResponse(emptyList())
            coEvery { listHistory(any(), any(), any()) } returns ListScaleHistoryResponse(emptyList())
        }
    }
}

private fun Application.installScaleRouteTestApp(service: ScaleService, jwtService: JwtService) {
    configureStatusPages()
    install(ContentNegotiation) {
        json()
    }
    configureAuth(jwtService)
    routing {
        registerScaleRoutes(service)
    }
}
