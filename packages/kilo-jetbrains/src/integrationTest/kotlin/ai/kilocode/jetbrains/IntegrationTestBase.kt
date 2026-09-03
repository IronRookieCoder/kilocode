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
import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.getToolWindow
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.ui.UiText
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.components.common.ideFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Base for Starter/Driver integration tests that run the built plugin against a real IDE
 * with every daemon request served by an in-process [FakeCsCloudDaemon] (spec §3).
 *
 * Per test it:
 *  - starts the mock daemon on a fixed port (high convention value; `M16.3` depends on it),
 *  - generates a throwaway fixture project (README + empty `src/`, no machine-specific paths),
 *  - creates an isolated home for the sandbox IDE and points only that home's
 *    `.costrict/cs-cloud/server_url` at the mock.
 *
 * "Class = one IDE session, scenario segment = one ordered assertion block" (spec §3.1): keep
 * 1-2 long test methods per class; each [runPluginIde] call costs one IDE launch (~2 min).
 */
abstract class IntegrationTestBase {

    companion object {
        /** G1 冷启动基线: connection must reach ready within this window after IDE startup. */
        const val READY_TIMEOUT_MS = 60_000L

        const val PLUGIN_ID = "ai.costrict.jetbrains"
        const val TOOL_WINDOW_ID = "Costrict"
    }

    protected lateinit var daemon: FakeCsCloudDaemon
        private set

    protected lateinit var fixtureProjectDir: Path
        private set

    protected lateinit var cloud: Path
        private set

    private lateinit var home: Path

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
                        // The SSE transport is killed while the IDE process tears down (daemon
                        // stop / socket close mid-read). That teardown abort is an artifact of
                        // the harness, not a product error — everything else still fails the test.
                        val transportTeardown = listOf(
                            "SocketDispatcher.read0",
                            "Connection reset",
                            "你的主机中的软件中止了一个已建立的连接",
                            "An established connection was aborted by the software in your host machine",
                            "Forcibly closed by the remote host",
                        ).any { details.contains(it) }
                        if (!transportTeardown) throw AssertionError("$testName fails: $message. \n$details")
                    }
                }
            }
        }
    }

    @BeforeEach
    fun setUpDaemonAndFixture() {
        System.setProperty("kilo.integrationTest.mock.requestLog", mockRequestLog.toString())
        Files.createDirectories(mockRequestLog.parent)
        Files.writeString(mockRequestLog, "\n==== ${getClass()}: new mock session ====\n")
        daemon = FakeCsCloudDaemon(FakeCsCloudDaemon.portFromProperty()).start()
        fixtureProjectDir = createFixtureProject()
        home = Files.createTempDirectory("costrict-it-home")
        cloud = home.resolve(".costrict").resolve("cs-cloud")
        configureDaemonEndpoint()
    }

    /** Every request the mock serves during this test, appended in arrival order. */
    private val mockRequestLog: Path =
        Paths.get("build", "integrationTest-mock-requests.log").toAbsolutePath()

    private fun getClass(): String = this::class.java.simpleName

    @AfterEach
    fun tearDownDaemonAndFixture() {
        daemon.stop()
        fixtureProjectDir.takeIf { Files.exists(it) }?.let { deleteRecursively(it) }
        home.takeIf { Files.exists(it) }?.let { deleteRecursively(it) }
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
        // The sandbox IDE inherits the machine's zh locale (imported config / system language),
        // which translates the platform UI and breaks every English-text driver lookup (Settings
        // dialog, menus). Force the platform UI to English.
        context.ide.vmOptions.addSystemProperty("user.language", "en")
        context.ide.vmOptions.addSystemProperty("user.country", "US")
        context.ide.vmOptions.addSystemProperty("user.home", home.toString())
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
    /**
     * G1 冷启动基线: wait for the fixture project to be fully open (the driver client can race
     * project init), open the tool window (the product connects lazily on first tool window
     * content — there is no eager boot-time connect), then the connection must reach ready
     * within [timeoutMs].
     */
    protected fun Driver.awaitColdStartReady(timeoutMs: Long = READY_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (runCatching { getOpenProjects() }.getOrNull().isNullOrEmpty()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Fixture project never opened within ${timeoutMs}ms")
            Thread.sleep(500)
        }
        openCostrictToolWindow()
        val healthBefore = daemon.requests.count { it.path == "/api/v1/runtime/health" }
        val sseBefore = daemon.requests.count { it.path == "/api/v1/events" }
        daemon.awaitNewRequest("GET", "/api/v1/runtime/health", healthBefore, timeoutMs)
        daemon.awaitNewRequest("GET", "/api/v1/events", sseBefore, timeoutMs)
    }

    // ------------------------------------------------------------------
    // Driver helpers
    // ------------------------------------------------------------------

    /** Type into the session prompt editor and send with Enter (Kilo.SendPrompt shortcut). */
    protected fun Driver.sendPrompt(text: String) {
        openCostrictToolWindow()
        awaitSessionUiReady()
        val promptEditor = ui.x {
            byJavaClass("ai.kilocode.client.session.ui.prompt.PromptEditorTextField")
        }
        // Take focus first: a Trial/license editor tab may otherwise own the keyboard.
        promptEditor.click()
        promptEditor.keyboard {
            typeText(text)
            enter()
        }
    }

    /** Open (and return the id of) the Costrict tool window, tolerating the project-init race. */
    protected fun Driver.openCostrictToolWindow() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                getToolWindow(TOOL_WINDOW_ID)?.let { openToolWindow(TOOL_WINDOW_ID) }
            }.onSuccess {
                if (getToolWindow(TOOL_WINDOW_ID) != null) return
            }.onFailure { lastError = it }
            Thread.sleep(1_000)
        }
        throw AssertionError(
            "Tool window '$TOOL_WINDOW_ID' never opened within ${READY_TIMEOUT_MS}ms" +
                (lastError?.let { ": ${it.message}" } ?: ""),
        )
    }

    /** Run [action] against the project frame UI. */
    protected fun Driver.ideFrameUi(action: (IdeaFrameUI) -> Unit) {
        ideFrame(action)
    }

    /**
     * Wait until the session prompt editor exists (session UI fully built). Actions like
     * `Kilo.NewSession` and typed prompts need this component present and focused.
     */
    protected fun Driver.awaitSessionUiReady(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            // `ui.x` resolves lazily — force a remote lookup so absence actually throws.
            runCatching {
                ui.x { byJavaClass("ai.kilocode.client.session.ui.prompt.PromptEditorTextField") }.getAllTexts()
            }.onSuccess { return }.onFailure { lastError = it }
            Thread.sleep(500)
        }
        throw AssertionError("Session prompt editor never appeared within ${timeoutMs}ms", lastError)
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
    // Isolated state: fixture project + daemon endpoint
    // ------------------------------------------------------------------

    private fun createFixtureProject(): Path {
        val dir = Files.createTempDirectory("costrict-it-fixture")
        Files.writeString(dir.resolve("README.md"), "# Fixture\n\nThrowaway project for Costrict integration tests.\n")
        Files.createDirectories(dir.resolve("src"))
        return dir
    }

    /** Point only the sandbox IDE's home at the mock daemon. */
    private fun configureDaemonEndpoint() {
        Files.createDirectories(cloud)
        Files.writeString(cloud.resolve("server_url"), daemon.baseUrl)
        Files.writeString(cloud.resolve("config.json"), """{"api_key":""}""")
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
