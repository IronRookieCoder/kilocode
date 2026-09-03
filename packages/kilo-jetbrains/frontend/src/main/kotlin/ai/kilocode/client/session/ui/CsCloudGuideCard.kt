package ai.kilocode.client.session.ui

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.ui.UiStyle
import ai.kilocode.client.ui.layout.Stack
import ai.kilocode.rpc.ConnectionErrorCode
import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.Centerizer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JButton
import javax.swing.JComponent

/** CoStrict documentation page for installing the `csc` CLI. */
internal const val CSC_INSTALL_DOCS_URL = "https://docs.costrict.ai/cli/guide/installation"

private val EMPTY_CONTEXT: DataContext = DataContext { null }

/**
 * Runs a registered IDE action by id (e.g. `Kilo.InstallCsc`) so guide buttons reuse the
 * recovery-menu actions — including their telemetry — instead of duplicating the dispatch.
 */
internal fun runRegisteredAction(id: String, source: Component? = null) {
    val action = ActionManager.getInstance().getAction(id) ?: return
    val context = source?.let { DataManager.getInstance().getDataContext(it) } ?: EMPTY_CONTEXT
    val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
    ActionUtil.updateAction(action, event)
    ActionUtil.performAction(action, event)
}

/**
 * Recovery guidance for a cs-cloud connection failure: a localized title, the primary fix
 * as a button, and — only when `csc` is missing — a link to the installation docs.
 *
 * Shared by [ConnectionPanel] and [EmptySessionPanel] so both failure surfaces answer the
 * same failure code with the same fix. Hosts that already show the code's title themselves
 * (the connection banner) pass `showTitle = false` so the sentence is not rendered twice.
 */
internal class CsCloudGuideCard(
    private val browse: (String) -> Unit = BrowserUtil::browse,
    private val centered: Boolean = false,
    private val showTitle: Boolean = true,
    /** Overrides the default "run the registered IDE action" dispatch (tests). */
    private val runAction: ((String) -> Unit)? = null,
) : BorderLayoutPanel() {

    private val title = JBLabel().apply {
        horizontalAlignment = if (centered) JBLabel.CENTER else JBLabel.LEADING
        foreground = UiStyle.Colors.fg()
    }

    private val button = JButton().apply {
        isFocusable = false
        setRequestFocusEnabled(false)
    }

    private val docs = ActionLink(KiloBundle.message("csCloud.guide.docs")) { browse(CSC_INSTALL_DOCS_URL) }

    // Declared after `button` so the fallback can use it as the data-context source.
    private val dispatch: (String) -> Unit = runAction ?: { id -> runRegisteredAction(id, button) }

    private var actionId: String? = null

    init {
        isOpaque = false
        layout = BorderLayout(0, UiStyle.Gap.sm())
        button.addActionListener { actionId?.let(dispatch) }
        val row: JComponent = Stack.horizontal(gap = UiStyle.Gap.md()).next(button).next(docs)
        if (showTitle) add(title, BorderLayout.NORTH)
        add(if (centered) Centerizer(row, Centerizer.TYPE.HORIZONTAL) else row, BorderLayout.CENTER)
        isVisible = false
    }

    /**
     * Shows the guidance for a guide-eligible cs-cloud code; hides the card for null codes,
     * NPM_NOT_FOUND (its fallback lives in the install notice) and legacy Core errors.
     */
    @RequiresEdt
    fun sync(code: String?) {
        val key = titleKey(code)
        if (key == null) {
            actionId = null
            if (showTitle) title.text = ""
            button.text = ""
            docs.isVisible = false
            isVisible = false
            return
        }
        val failure = requireNotNull(code)
        if (showTitle) title.text = KiloBundle.message(key)
        button.text = buttonLabel(failure)
        docs.isVisible = failure == ConnectionErrorCode.CSC_NOT_INSTALLED
        actionId = fixActionId(failure)
        isVisible = true
    }

    internal fun guideTitle() = title.text

    internal fun guideActionLabel() = button.text

    internal fun guideDocsLabel() = docs.text

    internal fun guideDocsVisible() = docs.isVisible

    internal fun clickGuideAction() = button.doClick()

    internal fun clickGuideDocs() = docs.doClick()

    companion object {
        /**
         * Bundle key of the guidance title for [code], or null when the code has no guide card.
         * Keys are spelled out so the bundle linter can verify them.
         */
        internal fun titleKey(code: String?): String? = when (code) {
            ConnectionErrorCode.CSC_NOT_INSTALLED -> "csCloud.error.csc_not_installed.title"
            ConnectionErrorCode.DAEMON_DOWN -> "csCloud.error.daemon_down.title"
            ConnectionErrorCode.UNAUTHORIZED -> "csCloud.error.unauthorized.title"
            else -> null
        }

        /**
         * Registered action that fixes [code], also used by the retry menu — one mapping for
         * both surfaces. Null for codes without a dedicated fix.
         */
        internal fun fixActionId(code: String?): String? = when (code) {
            // Start cs-cloud cannot succeed without csc, so install it first.
            ConnectionErrorCode.CSC_NOT_INSTALLED, ConnectionErrorCode.NPM_NOT_FOUND -> "Kilo.InstallCsc"
            ConnectionErrorCode.DAEMON_DOWN -> "Kilo.StartCsCloud"
            ConnectionErrorCode.UNAUTHORIZED -> "Kilo.SignInCsCloud"
            else -> null
        }

        private fun buttonLabel(code: String) = KiloBundle.message(
            when (code) {
                ConnectionErrorCode.CSC_NOT_INSTALLED -> "csCloud.guide.installCsc"
                ConnectionErrorCode.DAEMON_DOWN -> "csCloud.guide.startCsCloud"
                else -> "csCloud.guide.signIn"
            },
        )
    }
}
