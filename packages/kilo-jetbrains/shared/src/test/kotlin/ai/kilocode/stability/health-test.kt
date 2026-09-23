package ai.kilocode.stability

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * 采集健康（任务A6，设计7.1/11.2；§6.2增量协议）。
 *
 * health事实的drop/write_error是自上一条health事实以来的增量（cs-cloud直接求和），
 * 基线随Health实例（run重启=新实例，增量自然从零起算）；depth_bytes/oldest_age_ms是
 * 当前积压读数。只在后台生成，每30秒及损失变化（增量>0）时各一次；写盘失败向独立
 * KiloLog限频输出安全模板，绝不重新record；Health不收集业务内容。
 */
class HealthTest {

    @Test
    fun `health separates reasons and degrades when an operation end is lost`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            val health = fixture.health()
            assertEquals("good", health.snapshot()["quality"]?.jsonPrimitive?.content)
            fixture.recorder.record(invalidDraft())
            val sample = health.snapshot()
            assertEquals(1L, sample["drop_invalid"]?.jsonPrimitive?.long)
            assertEquals(1L, sample["drop_failure"]?.jsonPrimitive?.long)
            listOf("drop_contention", "drop_capacity", "drop_policy", "drop_oversize", "drop_evicted").forEach { key ->
                assertEquals(0L, sample[key]?.jsonPrimitive?.long, key)
            }
            assertEquals("degraded", sample["quality"]?.jsonPrimitive?.content)
            assertTrue(health.poll())
            fixture.flush()
            val fact = fixture.facts().single()
            assertEquals("degraded", fact.data["quality"]?.jsonPrimitive?.content)
            assertEquals(1L, fact.data["drop_invalid"]?.jsonPrimitive?.long)
        }
    }

    /** 夹具辅助：沿用既有三参构造；两次调用得到两个实例——基线随实例、不随源计数走。 */
    private fun Fixture.health(): Health = Health(recorder, writer, clock)

    @Test
    fun `sample contention is reported without degrading failure quality`() {
        Fixture(autoStart = false).use { fixture ->
            val field = Recorder::class.java.getDeclaredField("queue").apply { isAccessible = true }
            val queue = field.get(fixture.recorder) as StabilityQueue
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val job = pool.submit {
                    queue.tryWithProducerLock {
                        entered.countDown()
                        assertTrue(release.await(30, TimeUnit.SECONDS))
                    }
                }
                assertTrue(entered.await(30, TimeUnit.SECONDS))
                assertEquals(Admission.DROPPED, fixture.recorder.record(startedDraft()))
                val sample = fixture.health().snapshot()
                assertEquals(1L, sample["drop_contention"]?.jsonPrimitive?.long)
                assertEquals(0L, sample["drop_failure"]?.jsonPrimitive?.long)
                assertEquals("good", sample["quality"]?.jsonPrimitive?.content)
                release.countDown()
                job.get(30, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `policy and oversize losses have distinct reason counters`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            fixture.expireControl()
            assertEquals(Admission.DISABLED, fixture.recorder.record(wideCriticalDraft()))
            val sample = fixture.health().snapshot()
            assertEquals(1L, sample["drop_policy"]?.jsonPrimitive?.long)
            assertEquals(0L, sample["drop_invalid"]?.jsonPrimitive?.long)
            assertEquals(1L, sample["drop_failure"]?.jsonPrimitive?.long)
            assertEquals("degraded", sample["quality"]?.jsonPrimitive?.content)
        }
        Fixture(tickMs = 60_000L, maxFileBytes = 1).use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(wideCriticalDraft()))
            fixture.flush()
            val sample = fixture.health().snapshot()
            assertEquals(1L, sample["drop_oversize"]?.jsonPrimitive?.long)
            assertEquals(0L, sample["drop_policy"]?.jsonPrimitive?.long)
            assertEquals(1L, sample["drop_failure"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun `an unadmitted health report preserves its reason baseline`() {
        Fixture(autoStart = false).use { fixture ->
            val health = fixture.health()
            val draft = Draft("resource.snapshot", "sample", "critical", buildJsonObject {
                put("resource", "subscription")
                put("count", 1)
            })
            while (fixture.recorder.record(draft) == Admission.QUEUED) Unit
            assertFalse(health.poll(), "a saturated sample queue must refuse the report")
            val claim = requireNotNull(fixture.recorder.tryClaim(2000, 4 * 1024 * 1024))
            claim.release()
            assertTrue(health.poll())
            val report = requireNotNull(fixture.recorder.tryClaim(1, 4096))
            try {
                assertEquals(2L, report.records.single().fact.data["drop_capacity"]?.jsonPrimitive?.long)
            } finally {
                report.release()
            }
        }
    }

    @Test
    fun `snapshot carries cumulative counters and queue gauges`() {
        Fixture().use { fixture ->
            queueStarted(fixture)
            val health = fixture.health()
            val snapshot = health.snapshot()
            assertEquals(0L, snapshot["drop"]?.jsonPrimitive?.long)
            assertEquals(0L, snapshot["write_error"]?.jsonPrimitive?.long)
            assertTrue((snapshot["depth_bytes"]?.jsonPrimitive?.long ?: 0L) > 0, "queued record must show depth")
            assertEquals(0L, snapshot["oldest_age_ms"]?.jsonPrimitive?.long)

            fixture.advanceClock(5_000L)
            assertEquals(5_000L, health.snapshot()["oldest_age_ms"]?.jsonPrimitive?.long)
            fixture.flush()
        }
    }

    @Test
    fun `background generation waits for the interval without losses`() {
        Fixture().use { fixture ->
            val health = fixture.health()
            assertFalse(health.poll(), "no fact before the 30s interval and without losses")
            fixture.advanceClock(30_000L)
            assertTrue(health.poll(), "interval due generates the first snapshot")
            fixture.flush()
            val fact = fixture.facts().single { it.name == "telemetry.health" }
            assertEquals(0L, fact.data["drop"]?.jsonPrimitive?.long)
            assertEquals(0L, fact.data["write_error"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun `loss change generates the health fact immediately`() {
        Fixture().use { fixture ->
            val health = fixture.health()
            assertFalse(health.poll())
            injectWriteFailure(fixture)
            assertTrue(health.poll(), "a loss delta must generate without waiting for the interval")
            fixture.flush()
            val fact = fixture.facts().single { it.name == "telemetry.health" }
            assertEquals(1L, fact.data["write_error"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun `write failures emit the rate limited safe template without re-recording`() {
        Fixture().use { fixture ->
            val warnings = mutableListOf<String>()
            val health = fixture.health(warnings)

            injectWriteFailure(fixture)
            assertTrue(health.poll())
            fixture.flush()
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().startsWith("stability collector write failures: +1 write_error"))
            assertFalse(warnings.single().contains("ClosedChannel"), "template must stay numeric")

            // 限频窗口内的新损失：照常生成摘要，但不重复告警。
            injectWriteFailure(fixture)
            assertTrue(health.poll())
            fixture.flush()
            assertEquals(1, warnings.size)

            fixture.advanceClock(60_000L)
            injectWriteFailure(fixture)
            assertTrue(health.poll())
            assertEquals(2, warnings.size)
            assertTrue(warnings.last().startsWith("stability collector write failures: +1 write_error, cumulative 3"))

            fixture.flush()
            val facts = fixture.facts()
            assertTrue(facts.none { it.name.startsWith("error.") }, "write failures must never re-record")
            val writeErrors = facts
                .filter { it.name == "telemetry.health" }
                .map { it.data["write_error"]?.jsonPrimitive?.long }
            assertEquals(listOf(1L, 1L, 1L), writeErrors, "facts carry the write_error delta since the previous health fact")
        }
    }

    @Test
    fun `a fresh health instance starts from zero for a new run`() {
        Fixture().use { fixture ->
            val warnings = mutableListOf<String>()
            val health = fixture.health(warnings)
            injectWriteFailure(fixture)
            assertTrue(health.poll())

            // 新run = 新recorder + 新writer（StabilityService.activateRun同型）：计数从零开始。
            val nextRecorder = Recorder(NEXT_RUN_IDENTITY, fixture.policies, fixture.clock)
            val nextWriter = Writer(
                fixture.base.resolve("outbox-next"),
                "sc-fx-${NEXT_RUN_IDENTITY.producerId}.jsonl",
                NEXT_RUN_IDENTITY,
                nextRecorder,
                fixture.policies,
                fixture.clock,
            )
            val nextRunHealth = Health(nextRecorder, nextWriter, fixture.clock)
            assertEquals(0L, nextRunHealth.snapshot()["drop"]?.jsonPrimitive?.long)
            assertEquals(0L, nextRunHealth.snapshot()["write_error"]?.jsonPrimitive?.long)
            assertEquals(1, warnings.size)
            fixture.flush()
        }
    }

    @Test
    fun `dictionary violations surface in the health drop delta`() {
        Fixture().use { fixture ->
            assertEquals(Admission.DROPPED, fixture.recorder.record(invalidDraft()))
            assertEquals(1L, fixture.recorder.health().droppedInvalid)
            val health = fixture.health()
            assertTrue(health.poll(), "a nonzero drop delta generates immediately")
            fixture.flush()
            val fact = fixture.facts().single { it.name == "telemetry.health" }
            assertEquals(1L, fact.data.getValue("drop").jsonPrimitive.long)
        }
    }

    @Test
    fun `health facts carry the delta since the previous health fact`() {
        Fixture(tickMs = 50L).use { fixture ->
            val health = fixture.health()
            repeat(2) { fixture.recorder.record(invalidDraft()) }   // 结构性违规 → droppedInvalid
            assertTrue(health.poll())
            repeat(3) { fixture.recorder.record(invalidDraft()) }
            assertTrue(health.poll())

            fixture.flush()
            val healths = fixture.facts().filter { it.name == "telemetry.health" }
            assertEquals(2, healths.size)
            assertEquals(2L, healths[0].data.getValue("drop").jsonPrimitive.long) // 首条=自本实例起算的增量
            assertEquals(3L, healths[1].data.getValue("drop").jsonPrimitive.long) // 距上一条的增量，不是累计5
        }
    }

    @Test
    fun `baseline is per-health-instance so a new run starts from zero`() {
        Fixture(tickMs = 50L).use { fixture ->
            repeat(2) { fixture.recorder.record(invalidDraft()) }
            assertTrue(fixture.health().poll())                     // 实例1基线0，报2
            assertTrue(fixture.health().poll())                     // 实例2基线0（新run），再报2
            fixture.flush()
            val healths = fixture.facts().filter { it.name == "telemetry.health" }
            assertEquals(listOf(2L, 2L), healths.map { it.data.getValue("drop").jsonPrimitive.long })
        }
    }

    @Test
    fun `evicted lines count toward the drop delta`() {
        Fixture(tickMs = 50L, maxFileBytes = 2L * 1024).use { fixture ->
            repeat(40) { fixture.recorder.record(Draft("resource.snapshot", "sample", "critical", buildJsonObject {
                put("resource", "subscription")
                put("count", 1)
            })) }
            fixture.flush()
            // 采样基线取poll前读数：写入health事实自身的追加还可能触发重写淘汰，
            // 那部分按增量协议归入下一条health事实（writeLine先采样后追加）。
            val evictedBeforePoll = fixture.writer.stats().droppedEvicted
            assertTrue(evictedBeforePoll > 0, "fixture precondition: capacity rewrite evicted whole lines")
            fixture.health().poll()
            fixture.flush()
            val drop = fixture.facts().filter { it.name == "telemetry.health" }
                .last().data.getValue("drop").jsonPrimitive.long
            assertTrue(drop >= evictedBeforePoll, "eviction must be visible in health")
        }
    }

    /** health构造辅助（告警出口可注入）：统一走夹具的recorder/writer/clock三参构造。 */
    private fun Fixture.health(warnings: MutableList<String>): Health =
        Health(recorder, writer, clock) { message -> warnings += message }

    /** 真实故障注入：下一次写入前关闭通道，flush时该记录丢失并计write_error。 */
    private fun injectWriteFailure(fixture: Fixture) {
        fixture.failNextWrite()
        assertEquals(Admission.QUEUED, fixture.recorder.record(startedDraft()))
        fixture.flush()
    }

    private fun queueStarted(fixture: Fixture) {
        assertEquals(Admission.QUEUED, fixture.recorder.record(startedDraft()))
    }

    private fun startedDraft() = Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap()))

    /**
     * 结构性违规草稿：基础结构完全合法（rpc end相的五终态键与api_group词表内取值），
     * 仅携带一个任何name白名单都不含的键，Dictionary.violations只由它判违规
     * → record返回DROPPED并计入droppedInvalid（recorder.kt准入序：违规先于策略），
     * 无需生产侧测试钩子。
     */
    private fun invalidDraft(): Draft = Draft("rpc", "operation", "critical", buildJsonObject {
        put("phase", "end")
        put("result", "success")
        put("duration_ms", 1)
        put("stage", "unknown")
        put("cause", "unknown")
        put("error_code", "none")
        put("api_group", "session")
        put("zzz_not_in_any_whitelist", 1)
    })

    /** 更宽的critical载荷：error_code取64字节内长值，在小预算下更快触发容量重写淘汰（与writer-test同型）。 */
    private fun wideCriticalDraft(): Draft = Draft("rpc", "operation", "critical", buildJsonObject {
        put("phase", "end")
        put("result", "success")
        put("duration_ms", 1)
        put("stage", "unknown")
        put("cause", "unknown")
        put("error_code", "x".repeat(60))
        put("api_group", "session")
    }, purposes = setOf("metrics", "logs"))

    /** 新run的身份（run重置语义：新recorder的累计计数从零开始）。 */
    private val NEXT_RUN_IDENTITY = ProducerIdentity(
        producerId = "pr-a6b",
        runId = "run-a6b",
        deviceId = "device-a6b",
        pluginVersion = "1.0.0",
        ideProduct = "IU",
        ideBuild = "build-a6",
        ideBuildMajor = "2026.1",
        osFamily = "windows",
        arch = "x64",
        env = "test",
        mode = "monolith",
        side = "monolith",
        connectionProvider = "cs-cloud",
    )
}
