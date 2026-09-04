package ai.kilocode.client.session.controller

import ai.kilocode.client.session.model.SessionState
import ai.kilocode.rpc.dto.ChatEventDto
import ai.kilocode.rpc.dto.SessionStatusDto
import com.intellij.openapi.util.Disposer

class ListenerLifecycleTest : SessionControllerTestBase() {

    fun `test listener removed on parent dispose`() {
        val m = controller()
        val disposable = Disposer.newDisposable("listener-parent")
        Disposer.register(parent, disposable)

        val events = mutableListOf<SessionControllerEvent>()
        m.addListener(disposable) { events.add(it) }

        edt { m.prompt("before") }
        flush()

        Disposer.dispose(disposable)

        edt { m.prompt("after") }
        flush()

        assertControllerEvents("""
            AccountOverlayChanged hide
            ViewChanged session
            AppChanged
            WorkspaceChanged
        """, events)
    }

    fun `test all listeners notified`() {
        val m = controller()
        val events1 = mutableListOf<SessionControllerEvent>()
        val events2 = mutableListOf<SessionControllerEvent>()
        val d1 = Disposer.newDisposable("l1")
        val d2 = Disposer.newDisposable("l2")
        Disposer.register(parent, d1)
        Disposer.register(parent, d2)

        m.addListener(d1) { events1.add(it) }
        m.addListener(d2) { events2.add(it) }

        edt { m.prompt("go") }
        flush()

        assertEquals(events1, events2)
        assertControllerEvents("""
            AccountOverlayChanged hide
            ViewChanged session
            AppChanged
            WorkspaceChanged
        """, events1)
    }

    fun `test listener registered while an event is delivered is wired without breaking the loop`() {
        val m = controller()
        val first = mutableListOf<SessionControllerEvent>()
        val late = mutableListOf<SessionControllerEvent>()
        val d1 = Disposer.newDisposable("mid-fire-parent")
        val d2 = Disposer.newDisposable("late-parent")
        Disposer.register(parent, d1)
        Disposer.register(parent, d2)

        // The UI panels hook themselves up from inside event delivery (empty-session panel,
        // setup notifier), i.e. the listeners list grows while fire() iterates it.
        var registered = false
        m.addListener(d1) { event ->
            first.add(event)
            if (!registered) {
                registered = true
                m.addListener(d2) { late.add(it) }
            }
        }

        edt { m.prompt("go") }
        flush()
        edt { m.prompt("again") }
        flush()

        assertTrue("the first listener must receive events", first.isNotEmpty())
        assertTrue("the listener registered mid-delivery must receive later events", late.isNotEmpty())
    }

    fun `test session status idle fires StateChanged to Idle`() {
        val (m, _, modelEvents) = prompted()

        emit(ChatEventDto.TurnOpen("ses_test"))
        modelEvents.clear()

        emit(ChatEventDto.SessionStatusChanged("ses_test", SessionStatusDto("idle")))

        assertModelEvents("StateChanged Idle", modelEvents)
        assertEquals(SessionState.Idle, m.model.state)
    }

    fun `test session status busy fires StateChanged to Busy`() {
        val (_, _, modelEvents) = prompted()

        emit(ChatEventDto.SessionStatusChanged("ses_test", SessionStatusDto("busy", null)))

        assertModelEvents("StateChanged Busy", modelEvents)
    }

    fun `test session status busy ignored when already busy`() {
        val (_, _, modelEvents) = prompted()

        emit(ChatEventDto.TurnOpen("ses_test"))
        modelEvents.clear()

        emit(ChatEventDto.SessionStatusChanged("ses_test", SessionStatusDto("busy")))

        // Already in Busy — status busy is ignored
        assertTrue(modelEvents.isEmpty())
    }

    fun `test session status retry with zero attempt`() {
        val (m, _, _) = prompted()

        emit(ChatEventDto.SessionStatusChanged(
            "ses_test",
            SessionStatusDto("retry", "Waiting...", attempt = 0, next = 1000L),
        ))

        val state = m.model.state as SessionState.Retry
        assertEquals("Waiting...", state.message)
        assertEquals(0, state.attempt)
        assertEquals(1000L, state.next)
    }

    fun `test session status offline`() {
        val (m, _, _) = prompted()

        emit(ChatEventDto.SessionStatusChanged(
            "ses_test",
            SessionStatusDto("offline", "Disconnected", requestID = "req_1"),
        ))

        val state = m.model.state as SessionState.Offline
        assertEquals("Disconnected", state.message)
        assertEquals("req_1", state.requestId)
    }

    fun `test session status unknown type is ignored`() {
        val (m, _, modelEvents) = prompted()
        modelEvents.clear()

        emit(ChatEventDto.SessionStatusChanged("ses_test", SessionStatusDto("weird_future_status")))

        assertTrue(modelEvents.isEmpty())
        assertEquals(SessionState.Idle, m.model.state)
    }
}
