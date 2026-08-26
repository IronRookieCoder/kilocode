package ai.kilocode.client.settings

import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.settings.agents.AgentBehaviorConfigurable
import ai.kilocode.client.settings.autoapprove.AutoApproveConfigurable
import ai.kilocode.client.settings.context.ContextConfigurable
import ai.kilocode.client.settings.models.ModelsConfigurable
import ai.kilocode.client.settings.providers.ProvidersConfigurable
import ai.kilocode.client.settings.profile.UserProfileConfigurable
import ai.kilocode.client.ui.UiStyle
import ai.kilocode.client.ui.layout.Stack
import ai.kilocode.rpc.dto.KiloAppStatusDto
import com.intellij.ide.DataManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.components.service
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Root settings entry under Settings -> Tools -> Kilo Code.
 *
 * Displays a brief description and a link to the User Profile child page.
 * Child configurables are registered in XML (`kilo.jetbrains.frontend.xml`) as
 * `applicationConfigurable` entries with the appropriate `parentId` — that is the
 * single source of truth for the settings hierarchy. This class does NOT implement
 * [com.intellij.openapi.options.SearchableConfigurable.Parent] to avoid creating a
 * second `UserProfileConfigurable` instance alongside the one registered in XML.
 *
 * The link uses [UserProfileConfigurable.ID] to navigate via [Settings.find]/[Settings.select].
 */
class KiloSettingsConfigurable : SearchableConfigurable {

    override fun getId(): String = ID

    override fun getDisplayName(): String = KiloBundle.message("settings.kilo.displayName")

    override fun createComponent(): JComponent {
        val panel = Stack.vertical()
        panel.border = JBUI.Borders.empty(UiStyle.Gap.lg(), 0, 0, 0)

        val app = service<KiloAppService>()
        val state = app.state.value
        val status = JBLabel(statusText(state.status)).apply {
            border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm())
        }
        panel.next(status)
        if (state.status != KiloAppStatusDto.READY) {
            panel.next(ActionLink(KiloBundle.message("settings.connection.retry")) {
                app.retryAsync()
                status.text = KiloBundle.message("settings.connection.retrying")
            }.apply {
                border = JBUI.Borders.emptyBottom(UiStyle.Gap.pad())
            })
        }

        val desc = JBLabel(KiloBundle.message("settings.kilo.description"))
        desc.border = JBUI.Borders.emptyBottom(UiStyle.Gap.pad())
        panel.next(desc)

        val link = ActionLink(KiloBundle.message("settings.profile.displayName")) { e ->
            val src = e.source as? JComponent ?: return@ActionLink
            val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(src)) ?: return@ActionLink
            open(settings, UserProfileConfigurable.ID)
        }
        link.border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm())
        panel.next(link)

        val models = ActionLink(KiloBundle.message("settings.models.displayName")) { e ->
            val src = e.source as? JComponent ?: return@ActionLink
            val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(src)) ?: return@ActionLink
            open(settings, ModelsConfigurable.ID)
        }
        models.border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm())
        panel.next(models)

        val providers = ActionLink(KiloBundle.message("settings.providers.displayName")) { e ->
            val src = e.source as? JComponent ?: return@ActionLink
            val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(src)) ?: return@ActionLink
            open(settings, ProvidersConfigurable.ID)
        }
        providers.border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm())
        panel.next(providers)

        val behavior = ActionLink(KiloBundle.message("settings.agentBehavior.displayName")) { e ->
            val src = e.source as? JComponent ?: return@ActionLink
            val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(src)) ?: return@ActionLink
            open(settings, AgentBehaviorConfigurable.ID)
        }
        behavior.border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm())
        panel.next(behavior)

        val autoApprove = ActionLink(KiloBundle.message("settings.autoApprove.displayName")) { e ->
            val src = e.source as? JComponent ?: return@ActionLink
            val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(src)) ?: return@ActionLink
            open(settings, AutoApproveConfigurable.ID)
        }
        autoApprove.border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm())
        panel.next(autoApprove)

        val context = ActionLink(KiloBundle.message("settings.context.displayName")) { e ->
            val src = e.source as? JComponent ?: return@ActionLink
            val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(src)) ?: return@ActionLink
            open(settings, ContextConfigurable.ID)
        }
        context.border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm())
        panel.next(context)

        val advanced = ActionLink(KiloBundle.message("settings.advanced.displayName")) { e ->
            val src = e.source as? JComponent ?: return@ActionLink
            val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(src)) ?: return@ActionLink
            open(settings, AdvancedConfigurable.ID)
        }
        advanced.border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm())
        panel.next(advanced)

        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    internal fun open(settings: Settings, id: String = UserProfileConfigurable.ID) {
        settings.find(id)?.let { settings.select(it) }
    }

    companion object {
        const val ID = "ai.kilocode.jetbrains.settings"

        internal fun statusText(status: KiloAppStatusDto): String = when (status) {
            KiloAppStatusDto.READY -> KiloBundle.message("settings.connection.ready")
            KiloAppStatusDto.CONNECTING,
            KiloAppStatusDto.DOWNLOADING,
            KiloAppStatusDto.LOADING,
            KiloAppStatusDto.MIGRATION_REQUIRED -> KiloBundle.message("settings.connection.connecting")
            KiloAppStatusDto.ERROR -> KiloBundle.message("settings.connection.error")
            KiloAppStatusDto.DISCONNECTED -> KiloBundle.message("settings.connection.disconnected")
        }
    }
}
