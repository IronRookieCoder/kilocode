package ai.kilocode.cscloud

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class CsCloudHttpClientsTest {

    private val workspace = Files.createTempDirectory("cscloud-http-ws")

    @Test
    fun `request is routed, authed and envelope unwrapped`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"ok":true,"data":{"status":"ok","version":"1.0"}}""")
            )
            server.start()
            val clients = CsCloudHttpClients(
                CsCloudEndpoint(server.url("/").toString().trimEnd('/'), "secret"),
                { workspace },
            )

            val response = clients.client.newCall(
                Request.Builder().url(server.url("/global/health")).get().build()
            ).execute()

            assertEquals("""{"status":"ok","version":"1.0"}""", response.body?.string())
            val recorded = server.takeRequest()
            assertEquals("/api/v1/runtime/health", recorded.path)
            assertEquals("Bearer secret", recorded.getHeader("Authorization"))
            assertEquals("secret", recorded.getHeader("X-API-Key"))
            assertEquals(
                workspace.toAbsolutePath().normalize().toString(),
                recorded.getHeader("X-Workspace-Directory"),
            )
        }
    }

    @Test
    fun `session routes are rewritten to conversations`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"data":{}}"""))
            server.start()
            val clients = CsCloudHttpClients(CsCloudEndpoint(server.url("/").toString().trimEnd('/'), "key"), { workspace })

            clients.client.newCall(
                Request.Builder().url(server.url("/session/abc/message")).post("""{"text":"hi"}""".toRequestBody()).build()
            ).execute()

            assertEquals("/api/v1/conversations/abc/messages", server.takeRequest().path)
        }
    }

    @Test
    fun `error envelope maps to request exception`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"ok":false,"error":{"code":"unauthorized","message":"bad key"}}""")
            )
            server.start()
            val clients = CsCloudHttpClients(CsCloudEndpoint(server.url("/").toString().trimEnd('/'), "key"), { workspace })

            val error = assertFailsWith<CsCloudRequestException> {
                clients.client.newCall(
                    Request.Builder().url(server.url("/global/health")).get().build()
                ).execute()
            }
            assertEquals("unauthorized", error.code)
            assertEquals("bad key", error.message)
            assertEquals(401, error.status)
        }
    }

    @Test
    fun `retries idempotent request after connection drop`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"data":{"status":"ok"}}"""))
            server.start()
            val clients = CsCloudHttpClients(CsCloudEndpoint(server.url("/").toString().trimEnd('/'), "key"), { workspace })

            val response = clients.client.newCall(
                Request.Builder().url(server.url("/global/health")).get().build()
            ).execute()

            assertEquals(2, server.requestCount)
            assertEquals("""{"status":"ok"}""", response.body?.string())
        }
    }

    @Test
    fun `non-envelope success body passes through unchanged`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("plain text"))
            server.start()
            val clients = CsCloudHttpClients(CsCloudEndpoint(server.url("/").toString().trimEnd('/'), "key"), { workspace })

            val response = clients.client.newCall(
                Request.Builder().url(server.url("/plain")).get().build()
            ).execute()

            assertEquals("plain text", response.body?.string())
        }
    }

    @Test
    fun `sse client carries workspace and auth headers`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(""))
            server.start()
            val clients = CsCloudHttpClients(CsCloudEndpoint(server.url("/").toString().trimEnd('/'), "key"), { workspace })

            clients.sseClient.newCall(
                Request.Builder().url(server.url("/api/v1/events")).get().build()
            ).execute().close()

            val recorded = server.takeRequest()
            assertEquals("/api/v1/events", recorded.path)
            assertEquals("Bearer key", recorded.getHeader("Authorization"))
            assertEquals(
                workspace.toAbsolutePath().normalize().toString(),
                recorded.getHeader("X-Workspace-Directory"),
            )
        }
    }

    @Test
    fun `default backoff sequence doubles then caps at 30s`() {
        assertEquals(1_000L, CsCloudConnectionService.defaultBackoffMillis(0))
        assertEquals(2_000L, CsCloudConnectionService.defaultBackoffMillis(1))
        assertEquals(4_000L, CsCloudConnectionService.defaultBackoffMillis(2))
        assertEquals(8_000L, CsCloudConnectionService.defaultBackoffMillis(3))
        assertEquals(16_000L, CsCloudConnectionService.defaultBackoffMillis(4))
        assertEquals(30_000L, CsCloudConnectionService.defaultBackoffMillis(5))
        assertEquals(30_000L, CsCloudConnectionService.defaultBackoffMillis(10))
    }
}
