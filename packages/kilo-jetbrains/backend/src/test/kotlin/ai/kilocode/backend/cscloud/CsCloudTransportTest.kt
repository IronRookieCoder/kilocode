package ai.kilocode.backend.cscloud

import ai.kilocode.connection.TransportException
import ai.kilocode.cscloud.CsCloudEndpoint
import ai.kilocode.cscloud.CsCloudHttpClients
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class CsCloudTransportTest {

    private val workspace = Files.createTempDirectory("cscloud-transport-ws")

    private fun transport(server: MockWebServer): CsCloudTransport {
        val endpoint = CsCloudEndpoint(server.url("/").toString().trimEnd('/'), "key")
        return CsCloudTransport(endpoint.base, CsCloudHttpClients(endpoint, { workspace }).client)
    }

    @Test
    fun `call rewrites route, adds headers and unwraps envelope`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"data":{"id":"abc"}}"""))
            server.start()
            val t = transport(server)
            try {
                val body = t.call("POST", "/session", """{"text":"hi"}""")
                assertEquals("""{"id":"abc"}""", body)
                val recorded = server.takeRequest()
                assertEquals("/api/v1/conversations", recorded.path)
                assertEquals("Bearer key", recorded.getHeader("Authorization"))
                assertEquals(
                    workspace.toAbsolutePath().normalize().toString(),
                    recorded.getHeader("X-Workspace-Directory"),
                )
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `call maps envelope error to transport exception`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"ok":false,"error":{"code":"unauthorized","message":"bad key"}}""")
            )
            server.start()
            val t = transport(server)
            try {
                val error = assertFailsWith<TransportException> {
                    t.call("GET", "/global/health")
                }
                assertEquals(401, error.status)
                assertEquals("bad key", error.message)
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `call maps network failure to transport exception without status`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.start()
            val t = transport(server)
            try {
                val error = assertFailsWith<TransportException> {
                    t.call("POST", "/session", "{}")
                }
                assertNull(error.status)
            } finally {
                t.close()
            }
        }
    }

    @Test
    fun `non-envelope success body passes through`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("plain text"))
            server.start()
            val t = transport(server)
            try {
                val body = t.call("GET", "/plain")
                assertEquals("plain text", body)
            } finally {
                t.close()
            }
        }
    }
}
