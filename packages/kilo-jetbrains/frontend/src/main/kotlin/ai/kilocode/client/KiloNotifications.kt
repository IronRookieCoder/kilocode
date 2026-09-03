package ai.kilocode.client

import ai.kilocode.client.plugin.KiloBundle
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

object KiloNotifications {
    private const val GROUP = "Kilo Code"

    fun error(title: String, content: String? = null) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDefault }
        error(project, title, content)
    }

    fun error(project: Project?, title: String, content: String? = null) {
        error(project, title, content, emptyList())
    }

    /** Error notification with a single expiring action (e.g. a retry). */
    fun error(project: Project?, title: String, content: String?, actionLabel: String, action: () -> Unit) {
        error(project, title, content, listOf(actionLabel to action))
    }

    /** Error notification with two expiring actions, e.g. the primary fix plus a fallback link. */
    fun error(
        project: Project?,
        title: String,
        content: String?,
        primaryLabel: String,
        primaryAction: () -> Unit,
        secondaryLabel: String,
        secondaryAction: () -> Unit,
    ) {
        error(project, title, content, listOf(primaryLabel to primaryAction, secondaryLabel to secondaryAction))
    }

    private fun error(project: Project?, title: String, content: String?, actions: List<Pair<String, () -> Unit>>) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            ?.createNotification(title, content ?: "", NotificationType.ERROR)
            ?: Notification(GROUP, title, content ?: "", NotificationType.ERROR)
        actions.forEach { (label, action) ->
            notification.addAction(NotificationAction.createSimpleExpiring(label) { action() })
        }
        notification.notify(project)
    }

    fun info(title: String, content: String? = null) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDefault }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            ?.createNotification(title, content ?: "", NotificationType.INFORMATION)
            ?: Notification(GROUP, title, content ?: "", NotificationType.INFORMATION)
        notification.notify(project)
    }

    fun suggestion(project: Project?, title: String, content: String?, actionLabel: String, action: () -> Unit) {
        suggestion(project, title, content, actionLabel, action, KiloBundle.message("common.dont.show.again")) {}
    }

    /** Suggestion with a primary action plus a second one, e.g. a persistent "Don't show again". */
    fun suggestion(
        project: Project?,
        title: String,
        content: String?,
        actionLabel: String,
        action: () -> Unit,
        dismissLabel: String,
        onDismiss: () -> Unit,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            ?.createNotification(title, content ?: "", NotificationType.INFORMATION)
            ?: Notification(GROUP, title, content ?: "", NotificationType.INFORMATION)
        notification.setSuggestionType(true)
        notification.addAction(NotificationAction.createSimpleExpiring(actionLabel) { action() })
        notification.addAction(NotificationAction.createSimpleExpiring(dismissLabel) { onDismiss() })
        notification.notify(project)
    }
}
