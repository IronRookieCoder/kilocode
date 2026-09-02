package ai.kilocode.jetbrains

import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.elements.fileChooser
import com.intellij.driver.sdk.ui.ui
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * ★双项目同进程探索 gate (spec §9.3): two projects inside one IDE process, asserting the
 * workspace header stays root-scoped per project (B13/C13/M23.2 语义主体).
 *
 * Feasibility is explicitly unverified. Run the gate explicitly with
 * `-Dkilo.integrationTest.gate.multiProject=true` (a second fixture dir is derived from the
 * base fixture); when skipped, JUnit marks the test *skipped* — the suite never blocks on it.
 * A gate failure here means the dual-window semantics fall back to "T1 过滤断言 + 人工双窗口
 * 1 轮" per spec §9.3; record the outcome in the results template as 降级.
 */
class MultiProjectSmokeTest : IntegrationTestBase() {

    @Test
    fun `two projects in one process keep workspace headers apart`() {
        Assumptions.assumeTrue(
            System.getProperty("kilo.integrationTest.gate.multiProject") == "true",
            "exploratory gate; enable with -Dkilo.integrationTest.gate.multiProject=true",
        )
        // Second project next to the fixture so both exist before launch.
        val secondProject = fixtureProjectDir.resolveSibling(fixtureProjectDir.fileName.toString() + "-b")
        Files.createDirectories(secondProject)
        Files.writeString(secondProject.resolve("README.md"), "# Fixture B\n")

        runPluginIde("costrictMultiProjectGate") {
            awaitColdStartReady()
            val before = getOpenProjects().size
            openSecondProjectViaDialog(secondProject)

            val deadline = System.currentTimeMillis() + 60_000
            var projects: List<Project> = emptyList()
            while (System.currentTimeMillis() < deadline) {
                projects = getOpenProjects()
                if (projects.size >= before + 1) break
                Thread.sleep(500)
            }
            assertTrue(
                projects.size >= before + 1,
                "second project never opened in the same process — gate failed, " +
                    "fall back to spec §9.3 降级路径 (T1 过滤 + 人工双窗口)",
            )

            // Both projects see the daemon; every workspace header must equal one of the two roots.
            val allowed = setOf(fixtureProjectDir.toString(), secondProject.toString())
            val offending = daemon.requests.mapNotNull { req ->
                val header = req.header("X-Workspace-Directory")
                if (header != null && header !in allowed) req else null
            }
            assertTrue(offending.isEmpty(), "workspace headers escaped both roots: ${offending.take(5)}")
        }
    }

    /** File → Open through the driver file chooser; the exact step the gate exists to verify. */
    private fun com.intellij.driver.client.Driver.openSecondProjectViaDialog(dir: java.nio.file.Path) {
        invokeAction("OpenFile")
        ui.fileChooser({ byTitle("Open File or Project") }) {
            openPath(dir)
            pressButton("OK")
        }
    }
}
