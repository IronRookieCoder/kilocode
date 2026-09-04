package ai.kilocode.jetbrains

import ai.kilocode.jetbrains.mock.FavoriteFixture
import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.UiText
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.ui
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JTree

/**
 * [F] Cloud Hub settings page over a scripted favorites facade (spec §8 U6; anchors B8-B11)
 * plus the ★U6.6 启用即生效联动 segment driven by the G3 stateful catalog.
 *
 * The U6.1 **Settings gate** runs first: the Driver must open the modal Settings dialog and
 * land on Tools → Costrict → Agent Behavior → Cloud Hub. If the gate fails, the whole U6 UI
 * batch degrades to service-layer assertions (spec §8 降级路径) — that is a visible failure
 * here, not a silent pass. Permission/question cards are tool-window inline views and are not
 * part of this degradation scope.
 */
class CloudHubPanelTest : IntegrationTestBase() {

    @Test
    fun `cloud hub settings journey`() {
        // Four item types × mixed statuses (U6.2); the command row powers the G3 segment (U6.6).
        daemon.scenario.favorites.addAll(
            listOf(
                FavoriteFixture("fav-skill-1", "Golang Skill", itemType = "skill", status = "Active"),
                FavoriteFixture("fav-skill-2", "Review Skill", itemType = "skill", status = "Cloud"),
                FavoriteFixture("fav-agent-1", "Refactor Agent", itemType = "agent", status = "Downloaded"),
                FavoriteFixture("fav-command-1", "Explain Command", itemType = "command", status = "Unloaded"),
                FavoriteFixture("fav-mcp-1", "Search Mcp", itemType = "mcp", status = "Cloud"),
            ),
        )
        // G3 状态化目录: enabling a favorite must make the catalogs serve it (U6.6).
        daemon.scenario.catalogSideEffects = true

        runPluginIde("costrictCloudHub") {
            awaitColdStartReady()

            // —— Gate (U6.1): open Settings → Tools → Costrict → Agent Behavior → Cloud Hub ——
            ideFrameUi { it.openSettingsDialog() }
            openCloudHubPage()

            // —— Segment U6.2: four item-type sections with mixed statuses render ——
            awaitSettingsText({ it.contains("Golang Skill") }, "favorite row 'Golang Skill'")
            awaitSettingsText({ it.contains("Refactor Agent") }, "favorite row 'Refactor Agent'")
            awaitSettingsText({ it.contains("Explain Command") }, "favorite row 'Explain Command'")
            awaitSettingsText({ it.contains("Search Mcp") }, "favorite row 'Search Mcp'")
            awaitSettingsText({ it == "Active" }, "'Active' badge")

            // —— Segment U6.3: search filters rows ——
            typeIntoSettingsSearch("Golang")
            awaitSettingsText({ it.contains("Golang Skill") }, "'Golang Skill' after filtering")
            assertNoSettingsText({ it.contains("Search Mcp") }, "'Search Mcp' should be filtered out")
            clearSettingsSearch()

            // —— Segment U6.4: Enable → load POST receipt → row flips to Active ——
            assertTrue(clickSettingsText({ it == "Enable" }), "'Enable' affordance never appeared")
            daemon.awaitNewRequest("POST", "/api/v1/agents/favorites", -1, 20_000)
            awaitSettingsText({ it == "Active" }, "row badge after Enable")

            // —— Segment U6.6 (★G3): the loaded command now appears in the commands catalog ——
            assertTrue(
                daemon.scenario.loadedFavorites.contains("fav-command-1"),
                "load POST must arm the stateful catalog",
            )
            invokeAction("Kilo.NewSession")
            awaitCatalogServedWith("Explain Command")

            // —— Segment U6.5: Disable → unload POST receipt → row flips to Unloaded ——
            val unloadBefore = daemon.requests.count { it.path.endsWith("/unload") }
            assertTrue(clickSettingsText({ it == "Disable" }), "'Disable' affordance never appeared")
            awaitUnloadCount(atLeast = unloadBefore + 1)
            awaitSettingsText({ it == "Unloaded" }, "row badge after Disable")

            // —— Request-log sanity: the page fetched the favorites facade at least once ——
            assertTrue(daemon.requests("GET", "/api/v1/agents/favorites").isNotEmpty())
        }
    }

    @Test
    fun `favorites facade failures degrade visibly`() {
        runPluginIde("costrictCloudHubFailures") {
            awaitColdStartReady()

            // —— Segment U6.8/B12: load answers 404 — the page must surface an error, not die.
            // The same launch replays 401/503 through the same affordance (CH-2): each failure
            // mode must surface its typed guidance copy (hubError mapping).
            daemon.scenario.favorites.addAll(
                listOf(FavoriteFixture("fav-vanishing", "Vanishing Skill", itemType = "skill", status = "Cloud")),
            )
            daemon.scenario.override("POST", Regex("/api/v1/agents/favorites/.*/load")) {
                ai.kilocode.jetbrains.mock.MockResponse.error(404, "not_found", "favorite no longer exists")
            }

            ideFrameUi { it.openSettingsDialog() }
            openCloudHubPage()
            awaitSettingsText({ it.contains("Vanishing Skill") }, "favorite row 'Vanishing Skill'")
            assertTrue(clickSettingsText({ it == "Enable" }), "'Enable' affordance never appeared")
            // The dialog survives the failure: branded page still readable (no crash).
            awaitSettingsText({ it.contains("Costrict") || it.contains("Cloud Hub") }, "settings page still alive")

            // —— Segment CH-2a: load answers 401 → UNAUTHORIZED guidance points at sign-in ——
            // Overrides match in insertion order, so re-arm by clearing the list first.
            daemon.scenario.overrides.clear()
            daemon.scenario.override("POST", Regex("/api/v1/agents/favorites/.*/load")) {
                ai.kilocode.jetbrains.mock.MockResponse.error(401, "unauthorized", "auth expired")
            }
            assertTrue(clickSettingsText({ it == "Enable" }), "'Enable' affordance never appeared (401 segment)")
            awaitSettingsText(
                { it.contains("Sign in to CoStrict from the CoStrict connection panel") },
                "UNAUTHORIZED guidance for Cloud Hub",
            )

            // —— Segment CH-2b: load answers 503 → INTERNAL fallback copy, dialog still alive ——
            daemon.scenario.overrides.clear()
            daemon.scenario.override("POST", Regex("/api/v1/agents/favorites/.*/load")) {
                ai.kilocode.jetbrains.mock.MockResponse.error(503, "agent_down", "upstream unavailable")
            }
            assertTrue(clickSettingsText({ it == "Enable" }), "'Enable' affordance never appeared (503 segment)")
            awaitSettingsText({ it.contains("Cloud Hub request failed") }, "INTERNAL fallback copy for Cloud Hub")
        }
    }

    // ------------------------------------------------------------------
    // Settings-driving helpers (the gate). Every U6 UI assertion funnels through
    // them so a gate failure is reported once, pointing at the spec §8 降级路径.
    // ------------------------------------------------------------------

    /** Find the modal Settings dialog; each call re-resolves to survive tree navigation repaints. */
    private fun Driver.settingsDialog(action: DialogUiComponent.() -> Unit) {
        ui.dialog("Settings", action)
    }

    private fun Driver.openCloudHubPage() {
        // Tree path uses display names; the branded root sits under Tools.
        settingsDialog {
            val tree = x(JTreeUiComponent::class.java) { byType(JTree::class.java) }
            tree.clickPath("Tools", "Costrict", "Agent Behavior", "Cloud Hub")
        }
    }

    private fun Driver.typeIntoSettingsSearch(text: String) {
        settingsDialog {
            val field = x { byClass("com.intellij.ui.SearchTextField") }
            field.setFocus()
            field.keyboard { typeText(text) }
        }
    }

    private fun Driver.clearSettingsSearch() {
        settingsDialog {
            val field = x { byClass("com.intellij.ui.SearchTextField") }
            field.setFocus()
            field.keyboard { repeat(32) { backspace() } }
        }
    }

    private fun Driver.awaitSettingsText(matches: (String) -> Boolean, what: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var texts: List<String> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            settingsDialog { texts = getAllTexts().map(UiText::text) }
            texts.firstOrNull(matches)?.let { return }
            Thread.sleep(300)
        }
        throw AssertionError("$what not found in Settings dialog within ${timeoutMs}ms; visible: ${texts.take(80)}")
    }

    private fun Driver.assertNoSettingsText(matches: (String) -> Boolean, message: String) {
        settingsDialog {
            val offending = getAllTexts().map(UiText::text).filter(matches)
            assertTrue(offending.isEmpty(), "$message but saw: $offending")
        }
    }

    private fun Driver.clickSettingsText(matches: (String) -> Boolean, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var clicked = false
            settingsDialog {
                getAllTexts().firstOrNull { uiText -> matches(uiText.text) }?.let { uiText ->
                    uiText.click()
                    clicked = true
                }
            }
            if (clicked) return true
            Thread.sleep(300)
        }
        return false
    }

    private fun awaitCatalogServedWith(needle: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (daemon.servedCatalogResponses().any { it.contains(needle) }) return
            Thread.sleep(200)
        }
        throw AssertionError("commands catalog never served '$needle' after Enable (U6.6)")
    }

    private fun awaitUnloadCount(atLeast: Int, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (daemon.requests.count { it.path.endsWith("/unload") } >= atLeast) return
            Thread.sleep(200)
        }
        throw AssertionError("unload POST count never reached $atLeast within ${timeoutMs}ms")
    }
}
