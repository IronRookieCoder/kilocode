package ai.kilocode.jetbrains

import com.intellij.driver.sdk.getPlugin
import com.intellij.driver.sdk.isPluginLoaded
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [F] Brand smoke over a real installation (spec U1.1/U1.2, U8.1/U8.2/U8.5; anchors A7/A8/A12).
 *
 * One IDE session, two segments:
 *  1. U1.1/U8.1 — the installed plugin metadata carries the Costrict brand (name via Driver;
 *     vendor/url stay a T0 duty on plugin.xml, asserted by `scripts/brand-consistency-scan`).
 *  2. U1.2/U8.2/U8.5 — the tool window registers under the `Costrict` id, opens cleanly, and
 *     the empty session panel renders the branded welcome copy (BrandLogo load failures would
 *     surface here through the CIServer override). Visual aesthetics remain a 1-round human
 *     pass over the artifacts (spec §14).
 */
class BrandSmokeTest : IntegrationTestBase() {

    @Test
    fun `installed plugin presents Costrict brand`() {
        runPluginIde("costrictBrandSmoke") {
            // —— Segment 1: U1.1 / U8.1 installed metadata ——
            assertTrue(isPluginLoaded(PLUGIN_ID), "plugin $PLUGIN_ID must be loaded")
            val descriptor = getPlugin(PLUGIN_ID)
            assertTrue(descriptor != null, "plugin descriptor for $PLUGIN_ID must resolve")
            assertEquals("Costrict", descriptor?.getName(), "plugin name must be the Costrict brand")

            // G1 冷启动基线 (U2.6) — free for every T2 class.
            awaitColdStartReady()

            // —— Segment 2: U1.2 / U8.2 / U8.5 tool window + empty state ——
            val welcome = awaitFrameText({ it.contains("Costrict is an AI coding assistant") }, timeoutMs = 30_000)
            assertTrue(
                !welcome.contains("Kilo"),
                "user-visible empty state must not carry Kilo remnants (G4 rule ①): $welcome",
            )
        }
    }
}
