package ai.kilocode.stability

import java.util.concurrent.CancellationException
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 安全异常详情与采集健康（任务A6，设计11.1/6.2）。
 *
 * fault_id在故障边界生成并跨重复报告传递，同fault只计一次；fingerprint只含插件类/方法帧
 * （不含文件名、行号、message、cause）；critical最小计数仅metrics，diagnostic详情仅logs，
 * 两个event_id共享fault_id；每fingerprint每分钟最多3份详情，额外数在下一窗口汇总且不计入
 * 异常指标；CancellationException排除，致命错误保留原传播；限频键表有界，满时归固定overflow摘要。
 */
class FaultTest {

    @Test
    fun `detail throttling preserves fault counts`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            repeat(10) { faults.report(IllegalStateException("token=secret C:/Users/alice"), "frontend", true) }
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(10, facts.count { it.name == "error.reported" && it.channel == "critical" })
            assertEquals(3, facts.count { it.name == "error.reported" && it.channel == "diagnostic" })
            val text = facts.joinToString { it.toString() }
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("alice"))
        }
    }

    @Test
    fun `same fault id is counted once across repeat reports`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            repeat(5) { faults.report(IllegalStateException("boom once"), "frontend", true, "fault-fixed") }
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(1, facts.count { it.name == "error.reported" && it.channel == "critical" })
            assertEquals(1, facts.count { it.name == "error.reported" && it.channel == "diagnostic" })
            assertEquals(1L, facts.single { it.channel == "diagnostic" }.data["count"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun `extra reports beyond the detail quota become one summary in the next window`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            repeat(10) { index -> faults.report(FaultA(), "frontend", true, "fault-$index") }
            fixture.flush()
            val firstWindow = fixture.facts()
            assertEquals(10, firstWindow.count { it.channel == "critical" })
            assertEquals(3, firstWindow.count { it.channel == "diagnostic" })
            val detailFingerprint = firstWindow.first { it.channel == "diagnostic" }.data["fingerprint"]?.jsonPrimitive?.content

            fixture.advanceClock(WINDOW_STEP_MS)
            // 同一fault重复报告仅作为下一窗口的冲刷触发器，去重后不再计数。
            faults.report(FaultA(), "frontend", true, "fault-0")
            fixture.flush()

            val facts = fixture.facts()
            assertEquals(10, facts.count { it.channel == "critical" }, "dedup hit must not add a count")
            assertEquals(
                3,
                facts.count { it.channel == "diagnostic" && it.data["count"]?.jsonPrimitive?.long == 1L },
            )
            val summaries = facts.filter { it.data["count"]?.jsonPrimitive?.long == 7L }
            assertEquals(1, summaries.size, "the seven extra reports collapse into one summary")
            val summary = summaries.single()
            assertEquals(setOf("logs"), summary.purposes, "summary count must never enter the metrics sink")
            assertEquals(detailFingerprint, summary.data["fingerprint"]?.jsonPrimitive?.content)
            assertTrue(summary.data["message"]?.jsonPrimitive?.content?.contains("suppressed=7") == true)
        }
    }

    @Test
    fun `independent faults may share a fingerprint with distinct fault ids`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            faults.report(FaultA(), "frontend", true, "fault-1")
            faults.report(FaultA(), "frontend", true, "fault-2")
            fixture.flush()
            val counts = fixture.facts().filter { it.channel == "critical" }
            assertEquals(2, counts.size)
            assertEquals(2, counts.map { it.data["fault_id"]?.jsonPrimitive?.content }.distinct().size)
            assertEquals(1, counts.map { it.data["fingerprint"]?.jsonPrimitive?.content }.distinct().size)
        }
    }

    @Test
    fun `uncaught errors record error uncaught with metrics only count`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            faults.report(FaultB(), "shared", handled = false, fault = "fault-u1")
            fixture.flush()
            val facts = fixture.facts()
            val count = facts.single { it.channel == "critical" }
            assertEquals("error.uncaught", count.name)
            assertEquals(setOf("metrics"), count.purposes)
            assertEquals("illegal_state", count.data["error_class"]?.jsonPrimitive?.content)
            assertEquals(false, count.data["handled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
            assertEquals("fault-u1", count.context["fault_id"])
            val detail = facts.single { it.channel == "diagnostic" }
            assertEquals("error.uncaught", detail.name, "uncaught detail keeps the uncaught name (F4)")
            assertEquals(setOf("logs"), detail.purposes)
            assertEquals("fault-u1", detail.context["fault_id"], "both event ids share the fault id")
        }
    }

    @Test
    fun `logs open with metrics closed still records the detail`() {
        Fixture().use { fixture ->
            fixture.base.resolve("control.json").writeText(controlJson(metrics = false, logs = true))
            fixture.policies.refresh()
            val faults = Faults(fixture.recorder, fixture.clock)
            faults.report(FaultA(), "frontend", true, "fault-1")
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(0, facts.count { it.channel == "critical" }, "metrics closed: no count fact")
            assertEquals(1, facts.count { it.channel == "diagnostic" }, "logs open: detail still recorded")
        }
    }

    @Test
    fun `cancellation is excluded from collection`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            faults.report(CancellationException("client cancelled prompt"), "frontend", true)
            assertEquals(0L, fixture.recorder.health().accepted)
            fixture.flush()
            assertEquals(0, fixture.facts().size)
        }
    }

    @Test
    fun `fatal errors keep their original propagation`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            assertFailsWith<OutOfMemoryError> { faults.report(OutOfMemoryError("oom"), "frontend", true) }
            assertFailsWith<ThreadDeath> { faults.report(ThreadDeath(), "frontend", true) }
            assertEquals(0L, fixture.recorder.health().accepted)
            fixture.flush()
            assertEquals(0, fixture.facts().size, "fatal errors are never collected")
        }
    }

    @Test
    fun `linkage errors are observable instead of propagating`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            faults.report(NoClassDefFoundError("missing"), "shared", handled = false, fault = "fault-l1")
            faults.report(ClassCastException("cast"), "shared", handled = true, fault = "fault-l2")
            fixture.flush()
            val facts = fixture.facts()
            val uncaught = facts.single { it.name == "error.uncaught" && it.channel == "critical" }
            assertEquals("no_class_def_found", uncaught.data["error_class"]?.jsonPrimitive?.content)
            val reported = facts.single { it.name == "error.reported" && it.channel == "critical" }
            assertEquals("other", reported.data["error_class"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `details stay inside the safe frame whitelist`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            faults.report(FaultA(), "frontend", true, "fault-1")
            fixture.flush()
            val detail = fixture.facts().single { it.channel == "diagnostic" }
            val frames = detail.data["frames"]
            assertTrue(frames is JsonArray && frames.size <= 5)
            frames?.let { array ->
                array.forEach { frame ->
                    val text = frame.jsonPrimitive.content
                    assertTrue(text.startsWith("ai.kilocode."), "frame $text must stay inside plugin classes")
                    assertFalse(text.contains("C:"), "frame $text must not carry paths")
                }
            }
            val message = detail.data["message"]?.jsonPrimitive?.content ?: ""
            assertFalse(message.contains("boom"), "detail message must be a fixed template")
        }
    }

    @Test
    fun `rate keys beyond the table cap fall into the fixed overflow summary`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock, maxRateKeys = 1)
            faults.report(FaultA(), "frontend", true, "fault-a")
            repeat(5) { index -> faults.report(FaultB(), "frontend", true, "fault-b$index") }
            fixture.flush()
            assertEquals(6, fixture.facts().count { it.channel == "critical" })
            assertEquals(1, fixture.facts().count { it.channel == "diagnostic" }, "overflow fingerprints get no detail")

            fixture.advanceClock(WINDOW_STEP_MS)
            faults.report(FaultA(), "frontend", true, "fault-a")
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(6, facts.count { it.channel == "critical" })
            val overflow = facts.filter { it.data["count"]?.jsonPrimitive?.longOrNull == 5L }
            assertEquals(1, overflow.size)
            assertEquals("overflow", overflow.single().data["fingerprint"]?.jsonPrimitive?.content)
            assertEquals(setOf("logs"), overflow.single().purposes)
        }
    }

    @Test
    fun `uncaught detail and its next window summary keep the uncaught name`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            repeat(10) { index -> faults.report(FaultB(), "shared", handled = false, fault = "fault-u$index") }
            fixture.flush()
            fixture.advanceClock(WINDOW_STEP_MS)
            // 下一窗口的任意新报告惰性冲刷上一窗口摘要；handled报告不得把摘要改名。
            faults.report(FaultA(), "shared", handled = true, fault = "fault-h1")
            fixture.flush()
            val facts = fixture.facts()
            val summary = facts.single { it.data["count"]?.jsonPrimitive?.long == 7L }
            assertEquals("error.uncaught", summary.name, "summary follows the fingerprint's own name (F4)")
            val handledDetail = facts.single {
                it.channel == "diagnostic" && it.context["fault_id"] == "fault-h1"
            }
            assertEquals("error.reported", handledDetail.name)
        }
    }

    @Test
    fun `zero and one detail quotas preserve every fault count`() {
        listOf(0, 1).forEach { limit ->
            Fixture().use { fixture ->
                fixture.base.resolve("control.json").writeText(controlJson(true, true, limit))
                fixture.policies.refresh()
                val faults = Faults(fixture.recorder, fixture.clock)
                repeat(4) { faults.report(FaultA(), "other", true) }
                fixture.flush()
                assertEquals(4, fixture.facts().count { it.channel == "critical" })
                assertEquals(limit, fixture.facts().count { it.channel == "diagnostic" })
            }
        }
    }

    @Test
    fun `tightening quota in the same minute takes effect immediately`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            faults.report(FaultA(), "other", true)
            fixture.flush()
            fixture.base.resolve("control.json").writeText(controlJson(true, true, 1))
            fixture.policies.refresh()
            repeat(4) { faults.report(FaultA(), "other", true) }
            fixture.flush()
            assertEquals(5, fixture.facts().count { it.channel == "critical" })
            assertEquals(1, fixture.facts().count { it.channel == "diagnostic" })
            fixture.base.resolve("control.json").writeText(controlJson(true, true, 0))
            fixture.policies.refresh()
            fixture.advanceClock(WINDOW_STEP_MS)
            faults.report(FaultA(), "other", true)
            fixture.flush()
            assertEquals(6, fixture.facts().count { it.channel == "critical" })
            assertEquals(1, fixture.facts().count { it.channel == "diagnostic" }, "zero also forbids summaries")
        }
    }

    @Test
    fun `critical only logs forbid error details including channel disguises`() {
        Fixture().use { fixture ->
            val faults = Faults(fixture.recorder, fixture.clock)
            faults.report(FaultA(), "other", true)
            fixture.flush()
            val detail = fixture.facts().single { it.channel == "diagnostic" }
            fixture.base.resolve("control.json").writeText(controlJson(false, true, categories = listOf("critical")))
            fixture.policies.refresh()
            faults.report(FaultB(), "other", true)
            assertEquals(Admission.DISABLED, fixture.recorder.record(Draft(
                detail.name, detail.kind, "critical", detail.data, detail.context,
            )))
            assertEquals(Admission.QUEUED, fixture.operations.protocolError(
                ProtocolTransport.SSE, ProtocolStage.DECODE, ProtocolCode.DECODE_FAILED,
            ))
            fixture.flush()
            assertEquals(1, fixture.facts().count { it.channel == "diagnostic" })
            assertEquals(setOf("logs"), fixture.facts().last().purposes)
        }
    }

    @Test
    fun `writer rechecks queued detail category and zero quota`() {
        listOf(
            controlJson(true, true, categories = listOf("critical")),
            controlJson(true, true, limit = 0),
        ).forEach { policy ->
            Fixture(autoStart = false).use { fixture ->
                Faults(fixture.recorder, fixture.clock).report(FaultA(), "other", true)
                fixture.base.resolve("control.json").writeText(policy)
                fixture.policies.refresh()
                fixture.writer.start()
                fixture.flush()
                assertEquals(1, fixture.facts().size)
                assertEquals("critical", fixture.facts().single().channel)
            }
        }
    }

    private fun controlJson(metrics: Boolean, logs: Boolean, limit: Int = 3, categories: List<String> = listOf("critical", "diagnostic")): String = buildJsonObject {
        put("schema_major", 1)
        put("revision", 12L)
        put("enabled", true)
        put("metrics_enabled", metrics)
        put("metrics_expires_at", 9_000_000_000_000L)
        put("metrics_allowed_categories", JsonArray(listOf("critical", "diagnostic").map { JsonPrimitive(it) }))
        put("logs_enabled", logs)
        put("logs_expires_at", 9_000_000_000_000L)
        put("logs_allowed_categories", JsonArray(categories.map { JsonPrimitive(it) }))
        put("account_epoch", "acct-a")
        put("account_state", "ready")
        put("expires_at", 9_000_000_000_000L)
        put("log_detail_rate_limit", buildJsonObject { put("per_fingerprint_max_per_minute", limit) })
    }.toString()

    private class FaultA : IllegalStateException("secret-a")

    private class FaultB : IllegalStateException("secret-b")

    companion object {
        private const val WINDOW_STEP_MS = 61_000L
    }
}
