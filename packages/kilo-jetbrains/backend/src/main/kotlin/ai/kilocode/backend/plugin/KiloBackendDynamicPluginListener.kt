package ai.kilocode.backend.plugin

import ai.kilocode.KiloPlugin
import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.log.KiloLog
import ai.kilocode.stability.StabilityService
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated

class KiloBackendDynamicPluginListener : DynamicPluginListener {
    private val log = KiloLog.create(KiloBackendDynamicPluginListener::class.java)

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId != KiloPlugin.id) return
        log.info("Shutting down Kilo backend for plugin unload (isUpdate=$isUpdate)")
        // serviceIfCreated (not service) for the collector: never create it at shutdown.
        serviceIfCreated<StabilityService>()?.stop("unload")
        service<KiloBackendAppService>().shutdownForUnload()
    }
}
