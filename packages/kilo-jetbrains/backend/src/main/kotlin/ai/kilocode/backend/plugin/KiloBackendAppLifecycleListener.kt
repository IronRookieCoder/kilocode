package ai.kilocode.backend.plugin

import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.log.KiloLog
import ai.kilocode.stability.StabilityService
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.serviceIfCreated

class KiloBackendAppLifecycleListener : AppLifecycleListener {
    private val log = KiloLog.create(KiloBackendAppLifecycleListener::class.java)

    override fun appWillBeClosed(isRestart: Boolean) {
        log.info("appWillBeClosed(isRestart=$isRestart) — stopping Kilo CLI")
        runCatching {
            serviceIfCreated<KiloBackendAppService>()?.shutdownForAppClose()
            // serviceIfCreated (not service): never create the collector at shutdown.
            serviceIfCreated<StabilityService>()?.stop("app_close")
        }.onFailure { log.warn("Failed to stop CLI on app close", it) }
    }
}
