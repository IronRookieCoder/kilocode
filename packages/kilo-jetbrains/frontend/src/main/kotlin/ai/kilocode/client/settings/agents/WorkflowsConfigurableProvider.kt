package ai.kilocode.client.settings.agents

import ai.kilocode.client.app.KiloAppService
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableProvider

/**
 * Hides the Workflows page while connected through cs-cloud: CSC serves commands as
 * metadata only, so file-level workflows cannot be listed or edited there.
 */
class WorkflowsConfigurableProvider(private val csCloud: Boolean? = null) : ConfigurableProvider() {
    override fun createConfigurable(): Configurable? {
        val hidden = csCloud ?: (service<KiloAppService>().state.value.providerId == "cs-cloud")
        if (hidden) return null
        return WorkflowsConfigurable()
    }
}
