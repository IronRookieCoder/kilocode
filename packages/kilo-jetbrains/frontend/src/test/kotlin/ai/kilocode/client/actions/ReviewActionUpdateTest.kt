package ai.kilocode.client.actions

import ai.kilocode.client.app.KiloChatAccess
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.session.SessionManager
import ai.kilocode.client.session.SessionRef
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * C6 / U7.1: review entry points stay disabled until a session exists, and the toolbar entry
 * binds to `sendCommand("review", args)` with the current-changes fallback — the action-level
 * counterpart of the pure ReviewArgsTest.
 */
@Suppress("UnstableApiUsage")
class ReviewActionUpdateTest : BasePlatformTestCase() {

    fun `test review changes action is disabled without a session`() {
        val event = event(ReviewChangesAction(), manager = null)

        update(ReviewChangesAction(), event)

        assertFalse(event.presentation.isEnabled)
        assertEquals(KiloBundle.message("codereview.disabled.tooltip"), event.presentation.description)
    }

    fun `test review directory action is disabled without a session`() {
        val event = event(ReviewDirectoryAction(), manager = null)

        update(ReviewDirectoryAction(), event)

        assertFalse(event.presentation.isEnabled)
    }

    fun `test review actions enable once a session manager is in context`() {
        for (action in listOf(ReviewChangesAction(), ReviewEditorAction(), ReviewDirectoryAction())) {
            val event = event(action, manager = FakeSessionManager())

            update(action, event)

            assertTrue(action.javaClass.simpleName + " should be enabled", event.presentation.isEnabled)
        }
    }

    fun `test review actions enable from the tool window chat access fallback`() {
        val access = project.service<KiloChatAccess>()
        access.manager = FakeSessionManager()
        try {
            for (action in listOf(ReviewChangesAction(), ReviewEditorAction(), ReviewDirectoryAction())) {
                val event = event(action, manager = null)

                update(action, event)

                assertTrue(action.javaClass.simpleName + " should be enabled via KiloChatAccess", event.presentation.isEnabled)
            }
        } finally {
            access.manager = null
        }
    }

    fun `test review editor action is disabled without a session`() {
        val event = event(ReviewEditorAction(), manager = null)

        update(ReviewEditorAction(), event)

        assertFalse(event.presentation.isEnabled)
        assertEquals(KiloBundle.message("codereview.disabled.tooltip"), event.presentation.description)
    }

    fun `test review editor action switches text between file and selection`() {
        val action = ReviewEditorAction()
        val fileEvent = event(action, manager = FakeSessionManager())
        update(action, fileEvent)
        assertEquals(KiloBundle.message("action.Kilo.CodeReview.File.text"), fileEvent.presentation.text)

        val editor = editorWithSelection()
        try {
            val selectionEvent = event(action, manager = FakeSessionManager(), editor = editor)
            // Editor models may only be read on the EDT — matching the action's real
            // BACKWARD update thread — so this one update runs there instead of pooled.
            update(action, selectionEvent, onEdt = true)
            assertEquals(KiloBundle.message("action.Kilo.CodeReview.Selection.text"), selectionEvent.presentation.text)
        } finally {
            onEdt(Runnable { EditorFactory.getInstance().releaseEditor(editor) })
        }
    }

    fun `test toolbar entry sends review command with empty args for current changes`() {
        val manager = FakeSessionManager()
        val action = ReviewChangesAction()
        val event = event(action, manager = manager)

        // Direct dispatch: ActionUtil.performAction would wrap the data context and drop the
        // plain DataContext map this harness installs.
        action.actionPerformed(event)

        assertEquals("toolbar review must send bare /review", "review" to "", manager.commands.singleOrNull())
    }

    fun `test toolbar entry falls back to no-op without any session`() {
        val action = ReviewChangesAction()
        val event = event(action, manager = null)

        action.actionPerformed(event)

        // Reaching here without an exception is the assertion: the action silently no-ops.
    }

    // ------------------------------------------------------------------
    // Harness (same pattern as KiloRecoveryActionsTest)
    // ------------------------------------------------------------------

    private fun event(action: AnAction, manager: SessionManager?, editor: Editor? = null): AnActionEvent {
        val presentation = Presentation().apply { copyFrom(action.templatePresentation) }
        presentation.isEnabled = false
        return AnActionEvent.createFromDataContext("", presentation, context(manager, editor))
    }

    private fun update(action: AnAction, event: AnActionEvent, onEdt: Boolean = false) {
        if (onEdt) {
            onEdt(Runnable { ActionUtil.updateAction(action, event) })
        } else {
            ApplicationManager.getApplication().executeOnPooledThread {
                ActionUtil.updateAction(action, event)
            }.get()
        }
    }

    private fun context(manager: SessionManager?, editor: Editor? = null): DataContext = DataContext { id ->
        when (id) {
            SessionManager.KEY.name -> manager
            CommonDataKeys.PROJECT.name -> project
            CommonDataKeys.EDITOR.name -> editor
            else -> null
        }
    }

    /** Real editor (never mocked — the selection model drives production `customize()`). */
    private fun editorWithSelection(): Editor {
        val factory = EditorFactory.getInstance()
        lateinit var editor: Editor
        onEdt(Runnable {
            editor = factory.createEditor(factory.createDocument("review target text\nsecond line\n"), project)
            editor.selectionModel.setSelection(0, 6)
        })
        return editor
    }

    /** Explicit `Runnable` SAM keeps the `invokeAndWait(Runnable/Computable)` overloads unambiguous. */
    private fun onEdt(block: Runnable) {
        ApplicationManager.getApplication().invokeAndWait(block)
    }

    /** Records `/review`-style commands; every other SessionManager surface is a no-op. */
    private class FakeSessionManager : SessionManager {
        val commands = mutableListOf<Pair<String, String>>()

        override fun newSession() = Unit

        override fun showHistory(back: (() -> Unit)?) = Unit

        override fun openSession(ref: SessionRef) = Unit

        override fun sendCommand(command: String, args: String) {
            commands.add(command to args)
        }
    }
}
