package ai.kilocode.jetbrains

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * [F] First-connection journey over a real IDE (spec §6 U2; M10 连接侧) plus the in-session
 * daemon-outage self-heal (M17) and the startup-failure diagnostics (M18/U2.5, M25.2/U2.4).
 * Each method = one IDE launch; the second method pays one extra launch for a fresh connection
 * state machine (already-Connected sessions replay degraded paths, not first-connect paths).
 */
class ConnectionLifecycleTest : IntegrationTestBase() {

    @Test
    fun `connection journey on a healthy daemon survives a daemon outage`() {
        runPluginIde("costrictConnectionHealthy") {
            // —— Segment U2.1 + U2.6 (G1): health + SSE → connection auto-ready ——
            awaitColdStartReady()
            // Ready state hides the ConnectionPanel: no retry affordance left in the frame.
            assertNoFrameText({ it.contains("Try again") })

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
        }
    }

    @Test
    fun `startup failures surface typed diagnostics`() {
        // —— Launch A: U2.5/M18 — daemon answers 401 → "unauthorized" diagnostic, not a bare error.
        // (LoginRequiredView rendering is covered by T1; the browser OAuth flow itself is T3.)
        daemon.scenario.failHealth(401, "unauthorized", "invalid or missing API key")
        runPluginIde("costrictConnectionUnauthorized") {
            awaitFrameText({ it.contains("unauthorized", ignoreCase = true) }, timeoutMs = READY_TIMEOUT_MS)
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
                awaitFrameText({ it.contains("was not found", ignoreCase = true) }, timeoutMs = READY_TIMEOUT_MS)
                // Typed recovery: the diagnostic code maps to InstallCsc/StartCsCloud affordances
                // (asserted exhaustively at T1 by KiloRecoveryActionsTest).
            }
        } finally {
            daemon.start()
        }
    }
}
