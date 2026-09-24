package ai.kilocode.client.settings.base

import ai.kilocode.client.util.edtWait
import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.app.KiloWorkspaceService
import ai.kilocode.client.testing.FakeAppRpcApi
import ai.kilocode.client.testing.FakeWorkspaceRpcApi
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.stability.Fixture
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.text.JTextComponent

class BaseSettingsUiTest : BasePlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var appScope: CoroutineScope
    private lateinit var appRpc: FakeAppRpcApi
    private lateinit var app: KiloAppService
    private lateinit var workspaces: KiloWorkspaceService
    private lateinit var fixture: Fixture
    private var panel: FakePanel? = null

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob())
        appScope = CoroutineScope(SupervisorJob())
        appRpc = FakeAppRpcApi()
        app = KiloAppService(appScope, appRpc)
        workspaces = KiloWorkspaceService(appScope, FakeWorkspaceRpcApi())
        fixture = Fixture()
    }

    override fun tearDown() {
        try {
            val view = panel
            if (view != null) edt { view.dispose() }
            panel = null
            scope.cancel()
            appScope.cancel()
            if (this::fixture.isInitialized) fixture.close()
        } finally {
            super.tearDown()
        }
    }

    fun `test modified and reset use baseline`() {
        val view = create()

        edt {
            view.edit("new")
            assertTrue(view.modified())
            view.resetDraft()
            assertEquals("old", view.value())
            assertFalse(view.modified())
        }
    }

    fun `test pending save target is not modified`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
            assertFalse(view.modified())
            view.edit("other")
            assertTrue(view.modified())
        }
    }

    fun `test failed save keeps draft dirty and shows error`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
            view.fail()
        }
        flush()

        edt {
            assertEquals("new", view.value())
            assertTrue(view.modified())
            assertTrue(text(view.progress).contains("Failed"))
        }
    }

    fun `test edit clears save error`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
            view.fail()
        }
        flush()
        edt { view.edit("other") }
        flush()

        edt { assertFalse(text(view.progress).contains("Failed")) }
    }

    fun `test successful save preserves concurrent edit`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
            view.edit("other")
            view.succeed("new")
        }
        flush()

        edt {
            assertEquals("other", view.value())
            assertTrue(view.modified())
        }
    }

    fun `test apply uses change result when saved predicate diverges`() {
        val view = create { base, draft -> base.value.first() == draft.value.first() }

        edt {
            view.edit("other")
            assertFalse(view.modified())
            view.applyDraft()
            assertEquals(1, view.pendingSaves())
        }
    }

    fun `test failed save after dispose calls failure hook`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
            view.dispose()
            view.fail()
        }
        panel = null
        flush()

        assertEquals(1, view.disposedFailures)
    }

    fun `test login banner can be shown`() {
        val view = create()

        edt { view.banner(true) }

        edt { assertTrue(text(view).contains("Sign in to CoStrict")) }
    }

    // ------ M12（B4）settings_save 分母矩阵 ------

    private fun saveFacts(phase: String) = fixture.facts().filter {
        it.name == "action" && it.data["phase"]?.jsonPrimitive?.content == phase &&
            it.data["action"]?.jsonPrimitive?.content == "settings_save"
    }

    private fun saveStarts() = saveFacts("start")

    private fun saveEnds() = saveFacts("end")

    fun `test apply begins one settings save and settles success on confirmed readback`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
        }
        flush()
        fixture.flush()
        assertEquals(1, saveStarts().size)
        assertTrue(saveEnds().isEmpty())

        edt { view.succeed("new") }
        flush()
        fixture.flush()

        val end = saveEnds().single()
        assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("readback", end.data.getValue("stage").jsonPrimitive.content)
        assertEquals(1, saveStarts().size)
    }

    fun `test unmodified apply has no settings save denominator`() {
        val view = create()

        edt { view.applyDraft() }
        flush()
        fixture.flush()

        assertEquals(0, view.pendingSaves())
        assertTrue(saveStarts().isEmpty())
        assertTrue(saveEnds().isEmpty())
    }

    fun `test stale save result waits for matching snapshot then settles success`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
        }
        flush()
        edt { view.succeed("stale") }
        flush()
        fixture.flush()
        assertTrue(saveEnds().isEmpty())

        // 后续app状态推送触发acceptBase：匹配token.target的真实快照到达后success。
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY)
        pumpUntilReady(view)
        fixture.flush()

        val end = saveEnds().single()
        assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("readback", end.data.getValue("stage").jsonPrimitive.content)
    }

    fun `test failed save receipt settles failure once`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
        }
        flush()
        edt { view.fail() }
        flush()
        fixture.flush()

        val end = saveEnds().single()
        assertEquals("failure", end.data.getValue("result").jsonPrimitive.content)
    }

    fun `test confirmed readback plus later snapshot adds no second end`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
        }
        flush()
        edt { view.succeed("new") }
        flush()

        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY)
        pumpUntilReady(view)
        fixture.flush()

        assertEquals(1, saveEnds().size)
    }

    fun `test dispose during save does not cancel the denominator`() {
        val view = create()

        edt {
            view.edit("new")
            view.applyDraft()
        }
        flush()
        edt {
            view.dispose()
            view.succeed("new")
        }
        panel = null
        flush()
        fixture.flush()

        // UI关闭不强制cancel在途保存：不分母cancelled也不误报success，deadline兜底。
        assertEquals(1, saveStarts().size)
        assertTrue(saveEnds().isEmpty())
    }

    fun `test login banner can be disabled`() {
        val view = create(login = false)

        edt { view.banner(true) }

        edt { assertFalse(text(view).contains("Sign in to CoStrict")) }
    }

    private fun create(
        login: Boolean = true,
        saved: (Draft, Draft) -> Boolean = { base, draft -> base == draft },
    ): FakePanel {
        val view = edt { FakePanel(scope, app, workspaces, login, saved, fixture.operations) }
        panel = view
        return view
    }

    private fun flush() = runBlocking {
        edt { UIUtil.dispatchAllInvocationEvents() }
    }

    /** app状态推送（Default协程→EDT）跨线程汇流：轮询+pump直到面板已应用READY状态。 */
    private fun pumpUntilReady(view: FakePanel) = runBlocking {
        val deadline = System.currentTimeMillis() + 5_000
        while (view.appStateProbe().status != KiloAppStatusDto.READY && System.currentTimeMillis() < deadline) {
            Thread.yield()
            edt { UIUtil.dispatchAllInvocationEvents() }
        }
        check(view.appStateProbe().status == KiloAppStatusDto.READY) { "panel never reached READY" }
    }

    private fun <T> edt(block: () -> T): T = edtWait(block)

    private fun text(root: Container): String {
        val out = mutableListOf<String>()
        for (comp in components(root)) {
            if (!comp.isVisible) continue
            when (comp) {
                is AbstractButton -> comp.text?.let { out.add(it) }
                is JLabel -> comp.text?.let { out.add(it) }
                is JTextComponent -> comp.text?.let { out.add(it) }
            }
        }
        return out.joinToString("\n")
    }

    private fun components(root: Container): List<java.awt.Component> = buildList {
        fun visit(comp: java.awt.Component) {
            add(comp)
            if (comp is Container) comp.components.forEach { visit(it) }
        }
        visit(root)
    }

    private data class Draft(val value: String)
    private data class Change(val value: String)

    private class FakeContent : BaseContentPanel()

    private class FakePanel(
        cs: CoroutineScope,
        app: KiloAppService,
        workspaces: KiloWorkspaceService,
        login: Boolean,
        private val saved: (Draft, Draft) -> Boolean,
        operations: ai.kilocode.stability.Operations,
    ) : BaseSettingsUi<FakeContent, Draft, Change, Draft, Unit>(
        cs,
        Draft("old"),
        app,
        workspaces,
        loginBanner = login,
        operations = operations,
    ) {
        private val callbacks = mutableListOf<(Draft?) -> Unit>()
        var disposedFailures = 0
            private set

        init {
            startSettings(FakeContent())
        }

        fun edit(value: String) = updateDraft { copy(value = value) }

        fun value(): String = draft.value

        fun succeed(value: String) = callbacks.removeAt(0)(Draft(value))

        fun fail() = callbacks.removeAt(0)(null)

        fun pendingSaves(): Int = callbacks.size

        fun banner(login: Boolean) = syncLoginBanner(login) { top.hideBanner() }

        fun appStateProbe(): KiloAppStateDto = appState

        override fun change(from: Draft, to: Draft): Change? = if (from == to) null else Change(to.value)

        override fun save(change: Change, done: (Draft?) -> Unit) {
            callbacks += done
        }

        override fun base(result: Draft): Draft = result

        override fun saved(base: Draft, draft: Draft): Boolean = saved.invoke(base, draft)

        override fun draft(state: KiloAppStateDto): Draft = draft

        override suspend fun loadWorkspace(root: String) = Unit

        override fun applyWorkspace(result: Unit) = Unit

        override fun syncContent() {
            val err = saveError
            if (saving) {
                showProgress(pendingText())
                return
            }
            if (err != null) {
                showError(err)
                return
            }
            clearProgress()
        }

        override fun pendingText(): String = "Saving"

        override fun failedText(): String = "Failed"

        override fun onSaveFailedAfterDispose(change: Change) {
            disposedFailures++
        }
    }
}
