package ai.kilocode.stability

import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M04/M05登记词表（metrics 3.2/设计第9章）。trigger三值受控：initial=用户连接入口、
 * manual=用户显式重连/重启、recovery=后台自动重试内部trigger（绝不新建逻辑分母）。
 */
object ConnectionTriggers {
    const val INITIAL = "initial"
    const val MANUAL = "manual"
    const val RECOVERY = "recovery"
}

/**
 * 断连reason受控词表（metrics 3.2）：只登记provider实际可观测的丢失原因。daemon_restart
 * 必须有daemon实例身份变化的证据——connectionEpoch变化只代表新连接，绝不写daemon_restart；
 * 未识别的丢失归unknown。正常shutdown不产生断连事实，不走本词表。
 */
object ConnectionReasons {
    const val SSE_CLOSED = "sse_closed"
    const val HEARTBEAT_TIMEOUT = "heartbeat_timeout"
    const val HEALTH_FAILED = "health_failed"
    const val PROCESS_EXIT = "process_exit"
    const val PARTIAL_SSE_FAILED = "partial_sse_failed"
    const val UNKNOWN = "unknown"
}

/** 连接事实name（设计第9章字典）与deadline（全局约束：逻辑连接30秒、恢复30秒）。 */
private const val CONNECTION_NAME = "connection"
private const val ATTEMPT_NAME = "connection.attempt"
private const val RECOVERY_NAME = "connection.recovery"
private const val STATE_CHANGED_NAME = "connection.state_changed"

private const val LOGICAL_DEADLINE_MS = 30_000L
private const val ATTEMPT_DEADLINE_MS = 30_000L
private const val RECOVERY_DEADLINE_MS = 30_000L

private const val STAGE_RESOLVE = "resolve"
private const val STAGE_STREAMS = "streams"

private const val INTERVENTION_AUTOMATIC = "automatic"
private const val INTERVENTION_MANUAL = "manual"

private const val CAUSE_USER = "user"
private const val RESULT_CANCELLED = "cancelled"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILURE = "failure"

private const val CONTEXT_ATTEMPT_ID = "attempt_id"
private const val CHANNEL = "critical"
private const val TRANSITION_FROM = "connected"
private const val TRANSITION_TO = "connecting"

/**
 * M04逻辑连接与M05断连恢复（brief）：一次request开一个30秒逻辑[connection] operation；
 * 每轮endpoint/health/全部必需流是同一个[connection.attempt]（stage变化只progress），
 * 后台重试只增加attempt，逻辑分母不随之增加。ready后丢失开[connection.recovery]区间
 * （多流同败只开一次），connected()同时结算逻辑成功与恢复成功；ready前失败只落在
 * attempt诊断层（M04），不开恢复（M05）。正常close结算在途区间为cancelled且绝不产生
 * 断连事实。
 *
 * 字段（brief）：logical/recovery/ready/attempts/intervention。所有更新要求在provider
 * 连接状态机的单一串行上下文完成：跨SSE回调必须先投递（cs-cloud经scheduleReconnect的
 * 单飞协程，backend经单消费者Channel），方法自身再加synchronized兜底防止撕裂，但顺序性
 * 由provider保证。attempt经context.operation_id挂到逻辑旅程、context.attempt_id为独立
 * UUID；Operation对象本身绝不作为序列化字段暴露。
 */
class ConnectionObservation(
    private val operations: Operations,
    /** 生产默认30秒；测试注入更短deadline驱动真实超时定时器。 */
    internal val logicalDeadlineMs: Long = LOGICAL_DEADLINE_MS,
) {

    private var logical: Operation? = null
    private var recovery: Operation? = null
    private var currentAttempt: Operation? = null

    /** 当前逻辑旅程的operation id：attempt/recovery经context挂靠的对象。 */
    private var logicalId: String? = null

    private var ready = false
    private var attempts = 0
    private var intervention = INTERVENTION_AUTOMATIC
    private var stage = STAGE_RESOLVE
    private var closed = false

    /** 是否有在途attempt（未终态）：provider判断失败回调是否需要结算attempt的依据。 */
    val attemptOpen: Boolean
        get() = currentAttempt?.isSettled == false

    /**
     * 用户连接入口（initial/manual）或后台重试（recovery）。initial仅在无在途逻辑操作时
     * 开新分母（重入忽略）；manual取消旧逻辑操作（cancelled，在途attempt一并结算）并开
     * 新operation，已存在恢复区间时只把intervention改为manual，不另增恢复区间；recovery
     * 只服务于传输重试，绝不新建逻辑分母。
     */
    @Synchronized
    fun request(trigger: String) {
        if (closed) return
        when (trigger) {
            ConnectionTriggers.MANUAL -> {
                if (recovery != null) intervention = INTERVENTION_MANUAL
                logical?.end(RESULT_CANCELLED, stage, CAUSE_USER)
                currentAttempt?.let { it.end(RESULT_CANCELLED, stage, CAUSE_USER) }
                currentAttempt = null
                beginLogical(ConnectionTriggers.MANUAL)
            }
            ConnectionTriggers.INITIAL -> {
                // 已终态（如30s deadline先到）的旧分母视同缺席：用户的再次发起是新操作
                // （metrics 1.1），否则新旅程被吞、其成功end被CAS丢弃。
                val current = logical
                if (current == null || current.isSettled) {
                    logical = null
                    beginLogical(ConnectionTriggers.INITIAL)
                }
            }
            else -> Unit
        }
    }

    /**
     * 当前轮的attempt句柄（brief逐字签名）：在途未终态且stage变化→progress同一attempt；
     * 已终态（含deadline先到）→开启新attempt。context.operation_id=逻辑旅程id、
     * context.attempt_id=本轮独立UUID。恢复区间内每轮新attempt计入attempts。
     */
    @Synchronized
    fun attempt(stage: String): Operation {
        val current = currentAttempt
        if (current != null && !current.isSettled) {
            if (stage != this.stage) {
                current.progress(stage)
                this.stage = stage
            }
            return current
        }
        this.stage = stage
        val context = buildMap {
            logicalId?.let { put(CONTEXT_OPERATION_ID, it) }
            put(CONTEXT_ATTEMPT_ID, UUID.randomUUID().toString())
        }
        val operation = operations.begin(ATTEMPT_NAME, ATTEMPT_DEADLINE_MS, context = context)
        currentAttempt = operation
        if (recovery != null) attempts += 1
        return operation
    }

    /**
     * 全部必需流就绪（brief逐字段语义）：逻辑成功与恢复成功在此一并结算；重复调用只在
     * ready上幂等，已结算的operation由CAS丢弃，不产生第二条end（超时后成功仅诊断层可见）。
     */
    @Synchronized
    fun connected() {
        if (closed) return
        logical?.end(RESULT_SUCCESS, STAGE_STREAMS)
        recovery?.end(RESULT_SUCCESS, STAGE_STREAMS, fields = buildJsonObject {
            put("intervention", intervention)
            put("attempts", attempts)
        })
        logical = null
        recovery = null
        currentAttempt?.let { it.end(RESULT_SUCCESS, stage) }
        currentAttempt = null
        ready = true
    }

    /**
     * 旅程终败（M04 result=failure）：仅在**无任何重试被调度**的终局失败调用（如
     * backend init错误、cs-cloud空根目录）——metrics 1.2技术成功率=success/(success+
     * failure+timeout)，终败按30s timeout上报属于误分类。仍会重试的失败不调用本方法，
     * 继续在途并按deadline语义结算。结束逻辑操作与在途attempt（failure+映射的
     * stage/cause/error_code）；已终态的旧逻辑操作由CAS丢弃。M05恢复区间不受影响。
     */
    @Synchronized
    fun failed(stage: String, cause: String, code: String) {
        if (closed) return
        this.stage = stage
        logical?.end(RESULT_FAILURE, stage, cause, code)
        currentAttempt?.let { it.end(RESULT_FAILURE, stage, cause, code) }
        currentAttempt = null
        logical = null
    }

    /**
     * 已就绪连接意外丢失：开恰好一个恢复区间，并记一条connection.state_changed退化
     * transition（M05断连reason维度的载体）。ready前失败是M04连接失败：直接忽略；
     * 恢复已在途（多流同败/后续丢失）不重复开区间、不重复记transition。
     */
    @Synchronized
    fun lost(reason: String) {
        if (closed || !ready || recovery != null) return
        ready = false
        intervention = INTERVENTION_AUTOMATIC
        attempts = 0
        val context = logicalId?.let { mapOf(CONTEXT_OPERATION_ID to it) } ?: emptyMap()
        recovery = operations.begin(RECOVERY_NAME, RECOVERY_DEADLINE_MS, context = context)
        operations.record(
            Draft(
                STATE_CHANGED_NAME,
                "transition",
                CHANNEL,
                buildJsonObject {
                    put("from", TRANSITION_FROM)
                    put("to", TRANSITION_TO)
                    put("reason", reason)
                },
                context,
            ),
        )
    }

    /**
     * 正常关闭（dispose/shutdown）：在途逻辑/attempt/恢复区间一律结算cancelled（成熟
     * 区间之外，不进入错误与自动恢复比例），清除ready；绝不生成断连事实。幂等。
     */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        logical?.end(RESULT_CANCELLED, stage, CAUSE_USER)
        currentAttempt?.let { it.end(RESULT_CANCELLED, stage, CAUSE_USER) }
        recovery?.end(RESULT_CANCELLED, STAGE_STREAMS, CAUSE_USER, fields = buildJsonObject {
            put("intervention", intervention)
            put("attempts", attempts)
        })
        logical = null
        currentAttempt = null
        recovery = null
        ready = false
    }

    private fun beginLogical(trigger: String) {
        val operation = operations.begin(
            CONNECTION_NAME,
            logicalDeadlineMs,
            fields = buildJsonObject { put("trigger", trigger) },
        )
        logical = operation
        logicalId = operation.id
    }
}
