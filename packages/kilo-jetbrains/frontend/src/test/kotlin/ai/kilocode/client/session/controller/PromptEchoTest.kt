package ai.kilocode.client.session.controller

import ai.kilocode.rpc.dto.ChatEventDto
import ai.kilocode.rpc.dto.PartDto

/**
 * Covers the optimistic prompt echo: the user's input renders locally on send, backends that echo
 * user message events swap the bubble for the server copy, and backends that never echo (e.g.
 * cs-cloud) keep the optimistic bubble as the only visible copy of the input.
 */
class PromptEchoTest : SessionControllerTestBase() {

    fun `test prompt renders optimistic user message without backend echo`() {
        appRpc.state.value = ai.kilocode.rpc.dto.KiloAppStateDto(
            ai.kilocode.rpc.dto.KiloAppStatusDto.READY,
            config = ai.kilocode.rpc.dto.ConfigDto(model = "kilo/gpt-5"),
        )
        projectRpc.state.value = workspaceReady()
        val m = controller(echo = true)
        val modelEvents = collectModelEvents(m)
        flush()

        edt { m.prompt("go") }
        flush()

        assertModel(
            """
            user#msg_pending_1
            text#prt_pending_1:
              go
            """,
            m,
        )
        assertModelEvents(
            """
            MessageAdded msg_pending_1
            TurnAdded msg_pending_1 [msg_pending_1]
            ContentAdded msg_pending_1/prt_pending_1
            """,
            modelEvents,
        )
    }

    fun `test server echo replaces optimistic bubble without duplicate`() {
        val (m, _, _) = prompted(echo = true)

        emit(ChatEventDto.MessageUpdated("ses_test", msg("u1", "ses_test", "user")))
        emit(ChatEventDto.PartUpdated("ses_test", part("p1", "ses_test", "u1", "text", text = "go")))

        assertModel(
            """
            user#u1
            text#p1:
              go
            """,
            m,
        )
        assertNull(m.model.message("msg_pending_1"))
    }

    fun `test repeated part updates do not consume next pending bubble`() {
        val (m, _, _) = prompted(echo = true)
        edt { m.prompt("again") }
        flush()

        emit(ChatEventDto.MessageUpdated("ses_test", msg("u1", "ses_test", "user")))
        emit(ChatEventDto.PartUpdated("ses_test", part("p1", "ses_test", "u1", "text", text = "go")))
        // A refresh of the same rendered part must not pop the second pending bubble.
        emit(ChatEventDto.PartUpdated("ses_test", part("p1", "ses_test", "u1", "text", text = "go")))

        assertNotNull(m.model.message("msg_pending_2"))

        emit(ChatEventDto.MessageUpdated("ses_test", msg("u2", "ses_test", "user")))
        emit(ChatEventDto.PartUpdated("ses_test", part("p2", "ses_test", "u2", "text", text = "again")))

        assertModel(
            """
            user#u1
            text#p1:
              go
            ---
            user#u2
            text#p2:
              again
            """,
            m,
        )
    }

    fun `test assistant only backend keeps optimistic bubble`() {
        val (m, _, _) = prompted(echo = true)

        emit(ChatEventDto.MessageUpdated("ses_test", msg("a1", "ses_test", "assistant")))
        emit(ChatEventDto.PartUpdated("ses_test", part("ap1", "ses_test", "a1", "text", text = "hi")))

        assertModel(
            """
            user#msg_pending_1
            text#prt_pending_1:
              go
            ---
            assistant#a1
            text#ap1:
              hi
            """,
            m,
        )
    }

    fun `test synthetic user echo does not reconcile pending bubble`() {
        val (m, _, _) = prompted(echo = true)

        emit(ChatEventDto.MessageUpdated("ses_test", msg("u1", "ses_test", "user")))
        emit(
            ChatEventDto.PartUpdated(
                "ses_test",
                PartDto(id = "p1", sessionID = "ses_test", messageID = "u1", type = "text", text = "injected", synthetic = true),
            ),
        )

        assertNotNull(m.model.message("msg_pending_1"))
        assertNull(m.model.content("u1", "p1"))
    }

    fun `test blank prompt does not create optimistic bubble`() {
        appRpc.state.value = ai.kilocode.rpc.dto.KiloAppStateDto(
            ai.kilocode.rpc.dto.KiloAppStatusDto.READY,
            config = ai.kilocode.rpc.dto.ConfigDto(model = "kilo/gpt-5"),
        )
        projectRpc.state.value = workspaceReady()
        val m = controller(echo = true)
        flush()

        edt { m.prompt("   ") }
        flush()

        assertTrue(m.model.isEmpty())
        assertEquals(1, rpc.prompts.size)
    }

    fun `test command does not create optimistic bubble`() {
        val (m, _, _) = prompted(echo = true)

        edt { m.command("compact", "") }
        flush()

        assertModel(
            """
            user#msg_pending_1
            text#prt_pending_1:
              go
            """,
            m,
        )
        assertEquals(1, rpc.commands.size)
    }

    fun `test history reload drops pending bubbles`() {
        rpc.history.add(
            ai.kilocode.rpc.dto.MessageWithPartsDto(
                info = msg("u0", "ses_test", "user"),
                parts = listOf(part("p0", "ses_test", "u0", "text", text = "old")),
            ),
        )
        appRpc.state.value = ai.kilocode.rpc.dto.KiloAppStateDto(
            ai.kilocode.rpc.dto.KiloAppStatusDto.READY,
            config = ai.kilocode.rpc.dto.ConfigDto(model = "kilo/gpt-5"),
        )
        projectRpc.state.value = workspaceReady()
        val m = controller(id = "ses_test", echo = true)
        flush()

        edt { m.prompt("go") }
        flush()
        assertNotNull(m.model.message("msg_pending_1"))

        // Backend reconnect reloads history; the pending bubble must not survive it.
        appRpc.state.value = ai.kilocode.rpc.dto.KiloAppStateDto(ai.kilocode.rpc.dto.KiloAppStatusDto.ERROR)
        flush()
        println("DBG after-error app=" + edt { m.model.app.status })
        appRpc.state.value = ai.kilocode.rpc.dto.KiloAppStateDto(
            ai.kilocode.rpc.dto.KiloAppStatusDto.READY,
            config = ai.kilocode.rpc.dto.ConfigDto(model = "kilo/gpt-5"),
        )
        flush()
        println("DBG after-ready historyCalls=" + rpc.historyCalls + " model=" + edt { m.model.toString().replace("\n", "|") })

        assertNull(m.model.message("msg_pending_1"))
        assertModel(
            """
            user#u0
            text#p0:
              old
            """,
            m,
        )
    }

    fun `test echo disabled keeps legacy behavior`() {
        val (m, _, _) = prompted()

        assertTrue(m.model.isEmpty())
    }

    fun `test failed prompt send rolls back optimistic bubble`() {
        appRpc.state.value = ai.kilocode.rpc.dto.KiloAppStateDto(
            ai.kilocode.rpc.dto.KiloAppStatusDto.READY,
            config = ai.kilocode.rpc.dto.ConfigDto(model = "kilo/gpt-5"),
        )
        projectRpc.state.value = workspaceReady()
        rpc.promptThrows = IllegalStateException("prompt_async failed: HTTP 409: session is already processing a prompt")
        val m = controller(echo = true)
        flush()

        edt { m.prompt("go") }
        flush()

        // The backend rejected the send, so the optimistic bubble must not linger as if the
        // message had been delivered.
        assertTrue(m.model.isEmpty())
        assertTrue(m.model.state is ai.kilocode.client.session.model.SessionState.Error)
    }
}
