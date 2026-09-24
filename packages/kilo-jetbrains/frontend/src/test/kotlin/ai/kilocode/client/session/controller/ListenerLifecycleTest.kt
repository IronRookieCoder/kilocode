package ai.kilocode.client.session.controller

import ai.kilocode.client.diff.DiffEditorData
import ai.kilocode.client.diff.DiffOpenObservation
import ai.kilocode.client.session.SessionRef
import ai.kilocode.client.session.model.SessionState
import ai.kilocode.rpc.dto.ChatEventDto
import ai.kilocode.rpc.dto.PartDto
import ai.kilocode.rpc.dto.SessionStatusDto
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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

    // ------ M24（C5）资源数量：token绑定真实所有者，开/关回落基线，永不负数 ------

    private fun controllerCount(): Long = fixture.resources.snapshot().getValue("controller")

    private fun subscriptionCount(): Long = fixture.resources.snapshot().getValue("subscription")

    private fun ideOperationEnds(result: String? = null) = fixture.facts()
        .filter { it.name == "ide.operation" }
        .filter { it.data["phase"]?.jsonPrimitive?.content == "end" }
        .filter { result == null || it.data["result"]?.jsonPrimitive?.content == result }

    fun `test open and close 100 controllers returns counts to baseline`() {
        val baseline = fixture.resources.snapshot()

        val created = List(100) { controller() }
        assertEquals(baseline.getValue("controller") + 100, controllerCount())

        // 真实dispose（与生产同一Disposer路径）：每个controller的token释放一次。
        created.forEach { Disposer.dispose(it) }
        assertEquals(baseline, fixture.resources.snapshot())
    }

    fun `test repeated controller dispose never goes negative`() {
        val baseline = fixture.resources.snapshot()
        val m = controller()
        assertEquals(baseline.getValue("controller") + 1, controllerCount())

        // 直接走真实dispose实现：disposed标志与Resources的首次close语义双重防负数。
        m.dispose()
        m.dispose()
        assertEquals(baseline, fixture.resources.snapshot())
    }

    fun `test subscription token tracks the real session subscription`() {
        val baseline = fixture.resources.snapshot()
        val m = controller(SessionRef.Local("ses_res_local"))
        flush()

        // 订阅真正启动（onStart）才计入subscription；controller构造只计入controller。
        assertEquals(baseline.getValue("controller") + 1, controllerCount())
        assertEquals(baseline.getValue("subscription") + 1, subscriptionCount())

        // 重复dispose不负数：controller与subscription全部回落基线。
        m.dispose()
        m.dispose()
        flush()
        assertEquals(baseline, fixture.resources.snapshot())
    }

    fun `test child session subscription acquires and releases its own token`() {
        val (m, _, _) = prompted()
        val withMain = subscriptionCount()

        // task工具部件携带子会话ID → trackChild → 真正启动的子会话订阅（新token，+1）。
        val taskPart = PartDto(
            id = "prt_task",
            sessionID = "ses_test",
            messageID = "msg_task",
            type = "tool",
            tool = "task",
            metadata = mapOf("sessionId" to "ses_child"),
        )
        emit(ChatEventDto.PartUpdated("ses_test", taskPart))
        assertEquals(withMain + 1, subscriptionCount())

        // 部件移除 → untrackChild → 子订阅取消 → finally释放（取消订阅后无残留计数）。
        emit(ChatEventDto.PartRemoved("ses_test", "msg_task", "prt_task"))
        assertEquals(withMain, subscriptionCount())

        m.dispose()
        flush()
        assertEquals(0L, fixture.resources.snapshot().getValue("controller"))
        assertEquals(0L, subscriptionCount())
    }

    fun `test listener registration does not count as controller`() {
        val m = controller()
        val before = fixture.resources.snapshot()

        val disposable = Disposer.newDisposable("resource-listener")
        Disposer.register(parent, disposable)
        m.addListener(disposable) {}
        m.addListener(disposable) {}

        assertEquals(before, fixture.resources.snapshot())
    }

    // ------ M23（C5）open_diff：实际处理器记录一次，失败只有failure ------

    fun `test diff open failure settles failure only`() {
        val open = DiffOpenObservation(fixture.operations)

        // Connecting是数据未落地，不结算；Error是真实失败 → failure；晚到的成功数据被唯一end丢弃。
        open.settle(DiffEditorData.Connecting)
        open.settle(DiffEditorData.Error("load failed"))
        open.settle(DiffEditorData.Files(emptyList()))
        fixture.flush()

        val starts = fixture.facts().count { it.name == "ide.operation" && it.data["phase"]?.jsonPrimitive?.content == "start" }
        assertEquals(1, starts)
        assertEquals(1, ideOperationEnds().size)
        assertEquals(1, ideOperationEnds("failure").size)
        assertTrue(ideOperationEnds("success").isEmpty())
    }

    fun `test diff open success settles once and early dispose settles cancelled`() {
        val success = DiffOpenObservation(fixture.operations)
        success.settle(DiffEditorData.Files(emptyList()))
        success.settle(DiffEditorData.Empty)
        fixture.flush()
        assertEquals(1, ideOperationEnds("success").size)

        // 编辑器在diff数据到达前被关闭：cancelled结算，deadline不再误判timeout。
        val cancelled = DiffOpenObservation(fixture.operations)
        cancelled.cancel()
        fixture.flush()
        assertEquals(1, ideOperationEnds("cancelled").size)
    }
}
