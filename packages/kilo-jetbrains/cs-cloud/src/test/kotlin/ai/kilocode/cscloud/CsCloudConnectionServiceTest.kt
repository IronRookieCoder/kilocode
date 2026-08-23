package ai.kilocode.cscloud

import ai.kilocode.backend.app.ConnectionState
import ai.kilocode.backend.app.SseEvent
import ai.kilocode.log.KiloLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CsCloudConnectionServiceTest {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `health gates connection and forwards SSE events`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN)
                .setBody("event: session.idle\ndata: {\"payload\":{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}}\n\n"),
        )
        server.start()
        val root = Files.createTempDirectory("cs-cloud-test")
        Files.createDirectories(root.resolve(".costrict/cs-cloud"))
        Files.writeString(root.resolve(".costrict/cs-cloud/server_url"), server.url("/").newBuilder().host("127.0.0.1").build().toString())
        Files.writeString(root.resolve(".costrict/cs-cloud/config.json"), "{\"api_key\":\"secret\"}")
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            workspace = root,
        )
        val event = async { service.events.first() }
        try {
            service.connect()
            assertIs<ConnectionState.Connected>(service.state.value, service.state.value.toString())
            assertEquals(server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'), service.target?.base)
            val request = server.takeRequest()
            assertEquals("/api/v1/runtime/health", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            val sse = server.takeRequest()
            assertEquals("/api/v1/events", sse.path)
            assertEquals("Bearer secret", sse.getHeader("Authorization"))
            assertEquals(root.toAbsolutePath().normalize().toString(), sse.getHeader("X-Workspace-Directory"))
            assertEquals(SseEvent("session.idle", "{\"payload\":{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"s1\"}}}"), withTimeout(5_000) { event.await() })
            service.dispose()
            assertEquals(ConnectionState.Disconnected, service.state.value)
        } finally {
            event.cancel()
            service.dispose()
            server.shutdown()
        }
    }

    @Test
    fun `connects without auth when daemon has no API key`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN),
        )
        server.start()
        val root = Files.createTempDirectory("cs-cloud-no-auth")
        Files.createDirectories(root.resolve(".costrict/cs-cloud"))
        Files.writeString(root.resolve(".costrict/cs-cloud/server_url"), server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            workspace = root,
        )
        try {
            service.connect()
            assertIs<ConnectionState.Connected>(service.state.value, service.state.value.toString())
            assertNull(server.takeRequest().getHeader("Authorization"))
            assertNull(server.takeRequest().getHeader("Authorization"))
        } finally {
            service.dispose()
            server.shutdown()
        }
    }

    @Test
    fun `discovery and health failures are typed and reinstall is unsupported`() = runBlocking {
        val missing = Files.createTempDirectory("cs-cloud-missing")
        val service = CsCloudConnectionService(scope, CsCloudEndpointResolver(missing, emptyMap()), TestLog)
        service.connect()
        val missingState = assertIs<ConnectionState.Error>(service.state.value)
        assertEquals("cs-cloud server URL was not found", missingState.message)

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.start()
        val root = Files.createTempDirectory("cs-cloud-unavailable")
        Files.createDirectories(root.resolve(".costrict/cs-cloud"))
        Files.writeString(root.resolve(".costrict/cs-cloud/server_url"), server.url("/").newBuilder().host("127.0.0.1").build().toString())
        Files.writeString(root.resolve(".costrict/cs-cloud/config.json"), "{\"api_key\":\"secret\"}")
        val unavailable = CsCloudConnectionService(scope, CsCloudEndpointResolver(root, emptyMap()), TestLog, workspace = root)
        try {
            unavailable.connect()
            val error = assertIs<ConnectionState.Error>(unavailable.state.value)
            assertEquals("unavailable: Service Unavailable (HTTP 503)", error.message)
            assertFailsWith<CsCloudUnsupportedOperationException> { runBlocking { unavailable.reinstall() } }
        } finally {
            unavailable.dispose()
            server.shutdown()
        }
    }

    private object TestLog : KiloLog {
        override val isDebugEnabled = false
        override fun debug(block: () -> String) = Unit
        override fun info(msg: String) = Unit
        override fun warn(msg: String, t: Throwable?) = Unit
        override fun error(msg: String, t: Throwable?) = Unit
    }
}
