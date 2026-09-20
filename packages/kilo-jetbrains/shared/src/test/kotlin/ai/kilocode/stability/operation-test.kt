package ai.kilocode.stability

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * 唯一操作终态（任务A3，设计6.2/7.1/11.2）。
 *
 * timeout是唯一终态但不取消业务；deadline定时器与业务Job不共用取消路径；
 * 跨账户切换保留开始时的epoch；start时用途只能缩小不得加回；
 * end的fields不得覆盖phase/result/duration_ms/epoch等公共字段。
 * 除verbatim终态测试外全部使用真实Recorder与真实控制文件。
 */
class OperationTest {

    private val tempDirs = mutableListOf<Path>()
    private val stores = mutableListOf<PolicyStore>()

    @AfterTest
    fun tearDown() {
        stores.forEach { store -> store.close() }
        stores.clear()
        tempDirs.forEach { dir -> dir.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    // ---------- Step 1 verbatim：终态核心 ----------

    @Test
    fun `timeout is terminal but does not cancel business`() = runTest {
        val results = mutableListOf<String>()
        val operation = Terminal(0, 30_000) { result, _ -> results += result }
        assertTrue(operation.finish("timeout", 30_000))
        assertFalse(operation.finish("success", 30_001))
        assertEquals(listOf("timeout"), results)
        val business = async { "completed" }
        assertEquals("completed", business.await())
    }

    @Test
    fun `late completion after the deadline settles as timeout with the deadline duration`() = runTest {
        val results = mutableListOf<Pair<String, Long>>()
        val operation = Terminal(1_000, 30_000) { result, duration -> results += result to duration }
        assertTrue(operation.finish("success", 31_500))
        assertEquals(listOf("timeout" to 30_000L), results)
    }

    @Test
    fun `elapsed before the start never produces a negative duration`() {
        val results = mutableListOf<Pair<String, Long>>()
        val operation = Terminal(5_000, 1_000) { result, duration -> results += result to duration }
        assertTrue(operation.finish("success", 4_999))
        assertEquals(listOf("success" to 0L), results)
    }

    @Test
    fun `a failed emit still consumes the single terminal state`() {
        val operation = Terminal(0, 1) { _, _ -> throw IllegalStateException("boom") }
        assertFailsWith<IllegalStateException> { operation.finish("success", 1) }
        assertFalse(operation.finish("success", 2))
    }

    // ---------- 真实Recorder：start/end、公共字段、上下文 ----------

    @Test
    fun `start and end carry frozen public fields and the operation context`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin(
            "action",
            30_000,
            fields = buildJsonObject { put("action", "prompt_submit") },
            context = mapOf("workspace_id" to "ws-a3f0"),
        )

        assertTrue(operation.end("success", stage = "rpc", cause = "cs_cloud", code = "deadline_exceeded"))

        val facts = fixture.claimAll()
        assertEquals(2, facts.size)
        val start = facts[0]
        assertEquals("action", start.name)
        assertEquals("operation", start.kind)
        assertEquals("critical", start.channel)
        assertEquals("start", start.phase())
        assertEquals(30_000L, start.data.getValue("deadline_ms").jsonPrimitive.long)
        assertEquals("prompt_submit", start.text("action"))
        assertEquals("ws-a3f0", start.context["workspace_id"])
        assertEquals(operation.id, start.context["operation_id"])
        assertEquals("acct-a", start.account_epoch)
        assertEquals(12L, start.policy_revision)
        assertEquals(setOf("metrics", "logs"), start.purposes)
        assertEquals("jetbrains-plugin", start.source)
        assertEquals("run-a3", start.run_id)
        assertEquals("pr-a3", start.producer_id)

        val end = facts[1]
        assertEquals("end", end.phase())
        assertEquals("success", end.text("result"))
        assertEquals("rpc", end.text("stage"))
        assertEquals("cs_cloud", end.text("cause"))
        assertEquals("deadline_exceeded", end.text("error_code"))
        assertEquals("prompt_submit", end.text("action"))
        assertTrue(end.data.getValue("duration_ms").jsonPrimitive.long >= 0)
        assertEquals(operation.id, end.context["operation_id"])
        assertTrue(end.seq > start.seq)
        assertNotEquals(start.event_id, end.event_id)
    }

    @Test
    fun `second end is refused and emits nothing`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("backend.load", 30_000)

        assertTrue(operation.end("success"))
        assertFalse(operation.end("failure"))

        val facts = fixture.claimAll()
        assertEquals(2, facts.size)
        assertEquals(1, facts.count { it.phase() == "end" })
        assertEquals("success", facts.last().text("result"))
    }

    // ---------- deadline定时器与业务Job不共用取消路径 ----------

    @Test
    fun `deadline timer settles timeout even when the business job is cancelled`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        var started: Operation? = null
        val begun = CompletableDeferred<Unit>()
        val business = launch {
            started = operations.begin("backend.load", 5_000)
            begun.complete(Unit)
            awaitCancellation()
        }
        begun.await()
        business.cancel()
        advanceTimeBy(5_000)
        runCurrent()

        val facts = fixture.claimAll()
        assertEquals(2, facts.size)
        assertEquals("start", facts[0].phase())
        assertEquals("end", facts[1].phase())
        assertEquals("timeout", facts[1].text("result"))
        assertEquals(5_000L, facts[1].data.getValue("duration_ms").jsonPrimitive.long)
        assertEquals("unknown", facts[1].text("stage"))
        assertNotNull(started)
    }

    @Test
    fun `business end cancels the deadline timer so no timeout record follows`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("backend.load", 5_000)

        assertTrue(operation.end("success"))
        advanceTimeBy(60_000)
        runCurrent()

        val facts = fixture.claimAll()
        assertEquals(2, facts.size)
        assertEquals("success", facts[1].text("result"))
    }

    @Test
    fun `deadline timer keeps the business coroutine alive`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        operations.begin("backend.load", 1_000)
        advanceTimeBy(1_000)
        runCurrent()

        val business = async {
            delay(2_000)
            "completed"
        }
        advanceTimeBy(2_000)
        assertEquals("completed", business.await())
        assertEquals(2, fixture.claimAll().size)
    }

    // ---------- epoch与用途快照 ----------

    @Test
    fun `operation keeps the epoch it started with across an account switch`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("backend.load", 30_000)

        fixture.replaceControl(controlJson(epoch = "acct-b"))
        fixture.store.refresh()
        assertTrue(operation.end("success"))

        val facts = fixture.claimAll()
        assertEquals(2, facts.size)
        facts.forEach { fact -> assertEquals("acct-a", fact.account_epoch) }
        assertEquals(setOf("metrics", "logs"), facts[1].purposes)
    }

    @Test
    fun `purposes snapshotted at begin cannot regain a disabled purpose`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock, controlJson(logsEnabled = false))
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("backend.load", 30_000)

        fixture.replaceControl(controlJson())
        fixture.store.refresh()
        assertTrue(operation.end("success"))

        val facts = fixture.claimAll()
        assertEquals(2, facts.size)
        facts.forEach { fact -> assertEquals(setOf("metrics"), fact.purposes) }
    }

    // ---------- progress与fields白名单 ----------

    @Test
    fun `progress records the stage but never settles the operation`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("credentials.ready", 30_000)

        operation.progress("probe")
        assertTrue(operation.end("success", stage = "wait", cause = "cs_cloud"))
        operation.progress("wait")

        val facts = fixture.claimAll()
        assertEquals(listOf("start", "progress", "end"), facts.map { it.phase() })
        assertEquals("probe", facts[1].text("stage"))
        assertEquals("success", facts[2].text("result"))
    }

    @Test
    fun `end fields may not override phase result duration or epoch`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("backend.load", 30_000)

        val settled = operation.end(
            "success",
            fields = buildJsonObject {
                put("result", "failure")
                put("duration_ms", 999_999)
                put("epoch", "forged")
            },
        )
        assertTrue(settled)
        assertEquals(1, fixture.claimAll().size)
        assertEquals(1L, fixture.recorder.health().rejectedOverride)

        val second = operations.begin("backend.load", 30_000)
        assertTrue(second.end("success", fields = buildJsonObject { put("phase", "progress") }))
        assertEquals(1, fixture.claimAll().count { it.phase() == "start" })
        assertEquals(2L, fixture.recorder.health().rejectedOverride)
    }

    @Test
    fun `begin fields may not override phase or deadline`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)

        val operation = operations.begin("backend.load", 30_000, fields = buildJsonObject { put("deadline_ms", 1) })
        assertEquals(0, fixture.claimAll().size)
        assertEquals(1L, fixture.recorder.health().rejectedOverride)

        assertTrue(operation.end("success"))
        assertEquals(1, fixture.claimAll().size)
    }

    @Test
    fun `failed policy admission never blocks or throws the operation`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock, controlJson(enabled = false))
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("credentials.ready", 30_000)
        operation.progress("probe")
        assertTrue(operation.end("success"))
        assertEquals(0, fixture.claimAll().size)
        assertEquals(3L, fixture.recorder.health().disabledPolicy)
    }

    @Test
    fun `unregistered operation name still runs to a terminal state`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("not.in.dictionary", 30_000)
        operation.progress("probe")
        assertTrue(operation.end("success"))
        assertEquals(0, fixture.claimAll().size)
        assertEquals(3L, fixture.recorder.health().droppedInvalid)
    }

    @Test
    fun `shutdown stops operation admission deterministically`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val operations = Operations(fixture.recorder, clock, backgroundScope)
        val operation = operations.begin("backend.load", 30_000)

        fixture.recorder.close()
        assertTrue(operation.end("success"))

        assertEquals(1, fixture.claimAll().size)
        assertEquals(1L, fixture.recorder.health().disabledShutdown)
    }

    @Test
    fun `cancelled scope never fakes a timeout or shutdown record`() = runTest {
        val clock = schedulerClock(testScheduler)
        val fixture = newFixture(clock)
        val scope = CoroutineScope(coroutineContext + Job())
        val operations = Operations(fixture.recorder, clock, scope)
        val operation = operations.begin("backend.load", 1_000)

        scope.cancel()
        assertTrue(operation.end("success"))
        advanceTimeBy(60_000)
        runCurrent()

        val facts = fixture.claimAll()
        assertEquals(listOf("start", "end"), facts.map { it.phase() })
        assertEquals("success", facts[1].text("result"))
    }

    // ---------- 夹具 ----------

    private fun schedulerClock(scheduler: TestCoroutineScheduler): Clock =
        object : Clock {
            override fun wall(): Long = 1_790_000_000_000L + scheduler.currentTime
            override fun mono(): Long = scheduler.currentTime
        }

    private fun newFixture(clock: Clock, control: String = controlJson()): StabilityFixture {
        val dir = Files.createTempDirectory("stability-operation").also { tempDirs.add(it) }
        val file = dir.resolve("jetbrains.json").apply { writeText(control) }
        val store = PolicyStore(file, { clock.wall() }).also { stores.add(it) }
        val recorder = Recorder(PRODUCER_IDENTITY, store, clock)
        return StabilityFixture(recorder, store, file)
    }

    private inner class StabilityFixture(
        val recorder: Recorder,
        val store: PolicyStore,
        private val file: Path,
    ) {
        fun claimAll(): List<Fact> {
            val facts = mutableListOf<Fact>()
            while (true) {
                val claim = recorder.tryClaim(MAX_CLAIM_ITEMS, MAX_CLAIM_BYTES) ?: break
                facts += claim.records.map { record -> record.fact }
                val claimed = claim.records.size
                claim.release()
                if (claimed < MAX_CLAIM_ITEMS) break
            }
            return facts
        }

        fun replaceControl(text: String) {
            val temp = file.resolveSibling("jetbrains.json.tmp")
            temp.writeText(text)
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        private val PRODUCER_IDENTITY = ProducerIdentity(
            producerId = "pr-a3",
            runId = "run-a3",
            deviceId = "device-a3",
            pluginVersion = "1.0.0",
            ideProduct = "IU",
            ideBuild = "build-a3",
            ideBuildMajor = "2026.1",
            osFamily = "windows",
            arch = "x64",
            env = "test",
            mode = "split",
            side = "frontend",
            connectionProvider = "cs-cloud",
        )

        private const val MAX_CLAIM_ITEMS = 64
        private const val MAX_CLAIM_BYTES = 4 * 1024 * 1024

        /** 真实wire形状（control-schema.json字段闭集）；null表示该字段整体缺失。 */
        fun controlJson(
            major: Int = 1,
            revision: Long = 12,
            enabled: Boolean = true,
            metricsEnabled: Boolean? = true,
            metricsExpires: Long? = 9_000_000_000_000,
            metricsCategories: List<String>? = listOf("critical", "diagnostic"),
            logsEnabled: Boolean? = true,
            logsExpires: Long? = 9_000_000_000_000,
            logsCategories: List<String>? = listOf("critical", "diagnostic"),
            epoch: String = "acct-a",
            state: String = "ready",
            expires: Long = 9_000_000_000_000,
            rateLimit: Int? = 3,
        ): String = buildJsonObject {
            put("schema_major", major)
            put("revision", revision)
            put("enabled", enabled)
            if (metricsEnabled != null) put("metrics_enabled", metricsEnabled)
            if (metricsExpires != null) put("metrics_expires_at", metricsExpires)
            if (metricsCategories != null) {
                put("metrics_allowed_categories", JsonArray(metricsCategories.map { JsonPrimitive(it) }))
            }
            if (logsEnabled != null) put("logs_enabled", logsEnabled)
            if (logsExpires != null) put("logs_expires_at", logsExpires)
            if (logsCategories != null) {
                put("logs_allowed_categories", JsonArray(logsCategories.map { JsonPrimitive(it) }))
            }
            put("account_epoch", epoch)
            put("account_state", state)
            put("expires_at", expires)
            if (rateLimit != null) {
                put("log_detail_rate_limit", buildJsonObject { put("per_fingerprint_max_per_minute", rateLimit) })
            }
        }.toString()
    }
}

/** data字段安全读取的测试小助手：键缺失时让测试明确失败。 */
private fun Fact.phase(): String = text("phase")
private fun Fact.text(key: String): String = data.getValue(key).jsonPrimitive.content
