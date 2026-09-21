package ai.kilocode.client.stability

import ai.kilocode.client.testing.TestCoroutines
import ai.kilocode.client.testing.pumpEdt
import ai.kilocode.client.util.edtWait
import ai.kilocode.stability.Clock
import ai.kilocode.stability.Fixture
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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

    private fun newService(fixture: Fixture, clock: MutableClock): VisibilityService {
        val testCoroutines = TestCoroutines().also { coroutines.add(it) }
        return VisibilityService(
            project = project,
            cs = testCoroutines.scope,
            clock = clock,
            operations = { fixture.operations },
            stateSource = { "ready" },
            // 切片节奏远长于用例时长：tick语义由AvailabilityTest以纯时钟覆盖。
            tickPeriodMs = 600_000L,
            workspaceId = "ws-test",
        ).also { services.add(it) }
    }

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
}
