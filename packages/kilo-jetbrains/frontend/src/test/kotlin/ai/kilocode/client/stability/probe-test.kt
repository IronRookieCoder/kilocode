package ai.kilocode.client.stability

import ai.kilocode.client.testing.TestCoroutines
import ai.kilocode.stability.Clock
import ai.kilocode.stability.Draft
import ai.kilocode.stability.Fixture
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 真实平台EDT/后台调度器验证2秒阈值与阻塞栈；其余状态机用可变时钟同步驱动。
 * 真实阻塞用latch和executor屏障同步；唯一时间等待即被测阈值本身。
 */
class ProbeTest : BasePlatformTestCase() {

    private val timeout = 15L

    fun `test real scheduler captures the blocked edt before recovery`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.enableDiagnostics()
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val finished = CompletableFuture<Unit>()
            val thread = Thread.currentThread()
            val start = System.nanoTime()
            val clock = object : Clock {
                override fun wall() = System.currentTimeMillis()
                override fun mono() = (System.nanoTime() - start) / 1_000_000
            }
            val host = EdtProbeService(scope, clock, { fixture.operations }, post = { task ->
                scope.launch {
                    task()
                    // 时间仅驱动真实的2秒阈值；本轮后台检查已执行才释放EDT。
                    if (clock.mono() >= 2_500) release.countDown()
                }
            }, dispatchToEdt = { task ->
                ApplicationManager.getApplication().invokeLater {
                    task()
                    completed.countDown()
                }
            }, offerPeriodMs = 50)
            try {
                ApplicationManager.getApplication().invokeLater {
                    entered.countDown()
                    blocked(release, 80)
                }
                Thread.ofPlatform().start {
                    try {
                        check(entered.await(timeout, TimeUnit.SECONDS)) { "EDT did not enter blocking frame" }
                        host.setActive(this, true)
                        check(completed.await(timeout, TimeUnit.SECONDS)) { "EDT probe callback did not complete" }
                        host.setActive(this, false)
                        // 单线程executor barrier保证关闭命令和所有finalize已被消费。
                        scope.launch { finished.complete(Unit) }
                    } catch (error: Throwable) {
                        finished.completeExceptionally(error)
                    } finally {
                        release.countDown()
                    }
                }
                UIUtil.dispatchAllInvocationEvents()
                finished.get(timeout, TimeUnit.SECONDS)
                fixture.flush()
                val facts = fixture.facts()
                val stall = facts.single { it.name == "edt.stall" }
                val incident = facts.single { it.name == "diagnostic.reported" }
                assertEquals(incident.context["incident_id"], stall.context["incident_id"])
                assertEquals(thread.name, incident.data.getValue("thread_name").jsonPrimitive.content)
                assertEquals(thread.threadId(), incident.data.getValue("thread_id").jsonPrimitive.long)
                val stack = fixture.payload("edt_stack")
                assertEquals(81, stack.lineSequence().count { it.contains("ProbeTest.blocked(") })
                assertTrue(stack.contains("CountDownLatch.await"))
                assertTrue(stack.contains("java.awt.EventDispatchThread"))
                assertEquals(1, facts.count { it.name == "diagnostic.reported" })
            } finally {
                release.countDown()
                scope.cancel()
                dispatcher.close()
            }
        }
    }

    private fun blocked(release: CountDownLatch, depth: Int) {
        if (depth > 0) {
            blocked(release, depth - 1)
            return
        }
        check(release.await(timeout, TimeUnit.SECONDS)) { "Watchdog did not release the blocked EDT" }
    }

    private class MutableClock : Clock {
        var now = 0L
        override fun wall(): Long = now
        override fun mono(): Long = now
    }

    fun `test one delayed callback represents the entire wait`() {
        var now = 0L
        val clock = object : Clock { override fun wall() = now; override fun mono() = now }
        val events = mutableListOf<Draft>()
        val probe = Probe(clock, events::add)
        probe.enabled(true)
        val seq = checkNotNull(probe.offer())
        now = 1000
        assertNull(probe.offer())
        now = 3000
        probe.complete(seq)
        assertEquals(1, events.size)
        assertEquals(3000L, events.single().data.getValue("duration_ms").jsonPrimitive.long)
        assertEquals("valid", events.single().data.getValue("validity").jsonPrimitive.content)
    }

    fun `test offer is rejected while pending and seq advances only on delivery`() {
        var now = 0L
        val clock = object : Clock { override fun wall() = now; override fun mono() = now }
        val events = mutableListOf<Draft>()
        val probe = Probe(clock, events::add)
        probe.enabled(true)
        assertEquals(1L, checkNotNull(probe.offer()))
        // 已pending不投递；被拒绝的offer不消耗序号也不产出事实
        assertNull(probe.offer())
        assertNull(probe.offer())
        assertEquals(0, events.size)
        probe.complete(1L)
        assertEquals(1, events.size)
        assertEquals(2L, checkNotNull(probe.offer()))
        assertEquals(1, events.size)
    }

    fun `test interrupt invalidates the pending sample and rotates the observation id`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val probe = Probe(clock, events::add)
        probe.enabled(true)
        val first = checkNotNull(probe.offer())
        clock.now = 500
        probe.interrupt(VALIDITY_SCHEDULER_GAP)
        assertEquals(1, events.size)
        val invalid = events.single()
        assertEquals(VALIDITY_SCHEDULER_GAP, invalid.data.getValue("validity").jsonPrimitive.content)
        assertEquals(first, invalid.data.getValue("probe_seq").jsonPrimitive.long)
        assertEquals(0L, invalid.data.getValue("scheduled_mono_ms").jsonPrimitive.long)
        assertEquals(500L, invalid.data.getValue("completed_mono_ms").jsonPrimitive.long)
        val firstInterval = invalid.data.getValue("observation_id").jsonPrimitive.content
        UUID.fromString(firstInterval)

        clock.now = 1_000
        val second = checkNotNull(probe.offer())
        clock.now = 1_500
        probe.complete(second)
        assertEquals(2, events.size)
        val valid = events.last()
        assertEquals(VALIDITY_VALID, valid.data.getValue("validity").jsonPrimitive.content)
        assertNotEquals(firstInterval, valid.data.getValue("observation_id").jsonPrimitive.content)
        assertEquals(500L, valid.data.getValue("duration_ms").jsonPrimitive.long)
    }

    fun `test stale callback does not become valid in a new observation interval`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val probe = Probe(clock, events::add)
        probe.enabled(true)
        val stale = checkNotNull(probe.offer())
        clock.now = 100
        probe.enabled(false)
        assertEquals(VALIDITY_UNKNOWN, events.single().data.getValue("validity").jsonPrimitive.content)
        probe.enabled(true)
        clock.now = 200
        val fresh = checkNotNull(probe.offer())
        clock.now = 400
        probe.complete(stale)
        assertEquals(1, events.size)
        probe.complete(fresh)
        assertEquals(2, events.size)
        assertEquals(VALIDITY_VALID, events.last().data.getValue("validity").jsonPrimitive.content)
        assertEquals(200L, events.last().data.getValue("duration_ms").jsonPrimitive.long)
    }

    fun `test disable invalidates pending and rejects offers until re-enabled`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val probe = Probe(clock, events::add)
        probe.enabled(true)
        val seq = checkNotNull(probe.offer())
        probe.enabled(false)
        assertNull(probe.offer())
        assertEquals(VALIDITY_UNKNOWN, events.single().data.getValue("validity").jsonPrimitive.content)
        assertEquals(seq, events.single().data.getValue("probe_seq").jsonPrimitive.long)
        probe.enabled(true)
        clock.now = 50
        val resumed = checkNotNull(probe.offer())
        probe.complete(resumed)
        assertEquals(2, events.size)
        assertNotEquals(
            events.first().data.getValue("observation_id").jsonPrimitive.content,
            events.last().data.getValue("observation_id").jsonPrimitive.content,
        )
    }

    /** suspended词表仅随所有者显式传入产出（平台休眠通知经G1确认前生产不使用）。 */
    fun `test suspend validity is carried only when the owner reports it`() {
        val clock = MutableClock()
        val events = mutableListOf<Draft>()
        val probe = Probe(clock, events::add)
        probe.enabled(true)
        val seq = checkNotNull(probe.offer())
        clock.now = 10
        probe.interrupt(VALIDITY_SUSPENDED)
        assertEquals(VALIDITY_SUSPENDED, events.single().data.getValue("validity").jsonPrimitive.content)
        assertEquals(seq, events.single().data.getValue("probe_seq").jsonPrimitive.long)
    }

    private fun newHost(
        fixture: Fixture,
        clock: Clock,
        coroutines: TestCoroutines,
        dispatch: (() -> Unit) -> Unit = { it() },
    ): EdtProbeService = EdtProbeService(
        cs = coroutines.scope,
        clock = clock,
        operations = { fixture.operations },
        post = { it() },
        dispatchToEdt = dispatch,
        offerPeriodMs = 600_000L,
    )

    private fun edtFacts(fixture: Fixture) = fixture.facts().filter { it.name == "edt.delay" }

    fun `test host keeps the probe enabled while any contributor remains`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val coroutines = TestCoroutines()
            try {
                val host = newHost(fixture, clock, coroutines)
                val first = Any()
                val second = Any()
                host.setActive(first, true)
                host.setActive(second, true)
                host.tick()
                host.setActive(first, false)
                host.tick()
                host.setActive(second, false)
                host.tick()
                fixture.flush()

                // 并集：任一贡献在即继续投递；最后一个撤除即关闭（无第三条事实）
                val facts = edtFacts(fixture)
                assertEquals(2, facts.size)
                assertEquals(VALIDITY_VALID, facts[0].data.getValue("validity").jsonPrimitive.content)
                val interval = facts[0].data.getValue("observation_id").jsonPrimitive.content
                assertEquals(interval, facts[1].data.getValue("observation_id").jsonPrimitive.content)
                assertEquals(listOf(1L, 2L), facts.map { it.data.getValue("probe_seq").jsonPrimitive.long })
            } finally {
                coroutines.close {}
            }
        }
    }

    fun `test last contributor detach invalidates the pending sample`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val coroutines = TestCoroutines()
            try {
                val delivered = mutableListOf<() -> Unit>()
                val host = newHost(fixture, clock, coroutines) { delivered.add(it) }
                val owner = Any()
                host.setActive(owner, true)
                host.tick()
                assertEquals(1, delivered.size)
                host.setActive(owner, false)
                delivered.removeAt(0).invoke()
                fixture.flush()

                val facts = edtFacts(fixture)
                assertEquals(1, facts.size)
                assertEquals(VALIDITY_UNKNOWN, facts.single().data.getValue("validity").jsonPrimitive.content)
            } finally {
                coroutines.close {}
            }
        }
    }

    fun `test scheduler gap beyond tolerance invalidates the pending sample`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val coroutines = TestCoroutines()
            try {
                val delivered = mutableListOf<() -> Unit>()
                val host = newHost(fixture, clock, coroutines) { delivered.add(it) }
                host.setActive(Any(), true)
                host.tick()
                clock.now = 2_001L
                host.tick()
                assertEquals(2, delivered.size)

                // 断层前的旧callback：不计入新观测区间
                delivered.removeAt(0).invoke()
                fixture.flush()
                val facts = edtFacts(fixture)
                assertEquals(1, facts.size)
                assertEquals(VALIDITY_SCHEDULER_GAP, facts.single().data.getValue("validity").jsonPrimitive.content)
                val gapInterval = facts.single().data.getValue("observation_id").jsonPrimitive.content

                // 断层tick自身的新投递：正常完成，落在更换后的观测区间
                delivered.removeAt(0).invoke()
                fixture.flush()
                val after = edtFacts(fixture)
                assertEquals(2, after.size)
                assertEquals(VALIDITY_VALID, after[1].data.getValue("validity").jsonPrimitive.content)
                assertNotEquals(gapInterval, after[1].data.getValue("observation_id").jsonPrimitive.content)
            } finally {
                coroutines.close {}
            }
        }
    }

    fun `test scheduling interval at tolerance boundary does not invalidate`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val coroutines = TestCoroutines()
            try {
                val delivered = mutableListOf<() -> Unit>()
                val host = newHost(fixture, clock, coroutines) { delivered.add(it) }
                host.setActive(Any(), true)
                host.tick()
                clock.now = 2_000L
                host.tick()
                // 恰好2000ms不算断层；单一pending也不重复投递
                assertEquals(1, delivered.size)
                fixture.flush()
                assertEquals(0, edtFacts(fixture).size)

                delivered.removeAt(0).invoke()
                fixture.flush()
                val facts = edtFacts(fixture)
                assertEquals(1, facts.size)
                assertEquals(VALIDITY_VALID, facts.single().data.getValue("validity").jsonPrimitive.content)
                assertEquals(2_000L, facts.single().data.getValue("duration_ms").jsonPrimitive.long)
            } finally {
                coroutines.close {}
            }
        }
    }

    /** 两次重叠valid样本（同obs、seq连续、首尾相接合计≥2s）在观测终点合并产出一条edt.stall。 */
    fun `test overlapping valid samples merge into one stall fact when the observation ends`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val coroutines = TestCoroutines()
            try {
                val delivered = mutableListOf<() -> Unit>()
                val host = newHost(fixture, clock, coroutines) { delivered.add(it) }
                val owner = Any()
                host.setActive(owner, true)
                host.tick() // 样本1：scheduled=0
                clock.now = 1_500
                delivered.removeAt(0).invoke() // 窗口0..1_500（1.5s）
                host.tick() // 样本2：scheduled=1_500（首尾相接）
                clock.now = 3_000
                delivered.removeAt(0).invoke() // 合并窗口0..3_000（3s）
                host.setActive(owner, false) // 观测终点：终结stall窗口并产出edt.stall
                fixture.flush()

                val delays = edtFacts(fixture)
                assertEquals(2, delays.size)
                assertEquals(
                    listOf(1_500L, 1_500L),
                    delays.map { it.data.getValue("duration_ms").jsonPrimitive.long },
                )
                val stalls = fixture.facts().filter { it.name == "edt.stall" }
                assertEquals(1, stalls.size)
                assertEquals("sample", stalls.single().kind)
                assertEquals("critical", stalls.single().channel)
                assertEquals(setOf("metrics"), stalls.single().purposes)
                assertEquals(3_000L, stalls.single().data.getValue("duration_ms").jsonPrimitive.long)
                assertEquals(
                    delays[0].data.getValue("observation_id").jsonPrimitive.content,
                    stalls.single().data.getValue("observation_id").jsonPrimitive.content,
                )
            } finally {
                coroutines.close {}
            }
        }
    }

    fun `test edt violation draft carries registered operation and fixed evidence only`() {
        Fixture().use { fixture ->
            // operation=session是字典已登记token（API_GROUPS的session组，M19同词表）
            fixture.operations.record(edtViolationDraft("session"))
            fixture.flush()
            val facts = fixture.facts().filter { it.name == "edt.violation" }
            assertEquals(1, facts.size)
            assertEquals("diagnostic", facts.single().kind)
            assertEquals("session", facts.single().data.getValue("operation").jsonPrimitive.content)
            assertEquals(
                EVIDENCE_PLATFORM_THREAD_ASSERTION,
                facts.single().data.getValue("evidence").jsonPrimitive.content,
            )
            assertEquals(setOf("metrics", "logs"), facts.single().purposes)
            assertTrue(facts.single().data.keys.all { it == "operation" || it == "evidence" })
        }
    }
}
