package ai.kilocode.client.stability

import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.KiloWorkspaceStateDto
import ai.kilocode.rpc.dto.KiloWorkspaceStatusDto
import ai.kilocode.stability.Operations
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val READINESS_NAME = "plugin.readiness"
private const val READINESS_DEADLINE_MS = 60_000L

/** end的stage公共字段值（≤32字节有界字符串；begin不占name专属键）。 */
private const val READINESS_STAGE = "readiness"

/** blocked reason安全闭集（G0登记的常量token；不含路径/凭据/服务端message）。 */
internal const val READINESS_BLOCKED_MIGRATION = "migration_required"
internal const val READINESS_BLOCKED_CREDENTIALS = "credentials_missing"

/**
 * M03"打开到可用"（brief Step 2）：一次激活一个分母。构造即begin（60秒deadline），
 * [update]按五项条件推进；blocked→end(blocked, reason)，全部为真→end(success,
 * reason=none)。重复update不新建分母：终态唯一由Operation的CAS保证，已结算后的
 * update只被丢弃。reason是登记过的安全常量token，绝不透传服务端message；reason经
 * end的fields写入，error_code保持登记的安全码（reason不顶替error_code）。
 */
class Readiness(operations: Operations, context: Map<String, String>) {

    private val operation = operations.begin(READINESS_NAME, READINESS_DEADLINE_MS, context = context)

    /** 五项条件 + blocked是brief规定的接口形态（逐字签名），抑制参数个数与组合条件告警。 */
    @Suppress("LongParameterList", "ComplexCondition")
    fun update(
        view: Boolean,
        app: Boolean,
        workspace: Boolean,
        subscription: Boolean,
        input: Boolean,
        blocked: String? = null,
    ) {
        if (blocked != null) {
            operation.end("blocked", READINESS_STAGE, "environment", fields = buildJsonObject {
                put("reason", blocked)
            })
            return
        }
        if (view && app && workspace && subscription && input) {
            operation.end("success", READINESS_STAGE, fields = buildJsonObject {
                put("reason", "none")
            })
        }
    }
}

/**
 * M03激活级装配（brief Step 5）：工具窗首次激活（setup成功、根视图已安装）创建
 * **一个**[Readiness]；AppService/Workspace的现有状态流变更经EDT驱动五项条件，
 * 自动状态重复绝不新建分母。激活前的状态变更只累计条件，不创建分母。
 *
 * 五项条件的B1级真实来源（全部在EDT判定）：
 * - view：根视图安装完成（KiloToolWindowSetupService.setup成功后调用[activate]）。
 * - app：AppService状态流到达READY——这是后端在chat/sessions/models/workspace与
 *   全局SSE全部注册完成后才发出的完成信号，经真实RPC状态流跨进程送达
 *   （不是"Job已launch"）。
 * - workspace：Workspace状态流到达READY（agents/providers经注册的workspace流送达）。
 * - subscription：同app的READY完成信号——后端流注册完成在先、READY在后，READY即
 *   跨端注册完成的可观测回执。会话级订阅（真实onStart）由B3按session.restore细化。
 * - input：[inputProvider]返回的实际可交互状态（生产侧读取真实输入焦点组件，EDT）。
 *
 * blocked：MIGRATION_REQUIRED→[READINESS_BLOCKED_MIGRATION]；READY但无profile
 * （凭据缺失/未登录）→[READINESS_BLOCKED_CREDENTIALS]。连接中/加载中的暂时无凭据
 * 不算blocked，只是不满足条件；blocked一出现即end，同激活内后续重复blocked不再
 * 二次结算。用户重试（新的工具窗激活）由调用方新建本类实例，产生新分母。
 *
 * 线程纪律：状态读写只在真实EDT（状态流回调经[invokeLater]投递）；record路径
 * 非阻塞，不新增阻塞RPC。
 */
internal class ReadinessWatch(
    private val operations: Operations,
    scope: CoroutineScope,
    app: StateFlow<KiloAppStateDto>,
    workspace: StateFlow<KiloWorkspaceStateDto>,
    private val inputProvider: () -> Boolean,
) {

    private var readiness: Readiness? = null
    private var view = false
    private var appReady = false
    private var workspaceReady = false
    private var blocked: String? = null

    init {
        scope.launch { app.collect { state -> post { onApp(state) } } }
        scope.launch { workspace.collect { state -> post { onWorkspace(state) } } }
    }

    /** 首次激活（根视图已安装）调用；幂等——重复激活不新建分母。必须在EDT。
     * 激活前已观察到的blocked状态（如迁移已在进行）在此一并结算。 */
    @RequiresEdt
    fun activate() {
        if (view) return
        view = true
        refresh()
    }

    @RequiresEdt
    private fun onApp(state: KiloAppStateDto) {
        appReady = state.status == KiloAppStatusDto.READY
        blocked = blockedReason(state)
        refresh()
    }

    @RequiresEdt
    private fun onWorkspace(state: KiloWorkspaceStateDto) {
        workspaceReady = state.status == KiloWorkspaceStatusDto.READY
        refresh()
    }

    private fun blockedReason(state: KiloAppStateDto): String? = when {
        state.status == KiloAppStatusDto.MIGRATION_REQUIRED -> READINESS_BLOCKED_MIGRATION
        state.status == KiloAppStatusDto.READY && state.profile == null -> READINESS_BLOCKED_CREDENTIALS
        else -> null
    }

    @RequiresEdt
    private fun refresh() {
        if (!view) return
        val target = readiness ?: Readiness(operations, emptyMap()).also { readiness = it }
        target.update(
            view = view,
            app = appReady,
            workspace = workspaceReady,
            subscription = appReady,
            input = inputProvider(),
            blocked = blocked,
        )
    }

    private fun post(block: () -> Unit) {
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDispatchThread) block() else application.invokeLater(block)
    }
}
