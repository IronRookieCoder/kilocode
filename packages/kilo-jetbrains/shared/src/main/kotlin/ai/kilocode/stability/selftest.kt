package ai.kilocode.stability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** 自检事实的项目上下文标记（context闭集键，值≤128B无控制字符）：盘上可逐条辨识自检驱动面。 */
private const val SELFTEST_WORKSPACE = "ws-selftest"

/** 自检故障的显式fault_id（跨重复报告去重键；计数与详情两条事实经它关联）。 */
private const val SELFTEST_FAULT_REPORTED = "fault-selftest-reported"
private const val SELFTEST_FAULT_UNCAUGHT = "fault-selftest-uncaught"

private const val OUTBOX_FAILURES = 100
private const val OUTBOX_FILL_LIMIT = 100_000
private const val OUTBOX_DEADLINE_MS = 600_000L

/**
 * 采集链路自检的驱动面（设计第9章事件字典）：对每个登记name各产出至少一条字典合法事实，
 * 走与业务观测完全相同的[Recorder]/[Operations]/[Faults]/[Resources]入口——准入、队列、
 * 封存与outbox落盘全部经过真实管线，不做任何旁路写入。
 *
 * 范围（31 name中排除3个）：
 *  - `plugin.started`/`plugin.shutdown`是服务级每run恰一条的 lifecycle 单发
 *    （stability-service activateRun/stop 独占），自检重复发会破坏"唯一终态"口径；
 *  - `telemetry.health`的drop/write_error是自上一条health事实以来的增量（§6.2，cs-cloud
 *  直接求和），伪造事实会向下游损失总和注入虚假增量；真实IDE由Health后台周期产出，不经自检驱动。
 * 其余28个name（含error族计数/详情两形态、7种kind全部；edt.stall经真实[StallMerger]
 * 喂合成样本序列产出，不伪造Draft形状）在此驱动；真实端到端会话中
 * 被排除的3个由服务自身自然产出，全集覆盖由验收断言把关。
 *
 * 安全约定：值全部取自字典受控词表（与dictionary-sweep-test同一套已验证形状）；
 * operation走真实begin/end三段（rpc不发progress——progress必带stage而rpc无stage词表，
 * 词典外键拒绝是约束本身，自检不得制造故意违规去污染drop计数）；异常message绝不入事实
 * （Faults安全模板）；可用operation/草稿携带workspace_id=ws-selftest便于盘上辨识。
 * 线程纪律：全部入口非阻塞（record入内存队列、定时器挂调用方scope），可EDT调用。
 */
fun emitDictionarySweep(
    recorder: Recorder,
    operations: Operations,
    faults: Faults,
    resources: Resources,
) {
    fun selftestContext(): Map<String, String> = mapOf("workspace_id" to SELFTEST_WORKSPACE)

    // ---- operation：真实begin/progress/end（受控stage词表取合法值）----
    operations.begin("toolwindow.setup", 30_000, context = selftestContext()).end("success", stage = "setup")
    operations.begin("backend.load", 30_000, fields("trigger" to "startup", "reason" to "cold"), selftestContext())
        .end("success")
    operations.begin("plugin.readiness", 60_000, fields("reason" to "credentials_missing"), selftestContext())
        .end("blocked", cause = "environment")
    val connection = operations.begin("connection", 30_000, fields("trigger" to "toolwindow"), selftestContext())
    connection.progress("resolve")
    connection.end("success", stage = "streams")
    val attempt = operations.begin("connection.attempt", 30_000, context = selftestContext())
    attempt.progress("health")
    attempt.end("success", stage = "health")
    operations.begin("connection.recovery", 30_000, context = selftestContext()).end(
        "success",
        fields = buildJsonObject { put("intervention", "automatic"); put("attempts", 1) },
    )
    operations.begin("csc.install", 120_000, context = selftestContext())
        .end("success", fields = fields("package_manager" to "npm"))
    operations.begin("csc.start", 30_000, context = selftestContext()).end("success", stage = "health")
    operations.begin("credentials.ready", 60_000, context = selftestContext()).end("success", stage = "probe")
    operations.begin("cli.download", 120_000, context = selftestContext())
        .end("success", stage = "download", fields = buildJsonObject { put("cache_hit", true) })
    operations.begin("session.open", 30_000, fields("session_mode" to "create"), selftestContext()).end("success")
    operations.begin("session.restore", 30_000, fields("session_mode" to "open"), selftestContext())
        .end("success", stage = "history")
    operations.begin("action", 30_000, fields("action" to "prompt_submit"), selftestContext()).end("success")
    operations.begin("rpc", 30_000, fields("api_group" to "session"), selftestContext()).end("success")
    operations.begin("ide.operation", 30_000, fields("operation" to "vfs_refresh"), selftestContext()).end("success")

    // ---- transition ----
    recorder.record(
        Draft(
            "connection.state_changed", "transition", "critical",
            buildJsonObject {
                put("from", "connecting"); put("to", "ready"); put("reason", "streams_open")
                put("streams_open", 3); put("streams_total", 3)
            },
            context = selftestContext(),
        ),
    )
    recorder.record(
        Draft(
            "migration.required", "transition", "critical",
            buildJsonObject { put("migration_kind", "legacy_v5") },
            context = selftestContext(),
        ),
    )
    recorder.record(
        Draft(
            "session.dispose_risk", "transition", "critical",
            buildJsonObject { put("dispose_source", "global_disposed"); put("conversation_active", true) },
            context = selftestContext(),
        ),
    )

    // ---- interval / sample（落盘purposes由Dictionary投影收窄）----
    recorder.record(
        Draft(
            "availability", "interval", "critical",
            buildJsonObject {
                put("state", "ready"); put("begin_timestamp", 0L); put("end_timestamp", 100L); put("duration_ms", 100L)
            },
            context = selftestContext(),
        ),
    )
    recorder.record(
        Draft(
            "edt.delay", "sample", "critical",
            buildJsonObject {
                put("observation_id", "obs-selftest"); put("probe_seq", 1L)
                put("scheduled_mono_ms", 0L); put("completed_mono_ms", 12L)
                put("duration_ms", 12L); put("validity", "valid")
            },
            context = selftestContext(),
        ),
    )
    recorder.record(
        Draft(
            "render.apply", "sample", "critical",
            buildJsonObject {
                put("duration_ms", 34L); put("result", "success"); put("component", "messages")
                put("batch_size_bucket", "1"); put("sample_rate", 1.0)
            },
            context = selftestContext(),
        ),
    )
    // edt.stall经真实StallMerger驱动：同一观测区间内两枚首尾相接样本（seq 1→2，scheduled
    // 与上一窗口end相接于11_800）合并为2.5秒窗口，onObservationEnded终结后达标产出——
    // Draft形状由合并器产出，自检不伪造（该形状不带workspace上下文，与生产一致）。
    val stalls = mutableListOf<Draft>()
    val merger = StallMerger { stalls.add(it) }
    merger.onValidSample("obs-selftest", 1, 10_000, 11_800)
    merger.onValidSample("obs-selftest", 2, 11_800, 12_500)
    merger.onObservationEnded()
    stalls.forEach(recorder::record)

    resources.acquire("subscription").use { }
    resourceSnapshotDrafts(resources.snapshot()).forEach { recorder.record(it) }

    // ---- diagnostic证据形态（critical通道，dual用途）----
    recorder.record(
        Draft(
            "protocol.error", "diagnostic", "critical",
            buildJsonObject { put("transport", "sse"); put("stage", "decode"); put("error_code", "malformed_event") },
            context = selftestContext(),
        ),
    )
    recorder.record(
        Draft(
            "edt.violation", "diagnostic", "critical",
            buildJsonObject { put("operation", "session"); put("evidence", "platform_thread_assertion") },
            context = selftestContext(),
        ),
    )

    // ---- lifecycle：unclean登记形状（生产由UncleanDetector于启动时判定——Task 7已交付；
    //      自检仍以占位previous_run_id直投字典形状）----
    recorder.record(
        Draft(
            "plugin.unclean", "lifecycle", "critical",
            buildJsonObject { put("previous_run_id", "run-prev-selftest"); put("evidence", "writer_lock_recovered") },
            context = selftestContext(),
        ),
    )

    // ---- error族：真实Faults（计数critical/metrics + 详情diagnostic/logs，同一fault_id关联）----
    faults.report(
        IllegalStateException("selftest-handled"),
        component = "frontend",
        handled = true,
        fault = SELFTEST_FAULT_REPORTED,
    )
    faults.report(
        IllegalArgumentException("selftest-uncaught"),
        component = "backend",
        handled = false,
        fault = SELFTEST_FAULT_UNCAUGHT,
    )
}

/**
 * Hidden real-IDE load driver for schema-v2 outbox diagnostics. Network and decode incidents are
 * triggered through their real boundaries by the integration fixture; this hook only creates the
 * in-process saturation, admission-loss, and open-operation states that the daemon cannot induce.
 */
fun emitOutboxScenario(recorder: Recorder, operations: Operations) {
    // Fill the lower-priority quota before racing fresh samples with failure groups. The writer
    // may drain concurrently in the IDE, so the capacity counter—not an iteration guess—is the
    // completion condition.
    val baseline = recorder.health().droppedCapacity
    check((0 until OUTBOX_FILL_LIMIT).any { index ->
        recorder.record(outboxSample(index))
        recorder.health().droppedCapacity > baseline
    }) { "outbox self-test could not fill the sample queue" }

    val gate = CountDownLatch(1)
    val failed = AtomicReference<Throwable?>()
    val workers = listOf(
        thread(start = true, name = "outbox-samples") {
            gate.await()
            repeat(OUTBOX_FAILURES * 10) { recorder.record(outboxSample(it)) }
        },
    ) + (0 until 4).map { worker ->
        thread(start = true, name = "outbox-failures-$worker") {
            gate.await()
            repeat(OUTBOX_FAILURES / 4) { offset ->
                val index = worker * (OUTBOX_FAILURES / 4) + offset
                runCatching {
                    operations.report(DiagnosticInput.error(
                        component = "selftest.load",
                        code = "load_failure_${index.toString().padStart(3, '0')}",
                        message = "Concurrent failure $index",
                        context = mapOf("fault_id" to "load-$index"),
                        payloads = mapOf("response" to { "response-$index" }),
                    ))
                }.onFailure { error -> failed.compareAndSet(null, error) }
            }
        }
    }
    gate.countDown()
    workers.forEach(Thread::join)
    failed.get()?.let { throw it }

    // Deliberately left open. A hard-killed IDE must let the next run recover this ID from the
    // copied JSONL using per-producer/run/channel seq ordering rather than physical line order.
    operations.begin("session.open", OUTBOX_DEADLINE_MS, fields("session_mode" to "create"))
}

/** Force a rejected failure group so the next real health snapshot must report degraded quality. */
fun emitOutboxDegraded(recorder: Recorder): Admission = recorder.record(
    Draft(
        "diagnostic.reported",
        "diagnostic",
        "diagnostic",
        buildJsonObject { put("unexpected", true) },
        schemaVersion = "2.0",
    ),
)

private fun outboxSample(index: Int) = Draft(
    "render.apply",
    "sample",
    "critical",
    buildJsonObject {
        put("duration_ms", index.toLong())
        put("result", "success")
        put("component", "messages")
        put("batch_size_bucket", "1")
        put("sample_rate", 1.0)
    },
    context = mapOf("workspace_id" to SELFTEST_WORKSPACE),
    purposes = setOf("metrics"),
)

private fun fields(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) -> put(key, value) }
}
