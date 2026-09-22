package ai.kilocode.stability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 与Fixture.defaultControl同形状的wire控制文件，许可位可调（categories随用途开关）。 */
private fun controlJson(metrics: Boolean, logs: Boolean): String = buildJsonObject {
    fun categories(on: Boolean) = JsonArray((if (on) listOf("critical", "diagnostic") else emptyList()).map { JsonPrimitive(it) })
    put("schema_major", 1)
    put("revision", 12L)
    put("enabled", true)
    put("metrics_enabled", metrics)
    put("metrics_expires_at", 9_000_000_000_000L)
    put("metrics_allowed_categories", categories(metrics))
    put("logs_enabled", logs)
    put("logs_expires_at", 9_000_000_000_000L)
    put("logs_allowed_categories", categories(logs))
    put("account_epoch", "acct-a")
    put("account_state", "ready")
    put("expires_at", 9_000_000_000_000L)
    put("log_detail_rate_limit", buildJsonObject { put("per_fingerprint_max_per_minute", 3) })
}.toString()

/** 一个name在盘上的期望形态（Dictionary.purposes投影后的最终purposes）。 */
private data class Form(val kind: String, val channel: String, val purposes: Set<String>)

private val METRICS = setOf("metrics")
private val DUAL = setOf("metrics", "logs")
private val LOGS = setOf("logs")

/**
 * 全部登记name的期望形态（31个，非error键在第一用例内与Dictionary.names闭集对齐）；
 * plugin.unclean无插件发射点（设计7.3属消费端判定），本扫描经Draft验证其登记形状可落盘。
 * error.count/error.detail是error族两个schema分支的期望键，对应登记名error.reported/error.uncaught。
 */
private val EXPECTED: Map<String, Form> = mapOf(
    "plugin.started" to Form("lifecycle", "critical", DUAL),
    "plugin.shutdown" to Form("lifecycle", "critical", DUAL),
    "plugin.unclean" to Form("lifecycle", "critical", DUAL),
    "toolwindow.setup" to Form("operation", "critical", DUAL),
    "backend.load" to Form("operation", "critical", DUAL),
    "plugin.readiness" to Form("operation", "critical", DUAL),
    "connection" to Form("operation", "critical", DUAL),
    "connection.attempt" to Form("operation", "critical", DUAL),
    "connection.recovery" to Form("operation", "critical", DUAL),
    "csc.install" to Form("operation", "critical", DUAL),
    "csc.start" to Form("operation", "critical", DUAL),
    "credentials.ready" to Form("operation", "critical", DUAL),
    "cli.download" to Form("operation", "critical", DUAL),
    "session.open" to Form("operation", "critical", DUAL),
    "session.restore" to Form("operation", "critical", DUAL),
    "action" to Form("operation", "critical", DUAL),
    "rpc" to Form("operation", "critical", METRICS),
    "ide.operation" to Form("operation", "critical", DUAL),
    "connection.state_changed" to Form("transition", "critical", DUAL),
    "migration.required" to Form("transition", "critical", METRICS),
    "session.dispose_risk" to Form("transition", "critical", METRICS),
    "availability" to Form("interval", "critical", METRICS),
    "edt.delay" to Form("sample", "critical", METRICS),
    "render.apply" to Form("sample", "critical", METRICS),
    "resource.snapshot" to Form("sample", "critical", METRICS),
    "protocol.error" to Form("diagnostic", "critical", DUAL),
    "edt.violation" to Form("diagnostic", "critical", DUAL),
    "edt.stall" to Form("sample", "critical", METRICS),
    "telemetry.health" to Form("health", "critical", DUAL),
    // error族两形态：计数写critical仅metrics（限频不改变次数），详情写diagnostic仅logs。
    "error.count" to Form("diagnostic", "critical", METRICS),
    "error.detail" to Form("diagnostic", "diagnostic", LOGS),
)

private fun fields(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}

/**
 * 扫描用tick：全量覆盖断言要求突发写入零丢弃，而writer后台tick的tryClaim/release与
 * 生产者tryLock争用会把突发中的记录按CONTENTION计丢（fail-open语义，对逐name闭集断言
 * 即假失败——HEAD上曾观察到一次3条相邻transition事实被丢）。60秒初始延迟保证整个突发
 * 期间无后台排空，落盘只经flush()屏障（排空与tick无关），断言语义不变。
 */
private const val SWEEP_QUIET_TICK_MS = 60_000L

/**
 * 全字典落盘扫描（设计第9章事件字典/第6章事实格式；验收口径"插件端所有类型"）：
 * 真实Recorder→真实Writer→真实临时目录，对每个登记name产出至少一条合法事实，
 * flush后从追加事实文件（单一jsonl，Fixture.facts()）还原断言——
 *  - 31个登记name全部落盘，kind/channel/purposes与Dictionary投影一致；
 *  - error族计数形态（critical/metrics）与详情形态（diagnostic/logs）分道落盘；
 *  - 7种kind（operation/transition/lifecycle/interval/sample/diagnostic/health）齐备；
 *  - seq按通道从1连续；account_epoch/policy_revision来自控制文件；
 *  - operation经真实begin/progress/end三段产出且context.operation_id配对；
 *  - 单用途策略下盘上取舍正确（指标关→仅指标name与error计数不落盘；日志关→详情不落盘）。
 */
class DictionarySweepTest {

    @Test
    fun `all registered names land on disk in their dictionary form`() {
        Fixture(tickMs = SWEEP_QUIET_TICK_MS).use { fixture ->
            driveAllNames(fixture)
            fixture.flush()
            val facts = fixture.facts()

            // —— 闭集锚定：EXPECTED的非error键必须恰等于Dictionary登记名的非error子集
            // （error两形态期望键对应error.reported/error.uncaught两个登记名）；
            // 字典新增name而本扫描未补形态/驱动时，在此先失败 ——
            assertEquals(
                Dictionary.names.filter { !it.startsWith("error.") }.toSet(),
                EXPECTED.keys.filter { !it.startsWith("error.") }.toSet(),
                "EXPECTED must project exactly the registered non-error names",
            )
            assertEquals(setOf("error.reported", "error.uncaught"), Dictionary.names.filter { it.startsWith("error.") }.toSet())

            // —— 覆盖：每个登记name至少一条（error族按形态核对）——
            val names = facts.map { it.name }.filter { !it.startsWith("error.") }.toSet()
            val expectedNames = EXPECTED.keys
                .filter { !it.startsWith("error.") } // error两形态单独断言
                .toSet()
            assertEquals(expectedNames, names, "every registered name must reach the appended jsonl on disk")

            // —— 形态：kind/channel/purposes与Dictionary投影一致 ——
            val violations = facts.mapNotNull { fact ->
                val expected = when {
                    fact.name == "error.reported" || fact.name == "error.uncaught" ->
                        if (fact.channel == "critical") EXPECTED["error.count"] else EXPECTED["error.detail"]
                    else -> EXPECTED[fact.name]
                }
                when {
                    expected == null -> "unregistered name on disk: ${fact.name}"
                    fact.kind != expected.kind -> "${fact.name}: kind ${fact.kind} != ${expected.kind}"
                    fact.channel != expected.channel -> "${fact.name}: channel ${fact.channel} != ${expected.channel}"
                    fact.purposes.toSet() != expected.purposes -> "${fact.name}: purposes ${fact.purposes} != ${expected.purposes}"
                    else -> null
                }
            }
            assertTrue(violations.isEmpty(), "form violations:\n${violations.joinToString("\n")}")

            // —— error族两形态的data键闭集与计数/详情分离 ——
            val errorCounts = facts.filter { it.name.startsWith("error.") && it.channel == "critical" }
            val errorDetails = facts.filter { it.name.startsWith("error.") && it.channel == "diagnostic" }
            assertEquals(setOf("error.reported", "error.uncaught"), errorCounts.map { it.name }.toSet())
            assertEquals(setOf("fault_id", "error_class", "handled", "fingerprint", "component"), errorCounts[0].data.keys.toSet())
            assertEquals(setOf("message", "frames", "fingerprint", "count"), errorDetails[0].data.keys.toSet())
            assertTrue(errorDetails.all { it.data["count"].toString().trim('"').toLong() >= 1 })
            assertTrue(errorCounts.all { "secret" !in it.data.toString() && "boom" !in it.data.toString() }, "原始异常message不得进入事实")

            // —— seq按通道从1连续（critical与diagnostic独立）——
            listOf("critical", "diagnostic").forEach { channel ->
                val seqs = facts.filter { it.channel == channel }.map { it.seq }
                assertEquals((1L..seqs.size).toList(), seqs, "$channel channel seq must be contiguous from 1")
            }

            // —— 身份字段来自控制文件且全程稳定 ——
            assertEquals("acct-a", facts[0].account_epoch)
            assertEquals(12L, facts[0].policy_revision)
            assertEquals(setOf("pr-a4"), facts.map { it.producer_id }.toSet())
            assertEquals(setOf("device-a4"), facts.map { it.device_id }.toSet())

            // —— operation三段经真实Operations产出且operation_id配对 ——
            // progress的stage键受name级词表约束（rpc仅api_group，其progress被字典拒绝——
            // 词汇表外键不落盘即是该约束的活证明）；三段断言用带stage规格的connection.attempt。
            val attemptPhases = facts.filter { it.name == "connection.attempt" }.associateBy { it.data["phase"].toString().trim('"') }
            assertEquals(setOf("start", "progress", "end"), attemptPhases.keys)
            val operationIds = attemptPhases.values.map { it.context["operation_id"] }.toSet()
            assertEquals(1, operationIds.size, "三个phase必须共享同一operation_id")
            assertEquals("health", attemptPhases["progress"]?.data?.get("stage").toString().trim('"'))
            val rpcPhases = facts.filter { it.name == "rpc" }.map { it.data["phase"].toString().trim('"') }.toSet()
            assertEquals(setOf("start", "end"), rpcPhases, "rpc（无stage键的name）仅start/end两段")

            // —— 7种kind全部出现在盘上 ——
            assertEquals(
                setOf("operation", "transition", "lifecycle", "interval", "sample", "diagnostic", "health"),
                facts.map { it.kind }.toSet(),
            )
        }
    }

    @Test
    fun `logs-only policy keeps logs facts on disk and drops metrics-only names`() {
        Fixture(tickMs = SWEEP_QUIET_TICK_MS).use { fixture ->
            fixture.base.resolve("control.json").writeText(controlJson(metrics = false, logs = true))
            fixture.policies.refresh()
            val ops = fixture.operations
            ops.begin("rpc", 1_000, fields("api_group" to "session")).end("success") // metrics-only → 不落盘
            ops.begin("action", 30_000, fields("action" to "prompt_submit")).end("success") // dual → 仅logs
            Faults(fixture.recorder, fixture.clock)
                .report(IllegalStateException("sweep"), "frontend", handled = true, fault = "fault-logs-only")
            fixture.flush()
            val facts = fixture.facts()

            assertTrue(facts.none { it.name == "rpc" }, "metrics-only name must not reach disk without metrics permit")
            assertTrue(facts.none { it.name.startsWith("error.") && it.channel == "critical" }, "error计数(metrics)必须不落盘")
            val action = facts.filter { it.name == "action" }
            assertEquals(setOf("start", "end"), action.map { it.data["phase"].toString().trim('"') }.toSet())
            assertEquals(LOGS, action.first().purposes.toSet(), "dual name在logs-only许可下只保留logs用途")
            assertEquals(1, facts.count { it.name == "error.reported" && it.channel == "diagnostic" }, "error详情(logs)必须落盘")
        }
    }

    @Test
    fun `metrics-only policy keeps metrics facts on disk and drops log details`() {
        Fixture(tickMs = SWEEP_QUIET_TICK_MS).use { fixture ->
            fixture.base.resolve("control.json").writeText(controlJson(metrics = true, logs = false))
            fixture.policies.refresh()
            val ops = fixture.operations
            ops.begin("toolwindow.setup", 30_000).end("success", stage = "setup") // dual → 仅metrics
            fixture.recorder.record(
                Draft("availability", "interval", "critical", buildJsonObject {
                    put("state", "ready")
                    put("begin_timestamp", 0L)
                    put("end_timestamp", 10L)
                    put("duration_ms", 10L)
                }),
            )
            Faults(fixture.recorder, fixture.clock)
                .report(IllegalStateException("sweep"), "frontend", handled = true, fault = "fault-metrics-only")
            fixture.flush()
            val facts = fixture.facts()

            assertTrue(facts.none { it.channel == "diagnostic" }, "logs详情通道在logs-only-off下必须整通道为空")
            val setup = facts.filter { it.name == "toolwindow.setup" }
            assertEquals(METRICS, setup.first().purposes.toSet(), "dual name在metrics-only许可下只保留metrics用途")
            assertEquals(1, facts.count { it.name == "availability" })
            assertEquals(1, facts.count { it.name == "error.reported" && it.channel == "critical" }, "error计数(metrics)必须落盘")
        }
    }

    /** 对全部登记name各产出至少一条字典合法事实（发射面可达的用真实Operations/Faults/Resources）。 */
    private fun driveAllNames(fixture: Fixture) {
        val ops = fixture.operations

        // ---- operation：真实begin/progress/end（受控stage词表取合法值）----
        ops.begin("toolwindow.setup", 30_000).end("success", stage = "setup")
        ops.begin("backend.load", 30_000, fields("trigger" to "startup", "reason" to "cold")).end("success")
        ops.begin("plugin.readiness", 60_000, fields("reason" to "credentials_missing"))
            .end("blocked", cause = "environment", fields = fields("reason" to "credentials_missing"))
        val connection = ops.begin("connection", 30_000, fields("trigger" to "toolwindow"))
        connection.progress("resolve")
        connection.end("success", stage = "streams")
        val attempt = ops.begin("connection.attempt", 30_000)
        attempt.progress("health")
        attempt.end("success", stage = "health")
        ops.begin("connection.recovery", 30_000).end(
            "success",
            fields = buildJsonObject { put("intervention", "automatic"); put("attempts", 1) },
        )
        ops.begin("csc.install", 120_000).end("success", fields = fields("package_manager" to "npm"))
        ops.begin("csc.start", 30_000).end("success", stage = "health")
        ops.begin("credentials.ready", 60_000).end("success", stage = "probe")
        ops.begin("cli.download", 120_000).end("success", stage = "download", fields = buildJsonObject { put("cache_hit", true) })
        ops.begin("session.open", 30_000, fields("session_mode" to "create")).end("success")
        ops.begin("session.restore", 30_000, fields("session_mode" to "open"))
            .end("success", stage = "history", fields = fields("session_mode" to "open"))
        ops.begin("action", 30_000, fields("action" to "prompt_submit")).end("success")
        val rpc = ops.begin("rpc", 30_000, fields("api_group" to "session"))
        rpc.progress("other")
        rpc.end("success")
        ops.begin("ide.operation", 30_000, fields("operation" to "vfs_refresh")).end("success")

        // ---- transition ----
        fixture.recorder.record(
            Draft(
                "connection.state_changed", "transition", "critical",
                buildJsonObject {
                    put("from", "connecting"); put("to", "ready"); put("reason", "streams_open")
                    put("streams_open", 3); put("streams_total", 3)
                },
            ),
        )
        fixture.recorder.record(
            Draft("migration.required", "transition", "critical", fields("migration_kind" to "legacy_v5")),
        )
        fixture.recorder.record(
            Draft(
                "session.dispose_risk", "transition", "critical",
                buildJsonObject { put("dispose_source", "global_disposed"); put("conversation_active", true) },
            ),
        )

        // ---- interval / sample（draft用途交默认dual，落盘purposes由Dictionary投影收窄）----
        fixture.recorder.record(
            Draft(
                "availability", "interval", "critical",
                buildJsonObject {
                    put("state", "ready"); put("begin_timestamp", 0L); put("end_timestamp", 100L); put("duration_ms", 100L)
                },
                context = mapOf("workspace_id" to "ws-sweep"),
            ),
        )
        fixture.recorder.record(
            Draft(
                "edt.delay", "sample", "critical",
                buildJsonObject {
                    put("observation_id", "obs-sweep"); put("probe_seq", 1L)
                    put("scheduled_mono_ms", 0L); put("completed_mono_ms", 12L)
                    put("duration_ms", 12L); put("validity", "valid")
                },
            ),
        )
        fixture.recorder.record(
            Draft(
                "render.apply", "sample", "critical",
                buildJsonObject {
                    put("duration_ms", 34L); put("result", "success"); put("component", "messages")
                    put("batch_size_bucket", "1"); put("sample_rate", 1.0)
                },
            ),
        )
        // ---- edt.stall经真实StallMerger驱动：同区间两枚首尾相接样本（seq 1→2）合并为
        // 2.5秒窗口，onObservationEnded终结后达标产出——Draft形状由合并器产出，不伪造 ----
        val stalls = mutableListOf<Draft>()
        val stallMerger = StallMerger { stalls.add(it) }
        stallMerger.onValidSample("obs-sweep-stall", 1, 10_000, 11_800)
        stallMerger.onValidSample("obs-sweep-stall", 2, 11_800, 12_500)
        stallMerger.onObservationEnded()
        stalls.forEach(fixture.recorder::record)

        fixture.resources.acquire("subscription").use { }
        resourceSnapshotDrafts(fixture.resources.snapshot()).forEach { fixture.recorder.record(it) }

        // ---- diagnostic证据形态（critical通道，dual用途；与edtViolationDraft/CsCloudSseClient同构）----
        fixture.recorder.record(
            Draft(
                "protocol.error", "diagnostic", "critical",
                buildJsonObject { put("transport", "sse"); put("stage", "decode"); put("error_code", "malformed_event") },
            ),
        )
        fixture.recorder.record(
            Draft(
                "edt.violation", "diagnostic", "critical",
                buildJsonObject { put("operation", "session"); put("evidence", "platform_thread_assertion") },
            ),
        )

        // ---- health ----
        fixture.recorder.record(
            Draft(
                "telemetry.health", "health", "critical",
                buildJsonObject {
                    put("drop", 0L); put("write_error", 0L); put("depth_bytes", 0L); put("oldest_age_ms", 0L)
                },
            ),
        )

        // ---- lifecycle（started/shutdown有服务级发射面（producer-test/E2E），此处补齐unclean登记形状）----
        fixture.recorder.record(Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap())))
        fixture.recorder.record(
            Draft("plugin.shutdown", "lifecycle", "critical", buildJsonObject { put("end_kind", "unload") }),
        )
        fixture.recorder.record(
            Draft(
                "plugin.unclean", "lifecycle", "critical",
                buildJsonObject { put("previous_run_id", "run-prev0"); put("evidence", "writer_lock_recovered") },
            ),
        )

        // ---- error族：真实Faults（计数critical/metrics + 详情diagnostic/logs）----
        val faults = Faults(fixture.recorder, fixture.clock)
        faults.report(IllegalStateException("boom-secret"), "frontend", handled = true, fault = "fault-sweep-1")
        faults.report(IllegalArgumentException("uncaught-secret"), "backend", handled = false, fault = "fault-sweep-2")
    }
}
