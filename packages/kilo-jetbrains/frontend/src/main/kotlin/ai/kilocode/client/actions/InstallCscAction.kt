package ai.kilocode.client.actions

import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.plugin.KiloBundle
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

/**
 * Installs the Costrict csc CLI via npm on the backend (which then starts the
 * local cs-cloud daemon), so users never have to install it manually.
 */
class InstallCscAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        service<KiloAppService>().installCscAsync()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = true
        if (e.place == KiloActionPlaces.connectionRetryPopup()) {
            e.presentation.text = KiloBundle.message("action.Kilo.InstallCsc.text")
        }
    }

    companion object {
        const val CSC_NPM_URL = "https://www.npmjs.com/package/@costrict/csc"
    }
}
