package ai.kilocode.client.session

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.session.controller.SessionControllerEvent
import ai.kilocode.rpc.ConnectionErrorCode
import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

@Suppress("UnstableApiUsage")
class CsCloudSetupNotifierTest : BasePlatformTestCase() {

    private class Recorder {
        val shown = mutableListOf<Pair<String, String>>()
        var onInstall: () -> Unit = {}
        var onDismiss: () -> Unit = {}
    }

    private val installs = mutableListOf<String>()
    private val recorder = Recorder()

    override fun setUp() {
        super.setUp()
        installs.clear()
        recorder.shown.clear()
        recorder.onInstall = {}
        recorder.onDismiss = {}
        PropertiesComponent.getInstance().unsetValue(CsCloudSetupNotifier.DISMISSED_KEY)
        CsCloudSetupNotifier.resetSessionState()
    }

    override fun tearDown() {
        try {
            PropertiesComponent.getInstance().unsetValue(CsCloudSetupNotifier.DISMISSED_KEY)
            CsCloudSetupNotifier.resetSessionState()
        } finally {
            super.tearDown()
        }
    }

    fun `test shows the install notice once for a missing csc`() {
        val notifier = notifier()

        notifier.onEvent(error(ConnectionErrorCode.CSC_NOT_INSTALLED))

        assertEquals(1, recorder.shown.size)
        assertEquals(KiloBundle.message("csCloud.installNotice.title"), recorder.shown.single().first)
        assertTrue(recorder.shown.single().second.contains("npm install -g @costrict/csc"))

        notifier.onEvent(error(ConnectionErrorCode.CSC_NOT_INSTALLED))

        assertEquals(1, recorder.shown.size)
    }

    fun `test notice actions install csc and persist dont show again`() {
        val props = PropertiesComponent.getInstance()
        notifier().onEvent(error(ConnectionErrorCode.CSC_NOT_INSTALLED))

        recorder.onInstall()

        assertEquals(listOf("install"), installs)

        recorder.onDismiss()

        assertTrue(props.getBoolean(CsCloudSetupNotifier.DISMISSED_KEY, false))
    }

    fun `test other failures and events never show the notice`() {
        val notifier = notifier()

        notifier.onEvent(error(ConnectionErrorCode.DAEMON_DOWN))
        notifier.onEvent(error(ConnectionErrorCode.UNAUTHORIZED))
        notifier.onEvent(error(ConnectionErrorCode.NPM_NOT_FOUND))
        notifier.onEvent(error(null))
        notifier.onEvent(error("workspace"))
        notifier.onEvent(SessionControllerEvent.ConnectionChanged.ShowConnecting)

        assertTrue(recorder.shown.isEmpty())
    }

    fun `test dont show again suppresses the notice across sessions`() {
        val props = PropertiesComponent.getInstance()
        val first = notifier()
        first.onEvent(error(ConnectionErrorCode.CSC_NOT_INSTALLED))
        assertEquals(1, recorder.shown.size)

        recorder.onDismiss()
        CsCloudSetupNotifier.resetSessionState()

        val second = CsCloudSetupNotifier(
            project,
            install = { installs.add("install") },
            show = { title, content, onInstall, onDismiss ->
                recorder.shown.add(title to content)
                recorder.onInstall = onInstall
                recorder.onDismiss = onDismiss
            },
        )
        second.onEvent(error(ConnectionErrorCode.CSC_NOT_INSTALLED))

        assertEquals(1, recorder.shown.size)
        assertTrue(props.getBoolean(CsCloudSetupNotifier.DISMISSED_KEY, false))
    }

    fun `test a fresh session can show the notice again when not dismissed`() {
        notifier().onEvent(error(ConnectionErrorCode.CSC_NOT_INSTALLED))
        assertEquals(1, recorder.shown.size)

        CsCloudSetupNotifier.resetSessionState()

        notifier().onEvent(error(ConnectionErrorCode.CSC_NOT_INSTALLED))

        assertEquals(2, recorder.shown.size)
    }

    private fun notifier() = CsCloudSetupNotifier(
        project,
        install = { installs.add("install") },
        show = { title, content, onInstall, onDismiss ->
            recorder.shown.add(title to content)
            recorder.onInstall = onInstall
            recorder.onDismiss = onDismiss
        },
    )

    private fun error(code: String?) = SessionControllerEvent.ConnectionChanged.ShowError(
        "Connection failed",
        "csc is not installed",
        code = code,
    )
}
