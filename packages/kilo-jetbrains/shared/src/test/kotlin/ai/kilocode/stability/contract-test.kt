package ai.kilocode.stability

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
        assertEquals(6, context.getValue("maxProperties").jsonPrimitive.int)
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
    fun `diagnostic detail data with frames fits the data shell`() {
        val data = buildJsonObject {
            put("message", "操作超时：prompt_submit")
            put("error_class", "DeadlineExceeded")
            putJsonArray("frames") {
                add("ai.kilocode.Foo#bar")
                add("ai.kilocode.Baz#qux")
                add("ai.kilocode.Qux#quux")
                add("ai.kilocode.Corge#grault")
                add("ai.kilocode.Garply#waldo")
            }
            put("fingerprint", "deadline-exceeded-rpc")
            put("count", 3)
        }
        assertEquals(emptyList(), dataShellViolations(data))
    }

    @Test
    fun `data shell rejects non string arrays and oversized frames`() {
        val nestedObjects = buildJsonObject {
            putJsonArray("frames") {
                add("ai.kilocode.Foo#bar")
                addJsonObject { put("class", "Foo") }
            }
        }
        assertTrue(dataShellViolations(nestedObjects).isNotEmpty())

        val sixFrames = buildJsonObject {
            putJsonArray("frames") {
                repeat(6) { index -> add("ai.kilocode.${'A' + index}#${'a' + index}") }
            }
        }
        assertTrue(dataShellViolations(sixFrames).isNotEmpty())

        val nullValue = buildJsonObject { put("cause", JsonNull) }
        assertTrue(dataShellViolations(nullValue).isNotEmpty())
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
        val accepted = properties.getValue("accepted_fact_schema_majors").jsonObject
        assertEquals(true, accepted.getValue("uniqueItems").jsonPrimitive.boolean)
        assertEquals(1, accepted.getValue("items").jsonObject.getValue("minimum").jsonPrimitive.int)
    }

    @Test
    fun `output vectors declare constraints until output identity is frozen`() {
        val json = loadObject("output-vectors.json")
        assertEquals(SUPPORTED_SCHEMA_MAJOR, json.getValue("schema_major").jsonPrimitive.int)
        assertEquals("frozen", json.getValue("status").jsonPrimitive.content)
        assertEquals(4, json.getValue("vectors").jsonArray.size)

        // UUIDv5 命名空间冻结已废止：输出ID生成方案归 cs-cloud 内部，本契约只冻结约束声明。
        listOf("namespace", "array_encoding", "pending_vectors").forEach { retired ->
            assertFalse(json.containsKey(retired), "output-vectors.json must no longer freeze '$retired'")
        }

        val constraints = json.getValue("constraints").jsonObject
        assertEquals(
            listOf("determinism", "distinctness", "length_limits", "generation_owner"),
            constraints.keys.toList(),
        )
        val limits = constraints.getValue("length_limits").jsonObject
        assertEquals(64, limits.getValue("metrics_event_id_max_chars").jsonPrimitive.int)
        assertEquals(128, limits.getValue("logs_event_id_max_chars").jsonPrimitive.int)
        listOf("determinism", "distinctness", "generation_owner").forEach { key ->
            assertTrue(constraints.getValue(key).jsonPrimitive.content.isNotBlank(), "$key must state a constraint")
        }
    }

    @Test
    fun `v2 diagnostic drafts require incident context and logs`() {
        val parent = Draft(
            name = "diagnostic.reported",
            kind = "diagnostic",
            channel = "diagnostic",
            context = mapOf("incident_id" to "inc-1", "operation_id" to "op-1"),
            purposes = setOf("logs"),
            schemaVersion = "2.0",
            data = buildJsonObject {
                put("severity", "error")
                put("component", "backend.rpc")
                put("code", "json_decode_failed")
                put("message", "Expected object at $.projectID")
                put("thread_name", "DefaultDispatcher-worker-1")
                put("thread_id", 42)
                putJsonArray("payload_refs") { add("response") }
                put("truncated", false)
            },
        )
        val first = diagnosticChunk("response", 0)
        val second = diagnosticChunk("response", 1)

        assertTrue(Dictionary.validate(parent))
        val refs = parent.with(data = JsonObject(parent.data + ("payload_refs" to JsonArray((0 until 16).map { JsonPrimitive("part$it") }))))
        assertTrue(Dictionary.validate(refs))
        assertTrue(dataShellViolations(refs.data).isEmpty())
        assertFalse(Dictionary.validate(parent.with(data = JsonObject(parent.data + ("payload_refs" to JsonArray((0 until 17).map { JsonPrimitive("part$it") }))))))
        assertTrue(Dictionary.validate(listOf(first, second)))
        assertFalse(Dictionary.validate(parent.with(purposes = setOf("metrics"))))
        assertFalse(Dictionary.validate(parent.with(context = mapOf("operation_id" to "op-1"))))
        assertFalse(Dictionary.validate(parent.with(data = parent.data.with("severity", "fatal"))))
        assertFalse(Dictionary.validate(listOf(first, diagnosticChunk("response", 0))))
        assertFalse(Dictionary.validate(diagnosticChunk("response", 2)))
        assertFalse(Dictionary.validate(diagnosticChunk("response", 0, encoding = "hex")))
    }

    @Test
    fun `v2 diagnostic content type accepts common MIME values`() {
        val parent = diagnosticParent()
        assertTrue(Dictionary.validate(parent.with(data = parent.data.with("content_type", "application/json"))))
        assertTrue(Dictionary.validate(parent.with(data = parent.data.with("content_type", "text/plain"))))
    }

    @Test
    fun `output vectors retain mixed v1 and v2 facts`() {
        val json = loadObject("output-vectors.json")
        val facts = json.getValue("vectors").jsonArray.map { vector ->
            Json.decodeFromJsonElement(Fact.serializer(), vector)
        }

        assertEquals(listOf("1.0", "2.0", "2.0", "2.0"), facts.map { fact -> fact.schema_version })
        assertEquals(listOf("action", "diagnostic.reported", "diagnostic.payload", "diagnostic.payload"), facts.map { fact -> fact.name })
        val wire = Json { encodeDefaults = true }
        facts.forEachIndexed { index, fact ->
            assertEquals(json.getValue("vectors").jsonArray[index], wire.encodeToJsonElement(Fact.serializer(), fact))
        }
        val chunks = facts.filter { fact -> fact.name == "diagnostic.payload" }.sortedBy { fact -> fact.data.getValue("chunk_index").jsonPrimitive.int }
        val content = chunks.joinToString("") { fact -> fact.data.getValue("content").jsonPrimitive.content }
        val bytes = content.encodeToByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }
        assertEquals("firstsecond", content)
        assertEquals(11, bytes.size)
        assertEquals("da83f63e1a473003712c18f5afc5a79044221943d1083c7c5a7ac7236d85e8d2", sha)
        assertEquals(setOf(sha), chunks.map { fact -> fact.data.getValue("sha256").jsonPrimitive.content }.toSet())
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

    private fun diagnosticChunk(kind: String, index: Int, encoding: String = "utf8"): Draft = Draft(
        name = "diagnostic.payload",
        kind = "diagnostic",
        channel = "diagnostic",
        context = mapOf("incident_id" to "inc-1"),
        purposes = setOf("logs"),
        schemaVersion = "2.0",
        data = buildJsonObject {
            put("incident_id", "inc-1")
            put("payload_kind", kind)
            put("chunk_index", index)
            put("chunk_count", 2)
            put("encoding", encoding)
            put("content", "chunk-$index")
            put("original_bytes", 14)
            put("sha256", "a".repeat(64))
            put("truncated", false)
        },
    )

    private fun diagnosticParent(): Draft = Draft(
        name = "diagnostic.reported",
        kind = "diagnostic",
        channel = "diagnostic",
        context = mapOf("incident_id" to "inc-1", "operation_id" to "op-1"),
        purposes = setOf("logs"),
        schemaVersion = "2.0",
        data = buildJsonObject {
            put("severity", "error")
            put("component", "backend.rpc")
            put("code", "json_decode_failed")
            put("message", "Expected object at $.projectID")
            put("thread_name", "DefaultDispatcher-worker-1")
            put("thread_id", 42)
            putJsonArray("payload_refs") { add("response") }
            put("truncated", false)
        },
    )

    private fun Draft.with(
        data: JsonObject = this.data,
        context: Map<String, String> = this.context,
        purposes: Set<String> = this.purposes,
    ): Draft = Draft(name, kind, channel, data, context, epoch, purposes, schemaVersion)

    private fun JsonObject.with(key: String, value: String): JsonObject =
        JsonObject(LinkedHashMap(this).apply { put(key, JsonPrimitive(value)) })

    /** 按 fact-schema.json 冻结的 data 外壳约束校验：键 pattern、值为标量或不超过上限的字符串数组。 */
    private fun dataShellViolations(data: JsonObject): List<String> {
        val dataSchema = loadObject("fact-schema.json")
            .getValue("properties").jsonObject.getValue("data").jsonObject
        val keyPattern = Regex(dataSchema.getValue("propertyNames").jsonObject.getValue("pattern").jsonPrimitive.content)
        val arrayBranch = dataSchema.getValue("additionalProperties").jsonObject
            .getValue("anyOf").jsonArray
            .map { it.jsonObject }
            .firstOrNull { branch -> (branch["type"] as? JsonPrimitive)?.content == "array" }
            ?: error("fact-schema data shell lost the string-array branch")
        val maxItems = arrayBranch.getValue("maxItems").jsonPrimitive.int
        return data.mapNotNull { (key, value) ->
            val keyOk = keyPattern.matches(key)
            val valueOk = when (value) {
                is JsonObject -> false
                is JsonArray ->
                    value.size <= (dataSchema["properties"]?.jsonObject?.get(key)?.jsonObject?.get("maxItems")?.jsonPrimitive?.int ?: maxItems) &&
                        value.all { element -> element is JsonPrimitive && element !is JsonNull && element.isString }
                is JsonPrimitive -> value !is JsonNull
            }
            if (keyOk && valueOk) null else "data '$key' violates the frozen shell (keyOk=$keyOk, valueOk=$valueOk)"
        }
    }

    private companion object {
        const val SUPPORTED_SCHEMA_MAJOR = 1

        /** 与 contract.json 中 *_verified 键的顺序一致。 */
        val GATE_FLAGS = listOf(
            "identity_verified",
            "metrics_authority_verified",
            "series_key_verified",
            "late_query_verified",
            "output_identity_verified",
            "append_rewrite_contention_verified",
            "offset_commit_verified",
            "logs_contract_verified",
            "default_profile_verified",
            "fact_schema_v2_verified",
        )

        /** 设计6.1公共字段：除可选 context 外全部必填。 */
        val COMMON_FIELDS = listOf(
            "schema_version", "event_id", "timestamp", "producer_id", "run_id",
            "channel", "seq", "account_epoch", "policy_revision", "purposes",
            "source", "device_id", "plugin_version", "ide_product", "ide_build",
            "ide_build_major", "os_family", "arch", "env", "mode", "side",
            "connection_provider", "kind", "name", "data",
        )

        val CONTEXT_KEYS = listOf("operation_id", "attempt_id", "fault_id", "trace_id", "workspace_id", "incident_id")

        /** 设计第9章事件字典登记的 name。 */
        val EVENT_NAMES = listOf(
            "plugin.started", "plugin.shutdown", "plugin.unclean", "toolwindow.setup",
            "backend.load", "plugin.readiness", "connection", "connection.attempt",
            "connection.state_changed", "connection.recovery", "csc.install", "csc.start",
            "credentials.ready", "cli.download", "migration.required", "session.open",
            "session.restore", "action", "availability", "error.uncaught", "error.reported",
            "diagnostic.reported", "diagnostic.payload", "diagnostic.redaction_failed",
            "protocol.error", "telemetry.health", "session.dispose_risk", "rpc",
            "edt.delay", "edt.violation", "edt.stall", "render.apply", "ide.operation", "resource.snapshot",
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
