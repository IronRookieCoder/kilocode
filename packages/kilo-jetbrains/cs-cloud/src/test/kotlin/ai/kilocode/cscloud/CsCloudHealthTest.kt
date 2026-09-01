package ai.kilocode.cscloud

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CsCloudHealthTest {
    @Test
    fun `parses successful health envelope`() {
        val health = CsCloudHealth.parseHealth("""{"ok":true,"data":{"status":"ok","uptime":3,"version":"1.2.3"}}""")
        assertEquals(true, health.healthy)
        assertEquals("1.2.3", health.version)
    }

    @Test
    fun `parses service error envelope`() {
        val error = assertFailsWith<CsCloudRequestException> {
            CsCloudHealth.parseHealth("""{"ok":false,"error":{"code":"agent_down","message":"agent is unavailable"}}""")
        }
        assertEquals("agent_down", error.code)
        assertEquals("agent is unavailable", error.message)
        assertEquals(200, error.status)
    }

    @Test
    fun `maps HTTP failures to diagnostic codes`() {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(CsCloudRoute.responseInterceptor()).build()
            listOf(401 to "unauthorized", 404 to "not_found", 503 to "unavailable").forEach { (status, code) ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
                val request = Request.Builder().url(server.url("/api/v1/runtime/health")).build()
                val error = assertFailsWith<CsCloudRequestException> { client.newCall(request).execute() }
                assertEquals(code, error.code)
                assertEquals(status, error.status)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `health response interceptor unwraps envelope`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"9.0.0"}}"""))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(CsCloudRoute.responseInterceptor())
                .build()
            val response = client.newCall(Request.Builder().url(server.url("/api/v1/runtime/health")).build()).execute()
            assertEquals("{\"healthy\":true,\"version\":\"9.0.0\",\"capabilities\":[]}", response.body!!.string())
        } finally {
            server.shutdown()
        }
    }
}
