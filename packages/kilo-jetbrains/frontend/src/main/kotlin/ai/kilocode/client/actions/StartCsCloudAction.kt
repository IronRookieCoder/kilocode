package ai.kilocode.client.actions

import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.telemetry.Telemetry
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

/** Starts the local cs-cloud daemon via the backend (`csc cloud start`). */
class StartCsCloudAction : AnAction(), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        Telemetry.send("cs-cloud start clicked", mapOf("surface" to "connection"))
        service<KiloAppService>().startCsCloudAsync()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = true
        if (e.place == KiloActionPlaces.connectionRetryPopup()) {
            e.presentation.text = KiloBundle.message("action.Kilo.StartCsCloud.text")
        }
    }
}
