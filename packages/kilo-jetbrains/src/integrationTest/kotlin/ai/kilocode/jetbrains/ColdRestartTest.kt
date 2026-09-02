package ai.kilocode.jetbrains

import com.intellij.driver.sdk.invokeAction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * G2 ColdRestart orchestration (spec ★U2.6 收口 / U4.3 / U5.3): one test method, two IDE
 * launches — the first seeds state, the second asserts it survives the restart.
 *
 *  - U2.6: both launches are covered by the G1 cold-start baseline (auto-ready within N s).
 *  - U4.3: the conversation created in launch 1 is listed again after restart (history is
 *    served from the daemon, so this pins the plugin's restore path end to end).
 *  - U5.3 (设置改完留得住): deliberately **degraded** for now — driving the modal Settings
 *    tree to a toggle lives with the Settings gate iteration (see CloudHubPanelTest); the
 *    plugin-side persistence itself is covered by T1 (KiloMigrationServiceTest et al.). The
 *    results template must mark U5.3 as "UI 层未覆盖" until the settings segment lands here.
 */
class ColdRestartTest : IntegrationTestBase() {

    @Test
    fun `session survives an ide restart`() {
        // —— Launch 1: seed one conversation through the real UI ——
        runPluginIde("costrictColdRestartSeed") {
            awaitColdStartReady()
            openCostrictToolWindow()
            invokeAction("Kilo.NewSession")
            daemon.awaitRequest("POST", "/api/v1/conversations", 30_000)
            assertTrue(
                daemon.conversations.any { it.contains("Fixture Session") },
                "daemon should persist the created session",
            )
        }

        // —— Launch 2: auto-ready + the seeded session comes back through History ——
        runPluginIde("costrictColdRestartVerify") {
            // G1 baseline: cold reconnect after a full IDE restart (U2.6 收口).
            awaitColdStartReady()
            openCostrictToolWindow()
            invokeAction("Kilo.History")
            awaitFrameText({ it.contains("Fixture Session") }, timeoutMs = 30_000)
        }
    }
}
