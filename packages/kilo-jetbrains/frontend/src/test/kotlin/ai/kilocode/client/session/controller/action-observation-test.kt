package ai.kilocode.client.session.controller

import ai.kilocode.client.plugin.KiloPluginSettings
import ai.kilocode.client.session.model.SessionState
import ai.kilocode.rpc.dto.ChatEventDto
import ai.kilocode.rpc.dto.ConfigDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.PermissionReplyDto
import ai.kilocode.rpc.dto.PermissionRequestDto
import ai.kilocode.rpc.dto.QuestionInfoDto
import ai.kilocode.rpc.dto.QuestionReplyDto
import ai.kilocode.rpc.dto.QuestionRequestDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonPrimitive

/**
 * M12（B4）：五种关键操作的action分母观测。每个已接收的用户意图一个Operation；
 * success只在请求返回且本地状态更新后结算；自动批准/校验失败不进分母；
 * disposed早退结算cancelled而非success；超时后迟到不得改判success。
 */
class ActionObservationTest : SessionControllerTestBase() {

    override fun setUp() {
        super.setUp()
        edt { KiloPluginSettings.unsetAutoApprove() }
    }

    override fun tearDown() {
        try {
            edt { KiloPluginSettings.unsetAutoApprove() }
        } finally {
            super.tearDown()
        }
    }

    private fun actionFacts(phase: String, action: String) = fixture.facts().filter {
        it.name == "action" &&
            it.data["phase"]?.jsonPrimitive?.content == phase &&
            it.data["action"]?.jsonPrimitive?.content == action
    }

    private fun actionStarts(action: String) = actionFacts("start", action)

    private fun actionEnds(action: String) = actionFacts("end", action)

    /** 现成会话：跳过新建（open分母），后续用户意图的action分母唯一可读。 */
    private fun ready(): SessionController {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady()
        val m = controller("ses_test")
        flush()
        return m
    }

    fun `test prompt result excludes prompt content`() {
        prompted()
        flush()
        fixture.flush()
        val end = fixture.facts().single {
            it.name == "action" && it.data["action"]?.jsonPrimitive?.content == "prompt_submit" &&
                it.data["phase"]?.jsonPrimitive?.content == "end"
        }
        assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
        assertFalse(end.data.containsKey("prompt"))
        assertFalse(end.data.containsKey("text"))
    }

    fun `test prompt submit denominator is one paired operation`() {
        // 现成会话：首prompt新建会话时open与action两个begin在EDT/执行器并发，recorder的
        // 争用即弃策略（设计7.1，绝不阻塞）可能丢弃其一；分母配对用无并发对的场景验证。
        val m = ready()

        edt { m.prompt("go") }
        flush()
        fixture.flush()

        val starts = actionStarts("prompt_submit")
        val ends = actionEnds("prompt_submit")
        assertEquals(1, starts.size)
        assertEquals(1, ends.size)
        assertEquals(starts.single().context["operation_id"], ends.single().context["operation_id"])
        assertEquals("ui", ends.single().data.getValue("stage").jsonPrimitive.content)
    }

    fun `test stop settles success after local state update`() {
        val (m, _, _) = prompted()

        edt { m.abort() }
        flush()
        fixture.flush()

        assertEquals(1, rpc.aborts.size)
        assertEquals(1, actionStarts("stop").size)
        val end = actionEnds("stop").single()
        assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("ui", end.data.getValue("stage").jsonPrimitive.content)
    }

    fun `test permission reply settles success once`() {
        val (m, _, _) = prompted()
        emit(ChatEventDto.PermissionAsked("ses_test", permission("perm1")))

        edt { m.replyPermission("perm1", PermissionReplyDto("once")) }
        flush()
        fixture.flush()

        assertEquals(1, rpc.permissionReplies.size)
        assertEquals(1, actionStarts("permission_reply").size)
        val end = actionEnds("permission_reply").single()
        assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("ui", end.data.getValue("stage").jsonPrimitive.content)
    }

    fun `test auto approve sends replies without permission reply denominator`() {
        val (m, _, _) = prompted()
        edt { m.setAutoApprove(true) }

        emit(ChatEventDto.PermissionAsked("ses_test", permission("perm1")))
        flush()
        fixture.flush()

        assertEquals(1, rpc.permissionReplies.size)
        assertTrue(actionStarts("permission_reply").isEmpty())
        assertTrue(actionEnds("permission_reply").isEmpty())
    }

    fun `test question reply settles success once`() {
        val (m, _, _) = prompted()
        emit(ChatEventDto.QuestionAsked("ses_test", question("q1")))

        edt { m.replyQuestion("q1", QuestionReplyDto(listOf(listOf("A")))) }
        flush()
        fixture.flush()

        assertEquals(1, rpc.questionReplies.size)
        assertEquals(1, actionStarts("question_reply").size)
        val end = actionEnds("question_reply").single()
        assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("ui", end.data.getValue("stage").jsonPrimitive.content)
    }

    fun `test prompt send failure settles failure and keeps error state`() {
        val m = ready()
        rpc.promptThrows = IllegalStateException("boom")

        edt { m.prompt("go") }
        flush()
        fixture.flush()

        assertTrue(m.model.state is SessionState.Error)
        assertEquals(1, actionStarts("prompt_submit").size)
        val end = actionEnds("prompt_submit").single()
        assertEquals("failure", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("rpc", end.data.getValue("stage").jsonPrimitive.content)
        assertTrue(actionEnds("prompt_submit").none { it.data.getValue("result").jsonPrimitive.content == "success" })
    }

    fun `test stop request failure settles failure once`() {
        val (m, _, _) = prompted()
        rpc.abortThrows = IllegalStateException("boom")

        edt { m.abort() }
        flush()
        fixture.flush()

        val end = actionEnds("stop").single()
        assertEquals("failure", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("rpc", end.data.getValue("stage").jsonPrimitive.content)
        assertTrue(appRpc.telemetry.any { it.event == "Session Error" && it.properties["context"] == "abort" })
        assertTrue(m.model.state !is SessionState.Error)
    }

    fun `test late completion after deadline does not claim success`() {
        val m = ready()
        rpc.promptGate = CompletableDeferred()

        edt { m.prompt("go") }
        flush()
        fixture.flush()
        assertEquals(1, actionStarts("prompt_submit").size)
        assertTrue(actionEnds("prompt_submit").isEmpty())

        fixture.advanceClock(31_000)
        rpc.promptGate!!.complete(Unit)
        flush()
        fixture.flush()

        val ends = actionEnds("prompt_submit")
        assertEquals(1, ends.size)
        assertEquals("timeout", ends.single().data.getValue("result").jsonPrimitive.content)
    }

    fun `test disposed in flight prompt settles cancelled not success`() {
        val m = ready()
        rpc.promptGate = CompletableDeferred()

        edt { m.prompt("go") }
        flush()
        edt { m.dispose() }
        flush()
        fixture.flush()
        rpc.promptGate?.complete(Unit)

        val end = actionEnds("prompt_submit").single()
        assertEquals("cancelled", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("user", end.data.getValue("cause").jsonPrimitive.content)
    }

    fun `test stop with panel closed mid request settles cancelled not success`() {
        val (m, _, _) = prompted()
        rpc.abortGate = CompletableDeferred()

        edt { m.abort() }
        flush()
        assertTrue(actionEnds("stop").isEmpty())

        // 请求在途时面板关闭：状态更新已不可能，请求成功也不得记success。
        edt { m.dispose() }
        flush()
        rpc.abortGate!!.complete(Unit)
        flush()
        fixture.flush()

        val end = actionEnds("stop").single()
        assertEquals("cancelled", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("user", end.data.getValue("cause").jsonPrimitive.content)
    }

    fun `test send intent during revert does not begin a denominator`() {
        val (m, _, _) = prompted()
        rpc.revertGate = CompletableDeferred()
        edt { m.revert("x") }
        flush()
        fixture.flush()
        // 基线取revert占用前的实际计数（首prompt新建会话时open与action的begin并发，
        // 争用即弃策略可能丢弃其一）；断言revert期间被拒的新意图不新增任何分母与发送。
        val startsBefore = actionStarts("prompt_submit").size
        val endsBefore = actionEnds("prompt_submit").size
        val promptsBefore = rpc.prompts.size

        edt { m.prompt("go") }
        flush()
        fixture.flush()

        assertEquals(promptsBefore, rpc.prompts.size)
        assertEquals(startsBefore, actionStarts("prompt_submit").size)
        assertEquals(endsBefore, actionEnds("prompt_submit").size)

        rpc.revertGate!!.complete(Unit)
        flush()
    }

    private fun permission(id: String) = PermissionRequestDto(
        id = id,
        sessionID = "ses_test",
        permission = "edit",
        patterns = listOf("*.kt"),
        always = emptyList(),
    )

    private fun question(id: String) = QuestionRequestDto(
        id = id,
        sessionID = "ses_test",
        questions = listOf(QuestionInfoDto("Pick one", "Choice")),
    )
}
