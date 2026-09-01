// kilocode_change - new file
package ai.kilocode.client.codereview

import ai.kilocode.client.app.KiloChatAccess
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.KiloAppRpcApi
import ai.kilocode.rpc.dto.CodeReviewReportDto
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level collector for completed CoStrict code-review reports.
 *
 * Subscribes to the backend report flow, keeps only reports written under this
 * project's workspace, and shows a balloon with severity counts plus a
 * "View Report" action opening `review-report.md` in the IDE editor
 * (built-in Markdown preview when the Markdown plugin is present).
 */
@Service(Service.Level.PROJECT)
class CodeReviewNotifier internal constructor(
    private val project: Project,
    private val cs: CoroutineScope,
    private val rpc: KiloAppRpcApi? = null,
    private val sink: ((CodeReviewReportDto) -> Unit)? = null,
) {
    /** Platform constructor — resolves RPC from the service container (matches KiloSessionService pattern). */
    constructor(project: Project, cs: CoroutineScope) : this(project, cs, null, null)

    companion object {
        private val LOG = KiloLog.create(CodeReviewNotifier::class.java)
        private const val GROUP = "Kilo.CodeReview"

        /** True when [report] belongs to this project's [directory] (normalized comparison). */
        internal fun matches(report: CodeReviewReportDto, directory: String?): Boolean {
            if (directory == null) return false
            return report.directory.replace('\\', '/').trimEnd('/') ==
                directory.replace('\\', '/').trimEnd('/')
        }

        internal fun content(report: CodeReviewReportDto): String = when {
            report.degraded -> KiloBundle.message("codereview.degraded.content")
            report.highCount + report.middleCount + report.lowCount == 0 ->
                KiloBundle.message("codereview.noissues", report.qualitySummary ?: "")
            else -> KiloBundle.message(
                "codereview.completed.content",
                report.highCount,
                report.middleCount,
                report.lowCount,
                report.qualitySummary ?: "",
            )
        }

        private fun notifyReport(project: Project, report: CodeReviewReportDto) {
            val title = KiloBundle.message("codereview.completed.title")
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP)
                ?.createNotification(title, content(report), NotificationType.INFORMATION)
                ?: Notification(GROUP, title, content(report), NotificationType.INFORMATION)
            notification.addAction(NotificationAction.createSimpleExpiring(KiloBundle.message("codereview.open")) {
                openReport(project, report)
            })
            notification.notify(project)
        }

        private fun openReport(project: Project, report: CodeReviewReportDto) {
            // Prefer the human-readable Markdown twin; fall back to the JSON itself
            // (FileEditorManager cannot open directories).
            val urls = listOf(
                VfsUtilCore.pathToUrl(report.reportMdPath.replace('\\', '/')),
                VfsUtilCore.pathToUrl(report.reportJsonPath.replace('\\', '/')),
            )
            val file = urls.firstNotNullOfOrNull { VirtualFileManager.getInstance().refreshAndFindFileByUrl(it) }
            if (file == null) {
                LOG.warn("kind=codereview-open ok=false md=${report.reportMdPath}")
                return
            }
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private val started = AtomicBoolean(false)
    private val defaultSink: (CodeReviewReportDto) -> Unit = { report -> notifyReport(project, report) }
    private val handler: (CodeReviewReportDto) -> Unit = sink ?: defaultSink

    fun start() {
        if (!started.compareAndSet(false, true)) return
        cs.launch {
            try {
                val api = rpc
                if (api != null) api.cloudReviewReports().collect { onReportCatching(it) }
                else durable { KiloAppRpcApi.getInstance().cloudReviewReports().collect { onReportCatching(it) } }
            } catch (e: Exception) {
                LOG.warn("kind=codereview-subscription failed message=${e.message}", e)
            }
        }
    }

    private fun onReportCatching(report: CodeReviewReportDto) {
        runCatching { onReport(report) }.onFailure {
            LOG.warn("kind=codereview-report failed directory=${report.directory}", it)
        }
    }

    private fun onReport(report: CodeReviewReportDto) {
        val directory = project.service<KiloChatAccess>().workspaceDirectory ?: project.basePath
        if (!matches(report, directory)) return
        ApplicationManager.getApplication().invokeLater({ handler(report) }, ModalityState.nonModal())
    }
}
