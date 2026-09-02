package ai.kilocode.client.session.ui.selection

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.session.views.MessageToolbar
import ai.kilocode.client.ui.UiStyle
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent

/**
 * U3.10 (★): the message toolbar's copy affordance places the answer text on the system
 * clipboard — the component-level counterpart of SessionSelectionCopyTest.
 */
@Suppress("UnstableApiUsage")
class MessageToolbarActionsTest : BasePlatformTestCase() {

    fun `test copy button places answer text on the clipboard`() {
        val toolbar = MessageToolbar(text = { "the generated answer" })
        val copied = clickCopy(toolbar)

        assertTrue("copy button should report a performed action", copied)
        val clipboard: String? = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
        assertEquals("the generated answer", clipboard)
    }

    fun `test copy button keeps the hover tooltip from the bundle`() {
        val toolbar = MessageToolbar(text = { "text" })

        assertEquals(KiloBundle.message("session.copy.hover"), toolbar.copyButton().toolTipText)
    }

    fun `test prompt toolbar tooltip switches to copy prompt`() {
        val toolbar = MessageToolbar(text = { "prompt text" }, revert = { })

        assertEquals(KiloBundle.message("session.copy.prompt"), toolbar.copyButton().toolTipText)
    }

    fun `test sync toggles availability and active reports it`() {
        val toolbar = MessageToolbar(text = { "text" })

        toolbar.sync(false)
        assertFalse(toolbar.active())

        toolbar.sync(true)
        assertTrue(toolbar.active())
    }

    fun `test placeholder reserves toolbar space`() {
        val toolbar = MessageToolbar(text = { "text" })

        val placeholder = toolbar.placeholder()

        assertEquals(
            "placeholder must reserve the toolbar height",
            toolbar.preferredSize.height + UiStyle.Gap.xs(),
            placeholder.preferredSize.height,
        )
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /** Performs the button's action off the real mouse: EDT-safe and click-location free. */
    private fun clickCopy(toolbar: MessageToolbar): Boolean {
        val button = toolbar.copyButton()
        val listener = button.actionListeners.singleOrNull()
            ?: throw AssertionError("copy button must expose exactly one ActionListener")
        listener.actionPerformed(ActionEvent(button, ActionEvent.ACTION_PERFORMED, "copy"))
        return true
    }
}
