package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SERVICE_POLL_MS = 20L
private const val FAR_EXPIRES = 9_000_000_000_000L

/** 测试/fixture共用的Json实例约定（writer-test同款）：默认值随wire记录一并编码。 */
private val factJson = Json { encodeDefaults = true }

private fun Fact.phase(): String = data.getValue("phase").jsonPrimitive.content
private fun Fact.text(key: String): String = data.getValue(key).jsonPrimitive.content

/**
 * 13字段闭集控制文件（control-schema.json）的最小合法构造（brief Step 1）。
 * expiresAt由调用方传入；本文件统一用[FAR_EXPIRES]（brief示例字面9_999_999_999相对
 * SweepClock的wall基线1.79e12已是过去时刻，会使enabled=true的判期直接过期）。
 */
private fun controlJson(epoch: String, revision: Long, enabled: Boolean, expiresAt: Long): String =
    JsonObject(
        mapOf(
            "schema_major" to JsonPrimitive(1),
            "revision" to JsonPrimitive(revision),
            "enabled" to JsonPrimitive(enabled),
            "metrics_enabled" to JsonPrimitive(enabled),
            "metrics_expires_at" to JsonPrimitive(expiresAt),
            "logs_enabled" to JsonPrimitive(enabled),
            "logs_expires_at" to JsonPrimitive(expiresAt),
            "account_epoch" to JsonPrimitive(epoch),
            "account_state" to JsonPrimitive("ready"),
            "expires_at" to JsonPrimitive(expiresAt),
            "metrics_allowed_categories" to JsonArray(listOf(JsonPrimitive("critical"), JsonPrimitive("diagnostic"))),
            "logs_allowed_categories" to JsonArray(listOf(JsonPrimitive("critical"), JsonPrimitive("diagnostic"))),
            "log_detail_rate_limit" to JsonObject(mapOf("per_fingerprint_max_per_minute" to JsonPrimitive(3))),
        ),
    ).toString()

/** 列出目录下平铺的*.jsonl事实文件（service级断言用；不排序——本文件只数个数/取单个）。 */
private fun listJsonl(dir: Path): List<Path> =
    if (!Files.isDirectory(dir)) emptyList()
    else Files.list(dir).use { it.filter { p -> p.fileName.toString().endsWith(".jsonl") }.toList() }

private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(50)
    assertTrue(condition(), "condition not met within ${timeoutMs}ms")
}

/** 内存版设备ID存储：loadOrCreate语义与PropertiesComponent一致（首调用生成，之后复用）。 */
private class MemoryDeviceStore(private var value: String? = null) : DeviceIdStore {
    override fun loadOrCreate(): String = value ?: ("device-" + UUID.randomUUID().toString().replace("-", "").take(12)).also { value = it }
}

/** 服务夹具：真实临时目录、真实PolicyStore/Writer/Retention，只注入路径、时钟、scope-id与运行模式来源。 */
private class Harness(
    val base: Path = Files.createTempDirectory("stability-service"),
    val clock: SweepClock = SweepClock(),
    mode: RunMode = RunMode("monolith", "monolith"),
    deviceStore: DeviceIdStore = MemoryDeviceStore("device-fixed"),
    awaitActiveHook: (Writer) -> Boolean = ::defaultAwaitActive,
) : AutoCloseable {

    val telemetryHome = base.resolve("home").resolve(".costrict").resolve("telemetry")
    val controlFile = telemetryHome.resolve("control").resolve("jetbrains.json")
    val outbox = telemetryHome.resolve("outbox")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = StabilityService.create(
        scope = scope,
        modeSource = { mode },
        scopeStore = ScopeIdStore { "sc-fixed" },
        telemetryHome = telemetryHome,
        deviceStore = deviceStore,
        clock = clock,
        pollIntervalMs = SERVICE_POLL_MS,
        awaitActiveHook = awaitActiveHook,
    )

    fun writeControl(json: String) {
        Files.createDirectories(controlFile.parent)
        Files.writeString(controlFile, json)
    }

    fun awaitReason(reason: String): Coverage = awaitStatus(15_000) { it.reason == reason }

    fun awaitStatus(timeoutMs: Long, predicate: (Coverage) -> Boolean): Coverage {
        var waited = 0L
        while (waited <= timeoutMs) {
            val coverage = service.status.value
            if (predicate(coverage)) return coverage
            Thread.sleep(SERVICE_POLL_MS)
            waited += SERVICE_POLL_MS
        }
        error("timed out waiting for status; last=${service.status.value}")
    }

    /** 读outbox下的平铺追加事实文件（完整LF行）还原事实；撤销/停机清理后为空。 */
    fun facts(): List<Fact> =
        listReady(outbox).flatMap { file ->
            Files.readAllBytes(file).toString(Charsets.UTF_8)
                .lineSequence()
                .filter { line -> line.isNotBlank() }
                .map { line -> factJson.decodeFromString(Fact.serializer(), line) }
                .toList()
        }

    /** 等待facts满足条件（writer默认1s tick后才落盘）。 */
    fun awaitFacts(timeoutMs: Long, predicate: (List<Fact>) -> Boolean) {
        var waited = 0L
        while (waited <= timeoutMs) {
            val snapshot = runCatching { facts() }.getOrDefault(emptyList())
            if (predicate(snapshot)) return
            Thread.sleep(100)
            waited += 100
        }
        error("timed out waiting for facts")
    }

    override fun close() {
        runCatching { service.stop("unload") }
        runCatching { awaitStatus(15_000) { it.reason.startsWith("stopped") } }
        runCatching { scope.cancel() }
        runCatching { Thread.sleep(50) }
        runCatching { base.toFile().deleteRecursively() }
    }
}

private fun control(enabled: Boolean, metrics: Boolean, logs: Boolean): String = buildJsonObject {
    put("schema_major", 1)
    put("revision", 12L)
    put("enabled", enabled)
    put("metrics_enabled", metrics)
    put("metrics_expires_at", FAR_EXPIRES)
    put("metrics_allowed_categories", categories(metrics))
    put("logs_enabled", logs)
    put("logs_expires_at", FAR_EXPIRES)
    put("logs_allowed_categories", categories(logs))
    put("account_epoch", "acct-a")
    put("account_state", if (enabled) "ready" else "disabled")
    put("expires_at", FAR_EXPIRES)
    put("log_detail_rate_limit", buildJsonObject { put("per_fingerprint_max_per_minute", 3) })
}.toString()

private fun categories(on: Boolean) = JsonArray(
    if (on) listOf(JsonPrimitive("critical"), JsonPrimitive("diagnostic")) else emptyList(),
)

private fun validControl(): String = control(enabled = true, metrics = true, logs = true)
private fun metricsOnlyControl(): String = control(enabled = true, metrics = true, logs = false)
private fun disabledControl(): String = control(enabled = false, metrics = false, logs = false)

private fun startedDraft(): Draft =
    Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap()))

private fun JsonObject.field(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: error("missing $key")

class ProducerTest {

    @Test
    fun `outbox holds one scope-producer jsonl and no registrations`() {
        val home = Files.createTempDirectory("service-outbox")
        val logDir = Files.createTempDirectory("service-logdir")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = StabilityService.create(
            scope = scope,
            modeSource = { RunMode("monolith", "monolith") },
            scopeStore = ScopeIdStore { "sc-fixed" },
            telemetryHome = home,
            deviceStore = DeviceIdStore { "device-fixed" },
            clock = SweepClock(),
            pollIntervalMs = 50L,
        )
        try {
            service.start("frontend") // 无控制文件：fail-open应建立run
            val outbox = home.resolve("outbox")
            awaitUntil(10_000) { listJsonl(outbox).isNotEmpty() }
            service.stop("app_close")
            // stop的收尾排空经后台协程完成：等started已落盘再读断言（有界，不依赖实现细节时序）。
            awaitUntil(10_000) {
                listJsonl(outbox).any { file -> Files.readAllLines(file).any { it.contains("\"plugin.started\"") } }
            }
            val files = listJsonl(outbox)
            assertEquals(1, files.size)
            assertTrue(
                files[0].fileName.toString().matches(Regex("^sc-fixed-pr-[a-z0-9]+\\.jsonl$")),
                files[0].toString(),
            )
            assertFalse(Files.exists(home.resolve("registrations")))
            assertFalse(Files.exists(logDir.resolve("costrict-telemetry")))

            val facts = Files.readAllLines(files[0]).filter { it.isNotBlank() }
                .map { factJson.decodeFromString(Fact.serializer(), it) }
            assertEquals("plugin.started", facts.first().name)
            assertEquals("unbound", facts.first().account_epoch)
            assertEquals(0L, facts.first().policy_revision)
            assertEquals(setOf("metrics", "logs"), facts.first().purposes)
            // 无策略fail-open采集中：占位策略purposes双开，Coverage两用途为true（reason仅文案unbound）。
            assertTrue(service.status.value.metrics && service.status.value.logs, "unbound run reports both purposes enabled")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `revocation deletes the pending handover file without faking shutdown`() {
        val home = Files.createTempDirectory("service-revoke")
        val control = home.resolve("control").resolve("jetbrains.json")
        Files.createDirectories(control.parent)
        Files.writeString(control, controlJson(epoch = "acct-r1", revision = 1L, enabled = true, expiresAt = FAR_EXPIRES))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = StabilityService.create(
            scope, { RunMode("monolith", "monolith") }, ScopeIdStore { "sc-fixed" },
            home, DeviceIdStore { "device-fixed" }, SweepClock(), pollIntervalMs = 50L,
        )
        try {
            service.start("frontend")
            val outbox = home.resolve("outbox")
            // 等到started确实已写入文件（文件在writer ACTIVE即创建，存在≠已有事实）。
            awaitUntil(10_000) {
                listJsonl(outbox).singleOrNull()?.let { file ->
                    Files.readAllLines(file).any { it.contains("\"plugin.started\"") }
                } == true
            }
            val file = listJsonl(outbox).single()
            val before = Files.readAllLines(file)
            assertTrue(before.any { it.contains("\"plugin.started\"") })

            // 撤销：显式enabled=false（§8 停采并清理待交接数据，不保留补报）
            Files.writeString(control, controlJson(epoch = "acct-r1", revision = 2L, enabled = false, expiresAt = FAR_EXPIRES))
            awaitUntil(70_000) { listJsonl(outbox).isEmpty() } // 50ms轮询 + 收尾预算
            assertTrue(listJsonl(outbox).isEmpty(), "revoked handover data must be cleaned")
            // 撤销不伪造shutdown：被删文件内不含plugin.shutdown（end_kind闭集只有app_close/unload）
            assertTrue(before.none { it.contains("\"plugin.shutdown\"") })
        } finally {
            service.stop("app_close")
            scope.cancel()
        }
    }

    @Test
    fun `coverage reflects platform mode side profile and per purpose permits`() {
        Harness(mode = RunMode("split", "frontend")).use { harness ->
            harness.writeControl(validControl())
            harness.service.start("frontend")
            val ok = harness.awaitReason("ok")
            assertEquals("split", ok.mode)
            assertEquals("frontend", ok.side)
            assertEquals("default", ok.profile)
            assertTrue(ok.metrics && ok.logs, "both purposes permitted by the valid control")
            harness.writeControl(metricsOnlyControl())
            val narrowed = harness.awaitStatus(15_000) { it.metrics && !it.logs }
            assertEquals("ok", narrowed.reason, "single purpose close never fakes process exit")
        }
    }

    @Test
    fun `device id persists across producer instances while run and producer ids rotate`() {
        val store = MemoryDeviceStore()
        val firstDevice: String
        val secondDevice: String
        val firstProducerId: String
        val secondProducerId: String
        Harness(deviceStore = store).use { harness ->
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            // 追加协议无producer.json：身份改从落盘事实的公共字段读取（同快照来源）。
            harness.awaitFacts(15_000) { facts -> facts.isNotEmpty() }
            firstDevice = harness.facts().first().device_id
            firstProducerId = harness.facts().first().producer_id
        }
        Harness(deviceStore = store).use { harness ->
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            harness.awaitFacts(15_000) { facts -> facts.isNotEmpty() }
            secondDevice = harness.facts().first().device_id
            secondProducerId = harness.facts().first().producer_id
        }
        assertEquals(firstDevice, secondDevice, "device id is the persistent installation identity")
        assertTrue(firstProducerId != secondProducerId, "each instance gets a fresh producer id")
    }

    @Test
    fun `stop is cas deduplicated and records one shutdown via the closing writer`() {
        Harness().use { harness ->
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            harness.service.stop("app_close")
            harness.service.stop("app_close")
            harness.service.stop("unload")
            harness.awaitReason("stopped_app_close")
            val facts = harness.facts()
            assertEquals(1, facts.count { it.name == "plugin.shutdown" }, "stop is deduplicated to one shutdown fact")
            assertEquals("app_close", facts.last { it.name == "plugin.shutdown" }.data.field("end_kind"))
            val late = harness.service.recorder.record(
                Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap())),
            )
            assertEquals(Admission.DISABLED, late, "admission stays closed after stop")
        }
    }

    @Test
    fun `collection stays off without permit and activates on the first permit`() {
        // 显式撤销必须先于服务构造落盘（设计§8 fail open：无有效策略时默认不限制采集，
        // 预热若先读到"文件尚不存在"，会先按占位策略建run，随后轮询才读到撤销）。
        // 本用例覆盖的是"自首个观察点起就是显式撤销"，因此控制文件必须先写。
        val base = Files.createTempDirectory("stability-service")
        val controlDir = base.resolve("home").resolve(".costrict").resolve("telemetry").resolve("control")
        Files.createDirectories(controlDir)
        Files.writeString(controlDir.resolve("jetbrains.json"), disabledControl())
        Harness(base = base).use { harness ->
            harness.service.start("monolith")
            harness.awaitReason("unbound")
            assertTrue(harness.facts().isEmpty(), "no facts without a valid permit")
            assertTrue(harness.outboxFiles().isEmpty(), "no outbox file without a valid permit")
            val admission = harness.service.recorder.record(startedDraft())
            assertEquals(Admission.DISABLED, admission, "records stay disabled while unpermitted")
            harness.writeControl(validControl())
            harness.awaitReason("ok")
            assertTrue(harness.outboxFiles().isNotEmpty(), "the active run owns its outbox jsonl")
            harness.service.stop("unload")
            harness.awaitReason("stopped_unload")
            assertTrue(harness.facts().any { it.name == "plugin.started" }, "first permit starts the run")
        }
    }

    @Test
    fun `revocation ends the run without faking shutdown and re-enable starts a new run`() {
        Harness().use { harness ->
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            // 等首run的started已落盘（writer默认1s tick）。
            harness.awaitFacts(15_000) { facts -> facts.any { it.name == "plugin.started" } }
            val firstRunId = harness.facts().first { it.name == "plugin.started" }.run_id
            harness.writeControl(disabledControl())
            harness.awaitReason("unbound")
            // §8：撤销清理本实例待交接文件（首轮事实不保留补报），也绝不伪造shutdown。
            assertTrue(harness.facts().isEmpty(), "revocation cleans the pending handover file")
            assertEquals(0, harness.facts().count { it.name == "plugin.shutdown" }, "revocation never fakes exit")
            harness.writeControl(validControl())
            harness.awaitReason("ok")
            harness.service.stop("unload")
            harness.awaitReason("stopped_unload")
            val starts = harness.facts().filter { it.name == "plugin.started" }
            val runIds = starts.map { it.run_id }.toSet()
            assertTrue(firstRunId !in runIds, "the revoked run's facts were cleaned with its handover file")
            assertTrue(runIds.size >= 1 && starts.size == runIds.size, "exactly one started fact per surviving run")
            assertEquals(1, harness.facts().count { it.name == "plugin.shutdown" })
        }
    }

    @Test
    fun `standby captured before activation delivers facts to the active run`() {
        Harness().use { harness ->
            // 模拟EDT先于activateRun提交的长生命周期消费者：激活前捕获引用（F1）。
            val capturedOperations = harness.service.operations
            val capturedRecorder = harness.service.recorder
            val capturedFaults = harness.service.faults
            // 任何策略之前：fail open（设计§8）——unbound占位策略默认不限制采集，早捕获的
            // standby自身队列接收该事实（standby无writer，事实留在队列不落盘）；显式撤销
            // 才会DISABLED。激活后新事实经forwardTo直投活跃run，不再进入standby队列。
            assertEquals(
                Admission.QUEUED,
                capturedRecorder.record(Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap()))),
                "unbound placeholder admits collection before any policy",
            )
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            // 激活后经同一早捕获引用记录：必须落进活跃run（而非standby黑洞队列）。
            capturedOperations.begin("plugin.readiness", 60_000)
            assertEquals(
                Admission.QUEUED,
                capturedRecorder.record(Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap()))),
                "early-captured reference queues into the active run after activation",
            )
            capturedFaults.report(IllegalStateException("captured standby"), "frontend", handled = true, fault = "f-cap")
            assertEquals(
                1,
                capturedRecorder.depth().items,
                "standby queue holds only the pre-activation fact; post-activation facts are forwarded",
            )
            harness.service.stop("unload")
            harness.awaitReason("stopped_unload")
            val facts = harness.facts()
            val runId = facts.first { it.name == "plugin.started" }.run_id
            assertTrue(facts.any { it.name == "plugin.readiness" }, "captured operations reach the active run")
            assertTrue(facts.any { it.name == "error.reported" }, "captured faults reach the active run")
            assertTrue(
                facts.all { it.run_id == runId },
                "every fact lands in the active run, got ${facts.map { it.run_id }.toSet()}",
            )
        }
    }

    @Test
    fun `provider noted after prewarm is captured by the first run`() {
        Harness().use { harness ->
            val operations = harness.service.operations
            harness.service.noteConnectionProvider("cs-cloud")
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            operations.begin("plugin.readiness", 60_000)
            harness.awaitFacts(15_000) { facts -> facts.any { it.name == "plugin.readiness" } }

            val facts = harness.facts()
            assertEquals("cs-cloud", facts.first { it.name == "plugin.started" }.connection_provider)
            assertEquals("cs-cloud", facts.first { it.name == "plugin.readiness" }.connection_provider)
        }
    }

    @Test
    fun `provider is normalized and frozen for an active run`() {
        Harness().use { harness ->
            val operations = harness.service.operations
            harness.service.noteConnectionProvider("other")
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            operations.begin("plugin.readiness", 60_000)
            harness.awaitFacts(15_000) { facts -> facts.any { it.name == "plugin.readiness" } }

            harness.service.noteConnectionProvider("kilo-cli")
            operations.begin("plugin.readiness", 60_000)
            harness.awaitFacts(15_000) { facts -> facts.count { it.name == "plugin.readiness" } >= 2 }
            assertTrue(harness.facts().filter { it.name != "plugin.shutdown" }.all { it.connection_provider == "unknown" })

            harness.writeControl(disabledControl())
            harness.awaitReason("unbound")
            harness.writeControl(validControl())
            harness.awaitReason("ok")
            harness.service.stop("unload")
            harness.awaitReason("stopped_unload")
            assertEquals("kilo-cli", harness.facts().first { it.name == "plugin.started" }.connection_provider)
        }
    }

    @Test
    fun `standby captured rpc timeout reaches the active run`() {
        Harness().use { harness ->
            val operations = harness.service.operations
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")

            val operation = operations.begin(
                "rpc",
                100,
                buildJsonObject { put("api_group", "other") },
            )
            harness.awaitFacts(15_000) { facts ->
                facts.any { it.context[CONTEXT_OPERATION_ID] == operation.id && it.phase() == "end" }
            }

            val facts = harness.facts()
            val rpc = facts.filter { it.context[CONTEXT_OPERATION_ID] == operation.id }
            val run = facts.first { it.name == "plugin.started" }.run_id
            assertEquals(listOf("start", "end"), rpc.map { it.phase() })
            assertEquals("timeout", rpc.last().text("result"))
            assertTrue(rpc.all { it.run_id == run }, "captured rpc terminal reaches the active run")
        }
    }

    @Test
    fun `core prewarm runs in the background before any getter touches the standby`() {
        Harness().use { harness ->
            harness.writeControl(validControl())
            // 从不触任何getter：控制文件读取与轮询线程只能来自构造即启动的后台预热（F2）。
            var polled = false
            var waited = 0L
            while (waited < 15_000 && !polled) {
                polled = Thread.getAllStackTraces().keys.any { it.name == "kilo-stability-policy-poll" }
                if (!polled) {
                    Thread.sleep(SERVICE_POLL_MS)
                    waited += SERVICE_POLL_MS
                }
            }
            assertTrue(polled, "PolicyStore was prewarmed off the first-touch path without any getter call")
            // 预热不妨碍既有语义：start后照常激活。
            harness.service.start("monolith")
            harness.awaitReason("ok")
            harness.awaitFacts(15_000) { facts -> facts.count { it.name == "plugin.started" } == 1 }
            assertEquals(1, harness.facts().count { it.name == "plugin.started" })
        }
    }

    @Test
    fun `workspace ids are random per project lifecycle and never derived from paths`() {
        val ids = WorkspaceIds()
        val first = ids.idFor("project-a")
        val other = ids.idFor("project-b")
        assertEquals(first, ids.idFor("project-a"), "stable within the project lifecycle")
        assertTrue(first != other, "distinct projects map to distinct random ids")
        ids.forget("project-a")
        assertTrue(first != ids.idFor("project-a"), "after the lifecycle ends a fresh random id is drawn")
    }

    @Test
    fun `scope id persists across store instances and matches the file name pattern`() {
        val store = platformScopeIdStore() // 纯JVM环境：PropertiesComponent不可得时退化随机值
        val scopeId = store.loadOrCreate()
        assertTrue(scopeId.startsWith("sc-"), scopeId)
        assertTrue(Regex("^[a-z0-9][a-z0-9-]*$").matches(scopeId), scopeId)
        assertEquals(scopeId, store.loadOrCreate())
    }

    @Test
    fun `stop landing inside activation aborts the run without a started fact`() {
        val enteredActivation = java.util.concurrent.CountDownLatch(1)
        val releaseActivation = java.util.concurrent.CountDownLatch(1)
        val hook = { writer: Writer ->
            // 先等writer完成启动（outbox事实文件已创建），再停在"已启动、run未提交"的窗口。
            val active = defaultAwaitActive(writer)
            enteredActivation.countDown()
            releaseActivation.await(30, java.util.concurrent.TimeUnit.SECONDS)
            active
        }
        Harness(awaitActiveHook = hook).use { harness ->
            harness.writeControl(validControl())
            harness.service.start("monolith")
            assertTrue(enteredActivation.await(15, java.util.concurrent.TimeUnit.SECONDS), "hook entered the window")
            // 竞态窗口内裁决stop：run尚未提交（runActive=false），stop协程只发stopped状态。
            harness.service.stop("app_close")
            harness.awaitReason("stopped_app_close")
            // 放行activateRun：等待返回后必须复查stoppedOnce并放弃提交。
            releaseActivation.countDown()
            assertTrue(harness.facts().none { it.name == "plugin.started" }, "no started fact after the stop decision")
            // 未提交的writer被就地关闭：追加协议无锁文件（§3.1），以事实文件句柄释放为证——
            // Windows上句柄未释放时删除必败（旧协议以writer.lock可被第三方获取为同一断言）。
            val file = harness.outboxFiles().single()
            awaitUntil(15_000) {
                runCatching { Files.deleteIfExists(file); !Files.exists(file) }.getOrDefault(false)
            }
            assertTrue(
                harness.service.status.value.reason.startsWith("stopped"),
                "status stays stopped, got ${harness.service.status.value.reason}",
            )
            val admission = harness.service.recorder.record(
                Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap())),
            )
            assertEquals(Admission.DISABLED, admission, "admission stays closed after the aborted run")
        }
    }

    private fun Harness.outboxFiles(): List<Path> = listJsonl(outbox)
}
