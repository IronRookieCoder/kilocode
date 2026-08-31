package ai.kilocode.client.settings.hub

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.settings.base.DraftReadyConfigurable
import ai.kilocode.client.settings.base.SettingsListPanel
import ai.kilocode.client.settings.base.SettingsMessageException
import ai.kilocode.client.ui.UiStyle
import ai.kilocode.client.ui.list.ActiveListBadge
import ai.kilocode.client.ui.list.ActiveListCell
import ai.kilocode.client.ui.list.ActiveListConfig
import ai.kilocode.client.ui.list.ActiveListItem
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.KiloAppRpcApi
import ai.kilocode.rpc.dto.CloudFavoriteItem
import ai.kilocode.rpc.dto.CloudFavoritesErrors
import kotlinx.coroutines.CoroutineScope
import javax.swing.JComponent

/** Settings page listing Costrict cloud favorites; see docs/superpowers/specs/2026-08-31-jetbrains-cloud-hub-panel-design.md. */
class CloudHubConfigurable : DraftReadyConfigurable<JComponent>() {
    override fun getId(): String = ID
    override fun getDisplayName(): String = KiloBundle.message("settings.agentBehavior.cloudHub.displayName")

    override fun create(cs: CoroutineScope): JComponent = CloudHubSettingsUi(cs)

    override fun onReadyComponentCreated(component: JComponent) {
        (component as? CloudHubSettingsUi)?.reload()
    }

    companion object {
        const val ID = "ai.kilocode.jetbrains.settings.agentBehavior.cloudHub"
    }
}

internal class CloudHubSettingsUi(
    scope: CoroutineScope,
) : SettingsListPanel(scope, ActiveListConfig.Equal.copy(tooltip = false)) {
    private companion object {
        val LOG = KiloLog.create(CloudHubSettingsUi::class.java)
        val KNOWN_ITEM_TYPES = setOf("skill", "agent", "command", "mcp")
    }

    private var cache: List<CloudFavoriteItem> = emptyList()

    /** Authoritative row updates from load/unload responses; merged into the next fetch. */
    private val overrides = mutableMapOf<String, CloudFavoriteItem>()

    init {
        start()
    }

    override suspend fun fetch(): List<ActiveListItem> {
        val result = KiloAppRpcApi.getInstance().cloudFavorites()
        if (!result.ok) throw SettingsMessageException(hubError(result.errorCode, result.errorMessage))
        cache = mergeOverrides(result.items)
        cache.filterNot { it.itemType in KNOWN_ITEM_TYPES }.forEach {
            LOG.warn("cloud hub fetch: filtered unknown itemType='${it.itemType}' id='${it.id}'")
        }
        return HubRowLogic.ordered(cache).map(::row)
    }

    override fun onCell(key: String, cellId: String) {
        val item = cache.find { it.id == key } ?: return
        when (cellId) {
            HubRowLogic.ENABLE_CELL -> mutateAndReload { act(HubRowLogic.ENABLE_CELL, item) }
            HubRowLogic.DISABLE_CELL -> mutateAndReload { act(HubRowLogic.DISABLE_CELL, item) }
        }
    }

    private suspend fun act(cellId: String, item: CloudFavoriteItem): Boolean {
        val api = KiloAppRpcApi.getInstance()
        val result = if (cellId == HubRowLogic.ENABLE_CELL) api.loadCloudFavorite(item.id) else api.unloadCloudFavorite(item.id)
        if (!result.ok) throw SettingsMessageException(hubError(result.errorCode, result.errorMessage))
        result.item?.let { overrides[it.id] = it }
        return true
    }

    /** csc's list cache is 30s shared, so a row just acted on may come back stale; response items win. */
    private fun mergeOverrides(items: List<CloudFavoriteItem>): List<CloudFavoriteItem> {
        if (overrides.isEmpty()) return items
        val merged = items.map { overrides[it.id] ?: it }
        overrides.clear()
        return merged
    }

    override fun searchPlaceholder() = KiloBundle.message("settings.agentBehavior.cloudHub.search")

    override fun emptyText() = KiloBundle.message("settings.agentBehavior.cloudHub.empty")

    override fun loadingText() = KiloBundle.message("settings.agentBehavior.cloudHub.loading")

    private fun row(item: CloudFavoriteItem): ActiveListItem = object : ActiveListItem {
        override val key = item.id
        override val title = item.name.ifBlank { item.slug.ifBlank { item.id } }
        override val description = item.description
        override val search = "${item.slug} ${item.itemType}"
        override val section = sectionLabel(item.itemType)
        override val badges = listOf(ActiveListBadge(statusLabel(item.status), badgeStyle(item.status)))
        override val cells = listOf(
            ActiveListCell(
                HubRowLogic.cellId(item.status),
                cellLabel(item.status),
                primary = true,
            ),
        )
    }

    private fun badgeStyle(status: String) =
        if (status == HubRowLogic.ACTIVE) UiStyle.Badge.Primary else UiStyle.Badge.Secondary

    private fun sectionLabel(itemType: String) = KiloBundle.message(
        when (itemType) {
            "skill" -> "settings.agentBehavior.cloudHub.section.skills"
            "agent" -> "settings.agentBehavior.cloudHub.section.agents"
            "command" -> "settings.agentBehavior.cloudHub.section.commands"
            else -> "settings.agentBehavior.cloudHub.section.mcp"
        },
    )

    private fun cellLabel(status: String) = KiloBundle.message(
        if (status == HubRowLogic.ACTIVE) {
            "settings.agentBehavior.cloudHub.cell.disable"
        } else {
            "settings.agentBehavior.cloudHub.cell.enable"
        },
    )

    private fun statusLabel(status: String) = when (status) {
        HubRowLogic.ACTIVE -> KiloBundle.message("settings.agentBehavior.cloudHub.badge.active")
        HubRowLogic.DOWNLOADED -> KiloBundle.message("settings.agentBehavior.cloudHub.badge.downloaded")
        HubRowLogic.CLOUD -> KiloBundle.message("settings.agentBehavior.cloudHub.badge.cloud")
        HubRowLogic.UNLOADED -> KiloBundle.message("settings.agentBehavior.cloudHub.badge.unloaded")
        else -> status
    }

    private fun hubError(code: String?, fallback: String?): String = when (code) {
        CloudFavoritesErrors.UNAUTHORIZED -> KiloBundle.message("settings.agentBehavior.cloudHub.error.unauthorized")
        CloudFavoritesErrors.UNAVAILABLE -> KiloBundle.message("settings.agentBehavior.cloudHub.error.unavailable")
        CloudFavoritesErrors.NOT_FOUND -> KiloBundle.message("settings.agentBehavior.cloudHub.error.notfound")
        else -> {
            fallback?.takeIf { it.isNotBlank() }?.let { LOG.warn("cloud hub internal error detail: $it") }
            KiloBundle.message("settings.agentBehavior.cloudHub.error.internal")
        }
    }
}

/** Pure presentation logic for the hub list; no platform or bundle dependencies. */
internal object HubRowLogic {
    const val ACTIVE = "Active"
    const val DOWNLOADED = "Downloaded"
    const val CLOUD = "Cloud"
    const val UNLOADED = "Unloaded"
    const val ENABLE_CELL = "enable"
    const val DISABLE_CELL = "disable"
    private val SECTION_ORDER = listOf("skill", "agent", "command", "mcp")

    fun ordered(items: List<CloudFavoriteItem>): List<CloudFavoriteItem> = items
        .filter { it.itemType in SECTION_ORDER }
        .sortedWith(
            compareBy(
                { SECTION_ORDER.indexOf(it.itemType) },
                { statusRank(it.status) },
                { it.name.lowercase() },
            ),
        )

    fun statusRank(status: String): Int = when (status) {
        ACTIVE -> 0
        DOWNLOADED -> 1
        CLOUD -> 2
        UNLOADED -> 3
        else -> 4
    }

    fun cellId(status: String): String = if (status == ACTIVE) DISABLE_CELL else ENABLE_CELL
}
