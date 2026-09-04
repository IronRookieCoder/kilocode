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

    fun `test recovery menu maps each failure code to its fix`() {
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

        assertEquals(listOf("Kilo.InstallCsc"), panel.recoveryActionIds())

        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "connection refused",
                code = ConnectionErrorCode.DAEMON_DOWN,
            ))
        }

        assertEquals(listOf("Kilo.StartCsCloud"), panel.recoveryActionIds())

        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "cs-cloud rejected the current credentials",
                code = ConnectionErrorCode.UNAUTHORIZED,
            ))
        }

        assertEquals(listOf("Kilo.SignInCsCloud"), panel.recoveryActionIds())

        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "could not run npm: not found",
                code = ConnectionErrorCode.NPM_NOT_FOUND,
            ))
        }

        assertEquals(listOf("Kilo.InstallCsc"), panel.recoveryActionIds())

        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Workspace failed",
                "workspace did not start",
                code = "workspace",
            ))
        }

        assertEquals(listOf("Kilo.Restart", "Kilo.Reinstall"), panel.recoveryActionIds())
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
        assertTrue(xml.contains("<group id=\"Kilo.CliGroup\" text=\"cs-cloud\" popup=\"true\">"))
        assertTrue(xml.contains("<reference ref=\"Kilo.Restart\"/>"))
        assertTrue(xml.contains("<reference ref=\"Kilo.Reinstall\"/>"))
        assertFalse("Core info menu entry must stay hidden", xml.contains("<reference ref=\"Kilo.CoreInfo\"/>"))
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

    fun `test guide card offers install csc for a missing csc`() {
        val clicked = mutableListOf<String>()
        val browsed = mutableListOf<String>()
        val guide = ConnectionPanel(parent, controller, browse = { browsed.add(it) }, runGuideAction = { clicked.add(it) })

        edt {
            guide.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                KiloBundle.message("csCloud.error.csc_not_installed.title"),
                "csc is not installed",
                code = ConnectionErrorCode.CSC_NOT_INSTALLED,
            ))
        }

        assertTrue(guide.isVisible)
        assertTrue(guide.guideVisible())
        // The banner summary already shows csCloud.error.<code>.title, so the card must not repeat it.
        assertEquals("", guide.guideTitleText())
        assertEquals(KiloBundle.message("csCloud.guide.installCsc"), guide.guideActionText())
        assertEquals(KiloBundle.message("csCloud.guide.docs"), guide.guideDocsText())
        assertTrue(guide.guideDocsVisible())

        edt { guide.clickGuideAction() }
        edt { guide.clickGuideDocs() }

        assertEquals(listOf("Kilo.InstallCsc"), clicked)
        assertEquals(listOf(CSC_INSTALL_DOCS_URL), browsed)
    }

    fun `test guide card offers start cs-cloud for a stopped daemon`() {
        val clicked = mutableListOf<String>()
        val guide = ConnectionPanel(parent, controller, runGuideAction = { clicked.add(it) })

        edt {
            guide.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                KiloBundle.message("csCloud.error.daemon_down.title"),
                "connection refused",
                code = ConnectionErrorCode.DAEMON_DOWN,
            ))
        }

        assertTrue(guide.guideVisible())
        assertEquals(KiloBundle.message("csCloud.guide.startCsCloud"), guide.guideActionText())
        assertFalse(guide.guideDocsVisible())

        edt { guide.clickGuideAction() }

        assertEquals(listOf("Kilo.StartCsCloud"), clicked)
    }

    fun `test guide card offers sign in for unauthorized`() {
        val clicked = mutableListOf<String>()
        val guide = ConnectionPanel(parent, controller, runGuideAction = { clicked.add(it) })

        edt {
            guide.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                KiloBundle.message("csCloud.error.unauthorized.title"),
                "cs-cloud rejected the current credentials",
                code = ConnectionErrorCode.UNAUTHORIZED,
            ))
        }

        assertTrue(guide.guideVisible())
        assertEquals(KiloBundle.message("csCloud.guide.signIn"), guide.guideActionText())
        assertFalse(guide.guideDocsVisible())

        edt { guide.clickGuideAction() }

        assertEquals(listOf("Kilo.SignInCsCloud"), clicked)
    }

    fun `test guide card stays hidden without a guide eligible code`() {
        val banner = ConnectionPanel(parent, controller)

        edt { banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowError("Connection failed", null)) }
        assertFalse(banner.guideVisible())

        edt {
            banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Connection failed",
                "could not run npm: not found",
                code = ConnectionErrorCode.NPM_NOT_FOUND,
            ))
        }
        assertFalse(banner.guideVisible())

        edt {
            banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                "Workspace failed",
                "workspace did not start",
                code = "workspace",
            ))
        }
        assertFalse(banner.guideVisible())
    }

    fun `test guide card resets when connecting or hiding`() {
        val banner = ConnectionPanel(parent, controller)

        edt {
            banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                KiloBundle.message("csCloud.error.daemon_down.title"),
                null,
                code = ConnectionErrorCode.DAEMON_DOWN,
            ))
        }
        assertTrue(banner.guideVisible())

        edt { banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowConnecting) }

        assertFalse(banner.guideVisible())

        edt {
            banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                KiloBundle.message("csCloud.error.daemon_down.title"),
                null,
                code = ConnectionErrorCode.DAEMON_DOWN,
            ))
            banner.onEvent(SessionControllerEvent.ConnectionChanged.Hide)
        }

        assertFalse(banner.isVisible)
        assertFalse(banner.guideVisible())
    }

    fun `test guide card contributes its height to the banner`() {
        val banner = ConnectionPanel(parent, controller)
        edt {
            banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowError("Connection failed", null))
            banner.size = Dimension(480, 1000)
        }
        val plain = banner.preferredSize.height

        edt {
            banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                KiloBundle.message("csCloud.error.daemon_down.title"),
                null,
                code = ConnectionErrorCode.DAEMON_DOWN,
            ))
        }

        assertFalse(banner.detailsVisible())
        assertTrue(banner.guideVisible())
        assertTrue(banner.preferredSize.height > plain)
    }

    fun `test guide card is laid out after the banner relayouts`() {
        val banner = ConnectionPanel(parent, controller)
        edt {
            banner.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
                KiloBundle.message("csCloud.error.daemon_down.title"),
                null,
                code = ConnectionErrorCode.DAEMON_DOWN,
            ))
            banner.size = Dimension(480, 600)
            banner.doLayout()
        }

        // The card reached the bottom (SOUTH) region of the banner with real bounds.
        assertTrue(banner.guideVisible())
        assertTrue(banner.guideBounds().height > 0)
        assertEquals(480, banner.guideBounds().width)
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }
}
