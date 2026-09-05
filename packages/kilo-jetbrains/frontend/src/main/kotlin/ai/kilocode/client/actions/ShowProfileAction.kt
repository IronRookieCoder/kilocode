package ai.kilocode.client.actions

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.telemetry.Telemetry
import ai.kilocode.client.ui.CostrictLinks
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Toolbar action that opens the CoStrict user profile page (credit manager) in the browser.
 */
class ShowProfileAction : DumbAwareAction(
    KiloBundle.message("action.Kilo.ShowProfile.text"),
    KiloBundle.message("action.Kilo.ShowProfile.description"),
    AllIcons.General.User,
) {

    override fun actionPerformed(e: AnActionEvent) {
        Telemetry.send("Profile Settings Opened", mapOf("surface" to "tool_window"))
        BrowserUtil.browse(CostrictLinks.CREDIT_MANAGER)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
