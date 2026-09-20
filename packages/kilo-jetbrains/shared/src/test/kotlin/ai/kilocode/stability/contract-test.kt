package ai.kilocode.stability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * G0 交接契约门禁（docs/jetbrains-stability-design.md 6.1/8/9.1）。
 *
 * 读取真实 /stability 夹具：任一 *_verified 为 false 都必须阻止启用采集。
 * 正式 release gate 要求全部 true 且以外部证据为准；本测试通过不代表契约已冻结。
 */
class ContractTest {

    @Test
    fun `unverified contract cannot enable collection`() {
        val input = checkNotNull(javaClass.getResource("/stability/contract.json"))
        val json = Json.parseToJsonElement(input.readText()).jsonObject
        val flags = json.filterKeys { it.endsWith("_verified") }
        assertTrue(flags.isNotEmpty())
        assertFalse(flags.values.all { it.jsonPrimitive.boolean })
    }

    @Test
    fun `contract fixture keeps every gate flag unverified`() {
        val json = loadObject("contract.json")
        assertEquals(SUPPORTED_SCHEMA_MAJOR, json.getValue("schema_major").jsonPrimitive.int)
        val flags = json.filterKeys { it.endsWith("_verified") }
        assertEquals(GATE_FLAGS, flags.keys.toList())
        assertTrue(flags.values.all { !it.jsonPrimitive.boolean })
    }

    @Test
    fun `release gate enables only when every flag is verified`() {
        val flags = LinkedHashMap<String, Boolean>()
        GATE_FLAGS.forEach { flags[it] = false }
        repeat(1 shl GATE_FLAGS.size) { mask ->
            GATE_FLAGS.indices.forEach { index ->
                flags[GATE_FLAGS[index]] = (mask and (1 shl index)) != 0
            }
            val expected = mask == (1 shl GATE_FLAGS.size) - 1
            val enabled = releaseGateEnabled(contractJson(SUPPORTED_SCHEMA_MAJOR, flags))
            assertEquals(expected, enabled, "mask=$mask")
        }
    }

    @Test
    fun `unknown schema major or missing flags refuses enablement`() {
        val allTrue = GATE_FLAGS.associateWith { true }
        assertTrue(releaseGateEnabled(contractJson(SUPPORTED_SCHEMA_MAJOR, allTrue)))
        assertFalse(releaseGateEnabled(contractJson(SUPPORTED_SCHEMA_MAJOR + 1, allTrue)))
        assertFalse(releaseGateEnabled("{}"))
    }

    @Test
    fun `fact schema freezes the common field set`() {
        val schema = loadObject("fact-schema.json")
        assertEquals(false, schema.getValue("additionalProperties").jsonPrimitive.boolean)
        val properties = schema.getValue("properties").jsonObject
        assertEquals(COMMON_FIELDS, schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })

        assertEquals(
            "jetbrains-plugin",
            properties.getValue("source").jsonObject.getValue("const").jsonPrimitive.content,
        )

        val enumOf = { field: String ->
            properties.getValue(field).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(listOf("critical", "diagnostic"), enumOf("channel"))
        assertEquals(listOf("prod", "dev", "test"), enumOf("env"))
        assertEquals(listOf("monolith", "split"), enumOf("mode"))
        assertEquals(listOf("monolith", "frontend", "backend"), enumOf("side"))
        assertEquals(listOf("cs-cloud", "kilo-cli", "unknown"), enumOf("connection_provider"))
        assertEquals(
            listOf("operation", "transition", "lifecycle", "interval", "diagnostic", "health", "sample"),
            enumOf("kind"),
        )
        assertEquals(EVENT_NAMES, enumOf("name"))
        assertEquals(
            listOf("metrics", "logs"),
            properties.getValue("purposes").jsonObject
                .getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )

        val pattern = Regex(properties.getValue("schema_version").jsonObject.getValue("pattern").jsonPrimitive.content)
        assertTrue(pattern.matches("1.0"))
        assertFalse(pattern.matches("1"))
        assertFalse(pattern.matches("1.x"))

        val context = properties.getValue("context").jsonObject
        assertEquals(false, context.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(CONTEXT_KEYS, context.getValue("properties").jsonObject.keys.toList())

        val data = properties.getValue("data").jsonObject
        assertTrue(data.containsKey("maxProperties"))
        assertTrue(data.containsKey("propertyNames"))
    }

    @Test
    fun `canonical example record fits the fact schema`() {
        val example = Json.parseToJsonElement(EXAMPLE_RECORD.trimIndent()).jsonObject
        val schema = loadObject("fact-schema.json")
        val properties = schema.getValue("properties").jsonObject
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }

        val unknown = example.keys.filterNot { it in properties.keys }
        assertEquals(emptyList(), unknown)
        val missing = required.filterNot { it in example.keys }
        assertEquals(emptyList(), missing)

        listOf("channel", "env", "mode", "side", "connection_provider", "kind", "name").forEach { field ->
            val allowed = properties.getValue(field).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
            val value = example.getValue(field).jsonPrimitive.content
            assertTrue(value in allowed, "$field value $value is not in the frozen enum")
        }
        assertEquals("jetbrains-plugin", example.getValue("source").jsonPrimitive.content)
        assertTrue(example.getValue("purposes").jsonArray.all { it.jsonPrimitive.content in setOf("metrics", "logs") })
        assertTrue(example.getValue("context").jsonObject.keys.all { it in CONTEXT_KEYS })
        assertTrue(example.getValue("timestamp").jsonPrimitive.long >= 0)
        assertTrue(example.getValue("seq").jsonPrimitive.int >= 1)
    }

    @Test
    fun `control schema freezes per purpose switches and allows no jwt`() {
        val schema = loadObject("control-schema.json")
        assertEquals(false, schema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(CONTROL_FIELDS, schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val properties = schema.getValue("properties").jsonObject
        assertTrue(properties.keys.none { it.contains("jwt", ignoreCase = true) || it.contains("token", ignoreCase = true) })

        assertEquals(
            listOf("pending", "ready", "disabled"),
            properties.getValue("account_state").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        listOf("metrics_allowed_categories", "logs_allowed_categories").forEach { field ->
            assertEquals(
                listOf("critical", "diagnostic"),
                properties.getValue(field).jsonObject.getValue("items").jsonObject.getValue("enum")
                    .jsonArray.map { it.jsonPrimitive.content },
            )
        }
        val rateLimit = properties.getValue("log_detail_rate_limit").jsonObject
        assertEquals(false, rateLimit.getValue("additionalProperties").jsonPrimitive.boolean)
    }

    @Test
    fun `output vectors stay pending until namespace is frozen`() {
        val json = loadObject("output-vectors.json")
        assertEquals("pending_freeze", json.getValue("status").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("namespace"))
        assertEquals(emptyList(), json.getValue("vectors").jsonArray.map { it.jsonPrimitive.content })

        val encoding = json.getValue("array_encoding").jsonObject
        assertEquals(
            listOf("input_event_id", "sink", "output_name", "mapping_version"),
            encoding.getValue("canonical_elements").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(OUTPUT_PURPOSES, encoding.getValue("sink_enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("counter", "histogram", "log"),
            json.getValue("pending_vectors").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun loadObject(name: String): JsonObject = Json.parseToJsonElement(resourceText(name)).jsonObject

    private fun resourceText(name: String): String =
        checkNotNull(javaClass.getResource("/stability/$name")) { "missing fixture: /stability/$name" }.readText()

    /** release gate：未知 major 或任一 verified 缺失/false 都拒绝启用（设计第8章默认拒绝）。 */
    private fun releaseGateEnabled(contractJson: String): Boolean {
        val root = Json.parseToJsonElement(contractJson).jsonObject
        val major = root["schema_major"]?.jsonPrimitive?.intOrNull ?: return false
        if (major != SUPPORTED_SCHEMA_MAJOR) return false
        val flags = root.filterKeys { it.endsWith("_verified") }
        if (flags.isEmpty()) return false
        return flags.values.all { it.jsonPrimitive.boolean }
    }

    private fun contractJson(schemaMajor: Int, flags: Map<String, Boolean>): String {
        val verified = flags.entries.joinToString(",") { "\"${it.key}\": ${it.value}" }
        return "{\"schema_major\": $schemaMajor, $verified}"
    }

    private companion object {
        const val SUPPORTED_SCHEMA_MAJOR = 1

        /** 与 contract.json 中 *_verified 键的顺序一致。 */
        val GATE_FLAGS = listOf(
            "identity_verified",
            "metrics_authority_verified",
            "series_key_verified",
            "late_query_verified",
            "output_ids_verified",
            "cross_language_locks_verified",
            "durable_ack_verified",
            "logs_contract_verified",
            "default_profile_verified",
        )

        /** 设计6.1公共字段：除可选 context 外全部必填。 */
        val COMMON_FIELDS = listOf(
            "schema_version", "event_id", "timestamp", "producer_id", "run_id",
            "channel", "seq", "account_epoch", "policy_revision", "purposes",
            "source", "device_id", "plugin_version", "ide_product", "ide_build",
            "ide_build_major", "os_family", "arch", "env", "mode", "side",
            "connection_provider", "kind", "name", "data",
        )

        val CONTEXT_KEYS = listOf("operation_id", "attempt_id", "fault_id", "trace_id", "workspace_id")

        /** 设计第9章事件字典登记的 name。 */
        val EVENT_NAMES = listOf(
            "plugin.started", "plugin.shutdown", "plugin.unclean", "toolwindow.setup",
            "backend.load", "plugin.readiness", "connection", "connection.attempt",
            "connection.state_changed", "connection.recovery", "csc.install", "csc.start",
            "credentials.ready", "cli.download", "migration.required", "session.open",
            "session.restore", "action", "availability", "error.uncaught", "error.reported",
            "protocol.error", "telemetry.health", "session.dispose_risk", "rpc",
            "edt.delay", "edt.violation", "render.apply", "ide.operation", "resource.snapshot",
        )

        /** 设计第8章控制文件字段。 */
        val CONTROL_FIELDS = listOf(
            "schema_major", "revision", "enabled",
            "metrics_enabled", "metrics_expires_at",
            "logs_enabled", "logs_expires_at",
            "account_epoch", "account_state", "expires_at",
            "metrics_allowed_categories", "logs_allowed_categories",
            "log_detail_rate_limit",
        )

        val OUTPUT_PURPOSES = listOf("metrics", "logs")

        /** 设计6.1示例记录，逐字保留。 */
        const val EXAMPLE_RECORD = """
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
