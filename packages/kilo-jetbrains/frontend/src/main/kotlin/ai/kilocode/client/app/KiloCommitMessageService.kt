package ai.kilocode.client.app

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.CommitMessageRequestDto
import ai.kilocode.rpc.dto.CommitMessageResultDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level service generating commit messages via the Kilo backend.
 *
 * Owns the coroutine scope for commit-dialog action clicks (EDT handlers have
 * no thread-bound scope) and the in-flight guard the action's [update][com.intellij.openapi.actionSystem.AnAction.update]
 * reads. [generate] never touches Swing; the callback is delivered on the EDT.
 */
@Service(Service.Level.PROJECT)
class KiloCommitMessageService internal constructor(
    private val project: Project,
    private val cs: CoroutineScope,
    private val sessions: KiloSessionService?,
    private val workspaces: KiloWorkspaceService?,
    private val app: KiloAppService?,
) {
    /** Platform constructor — resolves sibling services from the container. */
    constructor(project: Project, cs: CoroutineScope) : this(project, cs, null, null, null)

    private val inflight = AtomicBoolean(false)

    val busy: Boolean get() = inflight.get()

    /**
     * Generate a commit message for the current project repository.
     * [complete] receives the RPC result (or failure) on the EDT.
     */
    fun generate(previousMessage: String?, complete: (Result<CommitMessageResultDto>) -> Unit) {
        if (!inflight.compareAndSet(false, true)) return
        cs.launch {
            val result = try {
                Result.success(run(previousMessage))
            } catch (e: CancellationException) {
                Result.failure(e)
            } catch (e: Exception) {
                LOG.warn("commit message generation failed message=${e.message}", e)
                Result.failure(e)
            }
            inflight.set(false)
            ApplicationManager.getApplication().invokeLater { complete(result) }
        }
    }

    private suspend fun run(previousMessage: String?): CommitMessageResultDto {
        val unready = awaitBackend()
        if (unready != null) return CommitMessageResultDto(error = unready)

        val hint = project.basePath.orEmpty()
        val sessions = sessions ?: project.service<KiloSessionService>()
        val workspaces = workspaces ?: service<KiloWorkspaceService>()
        val dir = workspaces.resolveProjectDirectory(null, hint).ifBlank { hint }
        val selection = CommitModelStore.selection()
        return sessions.generateCommitMessage(
            CommitMessageRequestDto(
                directory = dir,
                previousMessage = previousMessage?.takeIf { it.isNotBlank() },
                providerID = selection?.first,
                modelID = selection?.second,
            ),
        )
    }

    /**
     * Ensure the backend is connecting (the commit dialog may be the first Kilo surface
     * touched this session) and wait for it to become ready. Returns null when ready,
     * or a user-facing error when it cannot become ready in time.
     */
    private suspend fun awaitBackend(): String? {
        val app = app ?: service<KiloAppService>()
        app.connect()
        val state = withTimeoutOrNull(READY_TIMEOUT_MS) {
            app.state.first { it.status == KiloAppStatusDto.READY || it.status == KiloAppStatusDto.ERROR || it.status == KiloAppStatusDto.MIGRATION_REQUIRED }
        }
        if (state?.status == KiloAppStatusDto.READY) return null
        return when (state?.status) {
            KiloAppStatusDto.ERROR -> state.error?.takeIf { it.isNotBlank() }
                ?: KiloBundle.message("action.Kilo.GenerateCommitMessage.notReady.error")
            KiloAppStatusDto.MIGRATION_REQUIRED -> KiloBundle.message("action.Kilo.GenerateCommitMessage.notReady.migration")
            else -> KiloBundle.message("action.Kilo.GenerateCommitMessage.notReady.timeout")
        }
    }

    companion object {
        private val LOG = KiloLog.create(KiloCommitMessageService::class.java)
        private const val READY_TIMEOUT_MS = 90_000L
    }
}
