package ai.kilocode.cscloud

import ai.kilocode.backend.app.SseEvent
import ai.kilocode.backend.app.KiloBackendSessionManager
import ai.kilocode.jetbrains.api.client.DefaultApi
import ai.kilocode.log.KiloLog
import ai.kilocode.stability.Fixture
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

/**
 * M15（C2）：真实SSE流上的协议错误观测。
 *
 * 非法JSON解码失败记一次protocol.error（同事件在accept/infer的两次解析只计一次）；
 * 正常新增可选字段被ignoreUnknownKeys容忍，不算错误；host事件directory违反可解析路径
 * 约束记apply违规且既有丢弃行为不变；正常close不产生协议或释放事实。
 * 全部走生产CsCloudSseClient + 真实MockWebServer SSE流，不伪造解析。
 */
class SseObservationTest {

    private lateinit var server: MockWebServer
    private lateinit var fixture: Fixture
    private var workspace: java.nio.file.Path? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        fixture = Fixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
        scope.cancel()
        server.shutdown()
    }

    private fun protocolFacts() = fixture.facts().filter { it.name == "protocol.error" }

    private fun sse(vararg events: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setSocketPolicy(SocketPolicy.KEEP_OPEN)
            .setBody(events.joinToString("") { "data: $it\n\n" })

    private fun statuses(vararg events: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setSocketPolicy(SocketPolicy.KEEP_OPEN)
            .setBody(events.joinToString("") { "event: session.status\ndata: $it\n\n" })

    private fun open(vararg events: String): Harness {
        server.enqueue(sse(*events))
        return Harness(workspace)
    }

    private inner class Harness(workspace: java.nio.file.Path?) {
        val received = CopyOnWriteArrayList<SseEvent>()
        val opened = CountDownLatch(1)
        val client = CsCloudSseClient(
            http = OkHttpClient(),
            base = server.url("/").toString().trimEnd('/'),
            workspace = workspace,
            log = TestLog,
            onOpen = { opened.countDown() },
            onEvent = { event -> received.add(event) },
            onClosed = {},
            onFailure = { _, _ -> },
            operations = fixture.operations,
        )

        fun start(): Harness {
            client.start()
            assertTrue(opened.await(5, TimeUnit.SECONDS), "sse stream did not open")
            return this
        }

        fun await(size: Int): Harness {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && received.size < size) Thread.sleep(20)
            assertEquals(size, received.size, "expected $size events, got ${received.size}")
            return this
        }
    }

    @Test
    fun `invalid json records one decode protocol error and is still forwarded`() {
        val harness = open("{not-json", """{"payload":{"type":"session.idle","properties":{"sessionID":"s1"}}}""").start()

        harness.await(2)
        harness.client.close()
        fixture.flush()

        // 解码失败容忍转发（既有行为），但事实只记一次；第二个合法事件不产事实。
        val facts = protocolFacts()
        assertEquals(1, facts.size)
        val fact = facts.single()
        assertEquals("sse", fact.data.getValue("transport").jsonPrimitive.content)
        assertEquals("decode", fact.data.getValue("stage").jsonPrimitive.content)
        assertEquals("decode_failed", fact.data.getValue("error_code").jsonPrimitive.content)
        assertEquals("diagnostic", fact.kind)
        assertEquals("critical", fact.channel)
        assertTrue(fact.purposes.isNotEmpty())
    }

    @Test
    fun `unknown optional fields are tolerated without protocol error`() {
        val harness = open(
            """{"payload":{"type":"session.idle","properties":{"sessionID":"s1"}},""" +
                """"brandNewField":{"nested":1},"anotherOptional":"x"}""",
        ).start()

        harness.await(1)
        harness.client.close()
        fixture.flush()

        assertEquals(1, harness.received.size)
        assertTrue(protocolFacts().isEmpty())
    }

    @Test
    fun `host event with unparsable directory records apply violation and stays dropped`() {
        workspace = Files.createTempDirectory("sse-observation")
        // NUL在Windows与POSIX上都不是合法路径字符：违反host事件directory的可解析路径约束。
        // 尾随的合法会话事件保证前面的host事件已全部处理完毕（SSE按序投递）。
        val harness = open(
            "{\"type\":\"host.file.created\",\"directory\":\"a\\u0000b\"}",
            """{"type":"host.file.updated","directory":"/somewhere/outside/workspace"}""",
            """{"payload":{"type":"session.idle","properties":{"sessionID":"s1"}}}""",
        ).start()

        harness.await(1)
        harness.client.close()
        fixture.flush()

        // 违规事件照旧丢弃（既有行为），记一次apply违规；范围外有效路径只是正常过滤，不算协议错误。
        assertEquals(1, harness.received.size)
        val facts = protocolFacts()
        assertEquals(1, facts.size)
        val fact = facts.single()
        assertEquals("sse", fact.data.getValue("transport").jsonPrimitive.content)
        assertEquals("apply", fact.data.getValue("stage").jsonPrimitive.content)
        assertEquals("apply_violation", fact.data.getValue("error_code").jsonPrimitive.content)
    }

    @Test
    fun `repeated invalid events each count exactly once`() {
        val harness = open("{not-json", "{not-json").start()

        harness.await(2)
        harness.client.close()
        fixture.flush()

        // 每个真实解码失败计一次（accept与infer的二次解析不重复计数）；重复事件各自独立计。
        assertEquals(2, protocolFacts().size)
    }

    @Test
    fun `invalid cs cloud status is observed once before session manager continues`() = runBlocking {
        server.enqueue(statuses(
            "{not-json",
            """{"payload":{"type":"session.status","properties":{"sessionID":"ses_good","status":{"type":"busy"}}}}""",
        ))
        val base = server.url("/").toString().trimEnd('/')
        val http = OkHttpClient()
        val events = MutableSharedFlow<SseEvent>(replay = 2)
        val manager = KiloBackendSessionManager(scope, TestLog, fixture.operations)
        manager.start(DefaultApi(base, http), http, base, events)
        val opened = CountDownLatch(1)
        val client = CsCloudSseClient(
            http = http,
            base = base,
            workspace = null,
            log = TestLog,
            onOpen = { opened.countDown() },
            onEvent = { event -> events.tryEmit(event) },
            onClosed = {},
            onFailure = { _, _ -> },
            operations = fixture.operations,
        )
        try {
            client.start()
            assertTrue(opened.await(5, TimeUnit.SECONDS), "sse stream did not open")
            withTimeout(5_000) {
                manager.statuses.first { it["ses_good"]?.type == "busy" }
            }
            fixture.flush()

            assertEquals(1, protocolFacts().size)
        } finally {
            manager.stop()
            client.close()
        }
    }

    @Test
    fun `normal close records no protocol or dispose facts`() {
        val harness = open("""{"payload":{"type":"session.idle","properties":{"sessionID":"s1"}}}""").start()

        harness.await(1)
        harness.client.close()
        fixture.flush()

        assertEquals(1, harness.received.size)
        assertTrue(protocolFacts().isEmpty())
        assertTrue(fixture.facts().none { it.name == "session.dispose_risk" })
    }

    private object TestLog : KiloLog {
        override val isDebugEnabled = false
        override fun debug(block: () -> String) = Unit
        override fun info(msg: String) = Unit
        override fun warn(msg: String, t: Throwable?) = Unit
        override fun error(msg: String, t: Throwable?) = Unit
    }
}
