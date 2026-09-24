package ai.kilocode.client.settings.base

import ai.kilocode.client.KiloNotifications
import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.app.KiloWorkspaceService
import ai.kilocode.client.ui.CostrictLinks
import ai.kilocode.stability.Operation
import ai.kilocode.stability.Operations
import ai.kilocode.stability.StabilityService
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.ModelStateDto
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal abstract class BaseSettingsUi<C : BaseContentPanel, D, P, R, W>(
    protected val scope: CoroutineScope,
    initial: D,
    private val app: KiloAppService = service(),
    private val workspaces: KiloWorkspaceService = service(),
    private val hint: String? = null,
    private val loginBanner: Boolean = true,
    scroll: Boolean = true,
    pad: Boolean = true,
    // M12（B4）settings_save观测：实际操作观测依赖（P0结构约定）；真实构造点沿用默认值，
    // 测试基座经fixture注入同一recorder。
    private val operations: Operations = service<StabilityService>().operations,
) : SettingsPanel(scroll, pad), SettingsDraftPage {
    protected lateinit var form: C
        private set
    protected val jobs = mutableListOf<Job>()
    private val state = SettingsDraftState(initial) { base, draft -> saved(base, draft) }

    /**
     * M12（B4）settings_save：保存回执不含目标时，保持等待匹配token.target的acceptBase
     * 真实快照的在途操作；新保存开始或UI关闭即失效，30秒deadline兜底。
     */
    private var awaitingSave: Pair<Operation, D>? = null
    protected var draft: D
        get() = state.draft
        set(value) {
            state.draft = value
        }
    protected val saving get() = state.saving
    protected val saveError get() = state.error
    protected var appState: KiloAppStateDto = app.state.value
        private set
    protected var modelState: ModelStateDto = app.models.value
        private set
    protected var projectDirectory: String? = null
        private set
    protected val hasProjectDirectory get() = projectDirectory != null || hint != null
    protected var workspaceLoading = false
        private set
    protected var workspaceLoaded = false
        private set

    private var disposed = false

    @RequiresEdt
    protected fun startSettings(content: C) {
        form = content
        setContent(content)
        syncContent()
        start()
    }

    private fun start() {
        jobs += scope.launch {
            app.state.collect { state -> withContext(edt) { updateApp(state) } }
        }
        jobs += scope.launch {
            app.models.collect { state -> withContext(edt) { updateModels(state) } }
        }
        jobs += scope.launch { app.connect() }
        val path = hint ?: return
        jobs += scope.launch {
            val dir = workspaces.resolveProjectDirectory(null, path)
            withContext(edt) {
                projectDirectory = dir
                workspaceLoaded = false
                syncContent()
                load()
            }
        }
    }

    @RequiresEdt
    private fun updateApp(state: KiloAppStateDto) {
        appState = state
        if (state.status != KiloAppStatusDto.READY) {
            workspaceLoading = false
            unavailable(state)
            syncContent()
            return
        }
        acceptBase(draft(state))
        syncContent()
        load()
    }

    @RequiresEdt
    private fun updateModels(state: ModelStateDto) {
        modelState = state
        models(state)
        syncContent()
    }

    @RequiresEdt
    private fun load() {
        val root = projectDirectory ?: return
        if (appState.status != KiloAppStatusDto.READY || workspaceLoading || workspaceLoaded) return
        workspaceLoading = true
        clearWorkspaceError()
        syncContent()
        jobs += scope.launch {
            val state = loadWorkspace(root)
            withContext(edt) {
                applyWorkspace(state)
                workspaceLoaded = true
                workspaceLoading = false
                acceptBase(draft(appState))
                syncContent()
            }
        }
    }

    @RequiresEdt
    override fun modified(): Boolean {
        checkEdt()
        return state.modified()
    }

    @RequiresEdt
    override fun resetDraft() {
        checkEdt()
        state.reset()
        if (!saving) clearProgress()
        syncContent()
    }

    @RequiresEdt
    override fun applyDraft() {
        checkEdt()
        // M12（B4）settings_save：modified与validation通过后才begin（校验失败/未修改保存不计分母）。
        val change = change(state.baseline, draft) ?: return
        val token = state.start(force = true) ?: return
        awaitingSave = null
        val operation = operations.begin(ACTION_NAME, ACTION_DEADLINE_MS, buildJsonObject {
            put("action", ACTION_SETTINGS_SAVE)
        })
        logSaveStarted(change)
        showProgress(pendingText())
        syncContent()
        save(change) { result ->
            ApplicationManager.getApplication().invokeLater({
                if (disposed) {
                    awaitingSave = null
                    if (result == null) {
                        logSaveFailedAfterDispose(change)
                        onSaveFailedAfterDispose(change)
                    } else {
                        logSaveCompletedAfterDispose(change)
                    }
                    return@invokeLater
                }
                if (result != null) {
                    logSaveCompleted(change)
                    // 必须在complete之前检查真实返回状态：complete在不匹配时会回退到token.target，
                    // 之后的baseline不再反映回执本身。
                    val current = base(result)
                    val confirmed = saved(current, token.target)
                    state.complete(token, current)
                    clearProgress()
                    syncContent()
                    if (confirmed) {
                        operation.end(RESULT_SUCCESS, STAGE_READBACK)
                    } else {
                        // 回执不等目标：保持等待匹配目标的acceptBase真实快照，不再第二次save。
                        awaitingSave = operation to token.target
                    }
                    return@invokeLater
                }
                state.fail(token, failedText())
                logSaveFailed(change)
                syncContent()
                operation.end(RESULT_FAILURE, STAGE_SAVE)
            }, ModalityState.any())
        }
    }

    @RequiresEdt
    fun dispose() {
        checkEdt()
        disposed = true
        // 关闭UI不强制cancel在途保存业务：等待快照的在途操作留给deadline兜底。
        awaitingSave = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
    }

    @RequiresEdt
    protected fun updateDraft(fn: D.() -> D) {
        checkEdt()
        state.update(fn)
        syncContent()
    }

    @RequiresEdt
    protected fun acceptBase(base: D) {
        checkEdt()
        state.accept(base)
        // M12（B4）：等待中的settings_save由匹配目标（token.target）的真实快照确认成功；
        // 迟到快照经CAS去重，deadline兜底。
        val wait = awaitingSave
        if (wait != null && saved(base, wait.second)) {
            awaitingSave = null
            wait.first.end(RESULT_SUCCESS, STAGE_READBACK)
        }
    }

    @RequiresEdt
    protected fun syncLoginBanner(login: Boolean, fallback: () -> Unit) {
        checkEdt()
        if (loginBanner && login) {
            top.showNotLoggedIn { openProfile() }
            return
        }
        fallback()
    }

    private fun checkEdt() {
        check(ApplicationManager.getApplication().isDispatchThread) { "Settings UI updates must run on EDT" }
    }

    @RequiresEdt
    protected abstract fun change(from: D, to: D): P?

    @RequiresEdt
    protected abstract fun save(change: P, done: (R?) -> Unit)

    @RequiresEdt
    protected abstract fun base(result: R): D

    @RequiresEdt
    protected abstract fun syncContent()

    @RequiresEdt
    protected abstract fun pendingText(): String

    @RequiresEdt
    protected abstract fun failedText(): String

    @RequiresEdt
    protected abstract fun draft(state: KiloAppStateDto): D

    @RequiresBackgroundThread
    protected abstract suspend fun loadWorkspace(root: String): W

    @RequiresEdt
    protected abstract fun applyWorkspace(result: W)

    @RequiresEdt
    protected open fun saved(base: D, draft: D): Boolean = base == draft

    @RequiresEdt
    protected open fun onSaveFailedAfterDispose(change: P) = KiloNotifications.error(failedText())

    @RequiresEdt
    protected open fun logSaveStarted(change: P) = Unit

    @RequiresEdt
    protected open fun logSaveCompleted(change: P) = Unit

    @RequiresEdt
    protected open fun logSaveFailed(change: P) = Unit

    @RequiresEdt
    protected open fun logSaveFailedAfterDispose(change: P) = Unit

    @RequiresEdt
    protected open fun logSaveCompletedAfterDispose(change: P) = Unit

    @RequiresEdt
    protected open fun unavailable(state: KiloAppStateDto) = Unit

    @RequiresEdt
    protected open fun models(state: ModelStateDto) = Unit

    @RequiresEdt
    protected open fun clearWorkspaceError() = Unit

    private fun openProfile() {
        BrowserUtil.browse(CostrictLinks.CREDIT_MANAGER)
    }

    private companion object {
        val edt = Dispatchers.EDT + ModalityState.any().asContextElement()

        // M12（B4）settings_save观测常量：action名与30秒截止（恢复和普通交互同档）。
        const val ACTION_NAME = "action"
        const val ACTION_SETTINGS_SAVE = "settings_save"
        const val ACTION_DEADLINE_MS = 30_000L
        const val RESULT_SUCCESS = "success"
        const val RESULT_FAILURE = "failure"
        const val STAGE_READBACK = "readback"
        const val STAGE_SAVE = "save"
    }
}
