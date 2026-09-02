package ai.kilocode.jetbrains

import ai.kilocode.jetbrains.mock.FakeCsCloudDaemon
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.ide.installer.IdeInstallerFactory
import com.intellij.ide.starter.models.IDEStartResult
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.getToolWindow
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.ui.UiText
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Base for Starter/Driver integration tests that run the built plugin against a real IDE
 * with every daemon request served by an in-process [FakeCsCloudDaemon] (spec §3).
 *
 * Per test it:
 *  - starts the mock daemon on a fixed port (high convention value; `M16.3` depends on it),
 *  - generates a throwaway fixture project (README + empty `src/`, no machine-specific paths),
 *  - redirects `~/.costrict/cs-cloud/server_url` at the mock and blanks `config.json`'s
 *    `api_key` (backup + restore in tearDown **and** a JVM shutdown hook as double insurance
 *    against a killed process leaking the mock address),
 *  - holds a JVM-wide lock: `~/.costrict` is machine-shared state, tests must run serially
 *    (`maxParallelForks = 1` in build.gradle.kts is the build-side guard).
 *
 * "Class = one IDE session, scenario segment = one ordered assertion block" (spec §3.1): keep
 * 1-2 long test methods per class; each [runPluginIde] call costs one IDE launch (~2 min).
 */
abstract class IntegrationTestBase {

    companion object {
        /** Guards the machine-wide `~/.costrict` rewrite plus the fixed daemon port. */
        private val MACHINE_LOCK = ReentrantLock()

        /** G1 冷启动基线: connection must reach ready within this window after IDE startup. */
        const val READY_TIMEOUT_MS = 60_000L

        const val PLUGIN_ID = "ai.kilocode.jetbrains"
        const val TOOL_WINDOW_ID = "Costrict"
    }

    protected lateinit var daemon: FakeCsCloudDaemon
        private set

    protected lateinit var fixtureProjectDir: Path
        private set

    private var serverUrlBackup: Path? = null
    private var serverUrlContent: String? = null
    private var daemonConfigBackup: Path? = null
    private var daemonConfigContent: String? = null
    private var restoreHook: Thread? = null

    init {
        // Same DI overrides as PluginTest: use the locally cached IDE and turn IDE-process
        // exceptions (collected via MessageBus) into test failures through CIServer.
        di = DI {
            extend(di)
            val localIdeHome = System.getProperty("kilo.integrationTest.ideHome")
            if (!localIdeHome.isNullOrBlank()) {
                bindSingleton<IdeInstallerFactory>(overrides = true) {
                    object : IdeInstallerFactory() {
                        override fun createInstaller(ideInfo: IdeInfo, downloader: IdeDownloader): IdeInstaller =
                            ExistingIdeInstaller(Path.of(localIdeHome))
                    }
                }
            }
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?,
                    ) {
                        throw AssertionError("$testName fails: $message. \n$details")
                    }
                }
            }
        }
    }

    @BeforeEach
    fun setUpDaemonAndFixture() {
        MACHINE_LOCK.withLock {
            daemon = FakeCsCloudDaemon(FakeCsCloudDaemon.portFromProperty()).start()
            fixtureProjectDir = createFixtureProject()
            redirectDaemonEndpoint()
        }
    }

    @AfterEach
    fun tearDownDaemonAndFixture() {
        MACHINE_LOCK.withLock {
            try {
                restoreDaemonEndpoint()
            } finally {
                daemon.stop()
                restoreHook?.let { Runtime.getRuntime().removeShutdownHook(it) }
                restoreHook = null
                fixtureProjectDir.takeIf { Files.exists(it) }?.let { deleteRecursively(it) }
            }
        }
    }

    // ------------------------------------------------------------------
    // IDE lifecycle
    // ------------------------------------------------------------------

    /**
     * One full IDE launch with the built plugin ZIP installed; [driverAssertions] runs inside
     * `useDriverAndCloseIde` (receiver `Driver`), then the IDE is closed and awaited.
     */
    protected fun runPluginIde(testName: String, driverAssertions: Driver.() -> Unit): IDEStartResult {
        val zipPath = requireNotNull(System.getProperty("path.to.build.plugin")) {
            "path.to.build.plugin is not set; run integration tests via the Gradle integrationTest task"
        }
        // No withVersion()/useRelease(): they bypass the DI installer binding (see PluginTest).
        val context = Starter.newContext(
            testName = testName,
            TestCase(IdeProductProvider.IU, LocalProjectInfo(fixtureProjectDir)),
        )
        PluginConfigurator(context).installPluginFromPath(Path.of(zipPath))
        return context.runIdeWithDriver().useDriverAndCloseIde { driverAssertions() }
    }

    /**
     * G1 冷启动基线断言: within [timeoutMs] of IDE startup the plugin discovers the mock daemon
     * (health) and opens its event stream (SSE open = plugin-side `Connected`). Every test
     * class inherits "restart IDE → auto reconnect" for free by calling this first.
     *
     * Count-aware: the request log accumulates across IDE launches within one daemon, so the
     * awaited records must be strictly newer than whatever existed when this call started.
     */
    protected fun awaitColdStartReady(timeoutMs: Long = READY_TIMEOUT_MS) {
        val healthBefore = daemon.requests.count { it.path == "/api/v1/runtime/health" }
        val sseBefore = daemon.requests.count { it.path == "/api/v1/events" }
        daemon.awaitNewRequest("GET", "/api/v1/runtime/health", healthBefore, timeoutMs)
        daemon.awaitNewRequest("GET", "/api/v1/events", sseBefore, timeoutMs)
    }

    // ------------------------------------------------------------------
    // Driver helpers
    // ------------------------------------------------------------------

    /** Open (and return the id of) the Costrict tool window. */
    protected fun Driver.openCostrictToolWindow() {
        getToolWindow(TOOL_WINDOW_ID) ?: throw AssertionError("Tool window '$TOOL_WINDOW_ID' is not registered")
        openToolWindow(TOOL_WINDOW_ID)
    }

    /** Run [action] against the project frame UI. */
    protected fun Driver.ideFrameUi(action: (IdeaFrameUI) -> Unit) {
        ideFrame(action)
    }

    /** All visible texts of the project frame — coarse but robust for brand/state assertions. */
    protected fun Driver.frameTexts(): List<String> {
        val texts = mutableListOf<String>()
        ideFrame { texts += getAllTexts().map(UiText::text) }
        return texts
    }

    /** Poll the frame texts until one matches, then click it. */
    protected fun Driver.clickFrameText(matches: (String) -> Boolean, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var clicked = false
            ideFrame {
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

    /** Wait until any frame text matches [matches]; fails with the current texts. */
    protected fun Driver.awaitFrameText(matches: (String) -> Boolean, timeoutMs: Long = 15_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen: List<String> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            seen = frameTexts()
            seen.firstOrNull(matches)?.let { return it }
            Thread.sleep(300)
        }
        throw AssertionError("Frame text not found within ${timeoutMs}ms; visible texts: ${seen.take(80)}")
    }

    /** Assert no frame text matches (e.g. panel hidden in the ready state). */
    protected fun Driver.assertNoFrameText(matches: (String) -> Boolean, settleMs: Long = 2_000) {
        Thread.sleep(settleMs)
        val offending = frameTexts().filter(matches)
        check(offending.isEmpty()) { "Unexpected frame text(s): $offending" }
    }

    // ------------------------------------------------------------------
    // Security (M20a): credentials must not leak into the IDE log
    // ------------------------------------------------------------------

    /** The plugin-visible IDE log of the finished run, or null when the file is absent. */
    protected fun ideLogText(result: IDEStartResult): String? {
        val log = runCatching { result.runContext.logsDir.resolve("idea.log") }.getOrNull() ?: return null
        return if (Files.exists(log)) Files.readString(log) else null
    }

    /** Assert the run log contains none of the credential values known to the test process. */
    protected fun assertIdeLogHasNoCredentials(log: String?) {
        if (log == null) return
        val secrets = listOfNotNull(
            System.getenv("CS_BRIDGE_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
            System.getenv("CS_CLOUD_API_KEY")?.trim()?.takeIf { it.isNotEmpty() },
        )
        for (secret in secrets) {
            if (log.contains(secret)) throw AssertionError("Credential value leaked into idea.log")
        }
    }

    // ------------------------------------------------------------------
    // Machine state: fixture project + daemon endpoint redirect
    // ------------------------------------------------------------------

    private fun createFixtureProject(): Path {
        val dir = Files.createTempDirectory("costrict-it-fixture")
        Files.writeString(dir.resolve("README.md"), "# Fixture\n\nThrowaway project for Costrict integration tests.\n")
        Files.createDirectories(dir.resolve("src"))
        return dir
    }

    private fun costrictDir(): Path = userHome().resolve(".costrict").resolve("cs-cloud")

    private fun userHome(): Path = Paths.get(System.getProperty("user.home"))

    /** Point `~/.costrict/cs-cloud/server_url` at the mock; blank the config key if present. */
    private fun redirectDaemonEndpoint() {
        val dir = costrictDir()
        Files.createDirectories(dir)
        val serverUrl = dir.resolve("server_url")
        if (Files.exists(serverUrl)) {
            serverUrlBackup = serverUrl
            serverUrlContent = Files.readString(serverUrl)
        }
        Files.writeString(serverUrl, daemon.baseUrl)
        val config = dir.resolve("config.json")
        if (Files.exists(config)) {
            // Key resolution order is CS_BRIDGE_API_KEY > CS_CLOUD_API_KEY > config.json; a blank
            // config key keeps the daemon unauthenticated for the mock (M1.1 semantics).
            daemonConfigBackup = config
            daemonConfigContent = Files.readString(config)
            Files.writeString(config, """{"api_key":""}""")
        }
        // Double insurance: even a killed test JVM must not leave the mock address behind.
        val hook = Thread {
            runCatching {
                serverUrlBackup?.let { Files.writeString(it, serverUrlContent.orEmpty()) }
                    ?: Files.deleteIfExists(dir.resolve("server_url"))
                daemonConfigBackup?.let { Files.writeString(it, daemonConfigContent.orEmpty()) }
            }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        restoreHook = hook
    }

    private fun restoreDaemonEndpoint() {
        restoreHook?.let { Runtime.getRuntime().removeShutdownHook(it) }
        restoreHook = null
        val dir = costrictDir()
        serverUrlBackup?.let { Files.writeString(it, serverUrlContent.orEmpty()) }
            ?: Files.deleteIfExists(dir.resolve("server_url"))
        daemonConfigBackup?.let { Files.writeString(it, daemonConfigContent.orEmpty()) }
        serverUrlBackup = null
        serverUrlContent = null
        daemonConfigBackup = null
        daemonConfigContent = null
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
