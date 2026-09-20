package ai.kilocode.stability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** data字段允许的取值形态。 */
private enum class FieldType { STRING, INTEGER, POSITIVE_INTEGER, BOOLEAN, STRING_LIST, RATE }

/** 单个data键的白名单规则：类型、可选受控词表、UTF-8字节上限。 */
private class KeyRule(
    val type: FieldType,
    val allowed: Set<String> = emptySet(),
    val maxBytes: Int = STRING_BYTES,
    val maxItems: Int = LIST_ITEMS,
)

/** 事件字典的单个name条目：kind、专属键闭集、必填键；branches用于error族的双schema分支。 */
private class EventSpec(
    val name: String,
    val kind: String,
    val keys: Map<String, KeyRule>,
    val required: Set<String>,
    val branches: List<Set<String>>,
) {
    /** 专属键与必填键皆空、无分支要求的事件（如plugin.started）允许空data；operation必须携带phase。 */
    val allowsEmptyData: Boolean = required.isEmpty() && branches.isEmpty() && kind != KIND_OPERATION
}

/** operation公共phase键的规则（设计6.2）：start要求deadline_ms>0，end必须自包含。 */
private class PhaseRule(val required: Set<String>, val keys: Map<String, KeyRule>)

private val CHANNELS = setOf("critical", "diagnostic")
private val PURPOSES = setOf("metrics", "logs")
private val CONTEXT_KEYS = setOf("operation_id", "attempt_id", "fault_id", "trace_id", "workspace_id")

private val PHASE_VALUES = setOf("start", "progress", "end")

/** result六种取值与cause受控词表（指标文档1.2/1.3）。 */
private val RESULT_VALUES = setOf("success", "failure", "timeout", "blocked", "cancelled", "unknown")
private val CAUSE_VALUES = setOf("plugin", "ide", "cs_cloud", "agent_core", "network", "environment", "user", "unknown")

/** 设计第9章各name专属键中明确给定的受控词表；未列出的值域按有界字符串处理。 */
private val END_KIND_VALUES = setOf("app_close", "unload")
private val SETUP_STAGES = setOf("create", "setup")
private val STREAM_STAGES = setOf("resolve", "health", "streams")
private val START_STAGES = setOf("spawn", "exit", "health")
private val CREDENTIAL_STAGES = setOf("probe", "wait")
private val DOWNLOAD_STAGES = setOf("download", "extract", "verify", "cache")
private val OPEN_MODES = setOf("create")
private val RESTORE_MODES = setOf("open", "reconnect")
private val RESTORE_STAGES = setOf("history", "subscription", "pending", "ui")
private val ACTIONS = setOf("prompt_submit", "stop", "permission_reply", "question_reply", "settings_save")
private val INTERVENTIONS = setOf("automatic", "manual")
private val AVAILABILITY_STATES = setOf("ready", "connecting", "blocked", "error")
private val PROTOCOL_STAGES = setOf("decode", "apply")
private val DISPOSE_SOURCES = setOf("global_disposed", "server_instance_disposed")
private val API_GROUPS = setOf("profile", "config", "session", "workspace", "mcp", "other")
private val VALIDITIES = setOf("valid", "suspended", "scheduler_gap", "unknown")
private val BATCH_BUCKETS = setOf("1", "2-5", "6-20", "21-100", "100+")
private val IDE_OPERATIONS = setOf("apply_edit", "open_diff", "vfs_refresh", "mcp_register")
private val RESOURCES = setOf("subscription", "controller", "editor")

/** 长度上限按UTF-8字节数执行（设计6.1）；writer落盘前以真实编码再核对32KiB。 */
private const val MAX_RECORD_BYTES = 32 * 1024
private const val MAX_CONTEXT_KEYS = 5
private const val ID_BYTES = 128
private const val CONTEXT_VALUE_BYTES = 128
private const val EPOCH_BYTES = 64
private const val STAGE_BYTES = 32
private const val STRING_BYTES = 64
private const val MESSAGE_BYTES = 512
private const val FRAME_BYTES = 256
private const val LIST_ITEMS = 5
private const val MIN_RATE = 0.0
private const val MAX_RATE = 1.0
private const val CONTROL_LIMIT = 0x20
private const val DEL_CODE = 0x7f

private const val KIND_OPERATION = "operation"

/** operation的phase规则：终态字段只允许出现在end，start只允许deadline_ms。 */
private val PHASE_RULES: Map<String, PhaseRule> = mapOf(
    "start" to PhaseRule(
        required = setOf("deadline_ms"),
        keys = mapOf(
            "phase" to KeyRule(FieldType.STRING, PHASE_VALUES),
            "deadline_ms" to KeyRule(FieldType.POSITIVE_INTEGER),
        ),
    ),
    "progress" to PhaseRule(
        required = emptySet(),
        keys = mapOf("phase" to KeyRule(FieldType.STRING, PHASE_VALUES)),
    ),
    "end" to PhaseRule(
        required = setOf("result", "duration_ms", "stage", "cause", "error_code"),
        keys = mapOf(
            "phase" to KeyRule(FieldType.STRING, PHASE_VALUES),
            "result" to KeyRule(FieldType.STRING, RESULT_VALUES),
            "duration_ms" to KeyRule(FieldType.INTEGER),
            "stage" to KeyRule(FieldType.STRING, maxBytes = STAGE_BYTES),
            "cause" to KeyRule(FieldType.STRING, CAUSE_VALUES),
            "error_code" to KeyRule(FieldType.STRING),
        ),
    ),
)

/**
 * 事件字典（设计第9章）：登记插件可采集的全部事实name和逐name字段闭集。
 *
 * 白名单语义：未登记的name一律拒绝（新增name需契约修订），data里的未知键和错误类型
 * 直接拒绝而不是裁掉后放行；对象业务payload、路径、JWT与控制字符均不通过。
 * error族是两个schema分支：critical形状的最小计数（fault_id/error_class/handled/
 * fingerprint/component）与diagnostic形状的固定详情（message/frames/fingerprint/count），
 * data键集必须恰好等于其中一个完整分支——缺键、多键与混用一律拒绝，
 * 也不把详情白名单套到其他diagnostic上。
 */
object Dictionary {

    /** 设计第9章登记的全部name，顺序与fact-schema.json的enum一致。 */
    val names: List<String>
        get() = SPEC_TABLE.keys.toList()

    fun isRegistered(name: String): Boolean = SPEC_TABLE.containsKey(name)

    /** 白名单校验：合法返回true；任何拒绝都不抛异常，由recorder计数。 */
    fun validate(draft: Draft): Boolean = violations(draft).isEmpty()

    /** 返回全部拒绝原因，供recorder计数与本地限频日志使用；合法输入返回空列表。 */
    fun violations(draft: Draft): List<String> = buildList {
        val spec = SPEC_TABLE[draft.name] ?: return listOf("unregistered event name '${draft.name}'")
        if (draft.kind != spec.kind) add("kind '${draft.kind}' does not match registered kind '${spec.kind}'")
        if (draft.channel !in CHANNELS) add("channel '${draft.channel}' is outside $CHANNELS")
        if (draft.purposes.isEmpty()) add("purposes must not be empty")
        draft.purposes.filterNot { it in PURPOSES }.forEach { add("purpose '$it' is outside $PURPOSES") }
        draft.epoch?.let { epoch ->
            if (!boundedText(epoch, EPOCH_BYTES)) add("account epoch violates the id bounds")
        }
        if (draft.context.size > MAX_CONTEXT_KEYS) {
            add("context carries ${draft.context.size} keys, at most $MAX_CONTEXT_KEYS allowed")
        }
        draft.context.forEach { (key, value) ->
            if (key !in CONTEXT_KEYS) add("context key '$key' is outside the closed set")
            else if (!boundedText(value, CONTEXT_VALUE_BYTES)) add("context value for '$key' violates the id bounds")
        }
        addAll(dataViolations(spec, draft.data))
        if (estimateRecord(draft) >= MAX_RECORD_BYTES) add("estimated record size exceeds the 32KiB budget")
    }

    private fun dataViolations(spec: EventSpec, data: JsonObject): List<String> {
        if (data.isEmpty()) {
            return if (spec.allowsEmptyData) {
                emptyList()
            } else {
                listOf("data must not be empty for '${spec.name}'")
            }
        }
        val phase = (data[PHASE_KEY] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull
        val phaseRule = if (spec.kind == KIND_OPERATION) phase?.let { PHASE_RULES[it] } else null
        val rules: Map<String, KeyRule> = spec.keys + (phaseRule?.keys ?: emptyMap())
        val required = buildSet {
            addAll(spec.required)
            if (spec.kind == KIND_OPERATION) add(PHASE_KEY)
            phaseRule?.let { addAll(it.required) }
        }
        val problems = mutableListOf<String>()
        // error族双分支：data键集必须恰好等于一个完整分支（缺键的半条记录与混用同样拒绝）。
        if (spec.branches.isNotEmpty() && spec.branches.count { branch -> data.keys == branch } != 1) {
            problems += "data keys of '${spec.name}' do not match exactly one schema branch"
        }
        data.forEach { (key, value) ->
            val rule = rules[key]
            if (rule == null) problems += "data key '$key' is outside the '${spec.name}' allowlist"
            else problems += valueViolations(key, rule, value)
        }
        required.filterNot { it in data }.forEach { problems += "data key '$it' is required for '${spec.name}'" }
        return problems
    }

    private fun valueViolations(key: String, rule: KeyRule, value: JsonElement): List<String> = when {
        value is JsonNull -> listOf("data key '$key' must not be null")
        value is JsonObject -> listOf("data key '$key' must not carry an object payload")
        value is JsonArray -> listViolations(key, rule, value)
        value !is JsonPrimitive -> listOf("data key '$key' must be a scalar or a string array")
        rule.type == FieldType.STRING -> if (!value.isString) {
            listOf("data key '$key' must be a string")
        } else {
            stringViolations(key, value.content, rule)
        }
        rule.type == FieldType.BOOLEAN ->
            if (value.booleanOrNull == null) listOf("data key '$key' must be a boolean") else emptyList()
        rule.type == FieldType.STRING_LIST -> listOf("data key '$key' must be a string array")
        else -> numberViolations(key, rule, value)
    }

    private fun listViolations(key: String, rule: KeyRule, value: JsonArray): List<String> = when {
        rule.type != FieldType.STRING_LIST -> listOf("data key '$key' must not be an array")
        value.size > rule.maxItems ->
            listOf("data key '$key' carries ${value.size} items, at most ${rule.maxItems} allowed")
        else -> value.flatMapIndexed { index, element ->
            when {
                element is JsonObject -> listOf("data key '$key' item $index must not be an object")
                element is JsonArray -> listOf("data key '$key' item $index must not be nested")
                element !is JsonPrimitive || element is JsonNull ->
                    listOf("data key '$key' item $index must be a string")
                else -> stringViolations("$key[$index]", element.content, rule)
            }
        }
    }

    /** 字符串白名单：非空、字节上限、无控制字符、无换行、不含路径分隔符；allowed非空时同时校验词表。 */
    private fun stringViolations(key: String, value: String, rule: KeyRule): List<String> {
        val problems = when {
            value.isEmpty() -> listOf("data key '$key' must not be empty")
            value.encodeToByteArray().size > rule.maxBytes ->
                listOf("data key '$key' exceeds ${rule.maxBytes} utf-8 bytes")
            value.any { it.code < CONTROL_LIMIT || it.code == DEL_CODE } ->
                listOf("data key '$key' contains control characters")
            value.any { it == '/' || it == '\\' } -> listOf("data key '$key' must not carry path separators")
            else -> emptyList()
        }
        return if (rule.allowed.isNotEmpty() && value !in rule.allowed) {
            problems + "data key '$key' value '$value' is outside the controlled vocabulary"
        } else {
            problems
        }
    }

    private fun numberViolations(key: String, rule: KeyRule, value: JsonPrimitive): List<String> {
        if (rule.type == FieldType.RATE) {
            val rate = value.doubleOrNull
            return when {
                rate == null -> listOf("data key '$key' must be a sampling rate number")
                rate <= MIN_RATE || rate > MAX_RATE -> listOf("data key '$key' must stay inside (0, 1]")
                else -> emptyList()
            }
        }
        val min = if (rule.type == FieldType.POSITIVE_INTEGER) 1L else 0L
        val parsed = value.longOrNull
        return when {
            parsed == null -> listOf("data key '$key' must be an integer")
            parsed < min -> listOf("data key '$key' must not be below $min")
            else -> emptyList()
        }
    }

    /** 32KiB的保守预估（数据与context的UTF-8字节合计）；精确检查由writer落盘前执行。 */
    private fun estimateRecord(draft: Draft): Int =
        draft.data.entries.sumOf { (key, value) ->
            key.encodeToByteArray().size + value.toString().encodeToByteArray().size
        } + draft.context.entries.sumOf { (key, value) ->
            key.encodeToByteArray().size + value.encodeToByteArray().size
        }

    private fun boundedText(value: String, maxBytes: Int): Boolean =
        value.isNotEmpty() &&
            value.encodeToByteArray().size <= maxBytes &&
            value.none { it.code < CONTROL_LIMIT || it.code == DEL_CODE }

    private val SPEC_TABLE: Map<String, EventSpec> = buildMap {
        fun spec(
            name: String,
            kind: String,
            keys: List<Pair<String, KeyRule>> = emptyList(),
            required: Set<String> = emptySet(),
            branches: List<Set<String>> = emptyList(),
        ) {
            put(name, EventSpec(name, kind, keys.toMap(), required, branches))
        }

        fun key(name: String, type: FieldType, allowed: Set<String> = emptySet(), maxBytes: Int = STRING_BYTES) =
            name to KeyRule(type, allowed, maxBytes)

        val errorKeys = listOf(
            key("fault_id", FieldType.STRING, maxBytes = ID_BYTES),
            key("error_class", FieldType.STRING),
            key("handled", FieldType.BOOLEAN),
            key("fingerprint", FieldType.STRING, maxBytes = ID_BYTES),
            key("component", FieldType.STRING),
            key("message", FieldType.STRING, maxBytes = MESSAGE_BYTES),
            key("frames", FieldType.STRING_LIST, maxBytes = FRAME_BYTES),
            key("count", FieldType.POSITIVE_INTEGER),
        )
        val errorBranches = listOf(
            setOf("fault_id", "error_class", "handled", "fingerprint", "component"),
            setOf("message", "frames", "fingerprint", "count"),
        )

        spec("plugin.started", "lifecycle")
        spec(
            "plugin.shutdown", "lifecycle",
            keys = listOf(key("end_kind", FieldType.STRING, END_KIND_VALUES)),
            required = setOf("end_kind"),
        )
        spec(
            "plugin.unclean", "lifecycle",
            keys = listOf(
                key("previous_run_id", FieldType.STRING, maxBytes = ID_BYTES),
                key("evidence", FieldType.STRING),
            ),
            required = setOf("previous_run_id", "evidence"),
        )
        spec(
            "toolwindow.setup", "operation",
            keys = listOf(key("stage", FieldType.STRING, SETUP_STAGES, maxBytes = STAGE_BYTES)),
        )
        spec(
            "backend.load", "operation",
            keys = listOf(key("trigger", FieldType.STRING), key("reason", FieldType.STRING)),
        )
        spec("plugin.readiness", "operation", keys = listOf(key("reason", FieldType.STRING)))
        spec(
            "connection", "operation",
            keys = listOf(
                key("trigger", FieldType.STRING),
                key("stage", FieldType.STRING, STREAM_STAGES, maxBytes = STAGE_BYTES),
            ),
        )
        spec(
            "connection.attempt", "operation",
            keys = listOf(
                key("stage", FieldType.STRING, STREAM_STAGES, maxBytes = STAGE_BYTES),
                key("error_code", FieldType.STRING),
            ),
        )
        spec(
            "connection.state_changed", "transition",
            keys = listOf(
                key("from", FieldType.STRING),
                key("to", FieldType.STRING),
                key("reason", FieldType.STRING),
                key("streams_open", FieldType.INTEGER),
                key("streams_total", FieldType.INTEGER),
            ),
            required = setOf("from", "to"),
        )
        spec(
            "connection.recovery", "operation",
            keys = listOf(key("intervention", FieldType.STRING, INTERVENTIONS), key("attempts", FieldType.INTEGER)),
        )
        spec(
            "csc.install", "operation",
            keys = listOf(key("package_manager", FieldType.STRING), key("error_code", FieldType.STRING)),
        )
        spec(
            "csc.start", "operation",
            keys = listOf(
                key("stage", FieldType.STRING, START_STAGES, maxBytes = STAGE_BYTES),
                key("error_code", FieldType.STRING),
            ),
        )
        spec(
            "credentials.ready", "operation",
            keys = listOf(
                key("stage", FieldType.STRING, CREDENTIAL_STAGES, maxBytes = STAGE_BYTES),
                key("error_code", FieldType.STRING),
            ),
        )
        spec(
            "cli.download", "operation",
            keys = listOf(
                key("stage", FieldType.STRING, DOWNLOAD_STAGES, maxBytes = STAGE_BYTES),
                key("cache_hit", FieldType.BOOLEAN),
            ),
        )
        spec(
            "migration.required", "transition",
            keys = listOf(key("migration_kind", FieldType.STRING)),
            required = setOf("migration_kind"),
        )
        spec(
            "session.open", "operation",
            keys = listOf(key("session_mode", FieldType.STRING, OPEN_MODES)),
            required = setOf("session_mode"),
        )
        spec(
            "session.restore", "operation",
            keys = listOf(
                key("session_mode", FieldType.STRING, RESTORE_MODES),
                key("stage", FieldType.STRING, RESTORE_STAGES, maxBytes = STAGE_BYTES),
            ),
            required = setOf("session_mode"),
        )
        spec(
            "action", "operation",
            keys = listOf(key("action", FieldType.STRING, ACTIONS)),
            required = setOf("action"),
        )
        spec(
            "availability", "interval",
            keys = listOf(
                key("state", FieldType.STRING, AVAILABILITY_STATES),
                key("begin_timestamp", FieldType.INTEGER),
                key("end_timestamp", FieldType.INTEGER),
                key("duration_ms", FieldType.INTEGER),
            ),
            required = setOf("state", "begin_timestamp", "end_timestamp", "duration_ms"),
        )
        spec("error.uncaught", "diagnostic", keys = errorKeys, branches = errorBranches)
        spec("error.reported", "diagnostic", keys = errorKeys, branches = errorBranches)
        spec(
            "protocol.error", "diagnostic",
            keys = listOf(
                key("transport", FieldType.STRING),
                key("stage", FieldType.STRING, PROTOCOL_STAGES, maxBytes = STAGE_BYTES),
                key("error_code", FieldType.STRING),
            ),
            required = setOf("transport", "stage", "error_code"),
        )
        spec(
            "telemetry.health", "health",
            keys = listOf(
                key("drop", FieldType.INTEGER),
                key("write_error", FieldType.INTEGER),
                key("depth_bytes", FieldType.INTEGER),
                key("oldest_age_ms", FieldType.INTEGER),
            ),
            required = setOf("drop", "write_error", "depth_bytes", "oldest_age_ms"),
        )
        spec(
            "session.dispose_risk", "transition",
            keys = listOf(
                key("dispose_source", FieldType.STRING, DISPOSE_SOURCES),
                key("conversation_active", FieldType.BOOLEAN),
            ),
            required = setOf("dispose_source", "conversation_active"),
        )
        spec(
            "rpc", "operation",
            keys = listOf(key("api_group", FieldType.STRING, API_GROUPS)),
            required = setOf("api_group"),
        )
        spec(
            "edt.delay", "sample",
            keys = listOf(
                key("observation_id", FieldType.STRING, maxBytes = ID_BYTES),
                key("probe_seq", FieldType.POSITIVE_INTEGER),
                key("scheduled_mono_ms", FieldType.INTEGER),
                key("completed_mono_ms", FieldType.INTEGER),
                key("duration_ms", FieldType.INTEGER),
                key("validity", FieldType.STRING, VALIDITIES),
            ),
            required = setOf(
                "observation_id", "probe_seq", "scheduled_mono_ms", "completed_mono_ms", "duration_ms", "validity",
            ),
        )
        spec(
            "edt.violation", "diagnostic",
            keys = listOf(key("operation", FieldType.STRING), key("evidence", FieldType.STRING, maxBytes = ID_BYTES)),
            required = setOf("operation", "evidence"),
        )
        spec(
            "render.apply", "sample",
            keys = listOf(
                key("duration_ms", FieldType.INTEGER),
                key("result", FieldType.STRING),
                key("component", FieldType.STRING),
                key("batch_size_bucket", FieldType.STRING, BATCH_BUCKETS),
                key("sample_rate", FieldType.RATE),
            ),
            required = setOf("duration_ms"),
        )
        spec(
            "ide.operation", "operation",
            keys = listOf(key("operation", FieldType.STRING, IDE_OPERATIONS)),
            required = setOf("operation"),
        )
        spec(
            "resource.snapshot", "sample",
            keys = listOf(key("resource", FieldType.STRING, RESOURCES), key("count", FieldType.INTEGER)),
            required = setOf("resource", "count"),
        )
    }

    private const val PHASE_KEY = "phase"
}
