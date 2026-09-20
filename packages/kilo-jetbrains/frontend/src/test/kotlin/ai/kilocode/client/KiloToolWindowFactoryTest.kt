package ai.kilocode.client

import ai.kilocode.client.agentManager.SidePanelKeys
import ai.kilocode.client.agentManager.SidePanelMode
import ai.kilocode.client.agentManager.applySidePanelMode
import ai.kilocode.client.app.KiloWorkspaceService
import ai.kilocode.client.session.SessionSidePanelManager
import ai.kilocode.client.stability.ReadinessWatch
import ai.kilocode.client.testing.FakeWorkspaceRpcApi
import ai.kilocode.client.testing.TestCoroutines
import ai.kilocode.client.testing.TestLog
import ai.kilocode.client.testing.pumpEdt
import ai.kilocode.client.util.edtWait
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.KiloWorkspaceStateDto
import ai.kilocode.rpc.dto.KiloWorkspaceStatusDto
import ai.kilocode.rpc.dto.ProfileDto
import ai.kilocode.rpc.KiloWorkspaceRpcApi
import ai.kilocode.stability.Faults
import ai.kilocode.stability.Fixture
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import java.awt.ComponentOrientation
import javax.swing.JPanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonPrimitive

class KiloToolWindowFactoryTest : BasePlatformTestCase() {
    private val coroutines = mutableListOf<TestCoroutines>()

    fun `test agent manager content uses beta badge metadata`() = edtWait {
        val content = ContentFactory.getInstance().createContent(JPanel(), "Agent Manager", false)

        content.applyAgentManagerBetaBadge()

        assertSame(AllIcons.General.Beta, content.icon)
        assertEquals("Agent Manager (Beta)", content.description)
        assertEquals(true, content.getUserData(ToolWindow.SHOW_CONTENT_ICON))
        assertEquals(ComponentOrientation.RIGHT_TO_LEFT, content.getUserData(Content.TAB_LABEL_ORIENTATION_KEY))
    }

    fun `test content records side panel mode`() = edtWait {
        val content = ContentFactory.getInstance().createContent(JPanel(), "Branch", false)

        content.applySidePanelMode(SidePanelMode.CHAT)

        assertEquals(SidePanelMode.CHAT, content.getUserData(SidePanelKeys.CONTENT_MODE))
    }

    // ------ M01/M03失败路径（brief Step 6）：唯一end + 真实组件状态 ------

    private class ThrowingResolveRpc : KiloWorkspaceRpcApi by FakeWorkspaceRpcApi() {
        override suspend fun resolveProjectDirectory(projectId: ProjectId?, hint: String): String =
            throw LinkageError("resolve exploded")
    }

    private class GatedResolveRpc : KiloWorkspaceRpcApi by FakeWorkspaceRpcApi() {
        val gate = CompletableDeferred<Unit>()

        override suspend fun resolveProjectDirectory(projectId: ProjectId?, hint: String): String {
            gate.await()
            return "/test"
        }
    }

    private fun newCoroutines(): TestCoroutines = TestCoroutines().also { coroutines.add(it) }

    private fun toolWindow(): ToolWindow =
        ToolWindowManager.getInstance(project).registerToolWindow(RegisterToolWindowTask("KiloTest"))

    private fun endFacts(fixture: Fixture, name: String) = fixture.facts().filter {
        it.name == name && it.data["phase"]?.jsonPrimitive?.content == "end"
    }

    private fun startFacts(fixture: Fixture, name: String) = fixture.facts().filter {
        it.name == name && it.data["phase"]?.jsonPrimitive?.content == "start"
    }

    fun `test setup failure records single setup end and installs nothing`() {
        val testCoroutines = newCoroutines()
        val scope = CoroutineScope(SupervisorJob() + testCoroutines.dispatcher)
        val rpc = FakeWorkspaceRpcApi()
        val workspaces = KiloWorkspaceService(scope, rpc)
        ApplicationManager.getApplication().replaceService(
            KiloWorkspaceService::class.java, workspaces, testRootDisposable,
        )
        Fixture().use { fixture ->
            val service = KiloToolWindowSetupService(
                project = project,
                cs = scope,
                workspaces = workspaces,
                log = TestLog(),
                operations = fixture.operations,
                faults = Faults(fixture.recorder, fixture.clock),
                panel = { throw IllegalStateException("panel exploded") },
            )
            val toolWindow = toolWindow()

            service.create(toolWindow)
            testCoroutines.drain()
            fixture.flush()

            val ends = endFacts(fixture, "toolwindow.setup")
            assertEquals(1, ends.size)
            assertEquals("failure", ends.single().data["result"]?.jsonPrimitive?.content)
            assertEquals("setup", ends.single().data["stage"]?.jsonPrimitive?.content)
            assertEquals("other", ends.single().data["error_code"]?.jsonPrimitive?.content)
            assertEquals("plugin", ends.single().data["cause"]?.jsonPrimitive?.content)
            // 真实组件状态：根视图未安装，无任何内容；故障计数事实（critical）恰一条。
            assertEquals(0, toolWindow.contentManager.contentCount)
            val faults = fixture.facts().filter { it.name == "error.reported" && it.channel == "critical" }
            assertEquals(1, faults.size)
            assertEquals("frontend", faults.single().data["component"]?.jsonPrimitive?.content)
        }
    }

    fun `test async resolve failure records single end and propagates`() {
        val testCoroutines = newCoroutines()
        val raised = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> raised.add(error) }
        val scope = CoroutineScope(SupervisorJob() + testCoroutines.dispatcher + handler)
        val workspaces = KiloWorkspaceService(scope, ThrowingResolveRpc())
        ApplicationManager.getApplication().replaceService(
            KiloWorkspaceService::class.java, workspaces, testRootDisposable,
        )
        Fixture().use { fixture ->
            val service = KiloToolWindowSetupService(
                project = project,
                cs = scope,
                workspaces = workspaces,
                log = TestLog(),
                operations = fixture.operations,
                faults = Faults(fixture.recorder, fixture.clock),
            )
            val toolWindow = toolWindow()

            service.create(toolWindow)
            testCoroutines.drain()
            fixture.flush()

            val ends = endFacts(fixture, "toolwindow.setup")
            assertEquals(1, ends.size)
            assertEquals("failure", ends.single().data["result"]?.jsonPrimitive?.content)
            assertEquals("linkage", ends.single().data["error_code"]?.jsonPrimitive?.content)
            // LinkageError在同一自有边界记录后原样重抛。
            assertEquals(1, raised.size)
            assertTrue(raised.single() is LinkageError)
            // 真实组件状态：未安装任何内容。
            assertEquals(0, toolWindow.contentManager.contentCount)
        }
    }

    fun `test cancel while resolving records single cancelled end`() {
        val testCoroutines = newCoroutines()
        val scope = CoroutineScope(SupervisorJob() + testCoroutines.dispatcher)
        val rpc = GatedResolveRpc()
        val workspaces = KiloWorkspaceService(scope, rpc)
        ApplicationManager.getApplication().replaceService(
            KiloWorkspaceService::class.java, workspaces, testRootDisposable,
        )
        Fixture().use { fixture ->
            val service = KiloToolWindowSetupService(
                project = project,
                cs = scope,
                workspaces = workspaces,
                log = TestLog(),
                operations = fixture.operations,
                faults = Faults(fixture.recorder, fixture.clock),
            )
            val toolWindow = toolWindow()

            service.create(toolWindow)
            testCoroutines.drain()
            // 项目关闭等价物：取消setup协程的作用域，挂起中的resolve以取消恢复。
            scope.cancel()
            testCoroutines.drain()
            fixture.flush()

            val ends = endFacts(fixture, "toolwindow.setup")
            assertEquals(1, ends.size)
            assertEquals("cancelled", ends.single().data["result"]?.jsonPrimitive?.content)
            assertEquals("user", ends.single().data["cause"]?.jsonPrimitive?.content)
            assertEquals(0, toolWindow.contentManager.contentCount)
        }
    }

    fun `test partial readiness does not end success until all conditions hold`() {
        Fixture().use { fixture ->
            val app = MutableStateFlow(KiloAppStateDto(KiloAppStatusDto.LOADING))
            val workspace = MutableStateFlow(KiloWorkspaceStateDto(KiloWorkspaceStatusDto.PENDING))
            var input = false
            val watch = ReadinessWatch(
                operations = fixture.operations,
                scope = CoroutineScope(Dispatchers.Unconfined),
                app = app,
                workspace = workspace,
                inputProvider = { input },
            )
            watch.activate()
            // 部分资料失败（profile失败→app不READY）：不产生任何终态。
            app.value = KiloAppStateDto(KiloAppStatusDto.ERROR, error = "profile failed")
            pumpEdt()
            fixture.flush()
            assertEquals(1, startFacts(fixture, "plugin.readiness").size)
            assertEquals(0, endFacts(fixture, "plugin.readiness").size)

            // 其余就绪但input未交互：仍不成功，且不新建分母。
            workspace.value = KiloWorkspaceStateDto(KiloWorkspaceStatusDto.READY)
            pumpEdt()
            fixture.flush()
            assertEquals(0, endFacts(fixture, "plugin.readiness").size)
            assertEquals(1, startFacts(fixture, "plugin.readiness").size)

            // input真实可交互 + app READY这一真实状态变更：唯一success，reason=none。
            input = true
            app.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = ProfileDto(email = "user@example.com"))
            pumpEdt()
            fixture.flush()
            val ends = endFacts(fixture, "plugin.readiness")
            assertEquals(1, ends.size)
            assertEquals("success", ends.single().data["result"]?.jsonPrimitive?.content)
            assertEquals("none", ends.single().data["reason"]?.jsonPrimitive?.content)
        }
    }

    fun `test repeated MigrationRequired does not create new denominators`() {
        Fixture().use { fixture ->
            val app = MutableStateFlow(KiloAppStateDto(KiloAppStatusDto.LOADING))
            val workspace = MutableStateFlow(KiloWorkspaceStateDto(KiloWorkspaceStatusDto.PENDING))
            val watch = ReadinessWatch(
                operations = fixture.operations,
                scope = CoroutineScope(Dispatchers.Unconfined),
                app = app,
                workspace = workspace,
                inputProvider = { true },
            )
            watch.activate()
            fixture.flush()
            assertEquals(1, startFacts(fixture, "plugin.readiness").size)
            // MigrationRequired状态：blocked end恰一次，重复状态不新建分母。
            app.value = KiloAppStateDto(KiloAppStatusDto.MIGRATION_REQUIRED)
            pumpEdt()
            app.value = KiloAppStateDto(KiloAppStatusDto.MIGRATION_REQUIRED)
            pumpEdt()
            fixture.flush()
            assertEquals(1, startFacts(fixture, "plugin.readiness").size)
            val ends = endFacts(fixture, "plugin.readiness")
            assertEquals(1, ends.size)
            assertEquals("blocked", ends.single().data["result"]?.jsonPrimitive?.content)
            assertEquals("migration_required", ends.single().data["reason"]?.jsonPrimitive?.content)

            // 之后自动转为READY：不再产生第二条终态。
            app.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = ProfileDto(email = "user@example.com"))
            workspace.value = KiloWorkspaceStateDto(KiloWorkspaceStatusDto.READY)
            pumpEdt()
            fixture.flush()
            assertEquals(1, endFacts(fixture, "plugin.readiness").size)
        }
    }
}
