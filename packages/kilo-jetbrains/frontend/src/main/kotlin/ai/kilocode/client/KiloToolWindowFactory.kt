package ai.kilocode.client

import ai.kilocode.client.app.KiloWorkspaceService
import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.app.Workspace
import ai.kilocode.client.app.KiloSessionService
import ai.kilocode.client.app.KiloChatAccess
import ai.kilocode.client.session.SessionManager
import ai.kilocode.client.session.SessionSidePanelManager
import ai.kilocode.client.stability.ReadinessWatch
import ai.kilocode.stability.Faults
import ai.kilocode.stability.Operations
import ai.kilocode.client.agentManager.worktree.KiloWorktreeService
import ai.kilocode.client.agentManager.SidePanelKeys
import ai.kilocode.client.agentManager.SidePanelMode
import ai.kilocode.client.agentManager.applySidePanelMode
import ai.kilocode.client.agentManager.worktree.WorktreeController
import ai.kilocode.client.agentManager.AgentManagerPanel
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.stability.Operation
import ai.kilocode.stability.StabilityService
import ai.kilocode.log.KiloLog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.project.projectIdOrNull
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.ComponentOrientation
import javax.swing.JPanel

/**
 * Creates the Costrict tool window and delegates session content management.
 *
 * Resolves the project directory through the backend (handles split-mode
 * where `project.basePath` is a synthetic frontend path) before creating
 * the workspace. The tool window shows a loading state until resolution
 * completes.
 */
class KiloToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Shared stability collector entry (frontend side): idempotent start, async init,
        // mode/side always derived from the platform run-mode source (never from this call).
        runCatching { service<StabilityService>().start("frontend") }
        project.service<KiloToolWindowSetupService>().create(toolWindow)
    }
}

// Agent Manager（Beta）入口当前隐藏：只关闭工具窗 Tab 的注册，面板及其代码全部保留。
// 置 true 可恢复 Tab；工具栏 + 按钮的可见性绑定 AGENT_MANAGER 数据键，会随之自动恢复。
private const val AGENT_MANAGER_TAB_ENABLED = false

/** M01工具窗setup的观测deadline与name（brief Step 3：界面初始化30秒）。 */
private const val SETUP_OPERATION_NAME = "toolwindow.setup"
private const val SETUP_OPERATION_DEADLINE_MS = 30_000L
private const val FAULT_COMPONENT_FRONTEND = "frontend"

/** 服务定位失败（测试环境/采集禁用）返回null，绝不阻碍工具窗业务。 */
private fun resolveWorkspaces(): KiloWorkspaceService = service<KiloWorkspaceService>()

private fun resolveOperations(): Operations? = runCatching {
    service<StabilityService>().operations
}.getOrNull()

private fun resolveFaults(): Faults? = runCatching {
    service<StabilityService>().faults
}.getOrNull()

@Service(Service.Level.PROJECT)
@Suppress("LongParameterList")
internal class KiloToolWindowSetupService internal constructor(
    private val project: Project,
    private val cs: CoroutineScope,
    private val workspaces: KiloWorkspaceService,
    private val log: KiloLog,
    /** 采集入口（构造时定位；采集禁用/服务不可用时为null，绝不阻碍业务）。 */
    private val operations: Operations?,
    private val faults: Faults?,
    /** 面板构建 seam（null=生产真实SessionSidePanelManager；测试注入失败路径）。 */
    private val panel: ((Workspace) -> SessionSidePanelManager)? = null,
) {
    /** Platform constructor — resolves collaborators from the service container. */
    constructor(project: Project, cs: CoroutineScope) : this(
        project,
        cs,
        resolveWorkspaces(),
        KiloLog.create(KiloToolWindowSetupService::class.java),
        resolveOperations(),
        resolveFaults(),
    )

    private var readiness: ReadinessWatch? = null

    /** cancelled重抛属于正常取消语义（LinkageError记录后重抛按brief要求）。 */
    @Suppress("ThrowsCount")
    fun create(toolWindow: ToolWindow) {
        val operation = beginSetupOperation()
        val hint = project.basePath ?: ""
        try {
            // Experimental IntelliJ ProjectId API keeps multi-window and split-mode routing exact.
            val pid = project.projectIdOrNull()

            cs.launch {
                try {
                    val dir = workspaces.resolveProjectDirectory(pid, hint)
                    val workspace = workspaces.workspace(dir)
                    withContext(Dispatchers.Main) {
                        val manager = setup(project, toolWindow, workspace)
                        // M03：首次激活（根视图已安装 + 基础控制器已订阅）创建唯一Readiness。
                        activateReadiness(manager, workspace)
                    }
                    operation?.end("success", "setup")
                } catch (e: CancellationException) {
                    operation?.end("cancelled", "setup", "user")
                    throw e
                } catch (e: LinkageError) {
                    operation?.end("failure", "setup", "plugin", "linkage")
                    reportFault(e)
                    log.error("Failed to create Kilo tool window content", e)
                    throw e
                } catch (e: Exception) {
                    operation?.end("failure", "setup", "plugin", "other")
                    reportFault(e)
                    log.error("Failed to create Kilo tool window content", e)
                }
            }
        } catch (e: CancellationException) {
            operation?.end("cancelled", "create", "user")
            throw e
        } catch (e: LinkageError) {
            operation?.end("failure", "create", "plugin", "linkage")
            reportFault(e)
            log.error("Failed to create Kilo tool window content", e)
            throw e
        } catch (e: Exception) {
            operation?.end("failure", "create", "plugin", "other")
            reportFault(e)
            log.error("Failed to create Kilo tool window content", e)
        }
    }

    /** begin在采集入口存在时创建30秒operation；null时业务照常（无观测分母）。 */
    private fun beginSetupOperation(): Operation? = operations?.begin(
        SETUP_OPERATION_NAME,
        SETUP_OPERATION_DEADLINE_MS,
    )

    /** 同一自有边界的安全故障上报；致命错误按A6契约原样重抛。 */
    private fun reportFault(error: Throwable) {
        faults?.report(error, FAULT_COMPONENT_FRONTEND, handled = true)
    }

    /**
     * setup成功即一次真实激活：Watch内部对在途分母保持同激活上下文，对已结算分母
     * （blocked或success）创建新的Readiness（brief Step 5"用户重试创建新激活操作"）。
     */
    private fun activateReadiness(manager: SessionSidePanelManager, workspace: Workspace) {
        val setupOperations = operations ?: return
        val watch = readiness ?: ReadinessWatch(
            operations = setupOperations,
            scope = cs,
            app = service<KiloAppService>().state,
            workspace = workspace.state,
            inputProvider = { manager.defaultFocusedComponent != null },
        ).also { readiness = it }
        watch.activate()
    }

    private fun setup(
        project: Project,
        toolWindow: ToolWindow,
        workspace: Workspace,
    ): SessionSidePanelManager {
        val manager = panel?.invoke(workspace) ?: SessionSidePanelManager(project, workspace)

            val chat = object : JPanel(BorderLayout()), DataProvider {
                override fun getData(dataId: String): Any? {
                    if (SessionManager.KEY.`is`(dataId)) return manager
                    if (SessionManager.WORKSPACE_KEY.`is`(dataId)) return workspace
                    if (SidePanelKeys.MODE.`is`(dataId)) return SidePanelMode.CHAT
                    return null
                }
            }
            chat.add(manager.component, BorderLayout.CENTER)

            // Hide the "Costrict" id label in the header so only the content tabs remain.
            toolWindow.component.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, "true")

            val factory = ContentFactory.getInstance()
            val chatContent = factory.createContent(chat, KiloBundle.message("sidePanel.mode.branch"), false)
            chatContent.applySidePanelMode(SidePanelMode.CHAT)
            chatContent.setDisposer(manager)
            chatContent.setPreferredFocusedComponent { manager.defaultFocusedComponent }
            toolWindow.contentManager.addContent(chatContent)

            if (AGENT_MANAGER_TAB_ENABLED) {
                val worktrees = WorktreeController(
                    service<KiloWorktreeService>(),
                    workspace.directory,
                    cs,
                    activity = project.service<KiloSessionService>().activity,
                )
                val agentManagerPanel = AgentManagerPanel(manager, worktrees, project)
                val agent = object : JPanel(BorderLayout()), DataProvider {
                    override fun getData(dataId: String): Any? {
                        // Expose the shared manager here too so History works from the Agent Manager tab.
                        if (SessionManager.KEY.`is`(dataId)) return manager
                        if (SessionManager.WORKSPACE_KEY.`is`(dataId)) return workspace
                        if (SidePanelKeys.MODE.`is`(dataId)) return SidePanelMode.AGENT_MANAGER
                        if (SidePanelKeys.WORKTREE_PANEL.`is`(dataId)) return agentManagerPanel
                        return null
                    }
                }
                agent.add(agentManagerPanel.component, BorderLayout.CENTER)
                val agentContent = factory.createContent(agent, KiloBundle.message("sidePanel.mode.agentManager"), false)
                agentContent.applySidePanelMode(SidePanelMode.AGENT_MANAGER)
                agentContent.applyAgentManagerBetaBadge()
                agentContent.setPreferredFocusedComponent { agentManagerPanel.component }
                toolWindow.contentManager.addContent(agentContent)
                val listener = object : ContentManagerListener {
                    override fun selectionChanged(event: ContentManagerEvent) {
                        if (event.operation == ContentManagerEvent.ContentOperation.add && event.content === agentContent) {
                            agentManagerPanel.refresh()
                        }
                    }
                }
                toolWindow.contentManager.addContentManagerListener(listener)
                Disposer.register(manager) { toolWindow.contentManager.removeContentManagerListener(listener) }
            }
            toolWindow.contentManager.setSelectedContent(chatContent)
            manager.newSession()

            val access = project.service<KiloChatAccess>()
            access.manager = manager
            access.workspaceDirectory = workspace.directory
            Disposer.register(manager) {
                if (access.manager === manager) {
                    access.manager = null
                    access.workspaceDirectory = null
                }
            }

            val actions = listOfNotNull(
                ActionManager.getInstance().getAction("Kilo.NewSession"),
                ActionManager.getInstance().getAction("Kilo.NewWorktree"),
                ActionManager.getInstance().getAction("Kilo.History"),
                ActionManager.getInstance().getAction("Kilo.CodeReview.Changes"),
            )
            toolWindow.setTitleActions(actions)
            // Settings moves off the toolbar into the header gear (options) menu: Open Settings…,
            // Config Files, and Core, inlined from the declarative Kilo.SettingsGroup.
            (ActionManager.getInstance().getAction("Kilo.SettingsGroup") as? ActionGroup)?.let {
                toolWindow.setAdditionalGearActions(it)
            }
        // setup不吞异常：向协程内的唯一记录边界传播，失败与Opened绝不并存（brief Step 3）。
        return manager
    }
}

internal fun Content.applyAgentManagerBetaBadge() {
    icon = AllIcons.General.Beta
    description = KiloBundle.message("sidePanel.mode.agentManager.beta.description")
    putUserData(ToolWindow.SHOW_CONTENT_ICON, true)
    // TAB_LABEL_ORIENTATION_KEY is @ApiStatus.Experimental and may change or disappear between IDE
    // releases; we declare no untilBuild cap. Failure is benign: putUserData no-ops and the Beta
    // icon falls back to the left of the tab label.
    putUserData(Content.TAB_LABEL_ORIENTATION_KEY, ComponentOrientation.RIGHT_TO_LEFT)
}
