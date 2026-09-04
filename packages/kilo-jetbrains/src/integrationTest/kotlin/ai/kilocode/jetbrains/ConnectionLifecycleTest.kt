package ai.kilocode.jetbrains

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.singleProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * [F] First-connection journey over a real IDE (spec §6 U2; M10 连接侧) plus the in-session
 * daemon-outage self-heal (M17) and the startup-failure diagnostics (M18/U2.5, M25.2/U2.4).
 * Each method = one IDE launch; the second method pays one extra launch for a fresh connection
 * state machine (already-Connected sessions replay degraded paths, not first-connect paths).
 *
 * The missing-server_url launch also covers the onboarding surface (P1-1/P1-7, OB-2/3/4): the
 * guide-card affordance visibility, the one-shot install balloon, and the B2 rule that the
 * legacy Core download banner never appears anywhere in the lifecycle (HD-4).
 */
class ConnectionLifecycleTest : IntegrationTestBase() {

    @Test
    fun `connection journey on a healthy daemon survives a daemon outage`() {
        runPluginIde("costrictConnectionHealthy") {
            // —— Segment U2.1 + U2.6 (G1): health + SSE → connection auto-ready ——
            awaitColdStartReady()
            // Ready state hides the ConnectionPanel: no retry affordance left in the frame.
            assertNoFrameText({ it.contains("Try again") })
            // B2 (HD-4): the legacy Core download banner stays hidden in the ready state.
            assertNoFrameText({ it.contains("Downloading") })

            // —— Segment U2.2: model catalog loaded from the mock ——
            daemon.awaitRequest("GET", "/api/v1/agents/models", READY_TIMEOUT_MS)

            // —— Segment U2.7 (M15): every forwarded workspace header equals the fixture root ——
            daemon.assertWorkspaceHeaderEquals(fixtureProjectDir.toString())

            // —— Segment M17: daemon dies → typed diagnostics ——
            daemon.stop()
            awaitFrameText({ it.contains("Try again") }, timeoutMs = 30_000)

            // —— Segment M17 (self-heal): daemon returns on the same port → auto-ready ——
            val sseBefore = daemon.requests.count { it.path == "/api/v1/events" }
            daemon.start()
            daemon.awaitNewRequest("GET", "/api/v1/events", sseBefore, READY_TIMEOUT_MS)
            // B2 (HD-4): still no download banner after the outage/self-heal round-trip.
            assertNoFrameText({ it.contains("Downloading") })
        }
    }

    @Test
    fun `startup failures surface typed diagnostics`() {
        // —— Launch A: U2.5/M18 — daemon answers 401 → "unauthorized" diagnostic, not a bare error.
        // (LoginRequiredView rendering is covered by T1; the browser OAuth flow itself is T3.)
        daemon.scenario.failHealth(401, "unauthorized", "invalid or missing API key")
        runPluginIde("costrictConnectionUnauthorized") {
            awaitFrameText({ it.contains("unauthorized", ignoreCase = true) }, timeoutMs = READY_TIMEOUT_MS)
            // B2 (HD-4): even on the error path the legacy download banner never appears.
            assertNoFrameText({ it.contains("Downloading") })
        }

        // —— Launch B: U2.4/M25.2 — non-loopback server_url → typed diagnostic, zero mock traffic.
        // Rewritten before the IDE starts, so the resolver must reject before reaching the daemon.
        daemon.stop()
        val serverUrl = cloud.resolve("server_url")
        val original = Files.readString(serverUrl)
        try {
            Files.writeString(serverUrl, "https://costrict.example.invalid:8443")
            daemon.start()
            val healthBefore = daemon.requests.count { it.path == "/api/v1/runtime/health" }
            runPluginIde("costrictConnectionNonLoopback") {
                awaitFrameText({ it.contains("loopback", ignoreCase = true) }, timeoutMs = READY_TIMEOUT_MS)
                assertEquals(
                    healthBefore,
                    daemon.requests.count { it.path == "/api/v1/runtime/health" },
                    "non-loopback URL must be rejected before any request reaches the daemon (M20b)",
                )
            }
        } finally {
            daemon.stop()
            Files.writeString(serverUrl, original)
            daemon.start()
        }

        // —— Launch C: B5 等价 — server_url absent reads as "csc never ran" (MissingUrl →
        //   csc_not_installed). Same observable the CI-only 未装 csc masking produces, without
        //   needing @costrict/csc absence on this machine.
        daemon.stop()
        try {
            Files.deleteIfExists(serverUrl)
            runPluginIde("costrictConnectionMissingUrl") {
                // The guide surfaces live in the tool window (connection banner card + empty-session
                // welcome card) and the product connects lazily — open it to drive the failure path.
                openCostrictToolWindow()
                awaitFrameText({ it.contains("was not found", ignoreCase = true) }, timeoutMs = READY_TIMEOUT_MS)
                // Typed recovery (OB-2/OB-4): the Install csc affordance is rendered by both guide
                // surfaces; this frame-level check covers ConnectionPanel and EmptySessionPanel.
                // SAFETY: assert presence only — clicking would run `npm install -g @costrict/csc`
                // for real on this machine. The click/cancel chain stays manual
                // (docs/cs-plugin-p2-onboarding-manual-verification.md); IntegrationTestBase has no
                // PATH/env injection that would let a test stub npm.
                awaitFrameText({ it.contains("Install csc", ignoreCase = true) }, timeoutMs = READY_TIMEOUT_MS)
                // B2 (HD-4): the legacy Core download banner stays hidden here too.
                assertNoFrameText({ it.contains("Downloading") })

                // OB-3: the one-shot install balloon — fires exactly once per IDE session
                // (OFFERED dedupe), although background discovery keeps re-failing for a while.
                val project = singleProject()
                awaitNotification(project, emptyList()) {
                    it.contains("Install csc to connect to cs-cloud") && it.contains("@costrict/csc")
                }
                // Settle long enough for a duplicate balloon (broken dedupe) to show up, then
                // require the balloon to still be a single entry in the notification list.
                Thread.sleep(5_000)
                val balloons = notificationSnapshot(project).filter { it.contains("@costrict/csc") }
                assertEquals(1, balloons.size, "install balloon must fire exactly once per IDE session (OFFERED)")
            }
        } finally {
            daemon.start()
        }
    }

    // ------------------------------------------------------------------
    // Onboarding (OB-3): balloon notifications via the driver notification API
    // ------------------------------------------------------------------

    private fun Driver.notificationSnapshot(project: Project) =
        getNotifications(project).map { "${it.getGroupId()}|${it.getTitle()}|${it.getContent()}" }

    private fun Driver.awaitNotification(
        project: Project,
        before: List<String>,
        timeoutMs: Long = 30_000,
        matches: (String) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = notificationSnapshot(project)
        while (System.currentTimeMillis() < deadline) {
            val fresh = latest - before.toSet()
            if (fresh.any(matches)) return
            Thread.sleep(300)
            latest = notificationSnapshot(project)
        }
        throw AssertionError("expected a matching notification within ${timeoutMs}ms; latest: ${latest.takeLast(10)}")
    }
}
