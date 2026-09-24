package ai.kilocode.client.session.controller

import ai.kilocode.client.session.model.SessionState
import ai.kilocode.rpc.dto.CommandDto
import ai.kilocode.rpc.dto.ConfigDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonPrimitive

class CommandLifecycleTest : SessionControllerTestBase() {

    fun `test command creates new session and calls RPC`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady().copy(commands = listOf(CommandDto("deploy")))
        val m = controller()

        flush()
        edt { m.command("deploy", "prod") }
        flush()

        assertEquals(1, rpc.creates)
        assertEquals(1, rpc.commands.size)
        val call = rpc.commands.single()
        assertEquals("ses_test", call.id)
        assertEquals("/test", call.directory)
        assertEquals("deploy", call.command)
        assertEquals("prod", call.arguments)
    }

    fun `test command reuses existing session`() {
        val (m, _, _) = prompted()
        val created = rpc.creates

        edt { m.command("deploy", "prod") }
        flush()

        assertEquals(created, rpc.creates)
        assertEquals("ses_test", rpc.commands.single().id)
    }

    fun `test command records telemetry`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady().copy(commands = listOf(CommandDto("deploy")))
        val m = controller()

        flush()
        edt { m.command("deploy", "prod") }
        flush()

        val sent = appRpc.telemetry.single { it.event == "Conversation Send Clicked" }
        assertEquals("command", sent.properties["source"])
        assertEquals("true", sent.properties["hasSlashCommand"])
        assertEquals("server", sent.properties["slashCommandType"])
        val message = appRpc.telemetry.single { it.event == "Conversation Message" }
        assertEquals("command", message.properties["source"])
        assertEquals("true", message.properties["hasSlashCommand"])
        assertEquals("server", message.properties["slashCommandType"])
    }

    fun `test command errors set state and telemetry`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady().copy(commands = listOf(CommandDto("deploy")))
        rpc.commandThrows = IllegalStateException("boom")
        val m = controller()

        flush()
        edt { m.command("deploy", "prod") }
        flush()

        assertTrue(m.model.state is SessionState.Error)
        val event = appRpc.telemetry.single { it.event == "Session Error" }
        assertEquals("command", event.properties["context"])
    }

    // ------ M12（B4）prompt_submit 分母矩阵（command与prompt共用dispatch意图边界） ------

    private fun submitEnds() = fixture.facts().filter {
        it.name == "action" && it.data["phase"]?.jsonPrimitive?.content == "end" &&
            it.data["action"]?.jsonPrimitive?.content == "prompt_submit"
    }

    private fun submitStarts() = fixture.facts().filter {
        it.name == "action" && it.data["phase"]?.jsonPrimitive?.content == "start" &&
            it.data["action"]?.jsonPrimitive?.content == "prompt_submit"
    }

    fun `test command send settles one prompt submit success`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady().copy(commands = listOf(CommandDto("deploy")))
        // 现成会话：避免首prompt新建会话时open与action两个begin的并发争用弃录影响分母配对。
        val m = controller("ses_test")

        flush()
        edt { m.command("deploy", "prod") }
        flush()
        fixture.flush()

        assertEquals(1, submitStarts().size)
        val end = submitEnds().single()
        assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("ui", end.data.getValue("stage").jsonPrimitive.content)
    }

    fun `test command server failure settles failure once`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady().copy(commands = listOf(CommandDto("deploy")))
        rpc.commandThrows = IllegalStateException("boom")
        val m = controller("ses_test")

        flush()
        edt { m.command("deploy", "prod") }
        flush()
        fixture.flush()

        assertTrue(m.model.state is SessionState.Error)
        assertEquals(1, submitStarts().size)
        val end = submitEnds().single()
        assertEquals("failure", end.data.getValue("result").jsonPrimitive.content)
        assertEquals("rpc", end.data.getValue("stage").jsonPrimitive.content)
    }

    fun `test command during revert does not begin a denominator`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady().copy(commands = listOf(CommandDto("deploy")))
        val m = controller("ses_test")
        flush()
        rpc.revertGate = CompletableDeferred()
        edt { m.revert("x") }
        flush()

        edt { m.command("deploy", "prod") }
        flush()
        fixture.flush()

        assertEquals(0, rpc.commands.size)
        assertTrue(submitStarts().isEmpty())
        assertTrue(submitEnds().isEmpty())

        rpc.revertGate!!.complete(Unit)
        flush()
    }
}
