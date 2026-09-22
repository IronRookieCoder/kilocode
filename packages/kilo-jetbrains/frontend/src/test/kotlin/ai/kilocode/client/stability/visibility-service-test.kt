package ai.kilocode.client.stability

import ai.kilocode.client.testing.TestCoroutines
import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.testing.pumpEdt
import ai.kilocode.client.util.edtWait
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.ProfileDto
import ai.kilocode.stability.Clock
import ai.kilocode.stability.Fixture
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.coroutines.flow.emptyFlow

/**
 * M13真实平台观察（brief Step 5）：在真实IDE测试基座里驱动[VisibilityService]的全链路
 * （真实消息总线订阅、EDT路由、FIFO consumer、真实Recorder落盘）。
 *
 * 平台事件经服务的**真实监听器转发入口**驱动：onToolWindowStateChanged（工具窗状态）、
 * onApplicationActivated/onApplicationDeactivated（IDE前台）。监听器类本身在测试编译
 * 类路径上不可见（产品模块过滤），消息总线发布在测试作用域不可编译，但转发入口即
 * 监听器方法体，语义等价。工具窗可见性来源用[PanelVisibilitySource]注入（真实基座里
 * 注册的工具窗isAvailable恒false，show()不改变可见性，无法承载真实面板翻转）；
 * attach(toolWindow)的真实接线（层级监听+初始隐藏快照）另被断言。
 */
class VisibilityServiceTest : BasePlatformTestCase() {

    private class MutableClock : Clock {
        var now = 0L
        override fun wall(): Long = now
        override fun mono(): Long = now

        fun advance(ms: Long) {
            now += ms
        }
    }

    private class FlagSource : PanelVisibilitySource {
        @Volatile var visible = false
        override fun isVisible(): Boolean = visible
    }

    private val coroutines = mutableListOf<TestCoroutines>()
    private val services = mutableListOf<VisibilityService>()

    private fun newService(
        fixture: Fixture,
        clock: MutableClock,
        state: () -> String = { "ready" },
        probeHost: EdtProbeService? = null,
        app: KiloAppService? = null,
    ): VisibilityService {
        val testCoroutines = TestCoroutines().also { coroutines.add(it) }
        return VisibilityService(
            project = project,
            cs = testCoroutines.scope,
            clock = clock,
            operations = { fixture.operations },
            stateSource = { app?.let { availabilityState(it.state.value) } ?: state() },
            // 切片节奏远长于用例时长：tick语义由AvailabilityTest以纯时钟覆盖。
            tickPeriodMs = 600_000L,
            workspaceId = "ws-test",
            probeHost = probeHost,
            states = app?.state ?: emptyFlow(),
        ).also { services.add(it) }
    }

    private fun newHost(
        fixture: Fixture,
        clock: MutableClock,
        coroutines: TestCoroutines,
        dispatch: (() -> Unit) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
    ): EdtProbeService =
        EdtProbeService(
            cs = coroutines.scope,
            clock = clock,
            operations = { fixture.operations },
            dispatchToEdt = dispatch,
            // 循环周期600s在用例内不触发：tick由测试显式驱动（sleep类时效测试见类KDoc）。
            offerPeriodMs = 600_000L,
        )

    private fun edtFacts(fixture: Fixture) = fixture.facts().filter { it.name == "edt.delay" }

    private fun register(id: String): ToolWindow =
        ToolWindowManager.getInstance(project).registerToolWindow(RegisterToolWindowTask(id))

    private fun availabilityFacts(fixture: Fixture) = fixture.facts().filter { it.name == "availability" }

    /** 工具窗状态变化事件（EDT路由到consumer），返回即快照已被消费。 */
    private fun toolWindowChanged(service: VisibilityService) {
        service.onToolWindowStateChanged()
        pumpEdt()
        coroutines.single().drain()
    }

    private fun foregroundOn(service: VisibilityService) {
        service.onApplicationActivated()
        pumpEdt()
        coroutines.single().drain()
    }

    private fun foregroundOff(service: VisibilityService) {
        service.onApplicationDeactivated()
        pumpEdt()
        coroutines.single().drain()
    }

    private fun tearDownNow() {
        services.forEach { service -> runCatching { service.dispose() } }
        services.clear()
        coroutines.forEach { it.close() }
        coroutines.clear()
        pumpEdt()
    }

    fun `test availability state maps app state`() {
        assertEquals("blocked", availabilityState(KiloAppStateDto(KiloAppStatusDto.MIGRATION_REQUIRED)))
        assertEquals("blocked", availabilityState(KiloAppStateDto(KiloAppStatusDto.READY)))
        assertEquals(
            "ready",
            availabilityState(KiloAppStateDto(KiloAppStatusDto.READY, profile = ProfileDto(email = "user@example.com"))),
        )
        listOf(
            KiloAppStatusDto.CONNECTING,
            KiloAppStatusDto.DOWNLOADING,
            KiloAppStatusDto.LOADING,
        ).forEach { status ->
            assertEquals("connecting", availabilityState(KiloAppStateDto(status)))
        }
        listOf(KiloAppStatusDto.ERROR, KiloAppStatusDto.DISCONNECTED).forEach { status ->
            assertEquals("error", availabilityState(KiloAppStateDto(status)))
        }
        assertEquals("connecting", availabilityState(null))
    }

    fun `test availability state change closes blocked interval before ready interval`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val runtime = TestCoroutines()
            val app = KiloAppService(runtime.scope, null)
            app._state.value = KiloAppStateDto(KiloAppStatusDto.READY)
            val service = newService(fixture, clock, app = app)
            try {
                val panel = FlagSource()
                edtWait { service.attachPanel(panel) }
                coroutines.single().drain()

                clock.now = 1_000
                panel.visible = true
                toolWindowChanged(service)
                clock.now = 5_000
                app._state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = ProfileDto(email = "user@example.com"))
                coroutines.single().drain()
                fixture.flush()
                assertEquals("blocked", availabilityFacts(fixture).single().data.getValue("state").jsonPrimitive.content)
                clock.now = 9_000
                app._state.value = KiloAppStateDto(KiloAppStatusDto.READY)
                coroutines.single().drain()
                fixture.flush()

                val facts = availabilityFacts(fixture)
                assertEquals(2, facts.size)
                assertEquals("blocked", facts[0].data["state"]?.jsonPrimitive?.content)
                assertEquals(1_000L, facts[0].data["begin_timestamp"]?.jsonPrimitive?.long)
                assertEquals(5_000L, facts[0].data["end_timestamp"]?.jsonPrimitive?.long)
                assertEquals("ready", facts[1].data["state"]?.jsonPrimitive?.content)
                assertEquals(5_000L, facts[1].data["begin_timestamp"]?.jsonPrimitive?.long)
                assertEquals(9_000L, facts[1].data["end_timestamp"]?.jsonPrimitive?.long)
            } finally {
                tearDownNow()
                runtime.close()
            }
        }
    }

    fun `test visibility change records a single active interval`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val service = newService(fixture, clock)
            try {
                // 真实工具窗接线：层级监听注册 + 初始隐藏快照（不打开区间）。
                val toolWindow = register("CostrictVis1")
                val panel = FlagSource()
                edtWait {
                    service.attach(toolWindow)
                    service.attachPanel(panel)
                }
                coroutines.single().drain()
                fixture.flush()
                assertEquals(0, availabilityFacts(fixture).size)

                clock.now = 1_000
                panel.visible = true
                toolWindowChanged(service)
                clock.now = 11_000
                panel.visible = false
                toolWindowChanged(service)
                fixture.flush()

                val facts = availabilityFacts(fixture)
                assertEquals(1, facts.size)
                val data = facts.single().data
                assertEquals(1_000L, data["begin_timestamp"]?.jsonPrimitive?.long)
                assertEquals(11_000L, data["end_timestamp"]?.jsonPrimitive?.long)
                assertEquals(10_000L, data["duration_ms"]?.jsonPrimitive?.long)
                assertEquals("ready", data["state"]?.jsonPrimitive?.content)
                assertEquals("ws-test", facts.single().context["workspace_id"])
            } finally {
                tearDownNow()
            }
        }
    }

    fun `test dual panels of one project union their visibility`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val service = newService(fixture, clock)
            try {
                val first = FlagSource()
                val second = FlagSource()
                edtWait {
                    service.attach(register("CostrictVis2a"))
                    service.attachPanel(first)
                    service.attachPanel(second)
                }
                coroutines.single().drain()
                fixture.flush()
                assertEquals(0, availabilityFacts(fixture).size)

                clock.now = 1_000
                first.visible = true
                toolWindowChanged(service)
                clock.now = 5_000
                second.visible = true
                toolWindowChanged(service)
                clock.now = 9_000
                first.visible = false
                toolWindowChanged(service)
                clock.now = 15_000
                second.visible = false
                toolWindowChanged(service)
                fixture.flush()

                // 并集：中途一隐一显不切分区间，全部不可见才闭合，单段覆盖整个活跃跨度。
                val facts = availabilityFacts(fixture)
                assertEquals(1, facts.size)
                assertEquals(1_000L, facts.single().data["begin_timestamp"]?.jsonPrimitive?.long)
                assertEquals(14_000L, facts.single().data["duration_ms"]?.jsonPrimitive?.long)
            } finally {
                tearDownNow()
            }
        }
    }

    fun `test ide background opens no active interval`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val service = newService(fixture, clock)
            try {
                val toolWindow = register("CostrictVis3")
                val panel = FlagSource()
                foregroundOff(service)
                edtWait {
                    service.attach(toolWindow)
                    service.attachPanel(panel)
                }
                coroutines.single().drain()
                fixture.flush()
                assertEquals(0, availabilityFacts(fixture).size)

                // 面板可见但IDE在后台：后台不当不可用，也没有活跃区间。
                clock.now = 5_000
                panel.visible = true
                toolWindowChanged(service)
                fixture.flush()
                assertEquals(0, availabilityFacts(fixture).size)

                // 回前台：区间打开；再次切走：区间闭合（12秒）。
                clock.now = 8_000
                foregroundOn(service)
                clock.now = 20_000
                foregroundOff(service)
                fixture.flush()

                val facts = availabilityFacts(fixture)
                assertEquals(1, facts.size)
                assertEquals(8_000L, facts.single().data["begin_timestamp"]?.jsonPrimitive?.long)
                assertEquals(12_000L, facts.single().data["duration_ms"]?.jsonPrimitive?.long)
            } finally {
                tearDownNow()
            }
        }
    }

    /**
     * M20真实EDT验证（brief Step 4）：显式latch阻塞EDT，后台确认投递在途、事实未产出后
     * 再释放——watchdog只有10s防挂死作用；排队时长全部由可变时钟口径表达，非sleep类
     * 时效断言。complete回调在EDT上只读完成时刻，Draft构造与record经post回到后台。
     */
    fun `test edt probe sample waits for blocked edt and completes on release`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val testCoroutines = TestCoroutines().also { coroutines.add(it) }
            val host = newHost(fixture, clock, testCoroutines)
            val service = VisibilityService(
                project = project,
                cs = testCoroutines.scope,
                clock = clock,
                operations = { fixture.operations },
                stateSource = { "ready" },
                tickPeriodMs = 600_000L,
                workspaceId = "ws-test",
                probeHost = host,
            )
            try {
                val toolWindow = register("CostrictProbeEdt")
                val panel = FlagSource()
                edtWait {
                    service.attach(toolWindow)
                    service.attachPanel(panel)
                }
                clock.now = 100
                panel.visible = true
                toolWindowChanged(service)

                // 阶段1：显式latch阻塞真实EDT（10s watchdog仅防挂死）。测试主体运行在EDT上，
                // 阻塞场景交给后台线程，主体以pump驱动事件队列（gate在pump内真实占住EDT）。
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val finished = CountDownLatch(1)
                var backgroundFailure: Throwable? = null
                thread {
                    try {
                        ApplicationManager.getApplication().invokeLater {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                        assertTrue(entered.await(10, TimeUnit.SECONDS))

                        // 阶段2：后台确认投递（EDT被阻塞，callback只能排队）——无事实产出。
                        host.tick()
                        fixture.flush()
                        assertEquals(0, edtFacts(fixture).size)

                        // 阶段3：阻塞期间推进时钟，释放EDT——callback按队列顺序执行。
                        clock.now = 3_100
                    } catch (t: Throwable) {
                        backgroundFailure = t
                    } finally {
                        release.countDown()
                        finished.countDown()
                    }
                }
                while (!finished.await(50, TimeUnit.MILLISECONDS)) pumpEdt()
                backgroundFailure?.let { throw it }

                pumpEdt()
                testCoroutines.drain()
                fixture.flush()

                val facts = edtFacts(fixture)
                assertEquals(1, facts.size)
                val data = facts.single().data
                assertEquals(VALIDITY_VALID, data.getValue("validity").jsonPrimitive.content)
                assertEquals(100L, data.getValue("scheduled_mono_ms").jsonPrimitive.long)
                assertEquals(3_100L, data.getValue("completed_mono_ms").jsonPrimitive.long)
                assertEquals(3_000L, data.getValue("duration_ms").jsonPrimitive.long)
                assertEquals(1L, data.getValue("probe_seq").jsonPrimitive.long)
                UUID.fromString(data.getValue("observation_id").jsonPrimitive.content)
            } finally {
                tearDownNow()
            }
        }
    }

    /** 多服务/多面板共用一个JVM探针：任一贡献在即继续投递（同区间连续序号），全撤才关闭。 */
    fun `test probe enablement unions across two services and panels`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val testCoroutines = TestCoroutines().also { coroutines.add(it) }
            val delivered = mutableListOf<() -> Unit>()
            val host = newHost(fixture, clock, testCoroutines) { delivered.add(it) }
            val serviceA = VisibilityService(
                project = project,
                cs = testCoroutines.scope,
                clock = clock,
                operations = { fixture.operations },
                stateSource = { "ready" },
                tickPeriodMs = 600_000L,
                workspaceId = "ws-a",
                probeHost = host,
            )
            val serviceB = VisibilityService(
                project = project,
                cs = testCoroutines.scope,
                clock = clock,
                operations = { fixture.operations },
                stateSource = { "ready" },
                tickPeriodMs = 600_000L,
                workspaceId = "ws-b",
                probeHost = host,
            )
            try {
                val panelA1 = FlagSource()
                val panelA2 = FlagSource()
                val panelB = FlagSource()
                edtWait {
                    serviceA.attach(register("CostrictProbeA"))
                    serviceA.attachPanel(panelA1)
                    serviceA.attachPanel(panelA2)
                    serviceB.attach(register("CostrictProbeB"))
                    serviceB.attachPanel(panelB)
                }
                foregroundOn(serviceA)
                foregroundOn(serviceB)
                coroutines.single().drain()

                // 每次tick投递一个样本并就地完成（capturing dispatch），保证区间连续
                fun tickSample() {
                    host.tick()
                    delivered.removeAt(0).invoke()
                }
                panelA1.visible = true
                toolWindowChanged(serviceA)
                tickSample()
                panelA1.visible = false
                panelA2.visible = true
                toolWindowChanged(serviceA)
                tickSample()
                panelB.visible = true
                toolWindowChanged(serviceB)
                // A全隐、B仍可见：项目间并集保持观测区间连续
                panelA2.visible = false
                toolWindowChanged(serviceA)
                tickSample()
                panelB.visible = false
                toolWindowChanged(serviceB)
                // 最后一个贡献撤除：探针关闭，此后tick不再投递
                host.tick()
                pumpEdt()
                coroutines.single().drain()
                fixture.flush()
                assertEquals(0, delivered.size)

                val facts = edtFacts(fixture)
                assertEquals(3, facts.size)
                val interval = facts[0].data.getValue("observation_id").jsonPrimitive.content
                facts.forEach { fact ->
                    assertEquals(VALIDITY_VALID, fact.data.getValue("validity").jsonPrimitive.content)
                    assertEquals(interval, fact.data.getValue("observation_id").jsonPrimitive.content)
                }
                assertEquals(listOf(1L, 2L, 3L), facts.map { it.data.getValue("probe_seq").jsonPrimitive.long })
            } finally {
                services.forEach { service -> runCatching { service.dispose() } }
                services.clear()
                coroutines.forEach { it.close() }
                coroutines.clear()
                pumpEdt()
            }
        }
    }

    /** pause（挂起/休眠/调度中断语义入口）撤除贡献：在途样本按unknown作废，迟到callback不计valid。 */
    fun `test service pause invalidates the pending probe sample`() {
        Fixture().use { fixture ->
            val clock = MutableClock()
            val testCoroutines = TestCoroutines().also { coroutines.add(it) }
            val delivered = mutableListOf<() -> Unit>()
            val host = newHost(fixture, clock, testCoroutines) { delivered.add(it) }
            val service = VisibilityService(
                project = project,
                cs = testCoroutines.scope,
                clock = clock,
                operations = { fixture.operations },
                stateSource = { "ready" },
                tickPeriodMs = 600_000L,
                workspaceId = "ws-test",
                probeHost = host,
            )
            try {
                val toolWindow = register("CostrictProbePause")
                val panel = FlagSource()
                edtWait {
                    service.attach(toolWindow)
                    service.attachPanel(panel)
                }
                clock.now = 100
                panel.visible = true
                toolWindowChanged(service)

                host.tick()
                assertEquals(1, delivered.size)

                service.pause()
                fixture.flush()
                assertEquals(1, edtFacts(fixture).size)
                delivered.removeAt(0).invoke()
                fixture.flush()

                val facts = edtFacts(fixture)
                assertEquals(1, facts.size)
                assertEquals(VALIDITY_UNKNOWN, facts.single().data.getValue("validity").jsonPrimitive.content)
                host.tick()
                fixture.flush()
                // pause后贡献未恢复（无新快照前不再投递）
                assertEquals(1, edtFacts(fixture).size)
            } finally {
                tearDownNow()
            }
        }
    }
}
