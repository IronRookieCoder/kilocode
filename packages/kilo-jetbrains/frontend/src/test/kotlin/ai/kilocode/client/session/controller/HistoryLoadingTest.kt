package ai.kilocode.client.session.controller

import ai.kilocode.client.app.KiloSessionService
import ai.kilocode.client.session.model.SessionModelEvent
import ai.kilocode.client.session.model.SessionState
import ai.kilocode.rpc.dto.AgentDto
import ai.kilocode.rpc.dto.ConfigDto
import ai.kilocode.rpc.dto.DiffFileDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.MessageSummaryDto
import ai.kilocode.rpc.dto.MessageTimeDto
import ai.kilocode.rpc.dto.MessageWithPartsDto
import ai.kilocode.rpc.dto.ModelDto
import ai.kilocode.rpc.dto.PermissionRequestDto
import ai.kilocode.rpc.dto.ProviderDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonPrimitive

class HistoryLoadingTest : SessionControllerTestBase() {

    /** M11恢复分母的end事实；读取前先fixture.flush封存。 */
    private fun restoreEnds() = fixture.facts().filter {
        it.name == "session.restore" && it.data["phase"]?.jsonPrimitive?.content == "end"
    }

    fun `test existing session loads history on init`() {
        val m = msg("msg1", "ses_test", "user").copy(
            summary = MessageSummaryDto(listOf(DiffFileDto("src/A.kt", 2, 1, "@@ patch"))),
        )
        val part = part("prt1", "ses_test", "msg1", "text", text = "hello")
        rpc.history.add(MessageWithPartsDto(m, listOf(part)))

        val c = controller("ses_test")
        val modelEvents = collectModelEvents(c)
        flush()

        assertModelEvents("HistoryLoaded", modelEvents)
        assertModel(
            """
            user#msg1
            text#prt1:
              hello
            """,
            c,
        )
        assertEquals("src/A.kt", c.model.message("msg1")?.info?.summary?.diffs?.single()?.file)
    }

    fun `test non-empty history shows messages view`() {
        rpc.history.add(MessageWithPartsDto(msg("msg1", "ses_test", "user"), emptyList()))

        val c = controller("ses_test")
        val events = collect(c)
        flush()

        // ViewChanged progress fires immediately on controller construction (step 3 of plan).
        // ViewChanged session fires after non-empty history is loaded.
        assertControllerEvents("""
            AccountOverlayChanged hide
            AppChanged
            WorkspaceChanged
            ViewChanged progress
            ViewChanged session
        """, events)

        assertSession(
            """
            user#msg1

            [app: DISCONNECTED] [workspace: PENDING]
            """,
            c,
        )
    }

    fun `test empty explicit session history shows empty view`() {
        rpc.recent.add(session("ses_recent"))

        val c = controller("ses_test")
        val events = collect(c)
        val modelEvents = collectModelEvents(c)
        flush()

        assertTrue(rpc.recentCalls.isEmpty())
        assertModelEvents("HistoryLoaded", modelEvents)
        assertControllerEvents("""
            AccountOverlayChanged hide
            AppChanged
            WorkspaceChanged
            ViewChanged progress
            ViewChanged empty
        """, events)
        assertSession(
            """
            [app: DISCONNECTED] [workspace: PENDING]
            """,
            c,
            show = false,
        )
    }

    fun `test loaded history derives agent from latest message`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady(agents = agents(), default = "plan")
        rpc.history.add(MessageWithPartsDto(msg("msg1", "ses_test", "user").copy(agent = "plan", time = MessageTimeDto(1.0)), emptyList()))
        rpc.history.add(MessageWithPartsDto(msg("msg2", "ses_test", "assistant").copy(agent = "code", time = MessageTimeDto(2.0)), emptyList()))

        val c = controller("ses_test")
        flush()

        assertEquals("code", c.model.agent)
    }

    fun `test loaded history derives model from latest user message`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady(
            agents = agents(),
            default = "plan",
            providers = listOf(
                ProviderDto(
                    id = "kilo",
                    name = "Kilo",
                    models = mapOf("gpt-5" to ModelDto(id = "gpt-5", name = "GPT-5")),
                ),
                ProviderDto(
                    id = "anthropic",
                    name = "Anthropic",
                    models = mapOf("claude" to ModelDto(id = "claude", name = "Claude")),
                ),
            ),
            connected = listOf("kilo", "anthropic"),
            defaults = mapOf("plan" to "kilo/gpt-5", "code" to "kilo/gpt-5"),
        )
        rpc.history.add(MessageWithPartsDto(msg("msg1", "ses_test", "user").copy(
            agent = "code",
            providerID = "anthropic",
            modelID = "claude",
            time = MessageTimeDto(1.0),
        ), emptyList()))

        val c = controller("ses_test")
        flush()

        assertEquals("code", c.model.agent)
        assertEquals("anthropic/claude", c.model.model)
        assertFalse(c.model.modelOverride)
    }

    fun `test empty loaded history keeps workspace default agent`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady(agents = agents(), default = "plan")

        val c = controller("ses_test")
        flush()

        assertEquals("plan", c.model.agent)
    }

    // ------ M11 session.restore observation (B3) ------

    fun `test history success with pending failure ends restore at pending stage`() {
        sessions = KiloSessionService(
            project,
            scope,
            GatedPendingApi(rpc, pendingThrows = IllegalStateException("pending unavailable")),
        )

        val c = controller("ses_test")
        flush()

        // 业务不变：历史已应用，无卡片可恢复，session视图照常收尾。
        assertEquals(SessionState.Idle, c.model.state)
        assertTrue(c.model.showSession || c.model.isEmpty())

        fixture.flush()
        val ends = restoreEnds()
        assertEquals(1, ends.size)
        assertEquals("failure", ends.single().data.getValue("result").jsonPrimitive.content)
        assertEquals("pending", ends.single().data.getValue("stage").jsonPrimitive.content)
    }

    fun `test subscription stream failure ends restore at subscription stage`() {
        val gate = CompletableDeferred<Unit>()
        sessions = KiloSessionService(project, scope, GatedPendingApi(rpc, gate = gate))
        rpc.eventFlow = { _, _ -> flow { throw IllegalStateException("stream failed") } }
        rpc.pendingPermissionList.add(
            PermissionRequestDto(
                id = "perm_pending",
                sessionID = "ses_test",
                permission = "read",
                patterns = listOf("*.json"),
            )
        )

        val c = controller("ses_test")
        flush()

        // 恢复停在pending途中时订阅流已失败：不得success。
        assertFalse(c.model.state is SessionState.AwaitingPermission)
        fixture.flush()
        val ends = restoreEnds()
        assertEquals(1, ends.size)
        assertEquals("failure", ends.single().data.getValue("result").jsonPrimitive.content)
        assertEquals("subscription", ends.single().data.getValue("stage").jsonPrimitive.content)

        // 释放gate后业务照常恢复卡片，且不产生第二条end。
        gate.complete(Unit)
        flush()
        assertTrue(c.model.state is SessionState.AwaitingPermission)
        fixture.flush()
        assertEquals(1, restoreEnds().size)
    }

    fun `test quick switch of two sessions settles each restore once`() {
        val gate = CompletableDeferred<Unit>()
        sessions = KiloSessionService(project, scope, GatedPendingApi(rpc, gate = gate))
        val a = controller("ses_a")
        flush()

        // 切换到第二个会话：新controller走完自己的恢复。
        val second = KiloSessionService(project, scope, GatedPendingApi(rpc))
        sessions = second
        val b = controller("ses_b")
        flush()
        assertEquals(SessionState.Idle, b.model.state)

        // 切走即关闭旧面板：A cancelled、B success，两operation_id互不相同。
        a.dispose()
        flush()

        // A的pending迟到完成不得再产生新end。
        gate.complete(Unit)
        flush()

        fixture.flush()
        val ends = restoreEnds()
        assertEquals(2, ends.size)
        assertEquals(1, ends.count { it.data["result"]?.jsonPrimitive?.content == "cancelled" })
        assertEquals(1, ends.count { it.data["result"]?.jsonPrimitive?.content == "success" })
        assertTrue(
            "switched sessions must have independent restore operations",
            ends[0].context["operation_id"] != ends[1].context["operation_id"],
        )
    }

    fun `test old token late result never ends new restore`() {
        val gate = CompletableDeferred<Unit>()
        sessions = KiloSessionService(project, scope, GatedPendingApi(rpc, gate = gate))
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        val c = controller("ses_test")
        flush()

        // 同一会话断线重连：新token开启新恢复，旧token操作立即cancelled。
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.DISCONNECTED)
        flush()
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        flush()

        // 旧token的pending迟到：不得结束新token的恢复。
        gate.complete(Unit)
        flush()

        fixture.flush()
        val ends = restoreEnds()
        assertEquals(2, ends.size)
        val cancelled = ends.single { it.data["result"]?.jsonPrimitive?.content == "cancelled" }
        val success = ends.single { it.data["result"]?.jsonPrimitive?.content == "success" }
        assertEquals("pending", cancelled.data.getValue("stage").jsonPrimitive.content)
        assertEquals("ui", success.data.getValue("stage").jsonPrimitive.content)
        assertTrue(
            "old and new token must have independent restore operations",
            cancelled.context["operation_id"] != success.context["operation_id"],
        )
    }

    fun `test repeated reconnect notifications open one restore each`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        val c = controller("ses_test")
        flush()

        repeat(2) {
            appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.DISCONNECTED)
            flush()
            appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
            flush()
        }

        fixture.flush()
        val ends = restoreEnds()
        assertEquals(3, ends.size)
        assertEquals(3, ends.count { it.data["result"]?.jsonPrimitive?.content == "success" })
        assertEquals(3, ends.map { it.context["operation_id"] }.toSet().size)
        val modes = ends.map { it.data.getValue("session_mode").jsonPrimitive.content }
        assertEquals(1, modes.count { it == "open" })
        assertEquals(2, modes.count { it == "reconnect" })
    }

    private fun agents() = listOf(
        AgentDto(name = "plan", displayName = "Plan", mode = "plan"),
        AgentDto(name = "code", displayName = "Code", mode = "code"),
    )
}
