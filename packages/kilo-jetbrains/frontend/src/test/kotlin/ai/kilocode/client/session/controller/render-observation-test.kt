package ai.kilocode.client.session.controller

import ai.kilocode.client.stability.Render
import ai.kilocode.stability.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * M21（C4）合并批次渲染耗时采样：首条测试为brief逐字片段。Render单元行为（失败永不
 * 采样剔除、五桶、确定性均匀采样、字典字段闭集）全部经真实Fixture（真实Recorder/Writer
 * 落盘）验证；真实SessionUpdateQueue与真实EDT的"一次fire只有一个render样本"与
 * "无每Token落盘"断言见SessionUpdateQueueTest（平台测试）。
 */
class RenderObservationTest {

    @Test fun `failed render is never sampled out`() {
        Fixture().use { fixture ->
            val render = Render(fixture.clock, fixture.recorder, 0.01)
            assertFailsWith<IllegalStateException> {
                render.apply(5, "frontend") { throw IllegalStateException("private text") }
            }
            fixture.flush()
            val event = fixture.facts().single { it.name == "render.apply" }
            assertEquals("failure", event.data.getValue("result").jsonPrimitive.content)
            assertEquals(1.0, event.data.getValue("sample_rate").jsonPrimitive.double)
        }
    }

    @Test fun `batch size lands in one of five buckets`() {
        Fixture().use { fixture ->
            val render = Render(fixture.clock, fixture.recorder)
            val cases = listOf(1 to "1", 3 to "2-5", 10 to "6-20", 50 to "21-100", 200 to "100+")
            cases.forEachIndexed { index, (size, _) ->
                render.apply(size, "frontend") { fixture.advanceClock(index + 1L) }
            }
            fixture.flush()
            val events = fixture.facts().filter { it.name == "render.apply" }
            assertEquals(cases.size, events.size)
            cases.forEachIndexed { index, (_, bucket) ->
                val event = events.single { it.data.getValue("duration_ms").jsonPrimitive.long == index + 1L }
                assertEquals(bucket, event.data.getValue("batch_size_bucket").jsonPrimitive.content)
            }
        }
    }

    @Test fun `success at default rate records every batch`() {
        Fixture().use { fixture ->
            val render = Render(fixture.clock, fixture.recorder)
            repeat(3) { render.apply(2, "frontend") { fixture.advanceClock(1L) } }
            fixture.flush()
            val events = fixture.facts().filter { it.name == "render.apply" }
            assertEquals(3, events.size)
            events.forEach { event ->
                assertEquals("success", event.data.getValue("result").jsonPrimitive.content)
                assertEquals(1.0, event.data.getValue("sample_rate").jsonPrimitive.double)
                assertEquals(1L, event.data.getValue("duration_ms").jsonPrimitive.long)
            }
        }
    }

    @Test fun `success is sampled deterministically at the configured rate`() {
        Fixture().use { fixture ->
            val render = Render(fixture.clock, fixture.recorder, 0.5)
            repeat(4) { render.apply(2, "frontend") { fixture.advanceClock(1L) } }
            fixture.flush()
            val events = fixture.facts().filter { it.name == "render.apply" }
            // 确定性均匀采样（步长=1/rate）：第2、4次成功保留，第1、3次被采出；
            // 保留样本携带实际生效rate与真实duration_ms，绝不携带批次内容。
            assertEquals(2, events.size)
            events.forEach { event ->
                assertEquals("success", event.data.getValue("result").jsonPrimitive.content)
                assertEquals(0.5, event.data.getValue("sample_rate").jsonPrimitive.double)
                assertEquals(1L, event.data.getValue("duration_ms").jsonPrimitive.long)
            }
        }
    }

    @Test fun `render sample carries the dictionary closed field set only`() {
        Fixture().use { fixture ->
            val render = Render(fixture.clock, fixture.recorder, 0.01)
            assertFailsWith<IllegalStateException> {
                render.apply(1, "frontend") { throw IllegalStateException("private text") }
            }
            fixture.flush()
            val event = fixture.facts().single { it.name == "render.apply" }
            assertEquals(
                setOf("duration_ms", "result", "component", "batch_size_bucket", "sample_rate"),
                event.data.keys,
            )
            assertEquals("frontend", event.data.getValue("component").jsonPrimitive.content)
            assertEquals("1", event.data.getValue("batch_size_bucket").jsonPrimitive.content)
            // 仅metrics出口（字典METRICS_ONLY_NAMES），critical通道sample形态。
            assertEquals(setOf("metrics"), event.purposes)
            assertEquals("critical", event.channel)
            // 故障类别固定，绝不让异常原文进入任何字段。
            assertFalse("private text" in event.data.toString())
            assertTrue(event.context.isEmpty())
        }
    }
}
