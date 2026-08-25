package ai.kilocode.client.actions

import ai.kilocode.client.plugin.KiloBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Opens the Costrict csc npm package so users can install the CLI that downloads
 * and manages the local cs-cloud daemon.
 */
class InstallCscAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse(CSC_NPM_URL)
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
