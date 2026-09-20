package ai.kilocode.stability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * 安全事实模型与事件字典（任务A1，设计6.1/6.2/9）。
 *
 * 白名单语义：未知name、未知键、错误类型一律拒绝，而不是裁剪后放行；
 * Draft不接受JWT、路径或业务payload。
 */
class FactTest {

    @Test
    fun `action rejects raw content`() {
        val data = buildJsonObject {
            put("phase", "end"); put("action", "prompt_submit")
            put("result", "failure"); put("duration_ms", 20)
            put("stage", "rpc"); put("cause", "network"); put("error_code", "network")
            put("prompt", "secret source code")
        }
        assertFalse(Dictionary.validate(Draft("action", "operation", "critical", data)))
    }

    @Test
    fun `action end with the whitelisted fields validates`() {
        assertTrue(Dictionary.validate(Draft("action", "operation", "critical", actionEnd())))
    }

    @Test
    fun `dictionary registers every chapter nine event name in schema order`() {
        assertEquals(SCHEMA_EVENT_NAMES, Dictionary.names)
    }

    @Test
    fun `unregistered event name is rejected`() {
        assertFalse(Dictionary.isRegistered("agent.token"))
        val draft = Draft("agent.token", "lifecycle", "critical", buildJsonObject { put("x", 1) })
        assertFalse(Dictionary.validate(draft))
    }

    @Test
    fun `kind must match the registered kind`() {
        assertFalse(Dictionary.validate(Draft("action", "diagnostic", "critical", actionEnd())))
        assertFalse(Dictionary.validate(Draft("error.uncaught", "operation", "critical", errorDetail())))
    }

    @Test
    fun `channel is a closed enum`() {
        assertTrue(Dictionary.validate(Draft("plugin.started", "lifecycle", "critical", buildJsonObject { })))
        assertTrue(Dictionary.validate(Draft("plugin.started", "lifecycle", "diagnostic", buildJsonObject { })))
        assertFalse(Dictionary.validate(Draft("plugin.started", "lifecycle", "urgent", buildJsonObject { })))
    }

    @Test
    fun `purposes stay inside metrics and logs`() {
        val data = buildJsonObject { }
        assertTrue(Dictionary.validate(Draft("plugin.started", "lifecycle", "critical", data)))
        assertFalse(Dictionary.validate(Draft("plugin.started", "lifecycle", "critical", data, purposes = emptySet())))
        assertFalse(
            Dictionary.validate(
                Draft("plugin.started", "lifecycle", "critical", data, purposes = setOf("metrics", "ads")),
            ),
        )
    }

    @Test
    fun `non-enum stage is rejected`() {
        fun start(stage: String) = buildJsonObject {
            put("phase", "start"); put("deadline_ms", 1000); put("stage", stage)
        }
        assertFalse(Dictionary.validate(Draft("toolwindow.setup", "operation", "critical", start("explode"))))
        assertTrue(Dictionary.validate(Draft("toolwindow.setup", "operation", "critical", start("create"))))
    }

    @Test
    fun `operation start requires a positive deadline`() {
        fun start(deadline: Number?) = buildJsonObject {
            put("phase", "start"); put("trigger", "initial")
            if (deadline != null) put("deadline_ms", deadline)
        }
        assertFalse(Dictionary.validate(Draft("backend.load", "operation", "critical", start(null))))
        assertFalse(Dictionary.validate(Draft("backend.load", "operation", "critical", start(0))))
        assertFalse(Dictionary.validate(Draft("backend.load", "operation", "critical", start(-5))))
        assertTrue(Dictionary.validate(Draft("backend.load", "operation", "critical", start(30000))))
    }

    @Test
    fun `negative duration is rejected`() {
        val data = buildJsonObject {
            put("phase", "end"); put("action", "stop"); put("result", "success")
            put("duration_ms", -1); put("stage", "rpc"); put("cause", "plugin"); put("error_code", "none")
        }
        assertFalse(Dictionary.validate(Draft("action", "operation", "critical", data)))
    }

    @Test
    fun `operation end requires the self-contained terminal fields`() {
        listOf("result", "duration_ms", "stage", "cause", "error_code").forEach { missing ->
            val fields = LinkedHashMap(actionEnd())
            fields.remove(missing)
            val data = JsonObject(fields)
            assertFalse(Dictionary.validate(Draft("action", "operation", "critical", data)), "missing $missing")
        }
    }

    @Test
    fun `progress cannot act as an end`() {
        fun progress(extra: Pair<String, Any>? = null) = buildJsonObject {
            put("phase", "progress"); put("api_group", "session")
            if (extra != null) {
                when (val value = extra.second) {
                    is String -> put(extra.first, value)
                    is Number -> put(extra.first, value)
                }
            }
        }
        assertTrue(Dictionary.validate(Draft("rpc", "operation", "critical", progress())))
        assertFalse(Dictionary.validate(Draft("rpc", "operation", "critical", progress("result" to "success"))))
        assertFalse(Dictionary.validate(Draft("rpc", "operation", "critical", progress("duration_ms" to 5))))
        assertFalse(Dictionary.validate(Draft("rpc", "operation", "critical", progress("deadline_ms" to 100))))
    }

    @Test
    fun `error count facts use the minimal critical branch`() {
        listOf("error.uncaught", "error.reported").forEach { name ->
            val draft = Draft(name, "diagnostic", "critical", errorMinimal())
            assertTrue(Dictionary.validate(draft), name)
        }
    }

    @Test
    fun `error detail facts use the fixed diagnostic fields`() {
        listOf("error.uncaught", "error.reported").forEach { name ->
            val draft = Draft(name, "diagnostic", "diagnostic", errorDetail())
            assertTrue(Dictionary.validate(draft), name)
        }
    }

    @Test
    fun `error branches cannot be mixed`() {
        val mixed = LinkedHashMap(errorMinimal())
        mixed["message"] = Json.parseToJsonElement("\"读取配置失败：io_error\"")
        assertFalse(Dictionary.validate(Draft("error.uncaught", "diagnostic", "critical", JsonObject(mixed))))

        val detail = LinkedHashMap(errorDetail())
        detail.remove("count")
        detail["error_class"] = Json.parseToJsonElement("\"io_error\"")
        assertFalse(Dictionary.validate(Draft("error.reported", "diagnostic", "diagnostic", JsonObject(detail))))
    }

    @Test
    fun `diagnostic message limit counts utf-8 bytes not chars`() {
        val exactly512 = detailWithMessage("x".repeat(509) + "中")
        assertTrue(Dictionary.validate(Draft("error.uncaught", "diagnostic", "diagnostic", exactly512)))

        val exactly513 = detailWithMessage("x".repeat(510) + "中")
        assertFalse(Dictionary.validate(Draft("error.uncaught", "diagnostic", "diagnostic", exactly513)))
    }

    @Test
    fun `more than five frames is rejected`() {
        fun frames(count: Int) = buildJsonObject {
            putJsonArray("frames") { repeat(count) { add("ai.kilocode.Foo#bar") } }
            put("message", "渲染违规：edt")
            put("fingerprint", "edt-violation"); put("count", 1)
        }
        assertTrue(Dictionary.validate(Draft("error.reported", "diagnostic", "diagnostic", frames(5))))
        assertFalse(Dictionary.validate(Draft("error.reported", "diagnostic", "diagnostic", frames(6))))
    }

    @Test
    fun `context allows only the closed key set`() {
        val all = mapOf(
            "operation_id" to "op-713f",
            "attempt_id" to "at-22",
            "fault_id" to "f-1",
            "trace_id" to "t-9",
            "workspace_id" to "ws-a3f0",
        )
        assertTrue(Dictionary.validate(Draft("action", "operation", "critical", actionEnd(), context = all)))
        assertFalse(
            Dictionary.validate(
                Draft("action", "operation", "critical", actionEnd(), context = mapOf("user_email" to "a@b.c")),
            ),
        )
        assertFalse(
            Dictionary.validate(
                Draft("action", "operation", "critical", actionEnd(), context = all + ("session_id" to "s-1")),
            ),
        )
    }

    @Test
    fun `context value limit counts utf-8 bytes`() {
        val boundary = mapOf("workspace_id" to "x".repeat(125) + "中")
        assertTrue(Dictionary.validate(Draft("action", "operation", "critical", actionEnd(), context = boundary)))
        val over = mapOf("workspace_id" to "x".repeat(126) + "中")
        assertFalse(Dictionary.validate(Draft("action", "operation", "critical", actionEnd(), context = over)))
    }

    @Test
    fun `newline inside a string value is rejected`() {
        val data = buildJsonObject { put("migration_kind", "line1\nline2") }
        assertFalse(Dictionary.validate(Draft("migration.required", "transition", "critical", data)))
        val framed = detailWithMessage("第一行\n第二行：prompt_submit")
        assertFalse(Dictionary.validate(Draft("error.uncaught", "diagnostic", "diagnostic", framed)))
    }

    @Test
    fun `path and jwt-like payloads are rejected`() {
        val pathReason = buildJsonObject {
            put("phase", "start"); put("deadline_ms", 1000)
            put("trigger", "initial"); put("reason", "C:\\Users\\dev\\config")
        }
        assertFalse(Dictionary.validate(Draft("backend.load", "operation", "critical", pathReason)))

        val fields = LinkedHashMap(actionEnd())
        fields["error_code"] = Json.parseToJsonElement("\"eyJhbGciOiJIUzI1NiJ9.payload." + "x".repeat(80) + "\"")
        assertFalse(Dictionary.validate(Draft("action", "operation", "critical", JsonObject(fields))))
    }

    @Test
    fun `raw exception text is not accepted as a message`() {
        val raw = detailWithMessage("Exception in thread main: java.lang.NPE at C:\\repo\\Foo.kt:42")
        assertFalse(Dictionary.validate(Draft("error.uncaught", "diagnostic", "diagnostic", raw)))
    }

    @Test
    fun `object payload values are rejected while string arrays pass`() {
        val withObject = buildJsonObject {
            put("phase", "start"); put("deadline_ms", 1000)
            put("trigger", "initial")
            putJsonObject("payload") { put("token", "secret") }
        }
        assertFalse(Dictionary.validate(Draft("backend.load", "operation", "critical", withObject)))

        val withFrames = buildJsonObject {
            putJsonArray("frames") { add("ai.kilocode.Foo#bar"); add("ai.kilocode.Baz#qux") }
            put("message", "读取配置失败：io_error")
            put("fingerprint", "io-error-foo-bar"); put("count", 1)
        }
        assertTrue(Dictionary.validate(Draft("error.reported", "diagnostic", "diagnostic", withFrames)))
    }

    @Test
    fun `largest legal payload stays inside the 32kib budget`() {
        val data = buildJsonObject {
            put("message", "m".repeat(512))
            putJsonArray("frames") { repeat(5) { add("f".repeat(256)) } }
            put("fingerprint", "deadline-exceeded-rpc"); put("count", 1)
        }
        val context = mapOf(
            "operation_id" to "o".repeat(128),
            "attempt_id" to "a".repeat(128),
            "fault_id" to "f".repeat(128),
            "trace_id" to "t".repeat(128),
            "workspace_id" to "w".repeat(128),
        )
        assertTrue(Dictionary.validate(Draft("error.uncaught", "diagnostic", "diagnostic", data, context)))
    }

    @Test
    fun `draft snapshots incoming collections`() {
        val context = HashMap<String, String>()
        context["operation_id"] = "op-1"
        val purposes = HashSet<String>()
        purposes += "metrics"
        purposes += "logs"
        val draft = Draft(
            "plugin.readiness", "operation", "critical",
            buildJsonObject { put("phase", "start"); put("deadline_ms", 5000) },
            context, null, purposes,
        )
        context["user_email"] = "leak@example.com"
        purposes.remove("logs")
        purposes.add("ads")
        assertTrue(Dictionary.validate(draft))
    }

    @Test
    fun `fact round trips with the frozen wire field names`() {
        val wireJson = Json { encodeDefaults = true }
        val wire = Json.parseToJsonElement(EXAMPLE_FACT.trimIndent()).jsonObject
        val fact = Json.decodeFromJsonElement(Fact.serializer(), wire)
        assertEquals(setOf("metrics", "logs"), fact.purposes)
        assertEquals("jetbrains-plugin", fact.source)
        assertEquals("1.0", fact.schema_version)
        assertEquals(wire, Json.parseToJsonElement(wireJson.encodeToString(Fact.serializer(), fact)).jsonObject)
    }

    private fun actionEnd() = buildJsonObject {
        put("phase", "end"); put("action", "prompt_submit")
        put("result", "failure"); put("duration_ms", 20)
        put("stage", "rpc"); put("cause", "network"); put("error_code", "network")
    }

    private fun errorMinimal() = buildJsonObject {
        put("fault_id", "f-1"); put("error_class", "io_error"); put("handled", false)
        put("fingerprint", "io-error-foo-bar"); put("component", "frontend")
    }

    private fun errorDetail() = buildJsonObject {
        put("message", "操作超时：prompt_submit")
        putJsonArray("frames") { add("ai.kilocode.Foo#bar"); add("ai.kilocode.Baz#qux") }
        put("fingerprint", "deadline-exceeded-rpc"); put("count", 3)
    }

    private fun detailWithMessage(message: String) = buildJsonObject {
        put("message", message)
        putJsonArray("frames") { add("ai.kilocode.Foo#bar") }
        put("fingerprint", "deadline-exceeded-rpc"); put("count", 1)
    }

    private companion object {
        val SCHEMA_EVENT_NAMES = listOf(
            "plugin.started", "plugin.shutdown", "plugin.unclean", "toolwindow.setup",
            "backend.load", "plugin.readiness", "connection", "connection.attempt",
            "connection.state_changed", "connection.recovery", "csc.install", "csc.start",
            "credentials.ready", "cli.download", "migration.required", "session.open",
            "session.restore", "action", "availability", "error.uncaught", "error.reported",
            "protocol.error", "telemetry.health", "session.dispose_risk", "rpc",
            "edt.delay", "edt.violation", "render.apply", "ide.operation", "resource.snapshot",
        )

        /** 设计6.1示例记录，逐字保留。 */
        const val EXAMPLE_FACT = """
            {
              "schema_version": "1.0",
              "event_id": "c387ecbf-a8d9-487c-94d4-8b8777982401",
              "timestamp": 1789862400250,
              "producer_id": "pr-7c18",
              "run_id": "run-3e92",
              "channel": "critical",
              "seq": 21,
              "account_epoch": "acct-5b02",
              "policy_revision": 12,
              "purposes": ["metrics", "logs"],
              "source": "jetbrains-plugin",
              "device_id": "device-6d81",
              "plugin_version": "1.0.0",
              "ide_product": "IU",
              "ide_build": "example-build",
              "ide_build_major": "2026.1",
              "os_family": "windows",
              "arch": "x64",
              "env": "prod",
              "mode": "split",
              "side": "frontend",
              "connection_provider": "cs-cloud",
              "kind": "operation",
              "name": "action",
              "context": {"operation_id": "op-713f", "workspace_id": "ws-a3f0"},
              "data": {
                "phase": "end",
                "action": "prompt_submit",
                "result": "timeout",
                "duration_ms": 30000,
                "stage": "rpc",
                "cause": "unknown",
                "error_code": "deadline_exceeded"
              }
            }
        """
    }
}
