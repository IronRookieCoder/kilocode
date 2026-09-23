package ai.kilocode.backend.telemetry

import ai.kilocode.backend.cli.KiloBackendHttpClients
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.TimeUnit
import ai.kilocode.stability.Fixture
import kotlinx.serialization.json.jsonPrimitive

class KiloBackendTelemetryTest {
    @Test
    fun `compressed HTTP failure is captured as the exact decoded response before redaction`() = runBlocking {
        MockWebServer().use { server ->
            val compressed = okio.Buffer()
            okio.GzipSink(compressed).use { sink ->
                val plain = okio.Buffer().writeUtf8("""{"error":"compressed failure","password":"gzip-secret"}""")
                sink.write(plain, plain.size)
            }
            server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip").setBody(compressed))
            server.start()
            Fixture().use { fixture ->
                fixture.enableDiagnostics()
                val http = KiloBackendHttpClients.api("secret")
                try {
                    KiloBackendTelemetry(operations = fixture.operations).capture(http, server.url("/").toString(), "event", emptyMap())
                    fixture.flush()
                    assertTrue(fixture.payload("response").contains("compressed failure"))
                    assertTrue(!fixture.facts().joinToString().contains("gzip-secret"))
                } finally {
                    KiloBackendHttpClients.shutdown(http)
                }
            }
        }
    }
    @Test
    fun `capture 404 persists actual request response headers stack and operation`() = runBlocking {
        MockWebServer().use { server ->
            val body = """{"error":"missing telemetry route","password":"response-secret"}"""
            server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setHeader("Set-Cookie", "session=cookie-secret").setBody(body))
            server.start()
            Fixture().use { fixture ->
                fixture.enableDiagnostics()
                val http = KiloBackendHttpClients.api("request-secret")
                try {
                    KiloBackendTelemetry(operations = fixture.operations).capture(http, server.url("/").toString(), "Failure event", mapOf("password" to "payload-secret"))
                    fixture.flush()
                    val facts = fixture.facts()
                    val incident = facts.single { it.name == "diagnostic.reported" }
                    assertEquals("404", incident.data.getValue("http_status").jsonPrimitive.content)
                    assertEquals("POST", incident.data.getValue("method").jsonPrimitive.content)
                    assertEquals("/telemetry/capture", incident.data.getValue("route").jsonPrimitive.content)
                    assertTrue(fixture.payload("request").contains("Failure event"))
                    assertTrue(fixture.payload("response").contains("missing telemetry route"))
                    assertTrue(fixture.payload("stack").contains("KiloBackendTelemetry"))
                    assertTrue(fixture.payload("headers").contains("redacted"))
                    val end = facts.single { it.name == "rpc" && it.data["phase"]?.jsonPrimitive?.content == "end" }
                    assertEquals(end.context["operation_id"], incident.context["operation_id"])
                    listOf("request-secret", "response-secret", "cookie-secret", "payload-secret").forEach { secret ->
                        assertTrue(!facts.joinToString().contains(secret), secret)
                    }
                } finally {
                    KiloBackendHttpClients.shutdown(http)
                }
            }
        }
    }
    @Test
    fun `capture posts to telemetry endpoint with auth`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val http = KiloBackendHttpClients.api("secret")
        try {
            KiloBackendTelemetry().capture(http, "http://127.0.0.1:${server.port}", "Test Event", mapOf("source" to "test"))

            val req = server.takeRequest()
            assertEquals("/telemetry/capture", req.path)
            assertTrue(req.getHeader("Authorization")?.startsWith("Basic ") == true)
            val body = req.body.readUtf8()
            assertTrue(body.contains("\"event\":\"Test Event\""))
            assertTrue(!body.contains("JetBrains"))
            assertTrue(body.contains("\"platform\":\"jetbrains\""))
            assertTrue(!body.contains("appName"))
            assertTrue(body.contains("source"))
        } finally {
            KiloBackendHttpClients.shutdown(http)
            server.shutdown()
        }
    }

    @Test
    fun `set enabled posts to telemetry endpoint`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val http = KiloBackendHttpClients.api("secret")
        try {
            KiloBackendTelemetry().setEnabled(http, "http://127.0.0.1:${server.port}", true)

            val req = server.takeRequest()
            assertEquals("/telemetry/setEnabled", req.path)
            assertTrue(req.body.readUtf8().contains("enabled"))
        } finally {
            KiloBackendHttpClients.shutdown(http)
            server.shutdown()
        }
    }

    @Test
    fun `capture preserves a connection base path`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val http = KiloBackendHttpClients.api("secret")
        try {
            KiloBackendTelemetry().capture(http, "http://localhost:${server.port}/api/v1", "Test Event", emptyMap())

            assertEquals("/api/v1/telemetry/capture", server.takeRequest().path)
        } finally {
            KiloBackendHttpClients.shutdown(http)
            server.shutdown()
        }
    }

    @Test
    fun `capture failure does not throw`() = runBlocking {
        KiloBackendTelemetry().capture(null, null, "Test Event", emptyMap())
    }

    @Test
    fun `dev mode does not post capture`() = runBlocking {
        System.setProperty("idea.plugin.in.sandbox.mode", "true")
        val server = MockWebServer()
        server.start()
        val http = KiloBackendHttpClients.api("secret")
        try {
            KiloBackendTelemetry().capture(http, "http://127.0.0.1:${server.port}", "Test Event", emptyMap())

            assertEquals(null, server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally {
            System.clearProperty("idea.plugin.in.sandbox.mode")
            KiloBackendHttpClients.shutdown(http)
            server.shutdown()
        }
    }
}
