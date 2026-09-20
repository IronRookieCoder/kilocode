package ai.kilocode.stability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 采集健康（任务A6，设计7.1/11.2）。
 *
 * health摘要按本run累计drop/write_error与depth_bytes/oldest_age_ms输出；只在后台生成，
 * 每30秒及损失变化（drop计数增量）时各一次；写盘失败向独立KiloLog限频输出安全模板，
 * 绝不重新record；恢复后快照仍为累计值，consumer取差值；Health不收集业务内容。
 */
class HealthTest {

    @Test
    fun `snapshot carries cumulative counters and queue gauges`() {
        Fixture().use { fixture ->
            queueStarted(fixture)
            val health = Health(fixture.recorder, fixture.writer, fixture.clock)
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
            val health = Health(fixture.recorder, fixture.writer, fixture.clock)
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
            val health = Health(fixture.recorder, fixture.writer, fixture.clock)
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
            val health = Health(fixture.recorder, fixture.writer, fixture.clock) { message -> warnings += message }

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
            assertEquals(listOf(1L, 2L, 3L), writeErrors, "snapshots stay cumulative for consumer deltas")
        }
    }

    @Test
    fun `a fresh health instance starts from zero for a new run`() {
        Fixture().use { fixture ->
            val warnings = mutableListOf<String>()
            val health = Health(fixture.recorder, fixture.writer, fixture.clock) { message -> warnings += message }
            injectWriteFailure(fixture)
            assertTrue(health.poll())

            // 新run = 新recorder + 新writer（StabilityService.activateRun同型）：计数从零开始。
            val nextRecorder = Recorder(NEXT_RUN_IDENTITY, fixture.policies, fixture.clock)
            val nextWriter = Writer(
                fixture.base.resolve("outbox-next"),
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
    fun `storage full quota drops surface in the health drop total`() {
        Fixture().use { fixture ->
            fixture.recorder.setStorageFull(true)
            assertEquals(Admission.DROPPED, fixture.recorder.record(startedDraft()))
            assertEquals(1L, fixture.recorder.health().droppedQuota)
            val health = Health(fixture.recorder, fixture.writer, fixture.clock)
            assertEquals(1L, health.snapshot()["drop"]?.jsonPrimitive?.long)
            fixture.flush()
        }
    }

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
