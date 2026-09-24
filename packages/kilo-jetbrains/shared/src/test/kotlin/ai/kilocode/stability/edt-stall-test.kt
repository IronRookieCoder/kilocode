package ai.kilocode.stability

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * edt.stall合并器（任务9，设计10.3）：同观测区间内仅合并明确相交或首尾相接
 * （scheduled <= 上一窗口end）且探针序号连续的阻塞区间；序号缺失、中断标记或
 * observation_id变化打断合并，合并后持续≥2秒才产出一条edt.stall事实。
 */
class EdtStallTest {

    @Test
    fun `captured stack is retained until close and linked to the stall`() {
        Fixture().use { fixture ->
            fixture.enableDiagnostics()
            val stack = EdtStack(Thread.currentThread())
            val merger = StallMerger(fixture.operations::report, fixture.operations::record)
            merger.onValidSample("obs-stack", 1, 0, 2_500, stack)
            fixture.flush()
            assertTrue(fixture.facts().isEmpty())
            merger.onObservationEnded()
            merger.onObservationEnded()
            fixture.flush()
            val facts = fixture.facts()
            val stall = facts.single { it.name == "edt.stall" }
            val incident = facts.single { it.name == "diagnostic.reported" }
            assertEquals(incident.context["incident_id"], stall.context["incident_id"])
            assertEquals(Thread.currentThread().name, incident.data.getValue("thread_name").jsonPrimitive.content)
            assertEquals(Thread.currentThread().threadId(), incident.data.getValue("thread_id").jsonPrimitive.long)
            val payload = fixture.payload("edt_stack")
            stack.frames.forEach { assertTrue(payload.contains(it.toString()), "Missing captured frame: $it") }
            assertTrue(payload.contains("captured stack is retained"))
        }
    }

    private fun merger(): Pair<StallMerger, MutableList<Draft>> {
        val out = mutableListOf<Draft>()
        return StallMerger { out.add(it) } to out
    }

    @Test
    fun `single sample blocking over two seconds yields one stall`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 1_000, 3_500)
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(2_500, out[0].data["duration_ms"]?.jsonPrimitive?.long)
        assertEquals("obs-1", out[0].data["observation_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `overlapping and touching samples merge within one observation`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 11_800)
        m.onValidSample("obs-1", 2, 11_800, 12_500) // 首尾相接（scheduled==上一窗口end）合并
        m.onValidSample("obs-1", 3, 12_500, 13_100) // 首尾相接（scheduled==12_500==上一窗口end）合并
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(3_100, out[0].data["duration_ms"]?.jsonPrimitive?.long) // 10_000→13_100
    }

    @Test
    fun `sequence gap breaks the merge`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 12_500) // 2.5s窗口
        // 只钉住序号谓词：scheduled==12_500与上一窗口end首尾相接、obs相同，唯seq缺失（2丢失）
        // 打断合并；缺失部分不推断卡顿——旧窗口2.5s达标产出，新窗口0.2s丢弃。
        m.onValidSample("obs-1", 3, 12_500, 12_700)
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(2_500, out[0].data["duration_ms"]?.jsonPrimitive?.long)
    }

    @Test
    fun `observation change breaks the merge and ends the current window`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 12_500) // 2.5s窗口
        // 只钉住observation_id谓词：scheduled==12_500首尾相接且seq连续（1→2），唯换观测区间
        // 打断合并——终结上一窗口（产出obs-1的2.5s）并开新窗口（0.2s不足阈值丢弃）。
        m.onValidSample("obs-2", 2, 12_500, 12_700)
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(2_500, out[0].data["duration_ms"]?.jsonPrimitive?.long)
        assertEquals("obs-1", out[0].data["observation_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `windows shorter than two seconds yield nothing`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 10_500)
        // 起点10_600在上一窗口end（10_500）之后：不相接不合并，两个独立窗口各自不足2秒
        m.onValidSample("obs-1", 2, 10_600, 11_200)
        m.onObservationEnded()
        assertTrue(out.isEmpty())
    }

    @Test
    fun `an idle gap between samples does not continue the window`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 11_500) // 1.5s
        m.onValidSample("obs-1", 2, 20_000, 22_600) // 序连续但排程起点远在上一窗口end之后：不接续
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(2_600, out[0].data["duration_ms"]?.jsonPrimitive?.long) // 只有第二个区间达标
    }

    @Test
    fun `invalid samples never reach the merger`() {
        val (m, out) = merger()
        // 作废样本由探针侧拦在onValidSample之外：observing区间以onObservationEnded终结
        m.onValidSample("obs-1", 1, 10_000, 12_500)
        m.onObservationEnded() // 探针invalidate路径（失焦/暂停/调度断层/关闭）
        m.onObservationEnded() // 重复终结幂等
        assertEquals(1, out.size)
    }
}
