// kilocode_change - new file
package ai.kilocode.client.actions

import ai.kilocode.client.app.KiloChatAccess
import ai.kilocode.client.codereview.ReviewTarget
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.session.SessionManager
import ai.kilocode.client.telemetry.Telemetry
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import java.io.File

/** Base action sending `/review <args>` into the current Kilo session. */
abstract class CodeReviewAction(private val surface: String) : AnAction(), DumbAware {
    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val access = project.service<KiloChatAccess>()
        val manager = e.getData(SessionManager.KEY) ?: access.manager ?: return
        val root = (access.workspaceDirectory ?: project.basePath)?.let { File(it).canonicalPath } ?: return
        val target = target(e, root) ?: ReviewTarget.Changes
        Telemetry.send("Code Review Triggered", mapOf("surface" to surface, "target" to (target::class.simpleName ?: "Unknown")))
        manager.sendCommand("review", ReviewTarget.args(target))
    }

    final override fun update(e: AnActionEvent) {
        val available = e.getData(SessionManager.KEY) != null || e.project?.service<KiloChatAccess>()?.manager != null
        e.presentation.isEnabled = available
        e.presentation.description =
            if (available) templatePresentation.description else KiloBundle.message("codereview.disabled.tooltip")
        customize(e)
    }

    /** Hook for subclasses to adjust text (e.g. file vs selection). */
    protected open fun customize(e: AnActionEvent) {}

    /** Resolve the review target from the action context; null falls back to current changes. */
    protected open fun target(e: AnActionEvent, root: String): ReviewTarget? = null
}

/** Toolbar button: review the current working-tree changes (`/review` with no args). */
class ReviewChangesAction : CodeReviewAction("tool_window") {
    init {
        templatePresentation.text = KiloBundle.message("action.Kilo.CodeReview.Changes.text")
        templatePresentation.description = KiloBundle.message("action.Kilo.CodeReview.Changes.description")
    }
}

/** Editor popup: review this file, or the selection when one exists. */
class ReviewEditorAction : CodeReviewAction("editor_popup") {
    init {
        templatePresentation.text = KiloBundle.message("action.Kilo.CodeReview.File.text")
        templatePresentation.description = KiloBundle.message("action.Kilo.CodeReview.File.description")
    }

    override fun customize(e: AnActionEvent) {
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.text = KiloBundle.message(
            if (hasSelection) "action.Kilo.CodeReview.Selection.text" else "action.Kilo.CodeReview.File.text",
        )
    }

    override fun target(e: AnActionEvent, root: String): ReviewTarget? {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.path ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return ReviewTarget.fromEditor(file, root, null)
        val model = editor.selectionModel
        val lines = if (model.hasSelection()) {
            (editor.document.getLineNumber(model.selectionStart) + 1)..(editor.document.getLineNumber(model.selectionEnd) + 1)
        } else {
            null
        }
        return ReviewTarget.fromEditor(file, root, lines)
    }
}

/** Project view popup: review the selected directory. */
class ReviewDirectoryAction : CodeReviewAction("project_view") {
    init {
        templatePresentation.text = KiloBundle.message("action.Kilo.CodeReview.Directory.text")
        templatePresentation.description = KiloBundle.message("action.Kilo.CodeReview.Directory.description")
    }

    override fun target(e: AnActionEvent, root: String): ReviewTarget? {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (!file.isDirectory) return null
        return ReviewTarget.fromView(file.path, isDirectory = true, root = root)
    }
}
