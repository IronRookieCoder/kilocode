package ai.kilocode.client.settings.agents

import ai.kilocode.client.app.KiloAppService
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.settings.hub.CloudHubConfigurable
import ai.kilocode.client.settings.rules.RulesConfigurable
import ai.kilocode.client.ui.UiStyle
import ai.kilocode.client.ui.layout.Stack
import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ex.Settings
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

class AgentBehaviorConfigurable(private val csCloud: Boolean? = null) : SearchableConfigurable {
    override fun getId(): String = ID

    override fun getDisplayName(): String = KiloBundle.message("settings.agentBehavior.displayName")

    override fun createComponent(): JComponent {
        val panel = Stack.vertical()
        panel.border = JBUI.Borders.empty(UiStyle.Gap.lg(), 0, 0, 0)
        val desc = JBLabel(KiloBundle.message("settings.agentBehavior.description"))
        desc.border = JBUI.Borders.emptyBottom(UiStyle.Gap.pad())
        panel.next(desc)
        // cs-cloud serves workflows as metadata only, so the entry is hidden there.
        val csCloudMode = csCloud ?: (service<KiloAppService>().state.value.providerId == "cs-cloud")
        listOf(
            KiloBundle.message("settings.agentBehavior.agents.displayName") to AgentsConfigurable.ID,
            KiloBundle.message("settings.agentBehavior.mcp.displayName") to McpConfigurable.ID,
            KiloBundle.message("settings.agentBehavior.skills.displayName") to SkillsConfigurable.ID,
            KiloBundle.message("settings.agentBehavior.cloudHub.displayName") to CloudHubConfigurable.ID,
            KiloBundle.message("settings.agentBehavior.workflows.displayName") to WorkflowsConfigurable.ID,
            KiloBundle.message("settings.agentBehavior.rules.displayName") to RulesConfigurable.ID,
        ).filterNot { csCloudMode && it.second == WorkflowsConfigurable.ID }.forEach { (label, id) ->
            panel.next(ActionLink(label) { e ->
                val src = e.source as? JComponent ?: return@ActionLink
                val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(src)) ?: return@ActionLink
                settings.find(id)?.let { settings.select(it) }
            }.apply { border = JBUI.Borders.emptyBottom(UiStyle.Gap.sm()) })
        }
        return panel
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    companion object {
        const val ID = "ai.kilocode.jetbrains.settings.agentBehavior"
    }
}
