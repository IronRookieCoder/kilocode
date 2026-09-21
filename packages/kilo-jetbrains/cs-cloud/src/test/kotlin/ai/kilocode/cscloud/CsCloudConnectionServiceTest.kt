package ai.kilocode.cscloud

import ai.kilocode.KiloPlugin
import ai.kilocode.backend.app.ConnectionState
import ai.kilocode.backend.app.SseEvent
import ai.kilocode.cscloud.mcp.IdeMcpSessionFactory
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.CsCloudStartDto
import ai.kilocode.stability.Fact
import ai.kilocode.stability.Fixture
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicBoolean

@TestApplication
class CsCloudConnectionServiceTest {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun registerTestExtensionPoints() {
        // `@TestApplication` starts a bare application that loads no plugin descriptors,
        // so the `ideMcpSessionFactory` extension point declared in kilo.jetbrains.cs-cloud.xml
        // is absent. Register it (with no implementations) so the service constructor can read it.
        val area = ApplicationManager.getApplication().extensionArea
        if (!area.hasExtensionPoint(IdeMcpSessionFactory.EP)) {
            area.registerExtensionPoint(
                IdeMcpSessionFactory.EP.name,
                "ai.kilocode.cscloud.mcp.IdeMcpSessionFactory",
                ExtensionPoint.Kind.INTERFACE,
            )
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `cloud extension points use Costrict plugin id`() {
        val xml = checkNotNull(javaClass.classLoader.getResource("kilo.jetbrains.cs-cloud.xml")).readText()

        assertTrue(xml.contains("""<extensions defaultExtensionNs="${KiloPlugin.ID}">"""))
        assertEquals("${KiloPlugin.ID}.ideMcpSessionFactory", IdeMcpSessionFactory.EP.name)
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
        assertEquals(ConnectionErrorCode.CSC_NOT_INSTALLED, missingState.code)

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        server.start()
        val root = Files.createTempDirectory("cs-cloud-unavailable")
        Files.createDirectories(root.resolve(".costrict/cs-cloud"))
        Files.writeString(root.resolve(".costrict/cs-cloud/server_url"), server.url("/").newBuilder().host("127.0.0.1").build().toString())
        Files.writeString(root.resolve(".costrict/cs-cloud/config.json"), "{\"api_key\":\"secret\"}")
        val unavailable = CsCloudConnectionService(scope, CsCloudEndpointResolver(root, emptyMap()), TestLog, workspace = root)
        try {
            // `reinstall` throws unconditionally; kept first so the test's last expression stays
            // Unit — a trailing `assertFailsWith` makes the @Test return a value and JUnit skips it.
            assertFailsWith<CsCloudUnsupportedOperationException> { runBlocking { unavailable.reinstall() } }
            unavailable.connect()
            val error = assertIs<ConnectionState.Error>(unavailable.state.value)
            // The middle segment is the HTTP reason phrase, which okhttp/MockWebServer may vary.
            assertTrue(error.message.startsWith("unavailable: ") && error.message.endsWith("(HTTP 503)"), error.message)
            assertNull(error.code)
        } finally {
            unavailable.dispose()
            server.shutdown()
        }
    }

    @Test
    fun `rejected api key is reported as unauthorized`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.start()
        val root = Files.createTempDirectory("cs-cloud-unauthorized")
        Files.createDirectories(root.resolve(".costrict/cs-cloud"))
        Files.writeString(root.resolve(".costrict/cs-cloud/server_url"), server.url("/").newBuilder().host("127.0.0.1").build().toString())
        Files.writeString(root.resolve(".costrict/cs-cloud/config.json"), "{\"api_key\":\"secret\"}")
        val service = CsCloudConnectionService(scope, CsCloudEndpointResolver(root, emptyMap()), TestLog, workspace = root)
        try {
            service.connect()
            val error = assertIs<ConnectionState.Error>(service.state.value)
            assertEquals(ConnectionErrorCode.UNAUTHORIZED, error.code)
        } finally {
            service.dispose()
            server.shutdown()
        }
    }

    @Test
    fun `unreachable daemon is reported as daemon down`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.start()
        val root = Files.createTempDirectory("cs-cloud-daemon-down")
        Files.createDirectories(root.resolve(".costrict/cs-cloud"))
        Files.writeString(root.resolve(".costrict/cs-cloud/server_url"), server.url("/").newBuilder().host("127.0.0.1").build().toString())
        val service = CsCloudConnectionService(scope, CsCloudEndpointResolver(root, emptyMap()), TestLog, workspace = root)
        try {
            service.connect()
            val error = assertIs<ConnectionState.Error>(service.state.value)
            assertEquals(ConnectionErrorCode.DAEMON_DOWN, error.code)
        } finally {
            service.dispose()
            server.shutdown()
        }
    }

    @Test
    fun `polls until the cs-cloud daemon appears`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN),
        )
        server.start()
        val root = Files.createTempDirectory("cs-cloud-poll")
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            workspace = root,
        )
        try {
            service.connect()
            assertIs<ConnectionState.Error>(service.state.value)

            Files.createDirectories(root.resolve(".costrict/cs-cloud"))
            Files.writeString(root.resolve(".costrict/cs-cloud/server_url"), server.url("/").newBuilder().host("127.0.0.1").build().toString())
            Files.writeString(root.resolve(".costrict/cs-cloud/config.json"), "{\"api_key\":\"secret\"}")

            val connected = withTimeout(10_000) { service.state.first { it is ConnectionState.Connected } }
            assertIs<ConnectionState.Connected>(connected)
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
            service.dispose()
            assertEquals(ConnectionState.Disconnected, service.state.value)
        } finally {
            service.dispose()
            server.shutdown()
        }
    }

    @Test
    fun `connection epoch advances when the stream reconnects and resets when disconnected`() = runBlocking {
        val server = MockWebServer()
        // The stream ending is how a daemon restart shows up on the wire: the reconnect that
        // follows must move the connection epoch so MCP leases re-bind (P0-1).
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": keepalive\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END),
        )
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN),
        )
        server.start()
        val root = Files.createTempDirectory("cs-cloud-epoch")
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
        try {
            service.connect()
            assertIs<ConnectionState.Connected>(service.state.value, service.state.value.toString())
            val first = assertNotNull(service.connectionEpoch)

            withTimeout(10_000) {
                while (service.connectionEpoch == first) delay(50)
            }
            val second = assertNotNull(service.connectionEpoch)
            assertTrue(second > first, "epoch must advance across connections: $first -> $second")

            service.dispose()
            assertNull(service.connectionEpoch, "epoch must be gone once the transport is closed")
        } finally {
            service.dispose()
            server.shutdown()
        }
    }

    @Test
    fun `startCsCloud returns starter outcome and reconnects on success`() = runBlocking {
        val root = Files.createTempDirectory("cs-cloud-start")
        val fixture = Fixture()
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            starter = { CsCloudStartDto(true, "started") },
            operations = fixture.operations,
        )
        try {
            val result = service.startCsCloud()
            // Success triggers a connect attempt, which fails discovery here. Kept ahead of the
            // Unit-returning asserts — a trailing `assertIs` makes the @Test return a value and
            // JUnit silently skips it.
            assertIs<ConnectionState.Error>(service.state.value)
            assertTrue(result.ok)
            assertEquals("started", result.message)
            assertEquals(CsCloudStartDto.STAGE_START, result.stage)
            // M07（brief Step 3）：exit 0但daemon不可发现——健康确认失败不得记success。
            fixture.flush()
            val start = fixture.facts().filter { it.name == "csc.start" }
            assertTrue(start.any { it.data["phase"]?.jsonPrimitive?.content == "start" }, "missing start fact: ${start.map { it.data }}")
            val end = ends(start, "csc.start").single()
            assertEquals("failure", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("health", end.data["stage"]?.jsonPrimitive?.content)
            assertEquals("health_failed", end.data["error_code"]?.jsonPrimitive?.content)
        } finally {
            service.dispose()
            fixture.close()
        }
    }

    @Test
    fun `start operation ends success only after daemon health confirmation`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(sse(SocketPolicy.KEEP_OPEN))
        server.start()
        val root = Files.createTempDirectory("cs-cloud-start-healthy")
        writeDaemonFiles(server, root)
        val fixture = Fixture()
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            workspace = root,
            starter = { CsCloudStartDto(true, "started") },
            operations = fixture.operations,
        )
        try {
            val result = service.startCsCloud()
            assertTrue(result.ok, "result=$result")
            assertIs<ConnectionState.Connected>(service.state.value, service.state.value.toString())
            // daemon可发现且健康：唯一success在health阶段结算；完整连接归M04另一operation。
            factsUntil(fixture) { facts -> ends(facts, "csc.start").size == 1 }
            fixture.flush()
            val start = fixture.facts().filter { it.name == "csc.start" }
            val end = ends(start, "csc.start").single()
            assertEquals("success", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("health", end.data["stage"]?.jsonPrimitive?.content)
            assertEquals(1, ends(fixture.facts(), "connection").size)
        } finally {
            service.dispose()
            fixture.close()
            server.shutdown()
        }
    }

    @Test
    fun `startCsCloud failure does not reconnect`() = runBlocking {
        val root = Files.createTempDirectory("cs-cloud-start-fail")
        val fixture = Fixture()
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            starter = { CsCloudStartDto(false, "csc not installed", ConnectionErrorCode.CSC_NOT_INSTALLED) },
            operations = fixture.operations,
        )
        try {
            val result = service.startCsCloud()
            assertTrue(!result.ok)
            assertEquals("csc not installed", result.message)
            assertEquals(CsCloudStartDto.STAGE_START, result.stage)
            assertEquals(ConnectionState.Disconnected, service.state.value)
            fixture.flush()
            val start = fixture.facts().filter { it.name == "csc.start" }
            assertTrue(start.any { it.data["phase"]?.jsonPrimitive?.content == "start" }, "missing start fact: ${start.map { it.data }}")
            val end = ends(start, "csc.start").single()
            assertEquals("blocked", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("csc_not_installed", end.data["error_code"]?.jsonPrimitive?.content)
        } finally {
            service.dispose()
            fixture.close()
        }
    }

    @Test
    fun `installCsc installs then starts cs-cloud`() = runBlocking {
        val root = Files.createTempDirectory("cs-cloud-install")
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            installer = { CsCloudStartDto(true, "installed") },
            starter = { CsCloudStartDto(true, "started") },
        )
        try {
            val result = service.installCsc()
            // Success triggers a connect attempt, which fails discovery here. Kept ahead of the
            // Unit-returning asserts — a trailing `assertIs` makes the @Test return a value and
            // JUnit silently skips it.
            assertIs<ConnectionState.Error>(service.state.value)
            assertTrue(result.ok)
            assertEquals("started", result.message)
            assertEquals(CsCloudStartDto.STAGE_START, result.stage)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `installCsc failure does not start cs-cloud`() = runBlocking {
        val root = Files.createTempDirectory("cs-cloud-install-fail")
        val started = AtomicBoolean(false)
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            installer = { CsCloudStartDto(false, "npm not found", ConnectionErrorCode.NPM_NOT_FOUND) },
            starter = {
                started.set(true)
                CsCloudStartDto(true, "started")
            },
        )
        try {
            val result = service.installCsc()
            assertTrue(!result.ok)
            assertEquals(ConnectionErrorCode.NPM_NOT_FOUND, result.code)
            assertEquals(CsCloudStartDto.STAGE_INSTALL, result.stage)
            assertFalse(started.get())
            assertEquals(ConnectionState.Disconnected, service.state.value)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `installCsc reports the start stage when only the daemon fails to start`() = runBlocking {
        val root = Files.createTempDirectory("cs-cloud-install-start-fail")
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            installer = { CsCloudStartDto(true, "installed") },
            starter = { CsCloudStartDto(false, "csc cloud start failed", ConnectionErrorCode.CSC_NOT_INSTALLED) },
        )
        try {
            val result = service.installCsc()
            assertTrue(!result.ok)
            assertEquals("csc cloud start failed", result.message)
            assertEquals(CsCloudStartDto.STAGE_START, result.stage)
            assertEquals(ConnectionState.Disconnected, service.state.value)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `start payload written before the stage field still decodes`() {
        val dto = Json.decodeFromString<CsCloudStartDto>("""{"ok":false,"message":"legacy"}""")
        assertEquals("legacy", dto.message)
        assertNull(dto.stage)
    }

    @Test
    fun `loginCsCloud delegates to the login lambda`() = runBlocking {
        val root = Files.createTempDirectory("cs-cloud-login")
        var calls = 0
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            login = {
                calls += 1
                CsCloudStartDto(true, "signed in")
            },
        )
        try {
            val result = service.loginCsCloud()
            assertEquals(1, calls)
            assertTrue(result.ok)
            assertEquals("signed in", result.message)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `loginCsCloud failure is returned without connecting`() = runBlocking {
        val root = Files.createTempDirectory("cs-cloud-login-fail")
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            login = { CsCloudStartDto(false, "csc auth login failed") },
        )
        try {
            val result = service.loginCsCloud()
            assertTrue(!result.ok)
            assertEquals("csc auth login failed", result.message)
            assertEquals(ConnectionState.Disconnected, service.state.value)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `favorites list and actions route through the daemon`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN),
        )
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a1","slug":"my-skill","name":"My Skill","itemType":"skill","status":"Cloud"}]""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"item":{"id":"a1","slug":"my-skill","name":"My Skill",""" +
                    """"itemType":"skill","status":"Active"}}""",
            ),
        )
        server.start()
        val root = Files.createTempDirectory("cs-cloud-favorites")
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
        try {
            service.connect()
            val listed = service.cloudFavorites()
            assertTrue(listed.ok)
            assertEquals(1, listed.items.size)
            assertEquals("Cloud", listed.items[0].status)
            val loaded = service.loadCloudFavorite("my-skill")
            assertTrue(loaded.ok)
            assertEquals("Active", loaded.item?.status)
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization")) // health
            assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization")) // sse
            assertEquals("/api/v1/agents/favorites", server.takeRequest().path)            // list
            val action = server.takeRequest()
            assertEquals("POST", action.method)
            assertEquals("/api/v1/agents/favorites/my-skill/load", action.path)
            assertEquals("Bearer secret", action.getHeader("Authorization"))
        } finally {
            service.dispose()
            server.shutdown()
        }
    }

    @Test
    fun `favorites degrade to UNAVAILABLE before connect`() = runBlocking {
        val root = Files.createTempDirectory("cs-cloud-favorites-off")
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            workspace = root,
        )
        try {
            val listed = service.cloudFavorites()
            assertFalse(listed.ok)
            assertEquals("UNAVAILABLE", listed.errorCode)
        } finally {
            service.dispose()
        }
    }

    // ------ B2：M04/M05 真实HTTP/SSE回归（fixture观测注入） ------

    private fun ends(facts: List<Fact>, name: String) =
        facts.filter { it.name == name && it.data["phase"]?.jsonPrimitive?.content == "end" }

    private fun starts(facts: List<Fact>, name: String) =
        facts.filter { it.name == name && it.data["phase"]?.jsonPrimitive?.content == "start" }

    /** 观测任务与服务协程异步推进：轮询flush直到谓词满足（真实落盘后断言）。 */
    private fun factsUntil(fixture: Fixture, timeoutMs: Long = 10_000, predicate: (List<Fact>) -> Boolean): List<Fact> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var facts = emptyList<Fact>()
        while (System.currentTimeMillis() < deadline) {
            fixture.flush()
            facts = fixture.facts()
            if (predicate(facts)) return facts
            Thread.sleep(50)
        }
        error("observation condition not met within ${timeoutMs}ms; facts=${facts.map { it.name to it.data.toString() }}")
    }

    private fun writeDaemonFiles(server: MockWebServer, root: java.nio.file.Path) {
        Files.createDirectories(root.resolve(".costrict/cs-cloud"))
        Files.writeString(
            root.resolve(".costrict/cs-cloud/server_url"),
            server.url("/").newBuilder().host("127.0.0.1").build().toString(),
        )
        Files.writeString(root.resolve(".costrict/cs-cloud/config.json"), "{\"api_key\":\"secret\"}")
    }

    private fun sse(policy: SocketPolicy, bodyDelayMs: Long = 0): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(": keepalive\n\n")
            .setSocketPolicy(policy)
            .apply { if (bodyDelayMs > 0) setBodyDelay(bodyDelayMs, TimeUnit.MILLISECONDS) }

    @Test
    fun `two of three streams disconnecting open a single recovery`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        // 两条流连接建立后迟些断开（bodyDelay保证三流先全部open），第三条保持。
        server.enqueue(sse(SocketPolicy.DISCONNECT_AT_END, bodyDelayMs = 500))
        server.enqueue(sse(SocketPolicy.DISCONNECT_AT_END, bodyDelayMs = 500))
        server.enqueue(sse(SocketPolicy.KEEP_OPEN))
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        repeat(3) { server.enqueue(sse(SocketPolicy.KEEP_OPEN)) }
        server.start()
        val root = Files.createTempDirectory("cs-cloud-three-roots")
        writeDaemonFiles(server, root)
        val dirs = (1..3).map { Files.createDirectories(root.resolve("root$it")) }
        val fixture = Fixture()
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            roots = { dirs },
            operations = fixture.operations,
        )
        try {
            service.connect()
            assertIs<ConnectionState.Connected>(service.state.value, service.state.value.toString())
            // 两流断开：恰好一次退化transition与一个恢复区间（brief：多流同败只lost一次）。
            factsUntil(fixture) { facts -> facts.count { it.name == "connection.state_changed" } == 1 }
            factsUntil(fixture) { facts -> ends(facts, "connection.recovery").size == 1 }
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(1, facts.count { it.name == "connection.recovery" && it.data["phase"]?.jsonPrimitive?.content == "start" })
            assertEquals(1, ends(facts, "connection.recovery").size)
            assertEquals("success", ends(facts, "connection.recovery").single().data["result"]?.jsonPrimitive?.content)
            assertEquals("partial_sse_failed", facts.first { it.name == "connection.state_changed" }.data["reason"]?.jsonPrimitive?.content)
            service.dispose()
        } finally {
            service.dispose()
            fixture.close()
            server.shutdown()
        }
    }

    @Test
    fun `health 200 with a missing stream does not succeed`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(sse(SocketPolicy.KEEP_OPEN)) // 第二条流永不响应：健康200但缺流
        server.start()
        val root = Files.createTempDirectory("cs-cloud-missing-stream")
        writeDaemonFiles(server, root)
        val dirs = (1..2).map { Files.createDirectories(root.resolve("root$it")) }
        val fixture = Fixture()
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 1_000,
            roots = { dirs },
            operations = fixture.operations,
        )
        try {
            service.connect()
            assertIs<ConnectionState.Error>(service.state.value)
            service.dispose()
            fixture.flush()
            val facts = fixture.facts()
            assertTrue(
                ends(facts, "connection").none { it.data["result"]?.jsonPrimitive?.content == "success" },
                "missing stream must not end the connection success",
            )
            assertTrue(
                facts.none { it.name == "connection.recovery" || it.name == "connection.state_changed" },
                "pre-ready failure is not recovery",
            )
            assertTrue(
                ends(facts, "connection.attempt").any {
                    it.data["stage"]?.jsonPrimitive?.content == "streams" &&
                        it.data["result"]?.jsonPrimitive?.content == "failure"
                },
                "the streams attempt must end failure",
            )
        } finally {
            service.dispose()
            fixture.close()
            server.shutdown()
        }
    }

    @Test
    fun `restart opens a manual trigger journey`() = runBlocking {
        val server = MockWebServer()
        repeat(2) {
            server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
            server.enqueue(sse(SocketPolicy.KEEP_OPEN))
        }
        server.start()
        val root = Files.createTempDirectory("cs-cloud-manual")
        writeDaemonFiles(server, root)
        val fixture = Fixture()
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            workspace = root,
            operations = fixture.operations,
        )
        try {
            service.connect()
            assertIs<ConnectionState.Connected>(service.state.value)
            factsUntil(fixture) { facts -> starts(facts, "connection").size == 1 }
            // 用户显式restart：trigger=manual的新旅程（恢复中手动介入只改intervention的
            // 语义由ConnectionObservationTest确定性地覆盖，此处验证服务接线）。
            service.restart()
            factsUntil(fixture) { facts -> starts(facts, "connection").size == 2 }
            factsUntil(fixture) { facts -> ends(facts, "connection").size == 2 }
            fixture.flush()
            val facts = fixture.facts()
            assertEquals("manual", starts(facts, "connection")[1].data["trigger"]?.jsonPrimitive?.content)
            assertEquals("success", ends(facts, "connection")[1].data["result"]?.jsonPrimitive?.content)
            // 正常dispose无断连事实由专测覆盖；真实HTTP下重建传输与关闭回调存在固有的
            // 毫秒级竞态窗口，此处不断言恢复区间计数（该语义由单元测试确定性覆盖）。
        } finally {
            service.dispose()
            fixture.close()
            server.shutdown()
        }
    }

    @Test
    fun `normal dispose records no disconnect`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.enqueue(sse(SocketPolicy.KEEP_OPEN))
        server.start()
        val root = Files.createTempDirectory("cs-cloud-dispose")
        writeDaemonFiles(server, root)
        val fixture = Fixture()
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            workspace = root,
            operations = fixture.operations,
        )
        try {
            service.connect()
            assertIs<ConnectionState.Connected>(service.state.value)
            factsUntil(fixture) { facts -> ends(facts, "connection").size == 1 }
            service.dispose()
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(0, facts.count { it.name == "connection.state_changed" })
            assertEquals(0, facts.count { it.name == "connection.recovery" })
            assertEquals("success", ends(facts, "connection").single().data["result"]?.jsonPrimitive?.content)
        } finally {
            service.dispose()
            fixture.close()
            server.shutdown()
        }
    }

    @Test
    fun `empty roots at stream open end the connection as failure`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
        server.start()
        val root = Files.createTempDirectory("cs-cloud-empty-roots")
        writeDaemonFiles(server, root)
        val dir = Files.createDirectories(root.resolve("rootA"))
        // 首次roots()（connect的空检查）给出根目录，openSse再取时为空：命中终局失败路径。
        val calls = AtomicInteger()
        val fixture = Fixture()
        val service = CsCloudConnectionService(
            scope,
            CsCloudEndpointResolver(root, emptyMap()),
            TestLog,
            timeout = 5_000,
            roots = { if (calls.incrementAndGet() == 1) listOf(dir) else emptyList() },
            operations = fixture.operations,
        )
        try {
            service.connect()
            assertIs<ConnectionState.Error>(service.state.value)
            service.dispose()
            fixture.flush()
            val facts = fixture.facts()
            val journeyEnd = ends(facts, "connection").single()
            assertEquals("failure", journeyEnd.data["result"]?.jsonPrimitive?.content)
            assertEquals("streams", journeyEnd.data["stage"]?.jsonPrimitive?.content)
            assertEquals("environment", journeyEnd.data["cause"]?.jsonPrimitive?.content)
            assertEquals("other", journeyEnd.data["error_code"]?.jsonPrimitive?.content)
            assertEquals(0, facts.count { it.name == "connection.recovery" || it.name == "connection.state_changed" })
        } finally {
            service.dispose()
            fixture.close()
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
