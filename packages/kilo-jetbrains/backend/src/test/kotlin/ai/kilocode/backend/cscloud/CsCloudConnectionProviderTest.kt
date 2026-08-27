package ai.kilocode.backend.cscloud

import ai.kilocode.backend.testing.TestLog
import ai.kilocode.connection.BackendEvent
import ai.kilocode.connection.ConnectionState
import ai.kilocode.cscloud.CsCloudEndpointResolver
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Exercises the full real wiring: CsCloudConnectionProvider → CsCloudConnectionService.create
 * → CsCloudHttpClients → CsCloudSseClient against a MockWebServer. Proves state mapping,
 * event forwarding, transport factory lifecycle and error diagnosis without mocks.
 */
class CsCloudConnectionProviderTest {

    private val workspace = Files.createTempDirectory("cscloud-provider-ws")

    private fun resolver(server: MockWebServer): CsCloudEndpointResolver {
        val configDir = Files.createTempDirectory("cscloud-provider-cfg")
        // MockWebServer's url() may return the machine hostname (non-loopback); pin the loopback URL explicitly.
        Files.writeString(configDir.resolve("server_url"), "http://127.0.0.1:${server.port}/")
        return CsCloudEndpointResolver(configDir) { name ->
            if (name == "CS_BRIDGE_API_KEY") "test-key" else null
        }
    }

    /** Binds to loopback so the resolved endpoint URL passes the cs-cloud loopback check. */
    private fun MockWebServer.startLoopback() {
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /** Waits for an async state/event with a real-time deadline; yields so the test scheduler drains. */
    private suspend fun await(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            yield()
        }
    }

    @Test
    fun `connects to ready, forwards events and serves transports`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"ok":true,"data":{"status":"ok","version":"1.0"}}""")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "event: session.status\n" +
                            """data: {"sessionID":"s1","status":{"type":"idle"}}""" +
                            "\n\n"
                    )
            )
            server.startLoopback()

            val event = CompletableDeferred<BackendEvent>()
            val provider = CsCloudConnectionProvider(
                cs = this,
                workspace = { workspace },
                resolver = resolver(server),
                log = TestLog(),
            )
            val collector = launch { provider.events.collect { if (!event.isCompleted) event.complete(it) } }
            provider.connect()

            await("connected state") { provider.state.value is ConnectionState.Connected }
            val connected = assertIs<ConnectionState.Connected>(provider.state.value)
            assertEquals("http://127.0.0.1:${server.port}", connected.endpoint)

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"data":{"sessions":[]}}"""))
            val transport = provider.transportFactory()?.create()
            assertNotNull(transport)
            try {
                assertEquals("""{"sessions":[]}""", transport.call("GET", "/session"))
            } finally {
                transport.close()
            }

            await("forwarded event") { event.isCompleted }
            val forwarded = event.await()
            assertEquals("session.status", forwarded.type)
            assertTrue(forwarded.data.contains("s1"))

            val health = server.takeRequest()
            assertEquals("/api/v1/runtime/health", health.path)
            assertEquals("Bearer test-key", health.getHeader("Authorization"))
            assertEquals(
                workspace.toAbsolutePath().normalize().toString(),
                health.getHeader("X-Workspace-Directory"),
            )

            provider.dispose()
            collector.cancel()
        }
    }

    @Test
    fun `credentials rejected maps to error state with diagnosis`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"ok":false,"error":{"code":"unauthorized","message":"bad key"}}""")
            )
            server.startLoopback()

            val provider = CsCloudConnectionProvider(
                cs = this,
                workspace = { workspace },
                resolver = resolver(server),
                log = TestLog(),
            )
            provider.connect()

            await("error state") { provider.state.value is ConnectionState.Error }
            val error = assertIs<ConnectionState.Error>(provider.state.value)
            assertEquals("cs-cloud credentials are invalid", error.message)
            assertNotNull(error.details)
            provider.dispose()
        }
    }

    @Test
    fun `endpoint discovery failure maps to error state`() = runTest {
        val emptyConfig = Files.createTempDirectory("cscloud-provider-cfg-empty")
        val provider = CsCloudConnectionProvider(
            cs = this,
            workspace = { workspace },
            resolver = CsCloudEndpointResolver(emptyConfig) { null },
            log = TestLog(),
        )
        provider.connect()

        await("error state") { provider.state.value is ConnectionState.Error }
        val error = assertIs<ConnectionState.Error>(provider.state.value)
        assertEquals("cs-cloud connection is unavailable", error.message)
        assertNotNull(error.details)
        provider.dispose()
    }
}
