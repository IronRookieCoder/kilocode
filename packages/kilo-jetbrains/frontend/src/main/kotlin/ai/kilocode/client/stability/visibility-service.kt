package ai.kilocode.client.stability

import ai.kilocode.client.app.KiloAppService
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.stability.Clock
import ai.kilocode.stability.Operations
import ai.kilocode.stability.StabilityService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.Component
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TICK_PERIOD_MS = 30_000L

/** availability状态闭集（字典AVAILABILITY_STATES）：快照到不了的过渡/未知一律按connecting。 */
private const val STATE_READY = "ready"
private const val STATE_CONNECTING = "connecting"
private const val STATE_BLOCKED = "blocked"
private const val STATE_ERROR = "error"

/** 纳秒到毫秒换算（mono时钟口径与shared一致）。 */
private const val NANOS_PER_MS = 1_000_000L

/** 前端生产时钟（wall=UTC毫秒、mono=单调毫秒）；shared的SystemClock是internal，前端独立定义。C3起probe.kt共用。 */
internal object FrontendClock : Clock {
    override fun wall(): Long = System.currentTimeMillis()
    override fun mono(): Long = System.nanoTime() / NANOS_PER_MS
}

/** 项目内一个插件面板的可见性来源（当前生产实现为工具窗；EDT读取）。 */
internal fun interface PanelVisibilitySource {
    @RequiresEdt
    fun isVisible(): Boolean
}

/**
 * consumer事件：refresh携带EDT读取的UI快照（可见性并集、IDE前台、最新状态），[tick]
 * 标记30秒切片事件；[pause]是挂起/休眠/调度中断信号（丢弃在途区间，不产出事实）。
 */
private class Event(
    val visible: Boolean,
    val foreground: Boolean,
    val state: String,
    val tick: Boolean,
    val pause: Boolean,
)

/**
 * M13项目级观察装配（brief Step 4）：聚合**本项目全部插件面板**的可见性（并集），驱动
 * 每项目**一个**[Availability]（不是每面板各建一个）；workspace_id是本观察上下文的随机
 * ID（不同项目各一个，活跃时间独立累计、可相加，但不是机器在线时长）。
 *
 * 观察输入与公开监听（逐字brief）：
 * - [ApplicationActivationListener.TOPIC]：IDE应用前台/后台（app级事件，frame参数不参与
 *   判定；后台/睡眠不当不可用，只是不再打开活跃区间）。
 * - [ToolWindowManagerListener]公开[stateChanged]（只覆盖单参公开重载，不碰internal
 *   带事件类型参数的重载）：工具窗显示/隐藏。
 * - 已有组件的[HierarchyListener]：挂接在工具窗根组件上，补齐showing层级变化。
 *
 * 线程纪律：回调若不在EDT，经[ToolWindowManager.invokeLater]路由到EDT再读可见性；
 * EDT只读UI快照并入队（trySend非阻塞），由[cs]上**单一**consumer按FIFO顺序apply并
 * record——区间状态只在consumer线程演进，EDT绝不触碰区间。
 *
 * M20（C3）：本服务同时是[EdtProbeService]（单JVM探针所有者）的项目贡献方——consumer
 * 把"本项目任一面板可见且IDE前台"的并集快照推给它，项目间并集决定唯一探针的启停；
 * 失焦/pause/dispose撤除贡献使在途样本作废并更换observation_id。
 *
 * 前台初值：attach由工具窗创建（用户驱动的UI事件）触发，此刻IDE几乎总在前台，故初始
 * 观察前台=true，并在第一个applicationDeactivated自我纠正（该事件一到达即关闭在途区间）。
 * 每[tickPeriodMs]一次tick：EDT读快照、后台切片（设计6.2"每30秒及切换时记录不重叠区间"）。
 *
 * 采集不可用（operations返回null）时区间状态照常演进，仅丢弃事实——绝不阻塞UI回调。
 */
@Service(Service.Level.PROJECT)
@Suppress("LongParameterList")
internal class VisibilityService(
    private val project: Project,
    private val cs: CoroutineScope,
    private val clock: Clock,
    private val operations: () -> Operations?,
    private val stateSource: () -> String,
    private val tickPeriodMs: Long,
    private val workspaceId: String,
    private val probeHost: EdtProbeService? = null,
) : Disposable {

    /** Platform constructor — resolves collaborators from the service container. */
    constructor(project: Project, cs: CoroutineScope) : this(
        project,
        cs,
        FrontendClock,
        operations = ::defaultOperations,
        stateSource = ::currentAvailabilityState,
        tickPeriodMs = TICK_PERIOD_MS,
        workspaceId = newWorkspaceId(),
        probeHost = service<EdtProbeService>(),
    )

    private val availability = Availability(clock) { draft -> operations()?.record(draft) }
    private val events = Channel<Event>(Channel.BUFFERED)

    /** EDT-only：面板可见性来源（每attach的面板一个），并集判定。 */
    private val visibilitySources = mutableSetOf<PanelVisibilitySource>()
    private val hierarchyListeners = mutableListOf<Pair<Component, HierarchyListener>>()

    @Volatile private var foreground = true

    /** dispose后拒绝迟到的consumer事件重新打开探针贡献（项目关闭后贡献只能撤除）。 */
    @Volatile private var disposed = false

    init {
        project.messageBus.connect(this).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) = onToolWindowStateChanged()
            },
        )
        ApplicationManager.getApplication()?.messageBus?.connect(this)?.subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = onApplicationActivated()

                override fun applicationDeactivated(ideFrame: IdeFrame) = onApplicationDeactivated()
            },
        )
        cs.launch { for (event in events) apply(event) }
        cs.launch {
            while (true) {
                delay(tickPeriodMs)
                routeToEdt(tick = true)
            }
        }
    }

    /**
     * 工具窗/面板创建时挂接（必须在EDT）：注册该工具窗的可见性来源（并集成员之一），
     * 并在其根组件上加HierarchyListener（brief：已有组件的层级监听）补齐showing层级
     * 变化。同一服务可attach多个面板，活跃区间始终只有一条。
     */
    @RequiresEdt
    fun attach(toolWindow: ToolWindow) {
        val component = toolWindow.component
        val listener = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) routeToEdt(tick = false)
        }
        component.addHierarchyListener(listener)
        hierarchyListeners += component to listener
        attachPanel { toolWindow.isVisible }
    }

    /**
     * 注册一个面板可见性来源（必须在EDT）。项目下任意插件面板（工具窗、面板容器等）
     * 都经此入册；多个同项目面板对可见性取**并集**，全部不可见才算项目不可见。
     */
    @RequiresEdt
    internal fun attachPanel(source: PanelVisibilitySource) {
        visibilitySources += source
        refresh(tick = false)
    }

    /**
     * 挂起/休眠/调度中断观察起点重建（C3按平台信号接线）：在途区间的真实闭合时刻不可
     * 确认，整段丢弃（不产出事实）；经同一FIFO队列生效，先于任何后续快照。
     * C3：同时撤除M20探针贡献（本项目暂停即并集可能关闭）——当前pending按unknown作废
     * 并更换observation_id（平台休眠通知经G1确认前不用suspended，休眠归unknown）。
     */
    fun pause() {
        probeHost?.setActive(this, false)
        events.trySend(Event(visible = false, foreground = false, state = STATE_CONNECTING, tick = false, pause = true))
    }

    /** 工具窗状态变化（监听器转发入口，测试亦驱动此入口）：在EDT重读可见性快照。 */
    internal fun onToolWindowStateChanged() {
        routeToEdt(tick = false)
    }

    /**
     * IDE应用回前台（监听器转发入口，测试亦驱动此入口）：更新app级前台标记并重读快照。
     * frame参数不参与app级前台判定，故入口无需参数。
     */
    internal fun onApplicationActivated() {
        foreground = true
        routeToEdt(tick = false)
    }

    /** IDE应用切后台：后台不当不可用，只是关闭在途活跃区间并不再打开新的区间。 */
    internal fun onApplicationDeactivated() {
        foreground = false
        routeToEdt(tick = false)
    }

    override fun dispose() {
        disposed = true
        // C3：项目关闭即撤除探针贡献；若它曾是最后一个活跃贡献，JVM探针随之关闭
        // （pending按unknown作废并轮换区间），多项目时任一存活项目保持探针开启。
        probeHost?.setActive(this, false)
        events.close()
        hierarchyListeners.forEach { (component, listener) -> component.removeHierarchyListener(listener) }
        hierarchyListeners.clear()
    }

    /** 非EDT回调统一经ToolWindowManager.invokeLater路由；已在EDT则直接读快照。 */
    private fun routeToEdt(tick: Boolean) {
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDispatchThread) {
            refresh(tick)
        } else {
            runCatching { ToolWindowManager.getInstance(project).invokeLater { refresh(tick) } }
        }
    }

    /** EDT只读UI快照：可见性并集 + 最新状态，随后入队交后台record（绝不在此触碰区间）。 */
    @RequiresEdt
    private fun refresh(tick: Boolean) {
        val visible = visibilitySources.any { source -> source.isVisible() }
        events.trySend(Event(visible, foreground, stateSource(), tick, pause = false))
    }

    /** consumer线程（唯一区间演进点）：先按快照推进状态机，tick再切片，pause只丢弃。 */
    private fun apply(event: Event) {
        if (event.pause) {
            availability.pause()
            return
        }
        availability.update(workspaceId, event.visible, event.foreground, event.state)
        // C3：本项目"任一面板可见且IDE前台"作为M20探针贡献推送（JVM级并集见EdtProbeService）。
        if (!disposed) probeHost?.setActive(this, event.visible && event.foreground)
        if (event.tick) availability.tick()
    }
}

/** app状态→availability状态闭集；采集依赖不可用/过渡态按connecting（仍计活跃）。 */
private fun currentAvailabilityState(): String {
    val state = runCatching { service<KiloAppService>().state.value }.getOrNull()
    return availabilityState(state)
}

internal fun availabilityState(state: KiloAppStateDto?): String = when {
    state?.status == KiloAppStatusDto.MIGRATION_REQUIRED -> STATE_BLOCKED
    state?.status == KiloAppStatusDto.READY && state.profile == null -> STATE_BLOCKED
    state?.status == KiloAppStatusDto.READY -> STATE_READY
    state?.status == KiloAppStatusDto.ERROR || state?.status == KiloAppStatusDto.DISCONNECTED -> STATE_ERROR
    else -> STATE_CONNECTING
}

/** 平台默认采集入口：每次emit时定位，绝不缓存其他service实例（P0结构约定）。C3起probe.kt共用。 */
internal fun defaultOperations(): Operations? =
    runCatching { serviceIfCreated<StabilityService>()?.operations }.getOrNull()

/** 本观察上下文的随机workspace ID（32位hex；绝不携带项目路径）。 */
private fun newWorkspaceId(): String = UUID.randomUUID().toString().replace("-", "")
