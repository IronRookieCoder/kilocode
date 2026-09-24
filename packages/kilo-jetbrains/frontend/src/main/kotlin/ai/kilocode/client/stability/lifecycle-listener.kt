package ai.kilocode.client.stability

import ai.kilocode.stability.StabilityService
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.serviceIfCreated

/**
 * Frontend app-close hook (brief Step 3): forwards the real platform lifecycle event to the
 * shared collector as `stop("app_close")`. Registered in `kilo.jetbrains.frontend.xml`
 * applicationListeners. Uses [serviceIfCreated] so shutdown never instantiates the service,
 * and only calls stop — it adds no global uncaught handler and no other behavior. In monolith
 * mode the backend listener stops the same app service too; stop is CAS-deduplicated.
 */
class StabilityLifecycleListener : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        serviceIfCreated<StabilityService>()?.stop("app_close")
    }
}
