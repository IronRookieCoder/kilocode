package ai.kilocode.client.session.controller

import ai.kilocode.client.session.model.SessionState
import ai.kilocode.rpc.dto.ConfigDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.ModelDto
import ai.kilocode.rpc.dto.ModelStateDto
import ai.kilocode.rpc.dto.ProviderDto
import kotlinx.serialization.json.jsonPrimitive

class SessionCreationTest : SessionControllerTestBase() {

    /** M11新建分母的end事实；所有读取前先fixture.flush封存，避免线程时序偶然通过。 */
    private fun openEnds() = fixture.facts().filter {
        it.name == "session.open" && it.data["phase"]?.jsonPrimitive?.content == "end"
    }

    fun `test prompt creates session on first call`() {
        val m = controller()
        val events = collect(m)
        flush()
        events.clear()

        edt { m.prompt("hello") }
        flush()

        assertEquals(1, rpc.creates)
        assertEquals(1, rpc.prompts.size)
        assertEquals("ses_test", rpc.prompts[0].first)
        assertControllerEvents("ViewChanged session", events)
        assertSession(
            """
            [app: DISCONNECTED] [workspace: PENDING]
            """,
            m,
        )
    }

    fun `test prompt reuses existing session`() {
        val m = controller()

        edt { m.prompt("first") }
        flush()
        edt { m.prompt("second") }
        flush()

        assertEquals(1, rpc.creates)
        assertEquals(2, rpc.prompts.size)
        assertEquals("ses_test", rpc.prompts[1].first)
    }

    fun `test same-turn first prompts share session creation`() {
        val m = controller()

        edt {
            m.prompt("first")
            m.prompt("second")
        }
        flush()

        assertEquals(1, rpc.creates)
        assertEquals(listOf("ses_test", "ses_test"), rpc.prompts.map { it.first })
        assertEquals(listOf("first", "second"), rpc.prompts.map { it.third.parts.single().text.toString() }.sorted())
    }

    fun `test same-turn first prompt and command share session creation`() {
        val m = controller()

        edt {
            m.prompt("first")
            m.command("deploy", "prod")
        }
        flush()

        assertEquals(1, rpc.creates)
        assertEquals("ses_test", rpc.prompts.single().first)
        assertEquals("ses_test", rpc.commands.single().id)
    }

    fun `test prompt with existing ID skips creation`() {
        val m = controller("existing")
        collect(m)
        flush()

        edt { m.prompt("hello") }
        flush()

        assertEquals(0, rpc.creates)
        assertEquals(1, rpc.prompts.size)
        assertEquals("existing", rpc.prompts[0].first)
    }

    fun `test prompt sends selected model agent and variant`() {
        appRpc.models = ModelStateDto(variant = mapOf("kilo/gpt-5" to "medium"))
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady(
            providers = listOf(
                ProviderDto(
                    id = "kilo",
                    name = "Kilo",
                    models = mapOf(
                        "gpt-5" to ModelDto(id = "gpt-5", name = "GPT-5", variants = listOf("low", "medium", "high")),
                    ),
                ),
            ),
        )
        val m = controller("existing")
        collect(m)
        flush()

        edt { m.prompt("hello") }
        flush()

        val prompt = rpc.prompts.single().third
        assertEquals("kilo", prompt.providerID)
        assertEquals("gpt-5", prompt.modelID)
        assertEquals("code", prompt.agent)
        assertEquals("medium", prompt.variant)
    }

    fun `test prompt creation observes open denominator until subscription and ui`() {
        val m = controller()
        val events = collect(m)
        flush()
        events.clear()

        edt { m.prompt("hello") }
        flush()

        assertEquals(1, rpc.creates)
        assertEquals(1, rpc.prompts.size)
        // 新建分母：服务端ID、订阅已建立与UI都建立后才有唯一success end，start/end同operation_id。
        fixture.flush()
        val ends = openEnds()
        assertEquals(1, ends.size)
        assertEquals("success", ends.single().data.getValue("result").jsonPrimitive.content)
        assertEquals("ui", ends.single().data.getValue("stage").jsonPrimitive.content)
        assertEquals("create", ends.single().data.getValue("session_mode").jsonPrimitive.content)
        val starts = fixture.facts().filter {
            it.name == "session.open" && it.data["phase"]?.jsonPrimitive?.content == "start"
        }
        assertEquals(1, starts.size)
        assertEquals(
            ends.single().context["operation_id"],
            starts.single().context["operation_id"],
        )
    }

    fun `test create failure ends open denominator and retry opens new one`() {
        rpc.createThrows = IllegalStateException("paused backend")
        val m = controller()

        edt { m.prompt("hello") }
        flush()

        assertEquals(1, rpc.creates)
        assertTrue(m.model.state is SessionState.Error)

        fixture.flush()
        val failed = openEnds()
        assertEquals(1, failed.size)
        assertEquals("failure", failed.single().data.getValue("result").jsonPrimitive.content)
        assertEquals("create", failed.single().data.getValue("stage").jsonPrimitive.content)

        // 用户再次发起即新分母：第二次create独立结算success。
        rpc.createThrows = null
        edt { m.prompt("again") }
        flush()

        assertEquals(2, rpc.creates)
        fixture.flush()
        val ends = openEnds()
        assertEquals(2, ends.size)
        assertEquals(1, ends.count { it.data["result"]?.jsonPrimitive?.content == "success" })
        assertTrue(
            "each create attempt must have its own operation id",
            ends[0].context["operation_id"] != ends[1].context["operation_id"],
        )
    }

    fun `test create success is independent of prompt send failure`() {
        val m = controller()
        flush()
        rpc.promptThrows = IllegalStateException("send failed")

        edt { m.prompt("hello") }
        flush()

        assertEquals(1, rpc.creates)
        assertEquals(0, rpc.prompts.size)
        assertTrue(m.model.state is SessionState.Error)

        // 新建成功不等于prompt成功：open分母独立结算success。
        fixture.flush()
        val ends = openEnds()
        assertEquals(1, ends.size)
        assertEquals("success", ends.single().data.getValue("result").jsonPrimitive.content)
        assertEquals("ui", ends.single().data.getValue("stage").jsonPrimitive.content)
    }
}
