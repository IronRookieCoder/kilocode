package ai.kilocode.client.actions

import ai.kilocode.client.app.KiloCommitMessageService
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.telemetry.Telemetry
import ai.kilocode.client.ui.CostrictBrand
import ai.kilocode.rpc.dto.CommitMessageResultDto
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Commit-dialog action that generates a commit message with CoStrict AI from the
 * staged/pending changes and writes it into the commit message editor.
 *
 * When the editor already holds text it is sent as the previous message so the
 * model produces a different result (regenerate behavior).
 */
class GenerateCommitMessageAction : DumbAwareAction(
    KiloBundle.message("action.Kilo.GenerateCommitMessage.text"),
    KiloBundle.message("action.Kilo.GenerateCommitMessage.description"),
    logo(16),
) {
    init {
        isEnabledInModalContext = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && !project.isDisposed &&
            control(e) != null &&
            !project.service<KiloCommitMessageService>().busy
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val control = control(e) ?: return
        val previous = e.getData(CommonDataKeys.EDITOR)?.document?.text?.takeIf { it.isNotBlank() }
        Telemetry.send("Commit Message Triggered", mapOf("surface" to "commit_dialog"))
        project.service<KiloCommitMessageService>().generate(previous) { result ->
            result
                .onSuccess { dto -> handle(project, control, dto) }
                .onFailure { err ->
                    if (err !is CancellationException) {
                        notify(project, NotificationType.ERROR, err.message ?: err::class.java.simpleName)
                    }
                }
        }
    }

    private fun handle(project: Project, control: CommitMessageI, dto: CommitMessageResultDto) {
        val generated = dto.message
        if (generated == null) {
            val type = if (dto.noChanges) NotificationType.WARNING else NotificationType.ERROR
            notify(project, type, dto.error ?: KiloBundle.message("action.Kilo.GenerateCommitMessage.failed"))
            return
        }
        control.setCommitMessage(generated)
        Telemetry.send("Commit Message Generated", emptyMap())
    }

    private fun control(e: AnActionEvent): CommitMessageI? = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)

    private fun notify(project: Project, type: NotificationType, detail: String) {
        val title = KiloBundle.message("action.Kilo.GenerateCommitMessage.text")
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(CostrictBrand.NOTIFICATION_GROUP)
            ?.createNotification(title, detail, type)
            ?: Notification(CostrictBrand.NOTIFICATION_GROUP, title, detail, type)
        notification.notify(project)
    }

    companion object {
        /** Product logo scaled to a standard action-icon size. */
        fun logo(side: Int): Icon = Scaled(
            IconLoader.getIcon("/icons/costrict/logo.png", GenerateCommitMessageAction::class.java),
            JBUI.scale(side),
        )
    }

    /** Paints [base] uniformly scaled into a fixed logical [side]-pixel box. */
    private class Scaled(private val base: Icon, private val side: Int) : Icon {
        override fun getIconWidth() = side

        override fun getIconHeight() = side

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.translate(x, y)
                g2.scale(side.toDouble() / base.iconWidth, side.toDouble() / base.iconHeight)
                base.paintIcon(c, g2, 0, 0)
            } finally {
                g2.dispose()
            }
        }
    }
}
