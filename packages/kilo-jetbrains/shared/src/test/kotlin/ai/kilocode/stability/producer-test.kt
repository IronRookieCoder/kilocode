package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
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

/** 内存版设备ID存储：loadOrCreate语义与PropertiesComponent一致（首调用生成，之后复用）。 */
private class MemoryDeviceStore(private var value: String? = null) : DeviceIdStore {
    override fun loadOrCreate(): String = value ?: ("device-" + UUID.randomUUID().toString().replace("-", "").take(12)).also { value = it }
}

/** 服务夹具：真实临时目录、真实PolicyStore/Writer/Retention，只注入路径、时钟与运行模式来源。 */
private class Harness(
    val base: Path = Files.createTempDirectory("stability-service"),
    val clock: SweepClock = SweepClock(),
    mode: RunMode = RunMode("monolith", "monolith"),
    deviceStore: DeviceIdStore = MemoryDeviceStore("device-fixed"),
    awaitActiveHook: (Writer) -> Boolean = ::defaultAwaitActive,
    /** 生产默认=1小时（RETENTION_INTERVAL_MS为private，测试以字面值对齐）。 */
    retentionIntervalMs: Long = 3_600_000L,
    retentionMaxBytes: Long = DEFAULT_MAX_BYTES,
) : AutoCloseable {

    val telemetryHome = base.resolve("home").resolve(".costrict").resolve("telemetry")
    val logRoot = base.resolve("log")
    val v1Root = logRoot.resolve("costrict-telemetry").resolve("v1")
    val registrations = telemetryHome.resolve("registrations")
    val controlFile = telemetryHome.resolve("control").resolve("jetbrains.json")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = StabilityService.create(
        scope = scope,
        modeSource = { mode },
        logDirProvider = { logRoot },
        telemetryHome = telemetryHome,
        deviceStore = deviceStore,
        clock = clock,
        pollIntervalMs = SERVICE_POLL_MS,
        awaitActiveHook = awaitActiveHook,
        retentionIntervalMs = retentionIntervalMs,
        retentionMaxBytes = retentionMaxBytes,
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

    /** 激活后本实例唯一producer根目录。 */
    fun producerRoot(): Path = Files.list(v1Root).use { files ->
        val dirs = files.filter { Files.isDirectory(it) }.toList()
        assertEquals(1, dirs.size, "exactly one producer root expected, got $dirs")
        dirs.single()
    }

    fun registrationCount(): Int =
        if (Files.isDirectory(registrations)) Files.list(registrations).use { it.count() }.toInt() else 0

    /** 读producer根下的追加事实文件（完整LF行）还原事实。 */
    fun facts(): List<Fact> {
        val root = producerRoot()
        return listReady(root).flatMap { file ->
            Files.readAllBytes(file).toString(Charsets.UTF_8)
                .lineSequence()
                .filter { line -> line.isNotBlank() }
                .map { line -> FACT_JSON.decodeFromString(Fact.serializer(), line) }
                .toList()
        }
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

    companion object {
        private val FACT_JSON = Json { encodeDefaults = true }
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

private fun producerJsonField(root: Path, field: String): String {
    val json = Json.parseToJsonElement(Files.readString(root.resolve("producer.json"))) as JsonObject
    return json[field]?.jsonPrimitive?.contentOrNull ?: error("missing $field in producer.json")
}

private fun JsonObject.field(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: error("missing $key")

class ProducerTest {

    @Test
    fun `start is idempotent and yields one writer registration and started fact`() {
        Harness().use { harness ->
            harness.writeControl(validControl())
            harness.service.start("frontend")
            harness.service.start("backend")
            val coverage = harness.awaitReason("ok")
            assertEquals("monolith", coverage.mode)
            assertEquals("monolith", coverage.side)
            val root = harness.producerRoot()
            assertTrue(Files.exists(root.resolve("producer.json")), "producer.json is written once active")
            assertTrue(Files.exists(root.resolve("writer.lock")))
            assertTrue(Files.exists(root.resolve("exchange.lock")))
            assertEquals(1, harness.registrationCount(), "exactly one registration per producer")
            harness.service.stop("app_close")
            harness.awaitReason("stopped_app_close")
            assertEquals(1, harness.facts().count { it.name == "plugin.started" })
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
    fun `a second writer on the same root is disabled while the service writer is active`() {
        Harness().use { harness ->
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            val policies = PolicyStore(harness.controlFile, { harness.clock.wall() }, SERVICE_POLL_MS)
            val identity = ProducerIdentity(
                producerId = "pr-second",
                runId = "run-second",
                deviceId = "device-second",
                pluginVersion = "1.0.0",
                ideProduct = "IU",
                ideBuild = "build-test",
                ideBuildMajor = "2026.1",
                osFamily = "windows",
                arch = "x64",
                env = "test",
                mode = "monolith",
                side = "monolith",
                connectionProvider = "unknown",
            )
            val second = Writer(
                root = harness.producerRoot(),
                fileName = "sc-second-${identity.producerId}.jsonl",
                identity = identity,
                recorder = Recorder(identity, policies, harness.clock),
                policies = policies,
                clock = harness.clock,
            )
            try {
                second.start()
                var waited = 0L
                while (second.state == WriterState.CREATED && waited < 15_000) {
                    Thread.sleep(SERVICE_POLL_MS)
                    waited += SERVICE_POLL_MS
                }
                assertEquals(WriterState.DISABLED, second.state, "one writer per JVM root")
                assertTrue(!second.disabledReason.isNullOrEmpty())
            } finally {
                runCatching { second.close() }
                runCatching { policies.close() }
            }
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
            val root = harness.producerRoot()
            firstDevice = producerJsonField(root, "device_id")
            firstProducerId = producerJsonField(root, "producer_id")
        }
        Harness(deviceStore = store).use { harness ->
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")
            val root = harness.producerRoot()
            secondDevice = producerJsonField(root, "device_id")
            secondProducerId = producerJsonField(root, "producer_id")
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
            harness.awaitReason("no_policy")
            assertEquals(0, harness.producerDirCount(), "no producer root without a valid permit")
            assertEquals(0, harness.registrationCount(), "no registration without a valid permit")
            val admission = harness.service.recorder.record(
                Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap())),
            )
            assertEquals(Admission.DISABLED, admission, "records stay disabled while unpermitted")
            harness.writeControl(validControl())
            harness.awaitReason("ok")
            assertTrue(Files.exists(harness.producerRoot().resolve("producer.json")))
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
            // 等首run的started已落盘（writer默认1s tick），再撤销——撤销后未落盘事实按
            // 入盘前重判期丢弃属正确行为，这里只断言重开后出现"新run_id"的started。
            harness.awaitFacts(15_000) { facts -> facts.any { it.name == "plugin.started" } }
            val firstRunId = harness.facts().first { it.name == "plugin.started" }.run_id
            harness.writeControl(disabledControl())
            harness.awaitReason("no_policy")
            assertEquals(0, harness.facts().count { it.name == "plugin.shutdown" }, "revocation never fakes exit")
            harness.writeControl(validControl())
            harness.awaitReason("ok")
            harness.service.stop("unload")
            harness.awaitReason("stopped_unload")
            val starts = harness.facts().filter { it.name == "plugin.started" }
            val runIds = starts.map { it.run_id }.toSet()
            assertTrue(firstRunId in runIds, "first run's started fact survives revocation")
            assertTrue(runIds.size >= 2, "re-enable builds a new run id, got run ids $runIds")
            assertEquals(starts.size, runIds.size, "exactly one started fact per run")
            assertEquals(1, harness.facts().count { it.name == "plugin.shutdown" })
        }
    }

    @Test
    fun `outbox full gate closes admission and reopens after the age seal and eviction`() {
        Harness(retentionMaxBytes = 2 * 1024, retentionIntervalMs = 200).use { harness ->
            harness.writeControl(validControl())
            harness.service.start("monolith")
            harness.awaitReason("ok")

            // 2KiB预算下单一.open段超预算且无.ready可淘汰：sweepOwnSource返回false→
            // outbox_full（状态发布）→storage闸门拒绝新记录（R9，验收记录第135行缺口）。
            repeat(6) {
                assertEquals(Admission.QUEUED, harness.service.recorder.record(startedDraft()))
            }
            harness.awaitStatus(15_000) { it.reason == "outbox_full" }
            assertEquals(
                Admission.DROPPED,
                harness.service.recorder.record(startedDraft()),
                "storage gate must refuse new records while over budget",
            )

            // critical 30s年龄封存把.open转.ready后，下一轮sweep按预算淘汰最旧.ready：
            // 预算恢复→闸门重开→新记录重新入队（closed>full>capacity优先级中的full分支解除）。
            harness.clock.advance(31_000)
            harness.awaitStatus(15_000) { it.reason == "ok" }
            assertEquals(Admission.QUEUED, harness.service.recorder.record(startedDraft()))
            harness.service.stop("unload")
            harness.awaitReason("stopped_unload")
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
            // 先等writer完成启动（根目录/锁文件已就位），再停在"已启动、run未提交"的窗口。
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
            // writer被就地关闭=writer.lock可被第三方获取（同步点兼断言）。
            val root = harness.producerRoot()
            val lock = root.resolve("writer.lock")
            var acquired = false
            var waited = 0L
            while (waited < 15_000 && !acquired) {
                java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                    acquired = channel.tryLock(0, 1, false) != null
                }
                if (!acquired) {
                    Thread.sleep(50)
                    waited += 50
                }
            }
            assertTrue(acquired, "uncommitted writer is closed so the lock is free")
            assertTrue(harness.facts().none { it.name == "plugin.started" }, "no started fact after the stop decision")
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

    private fun Harness.producerDirCount(): Int =
        if (Files.isDirectory(v1Root)) Files.list(v1Root).use { files -> files.filter { Files.isDirectory(it) }.count() }.toInt() else 0
}
