package ai.kilocode.client.session.ui

import ai.kilocode.client.session.controller.SessionController
import ai.kilocode.client.session.controller.SessionControllerEvent
import ai.kilocode.client.session.controller.SessionControllerTestBase
import ai.kilocode.client.session.ui.style.SessionEditorStyle
import ai.kilocode.client.ui.UiStyle
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.border.CompoundBorder

@Suppress("UnstableApiUsage")
class ConnectionPanelTest : SessionControllerTestBase() {

    private lateinit var panel: ConnectionPanel
    private lateinit var controller: SessionController

    override fun setUp() {
        super.setUp()
        controller = controller("ses_test")
        panel = ConnectionPanel(parent, controller)
        flush()
    }

    fun `test loading hides retry and details`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowConnecting)
        }

        assertTrue(panel.isVisible)
        assertEquals("Loading...", panel.summaryText())
        assertEquals("", panel.detailsText())
        assertFalse(panel.toggleVisible())
        assertFalse(panel.detailsVisible())
        assertFalse(panel.retryVisible())
    }

    fun `test downloading entry is hidden and panel stays invisible`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowDownloading(42, "1.2.3", "darwin-arm64"))
        }

        assertFalse(panel.isVisible)
        assertFalse(panel.retryVisible())
    }

    fun `test app error starts collapsed and expands details`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "CLI startup failed",
                "stderr line\nconfig: HTTP 500: broken",
            ))
        }

        assertTrue(panel.isVisible)
        assertEquals("CLI startup failed", panel.summaryText())
        assertEquals(UiStyle.Colors.errorLabelForeground(), panel.summaryColor())
        assertTrue(panel.toggleVisible())
        assertFalse(panel.toggleExpanded())
        assertFalse(panel.detailsVisible())
        assertEquals("stderr line\nconfig: HTTP 500: broken", panel.detailsText())
        assertEquals(UiStyle.Colors.fg(), panel.detailsColor())
        assertTrue(panel.retryVisible())
        assertFalse(panel.retryFocusable())

        edt { panel.clickSummary() }

        assertTrue(panel.toggleExpanded())
        assertTrue(panel.detailsVisible())
        assertTrue(panel.components.filterIsInstance<JBScrollPane>().single().border is CompoundBorder)

        edt { panel.clickToggle() }

        assertFalse(panel.toggleExpanded())
        assertFalse(panel.detailsVisible())
    }

    fun `test cs-cloud not installed error auto-expands install guidance`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "csc is not installed - install it with `npm install -g @costrict/csc`",
                code = ConnectionErrorCode.CSC_NOT_INSTALLED,
            ))
        }

        assertTrue(panel.isVisible)
        assertTrue(panel.toggleVisible())
        assertTrue(panel.toggleExpanded())
        assertTrue(panel.detailsVisible())
    }

    fun `test recovery menu offers cs-cloud actions only for cs-cloud failures`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError("CLI startup failed", null))
        }

        assertEquals(listOf("Kilo.Restart", "Kilo.Reinstall"), panel.recoveryActionIds())

        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "cs-cloud server URL was not found",
                code = ConnectionErrorCode.CSC_NOT_INSTALLED,
            ))
        }

        assertEquals(
            listOf("Kilo.Restart", "Kilo.Reinstall", "Kilo.StartCsCloud", "Kilo.InstallCsc"),
            panel.recoveryActionIds(),
        )

        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "connection refused",
                code = ConnectionErrorCode.DAEMON_DOWN,
            ))
        }

        assertEquals(
            listOf("Kilo.Restart", "Kilo.Reinstall", "Kilo.StartCsCloud"),
            panel.recoveryActionIds(),
        )

        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "cs-cloud API key is invalid",
                code = ConnectionErrorCode.UNAUTHORIZED,
            ))
        }

        assertEquals(
            listOf("Kilo.Restart", "Kilo.Reinstall", "Kilo.StartCsCloud"),
            panel.recoveryActionIds(),
        )
    }

    fun `test recovery menu hides reinstall on cs-cloud provider`() {
        edt {
            controller.model.app = KiloAppStateDto(
                KiloAppStatusDto.ERROR,
                providerId = "cs-cloud",
            )
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "cs-cloud server URL was not found",
                code = ConnectionErrorCode.CSC_NOT_INSTALLED,
            ))
        }

        assertEquals(listOf("Kilo.Restart", "Kilo.StartCsCloud", "Kilo.InstallCsc"), panel.recoveryActionIds())
    }

    fun `test workspace error shows retry without details`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError("Workspace failed", null))
        }

        assertTrue(panel.isVisible)
        assertEquals("Workspace failed", panel.summaryText())
        assertFalse(panel.toggleVisible())
        assertFalse(panel.detailsVisible())
        assertEquals("", panel.detailsText())
        assertTrue(panel.retryVisible())
        assertEquals("Try again", panel.retryText())
    }

    fun `test retry popup group uses core recovery actions`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError("CLI startup failed", null))
        }
        val xml = requireNotNull(javaClass.classLoader.getResourceAsStream("kilo.jetbrains.frontend.xml"))
            .bufferedReader()
            .use { it.readText() }

        assertTrue(panel.retryVisible())
        assertEquals("Kilo.CliGroup", ConnectionPanel.CLI_GROUP_ID)
        assertTrue(xml.contains("<group id=\"Kilo.CliGroup\" text=\"Core\" popup=\"true\">"))
        assertTrue(xml.contains("<reference ref=\"Kilo.Restart\"/>"))
        assertTrue(xml.contains("<reference ref=\"Kilo.Reinstall\"/>"))
        assertTrue(xml.contains("<reference ref=\"Kilo.CoreInfo\"/>"))
    }

    fun `test ready warnings show collapsed banner with retry`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowWarning(
                "Configuration warnings",
                ".kilo/kilo.json: Invalid JSON\nCloseBraceExpected at line 11, column 1",
            ))
        }

        assertTrue(panel.isVisible)
        assertEquals("Configuration warnings", panel.summaryText())
        assertEquals(UiStyle.Colors.warningLabelForeground(), panel.summaryColor())
        assertTrue(panel.toggleVisible())
        assertFalse(panel.toggleExpanded())
        assertFalse(panel.detailsVisible())
        assertTrue(panel.retryVisible())
        assertFalse(panel.retryFocusable())
        assertEquals(
            ".kilo/kilo.json: Invalid JSON\nCloseBraceExpected at line 11, column 1",
            panel.detailsText(),
        )

        edt { panel.clickSummary() }

        assertTrue(panel.toggleExpanded())
        assertTrue(panel.detailsVisible())
    }

    fun `test expanded details preferred height fits full text`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError("CLI startup failed", lines(10)))
            panel.size = Dimension(480, 1000)
        }

        edt { panel.clickSummary() }
        val ten = panel.preferredSize.height

        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError("CLI startup failed", lines(30)))
            panel.clickSummary()
        }

        assertTrue(panel.detailsVisible())
        assertTrue(panel.preferredSize.height > ten)
    }

    fun `test expanded details grow for a wrapped single line`() {
        val sentence = (1..40).joinToString(" ") { "word$it" }
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError("CLI startup failed", sentence))
            panel.size = Dimension(240, 1000)
            panel.clickSummary()
        }

        val fontHeight = fontHeight()
        assertTrue(panel.detailsVisible())
        // A single logical line that wraps must contribute more than one visual row of height.
        assertTrue(panel.preferredSize.height > fontHeight * 2)
    }

    private fun fontHeight(): Int {
        val details = panel.components.filterIsInstance<JBScrollPane>().single().viewport.view
        return details.getFontMetrics(details.font).height
    }

    fun `test raw app and workspace events do not render panel`() {
        edt {
            panel.onEvent(SessionControllerEvent.AppChanged)
            panel.onEvent(SessionControllerEvent.WorkspaceChanged)
        }

        assertFalse(panel.isVisible)
    }

    fun `test panel uses prompt background without separator`() {
        assertFalse(panel.isOpaque)
        assertEquals(SessionEditorStyle.current().editorScheme.defaultBackground.rgb, panel.background.rgb)
        assertFalse(panel.hasSeparator())
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }
}
