package ai.kilocode.stability

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自检驱动面（selftest.kt）的单元级验证：真实Fixture管线（Recorder→队列→Writer→封存→
 * .ready还原）驱动emitDictionarySweep，断言其覆盖面与形态——真实IDE E2E
 * （StabilityDictionaryE2eTest）经隐藏动作调用同一入口，此处先证明驱动内容本身正确。
 */
class SelfTestTest {

    @Test
    fun `outbox scenario preserves every load incident under sample pressure`() {
        Fixture(autoStart = false).use { fixture ->
            fixture.enableDiagnostics()

            emitOutboxScenario(fixture.recorder, fixture.operations)
            val good = Health(fixture.recorder, fixture.writer, fixture.clock, intervalMs = 0).snapshot()

            fixture.writer.start()
            fixture.flush()
            val facts = fixture.facts()
            val incidents = facts.filter { it.name == "diagnostic.reported" }

            val expected = (0 until 100).map { "load_failure_${it.toString().padStart(3, '0')}" }.toSet()
            assertEquals(expected, incidents.map { it.data.getValue("code").jsonPrimitive.content }.toSet())
            assertEquals(expected.size, incidents.size)
            incidents.forEach { incident ->
                val id = incident.context.getValue("incident_id")
                assertTrue(incident.data.getValue("payload_refs").toString().contains("response"))
                assertTrue(facts.any {
                    it.name == "diagnostic.payload" && it.context["incident_id"] == id &&
                        it.data["payload_kind"]?.jsonPrimitive?.content == "response"
                })
            }
            assertTrue(good.getValue("drop_evicted").jsonPrimitive.long > 0)
            assertEquals("good", good.getValue("quality").jsonPrimitive.content)

            assertEquals(Admission.DROPPED, emitOutboxDegraded(fixture.recorder))
            val degraded = Health(fixture.recorder, fixture.writer, fixture.clock, intervalMs = 0).snapshot()
            assertTrue(degraded.getValue("drop_failure").jsonPrimitive.long > 0)
            assertEquals("degraded", degraded.getValue("quality").jsonPrimitive.content)
        }
    }

    @Test
    fun `sweep lands every driven name on disk in dictionary form`() {
        Fixture().use { fixture ->
            emitDictionarySweep(fixture.recorder, fixture.operations, Faults(fixture.recorder, fixture.clock), fixture.resources)
            fixture.flush()
            val facts = fixture.facts()

            // —— 覆盖：31个登记name中除plugin.started/plugin.shutdown/telemetry.health
            //    （服务级单发与真实快照，见selftest.kt KDoc）外全部落盘 ——
            val expectedNames = Dictionary.names.toSet() - setOf(
                "plugin.started", "plugin.shutdown", "telemetry.health",
                "diagnostic.reported", "diagnostic.redaction_failed", "diagnostic.payload",
            )
            assertEquals(
                expectedNames,
                facts.map { it.name }.toSet() - setOf("telemetry.health"),
                "sweep驱动的每个name都必须经.ready落盘",
            )

            // —— edt.stall：必须经真实StallMerger产出——同观测区间两枚首尾相接样本（seq 1→2）
            //    合并为2.5秒窗口，onObservationEnded终结后达标；驱动面不得伪造stall Draft形状 ——
            val stalls = facts.filter { it.name == "edt.stall" }
            assertEquals(1, stalls.size, "sweep应经StallMerger产出恰一条edt.stall")
            assertTrue(
                (stalls.single().data["duration_ms"]?.jsonPrimitive?.long ?: 0L) >= 2_000L,
                "edt.stall duration_ms必须≥2000（合并窗口10_000→12_500=2500ms）",
            )

            // —— 形态：kind/channel/purposes与Dictionary投影逐条一致 ——
            val violations = facts.mapNotNull { fact ->
                val expected = when {
                    fact.name.startsWith("error.") ->
                        if (fact.channel == "critical") "diagnostic/critical" else "diagnostic/diagnostic"
                    else -> "${expectedKind(fact.name)}/${expectedChannel(fact.name)}"
                }
                val kindChannel = "${fact.kind}/${fact.channel}"
                when {
                    kindChannel != expected -> "${fact.name}: $kindChannel != $expected"
                    fact.purposes != expectedPurposes(fact) -> "${fact.name}: purposes ${fact.purposes}"
                    else -> null
                }
            }
            assertTrue(violations.isEmpty(), "form violations:\n${violations.joinToString("\n")}")

            // —— error族两形态分道：计数critical/metrics、详情diagnostic/logs，同一fault_id关联 ——
            val count = facts.filter { it.channel == "critical" && it.name.startsWith("error.") }
            val detail = facts.filter { it.channel == "diagnostic" }
            assertEquals(setOf("error.reported", "error.uncaught"), count.map { it.name }.toSet())
            assertEquals(setOf("metrics"), count.first().purposes)
            assertEquals(2, detail.size, "两条自检故障各产出一份详情")
            assertTrue(detail.all { it.purposes == setOf("logs") && it.name.startsWith("error.") })
            assertEquals(
                count.map { it.context["fault_id"] }.toSet(),
                detail.mapNotNull { it.context["fault_id"] }.toSet(),
                "计数与详情经同一组fault_id关联",
            )

            // —— 自检标记：驱动面事实带workspace_id=ws-selftest（error族经显式fault_id辨识；
            //    edt.stall由真实StallMerger产出，其Draft形状不带workspace上下文，与生产一致）——
            assertTrue(
                facts.filter {
                    it.name !in setOf(
                        "error.reported", "error.uncaught", "resource.snapshot", "edt.stall", "telemetry.health",
                    )
                }
                    .all { it.context["workspace_id"] == "ws-selftest" },
                "全部自检事实（error、resource.snapshot与StallMerger产出的edt.stall除外）都携带ws-selftest标记",
            )

            // —— failure-first物理写序不承诺业务顺序；按通道seq重建后必须从1连续 ——
            listOf("critical", "diagnostic").forEach { channel ->
                val seqs = facts.filter { it.channel == channel }.map { it.seq }.sorted()
                assertEquals((1L..seqs.size).toList(), seqs, "$channel seq must be contiguous from 1")
            }

            // —— operation三段经真实Operations产出且operation_id配对（connection.attempt）——
            val attemptPhases = facts.filter { it.name == "connection.attempt" }
                .associateBy { it.data["phase"].toString().trim('"') }
            assertEquals(setOf("start", "progress", "end"), attemptPhases.keys)
            assertEquals(1, attemptPhases.values.map { it.context["operation_id"] }.toSet().size)
        }
    }

    private fun expectedKind(name: String): String = when (name) {
        "connection.state_changed", "migration.required", "session.dispose_risk" -> "transition"
        "plugin.unclean" -> "lifecycle"
        "availability" -> "interval"
        "edt.delay", "edt.stall", "render.apply", "resource.snapshot" -> "sample"
        "protocol.error", "edt.violation" -> "diagnostic"
        "telemetry.health" -> "health"
        else -> "operation"
    }

    private fun expectedChannel(name: String): String = "critical"

    private fun expectedPurposes(fact: Fact): Set<String> = when {
        fact.name.startsWith("error.") -> if (fact.channel == "critical") setOf("metrics") else setOf("logs")
        fact.name in setOf(
            "rpc", "render.apply", "edt.delay", "edt.stall", "resource.snapshot", "availability",
            "migration.required", "session.dispose_risk",
        ) -> setOf("metrics")
        else -> setOf("metrics", "logs")
    }
}
