package ai.kilocode.client.session.controller

import ai.kilocode.client.session.model.SessionState
import ai.kilocode.rpc.dto.ChatEventDto
import ai.kilocode.rpc.dto.ConfigDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.SessionStatusDto
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * M18（C2）：活跃会话遇到服务释放的风险观测，经真实SessionController驱动。
 *
 * daemon端global.disposed/server_instance_disposed在前端唯一可观测的信号是app状态
 * 无中间断连状态的READY→LOADING直接转换（backend仅这两个SSE事件在Ready时直接触发
 * 整体reload）。只有会话活跃且非本面板正常退出才记录；同episode内的重复LOADING
 * 投递按"连接代际+来源+状态转换"去重；断连后的恢复reload（先经CONNECTING）与
 * 用户retry/restart（先经DISCONNECTED）都不算释放风险。
 */
class DisposeObservationTest : SessionControllerTestBase() {

    override fun setUp() {
        super.setUp()
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.DISCONNECTED)
    }

    private fun disposeFacts() = fixture.facts().filter { it.name == "session.dispose_risk" }

    /** 现成会话+进行中的对话（Busy）：dispose分母的活跃前提。 */
    private fun busy(): SessionController {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady()
        val m = controller("ses_test")
        flush()
        edt { m.prompt("go") }
        flush()
        emit(ChatEventDto.SessionStatusChanged("ses_test", SessionStatusDto("busy")))
        assertTrue(m.model.state is SessionState.Busy)
        fixture.flush()
        assertTrue(disposeFacts().isEmpty())
        return m
    }

    private fun transition(vararg statuses: KiloAppStatusDto) {
        statuses.forEach { status ->
            appRpc.state.value = KiloAppStateDto(status)
            flush()
        }
    }

    fun `test dispose reload during active conversation records once per episode`() {
        busy()

        // 直接READY→LOADING：daemon端dispose触发的整体reload；同一episode内重复LOADING
        // 投递（进度更新）不重复计数。
        transition(KiloAppStatusDto.LOADING, KiloAppStatusDto.LOADING, KiloAppStatusDto.READY)
        fixture.flush()

        assertEquals(1, disposeFacts().size)
        val fact = disposeFacts().single()
        assertEquals("global_disposed", fact.data.getValue("dispose_source").jsonPrimitive.content)
        assertEquals(true, fact.data.getValue("conversation_active").jsonPrimitive.boolean)
        assertEquals("transition", fact.kind)
        assertEquals("critical", fact.channel)
    }

    fun `test distinct dispose episodes each record once`() {
        val m = busy()

        transition(KiloAppStatusDto.LOADING, KiloAppStatusDto.READY)
        flush()
        // 第一次释放后controller自动重连恢复（M11 reconnect），恢复后对话重新活跃
        // （如用户重发）；活跃前提重新成立后第二次真实释放再次记录。
        emit(ChatEventDto.SessionStatusChanged("ses_test", SessionStatusDto("busy")))
        assertTrue(m.model.state is SessionState.Busy)
        transition(KiloAppStatusDto.LOADING, KiloAppStatusDto.READY)
        fixture.flush()

        // 去重是按事件（连接代际+来源+状态转换）而非按会话生命周期。
        assertEquals(2, disposeFacts().size)
    }

    fun `test dispose reload without active conversation records nothing`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, config = ConfigDto(model = "kilo/gpt-5"))
        projectRpc.state.value = workspaceReady()
        val m = controller("ses_test")
        flush()

        transition(KiloAppStatusDto.LOADING, KiloAppStatusDto.READY)
        fixture.flush()

        assertTrue(m.model.state is SessionState.Idle)
        assertTrue(disposeFacts().isEmpty())
    }

    fun `test normal shutdown records no dispose risk`() {
        val m = busy()

        edt { m.dispose() }
        flush()
        // 正常退出后的任何状态迁移（含后续dispose式reload）都不再产事实。
        transition(KiloAppStatusDto.DISCONNECTED, KiloAppStatusDto.LOADING, KiloAppStatusDto.READY)
        fixture.flush()

        assertTrue(disposeFacts().isEmpty())
    }

    fun `test connection recovery reload is not a dispose risk`() {
        busy()

        // 断连→重连恢复：LOADING之前先经过非READY的连接态，不是dispose信号。
        transition(KiloAppStatusDto.CONNECTING, KiloAppStatusDto.LOADING, KiloAppStatusDto.READY)
        fixture.flush()

        assertTrue(disposeFacts().isEmpty())
    }

    fun `test user retry disconnect reload is not a dispose risk`() {
        busy()

        // 用户retry/restart先经DISCONNECTED（backend reset()），随后reload不算释放风险。
        transition(KiloAppStatusDto.DISCONNECTED, KiloAppStatusDto.LOADING, KiloAppStatusDto.READY)
        fixture.flush()

        assertTrue(disposeFacts().isEmpty())
    }
}
