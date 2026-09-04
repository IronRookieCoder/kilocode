package ai.kilocode.cscloud.mcp

import ai.kilocode.backend.app.CapabilityResult
import ai.kilocode.cscloud.CsCloudEndpoint
import ai.kilocode.cscloud.CsCloudRequestException
import ai.kilocode.log.KiloLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CsCloudMcpBridgeTest {
    @Test
    fun `lease cancellation is not logged as a failure`() = runBlocking {
        val ready = CompletableDeferred<IdeMcpTransport>()
        val log = TestLog()

        assertFailsWith<CancellationException> {
            runLease(ready, "lease failed", log) {
                throw CancellationException("released")
            }
        }

        assertFailsWith<CancellationException> { ready.await() }
        assertEquals(0, log.warnings)
    }

    @Test
    fun `lease failure completes readiness and is logged`() = runBlocking {
        val ready = CompletableDeferred<IdeMcpTransport>()
        val log = TestLog()
        val error = IllegalStateException("failed")

        runLease(ready, "lease failed", log) { throw error }

        val failure = assertFailsWith<IllegalStateException> { ready.await() }
        assertEquals("failed", failure.message)
        assertEquals(1, log.warnings)
        assertSame(error, log.error)
    }

    @Test
    fun `missing downstream CSC capability is optional`() {
        val error = CsCloudRequestException("capability_bind_failed", "csc IDE capability request failed: HTTP 404", 502)

        assertEquals("ide_capability_unsupported", capabilityBindReason(error))
    }

    @Test
    fun `other capability bind failures remain blocking`() {
        val rejected = CsCloudRequestException("capability_bind_failed", "csc IDE capability request failed: HTTP 500", 502)
        val unavailable = CsCloudRequestException("unavailable", "service unavailable", 503)

        assertEquals("ide_capability_bind_failed", capabilityBindReason(rejected))
        assertEquals("ide_capability_bind_failed", capabilityBindReason(unavailable))
        assertEquals("ide_capability_bind_failed", capabilityBindReason(IllegalStateException("failed")))
    }

    // ------ connection epoch gates lease reuse (daemon restart on the same port must re-bind) ------

    @Test
    fun `ensure reuses the lease only within the same connection epoch`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/global/health" -> MockResponse().setBody("""{"capabilities":["conversation_ide_capability_v1"]}""")
                request.method == "PUT" -> MockResponse().setResponseCode(200).setBody("{}")
                request.method == "DELETE" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspace = Files.createTempDirectory("cs-cloud-bridge").toString()
        var epoch: Long? = 1
        val bridge = CsCloudMcpBridge(
            scope,
            endpoint = { CsCloudEndpoint(server.url("/").toString().trimEnd('/'), null) },
            client = { OkHttpClient() },
            epoch = { epoch },
            factory = FakeIdeMcpSessionFactory(),
            log = TestLog(),
            project = { directory -> directory },
        )
        try {
            // First ensure under epoch 1 binds a fresh generation via PUT.
            val first = runBlocking { bridge.ensure("conv-1", workspace) }
            val ready1 = assertIs<CapabilityResult.Ready>(first)
            val bind1 = server.drain().filter { it.method == "PUT" }.single()
            assertEquals(workspace, bind1.getHeader("X-Workspace-Directory"))
            assertEquals(ready1.generation, GENERATION.find(bind1.body.readUtf8())?.groupValues?.get(1))

            // Same epoch: the lease is reused, no second PUT reaches the daemon.
            val second = runBlocking { bridge.ensure("conv-1", workspace) }
            val ready2 = assertIs<CapabilityResult.Ready>(second)
            assertEquals(ready1.generation, ready2.generation)
            assertEquals(0, server.drain().count { it.method == "PUT" })

            // Epoch bumped (daemon reconnected/restarted): the lease must NOT be reused —
            // a new PUT binds a new generation and the old one is released with a DELETE.
            epoch = 2
            val third = runBlocking { bridge.ensure("conv-1", workspace) }
            val ready3 = assertIs<CapabilityResult.Ready>(third)
            assertNotEquals(ready1.generation, ready3.generation)
            val afterBump = server.drain()
            val rebind = afterBump.filter { it.method == "PUT" }.single()
            assertEquals(ready3.generation, GENERATION.find(rebind.body.readUtf8())?.groupValues?.get(1))
            val release = afterBump.filter { it.method == "DELETE" }.single()
            assertEquals(ready1.generation, release.requestUrl?.queryParameter("generation"))

            // Disconnected (epoch null): no binding is attempted at all.
            epoch = null
            val down = runBlocking { bridge.ensure("conv-1", workspace) }
            assertEquals("ide_capability_unsupported", assertIs<CapabilityResult.Unavailable>(down).reason)
            assertEquals(0, server.drain().count { it.method == "PUT" })
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    private class TestLog : KiloLog {
        var warnings = 0
        var error: Throwable? = null
        override val isDebugEnabled = false
        override fun debug(block: () -> String) = Unit
        override fun info(msg: String) = Unit
        override fun warn(msg: String, t: Throwable?) {
            warnings++
            error = t
        }
        override fun error(msg: String, t: Throwable?) = Unit
    }

    /** Opens the MCP listener instantly with a loopback transport — enough to drive bind/rotate. */
    private class FakeIdeMcpSessionFactory : IdeMcpSessionFactory {
        override fun enabled(allow: Set<String>) = setOf("read_file")
        override suspend fun open(tools: Set<String>, ready: suspend (IdeMcpTransport) -> Nothing): Nothing {
            ready(IdeMcpTransport(port = 49152, authHeader = "X-Test-Auth", token = "test-token"))
        }
    }

    private fun MockWebServer.drain(): List<RecordedRequest> =
        generateSequence { takeRequest(500, TimeUnit.MILLISECONDS) }.toList()

    private companion object {
        val GENERATION = Regex("\"generation\":\"([^\"]+)\"")
    }
}
