package ai.kilocode.client.session

import ai.kilocode.client.KiloNotifications
import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.session.controller.SessionControllerEvent
import ai.kilocode.client.session.controller.SessionControllerListener
import ai.kilocode.rpc.ConnectionErrorCode
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time setup nudge for the most common first-run failure: the `csc` CLI is missing.
 *
 * The connection banner already explains it, but it is only visible inside the tool window,
 * so this notice carries the same fix to the IDE balloon area. Shown at most once per IDE
 * session, and never again once the user picks "Don't show again" (persisted via
 * [PropertiesComponent]). Registered by the session UI composition root, which is the
 * smallest hook that already sees every connection transition — no polling involved.
 */
class CsCloudSetupNotifier(
    private val project: Project?,
    private val install: () -> Unit = { service<KiloAppService>().installCscAsync() },
    private val props: PropertiesComponent = PropertiesComponent.getInstance(),
    private val show: (String, String, () -> Unit, () -> Unit) -> Unit = { title, content, onInstall, onDismiss ->
        notifyInstallNotice(project, title, content, onInstall, onDismiss)
    },
) : SessionControllerListener {

    override fun onEvent(event: SessionControllerEvent) {
        if (event !is SessionControllerEvent.ConnectionChanged.ShowError) return
        if (event.code != ConnectionErrorCode.CSC_NOT_INSTALLED) return
        maybeShow()
    }

    private fun maybeShow() {
        // App-level dedup: reconnect attempts flip the same failure repeatedly, and every
        // open tool window would otherwise raise its own notice.
        if (!OFFERED.compareAndSet(false, true)) return
        if (props.getBoolean(DISMISSED_KEY, false)) return
        show(
            KiloBundle.message("csCloud.installNotice.title"),
            KiloBundle.message("csCloud.installNotice.content"),
            install,
            { props.setValue(DISMISSED_KEY, true.toString()) },
        )
    }

    companion object {
        /** Persistent "Don't show again" flag, application-wide. */
        const val DISMISSED_KEY = "kilo.csCloud.installNotice.dismissed"

        private val OFFERED = AtomicBoolean(false)

        /** Clears the per-IDE-session dedup (tests, diagnostics). */
        internal fun resetSessionState() {
            OFFERED.set(false)
        }
    }
}

/** Balloon with the install fix and a persistent "Don't show again" opt-out. */
private fun notifyInstallNotice(
    project: Project?,
    title: String,
    content: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    KiloNotifications.suggestion(
        project,
        title,
        content,
        KiloBundle.message("csCloud.installNotice.install"),
        onInstall,
        KiloBundle.message("common.dont.show.again"),
        onDismiss,
    )
}
