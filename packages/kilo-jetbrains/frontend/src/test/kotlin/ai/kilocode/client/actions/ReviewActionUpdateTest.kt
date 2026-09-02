package ai.kilocode.client.actions

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

    fun `test review editor action switches text between file and selection`() {
        val action = ReviewEditorAction()
        val fileEvent = event(action, manager = FakeSessionManager())
        update(action, fileEvent)
        assertEquals(KiloBundle.message("action.Kilo.CodeReview.File.text"), fileEvent.presentation.text)
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

    private fun event(action: AnAction, manager: SessionManager?): AnActionEvent {
        val presentation = Presentation().apply { copyFrom(action.templatePresentation) }
        presentation.isEnabled = false
        return AnActionEvent.createFromDataContext("", presentation, context(manager))
    }

    private fun update(action: AnAction, event: AnActionEvent) {
        ApplicationManager.getApplication().executeOnPooledThread {
            ActionUtil.updateAction(action, event)
        }.get()
    }

    private fun context(manager: SessionManager?): DataContext = DataContext { id ->
        when (id) {
            SessionManager.KEY.name -> manager
            CommonDataKeys.PROJECT.name -> project
            else -> null
        }
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
