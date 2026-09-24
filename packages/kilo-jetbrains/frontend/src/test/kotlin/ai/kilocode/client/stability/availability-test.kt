package ai.kilocode.client.stability

import ai.kilocode.stability.Clock
import ai.kilocode.stability.Draft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class AvailabilityTest {

    @Test fun `state changes split active intervals`() {
        var now = 0L
        val clock = object : Clock { override fun wall() = now; override fun mono() = now }
        val events = mutableListOf<Draft>()
        val availability = Availability(clock, events::add)
        availability.update("ws-a", true, true, "ready")
        now = 10_000
        availability.update("ws-a", true, true, "connecting")
        now = 30_000
        availability.update("ws-a", false, true, "connecting")
        assertEquals(listOf(10_000L, 20_000L), events.map { it.data.getValue("duration_ms").jsonPrimitive.long })
        now = 60_000
        availability.tick()
        assertEquals(2, events.size)
    }

    @Test fun `closed intervals do not overlap and carry workspace context`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val availability = Availability(clock, events::add)

        availability.update("ws-a", true, true, "ready")
        clock.advance(10_000)
        availability.update("ws-a", true, true, "connecting")
        clock.advance(15_000)
        availability.update("ws-a", false, true, "connecting")

        assertEquals(2, events.size)
        events.forEach { draft ->
            assertEquals("availability", draft.name)
            assertEquals("interval", draft.kind)
            assertEquals("critical", draft.channel)
            assertEquals(setOf("metrics"), draft.purposes)
            assertEquals("ws-a", draft.context.getValue("workspace_id"))
        }
        val first = events[0].data
        val second = events[1].data
        // 半开区间：前段end_timestamp与后段begin_timestamp共享边界，绝不重叠。
        assertEquals(0L, first.getValue("begin_timestamp").jsonPrimitive.long)
        assertEquals(10_000L, first.getValue("end_timestamp").jsonPrimitive.long)
        assertEquals("ready", first.getValue("state").jsonPrimitive.content)
        assertEquals(10_000L, second.getValue("begin_timestamp").jsonPrimitive.long)
        assertEquals(25_000L, second.getValue("end_timestamp").jsonPrimitive.long)
        assertEquals("connecting", second.getValue("state").jsonPrimitive.content)
        assertEquals(
            listOf(10_000L, 15_000L),
            events.map { it.data.getValue("duration_ms").jsonPrimitive.long },
        )
    }

    @Test fun `inactive observations record no interval`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val availability = Availability(clock, events::add)

        // 首次观察即不可见：不开区间，也不产出事实。
        availability.update("ws-a", false, false, "ready")
        clock.advance(60_000)
        availability.update("ws-a", true, false, "ready")
        clock.advance(60_000)
        availability.update("ws-a", true, false, "connecting")
        clock.advance(60_000)
        // 前台一直为false：后台/失联不当不可用，也没有活跃区间。
        assertEquals(0, events.size)

        clock.advance(1_000)
        availability.update("ws-a", true, true, "connecting")
        clock.advance(5_000)
        availability.update("ws-a", false, true, "connecting")
        assertEquals(1, events.size)
        assertEquals(5_000L, events.single().data.getValue("duration_ms").jsonPrimitive.long)
    }

    @Test fun `tick slices open interval and rebuilds from latest state`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val availability = Availability(clock, events::add)

        availability.update("ws-a", true, true, "ready")
        clock.advance(30_000)
        availability.tick()
        clock.advance(5_000)
        availability.update("ws-a", false, true, "ready")

        assertEquals(
            listOf(30_000L, 5_000L),
            events.map { it.data.getValue("duration_ms").jsonPrimitive.long },
        )
        assertEquals(30_000L, events[1].data.getValue("begin_timestamp").jsonPrimitive.long)
    }

    @Test fun `pause discards the untrusted open span and rebuilds the origin`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val availability = Availability(clock, events::add)

        availability.update("ws-a", true, true, "ready")
        clock.advance(20_000)
        // 挂起/休眠/调度中断：开始时刻之后的闭合不可信，丢弃整段，不产出巨大区间。
        availability.pause()
        assertEquals(0, events.size)
        clock.advance(5_000)
        availability.update("ws-a", true, true, "ready")
        clock.advance(10_000)
        availability.update("ws-a", false, true, "ready")

        assertEquals(1, events.size)
        val only = events.single().data
        assertEquals(25_000L, only.getValue("begin_timestamp").jsonPrimitive.long)
        assertEquals(35_000L, only.getValue("end_timestamp").jsonPrimitive.long)
        assertEquals(10_000L, only.getValue("duration_ms").jsonPrimitive.long)
        assertTrue(only.getValue("duration_ms").jsonPrimitive.long < 30_000L)
    }

    @Test fun `different workspaces stay independent`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val availability = Availability(clock, events::add)

        availability.update("ws-a", true, true, "ready")
        availability.update("ws-b", true, true, "ready")
        clock.advance(30_000)
        availability.tick()
        clock.advance(10_000)
        availability.update("ws-a", false, true, "ready")
        clock.advance(10_000)
        availability.tick()

        val byWorkspace = events.groupBy { it.context.getValue("workspace_id") }
        assertEquals(
            listOf(30_000L, 10_000L),
            byWorkspace.getValue("ws-a").map { it.data.getValue("duration_ms").jsonPrimitive.long },
        )
        assertEquals(
            listOf(30_000L, 20_000L),
            byWorkspace.getValue("ws-b").map { it.data.getValue("duration_ms").jsonPrimitive.long },
        )
        // 两个项目的活跃时间独立累计，区间各自不重叠。
        assertEquals(2, byWorkspace.getValue("ws-a").size)
        assertEquals(2, byWorkspace.getValue("ws-b").size)
    }
}

/** 测试用双时钟：wall与mono同步推进，区间切分断言不依赖真实时间。 */
private class MutableClock : Clock {
    var now = 0L
    override fun wall(): Long = now
    override fun mono(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}
