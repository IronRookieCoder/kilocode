package ai.kilocode.stability

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val KIND_OPERATION = "operation"
private const val CHANNEL_CRITICAL = "critical"

/**
 * 双时钟（设计6.2）：wall()为UTC毫秒（策略判期/时间戳），mono()为单调毫秒（operation
 * 起止与deadline判定，不受系统时间跳变影响）。产品依赖，测试经testScheduler派生实现。
 */
interface Clock {
    fun wall(): Long
    fun mono(): Long
}

private const val PHASE_START = "start"
private const val PHASE_PROGRESS = "progress"
private const val PHASE_END = "end"
private const val RESULT_TIMEOUT = "timeout"

private const val DEFAULT_STAGE = "unknown"
private const val DEFAULT_CAUSE = "unknown"
private const val DEFAULT_CODE = "none"

/** context闭集中的逻辑操作键（设计6.2/第9章）：三个phase记录都自动注入，保证start/end配对。 */
private const val CONTEXT_OPERATION_ID = "operation_id"

/**
 * begin的fields不得触碰的键：phase与deadline_ms是start自身的公共字段，epoch是公共身份字段。
 */
private val BEGIN_RESERVED_KEYS = setOf("phase", "deadline_ms", "epoch")

/**
 * end的fields不得触碰的键（设计6.2/任务A3）：phase/result/duration_ms/stage/cause/error_code
 * 是end自包含终态的公共字段，epoch是开始时账户代际——fields只允许本name专属字段。
 */
private val END_RESERVED_KEYS = setOf(
    "phase", "deadline_ms", "result", "duration_ms", "stage", "cause", "error_code", "epoch",
)

/**
 * 单调时间与时限的唯一裁决（设计6.2）：定时器到点或业务完成，先发生者成为终态。
 * CAS保证同一operation仅一个end；elapsed不为负；晚到的业务完成按单调时间重新检查
 * deadline，超过即改判timeout并以deadline为时长。emit在CAS成功后同步执行一次。
 */
internal class Terminal(
    private val start: Long,
    private val deadline: Long,
    private val emit: (String, Long) -> Unit,
) {
    private val done = java.util.concurrent.atomic.AtomicBoolean()
    fun finish(result: String, now: Long): Boolean {
        if (!done.compareAndSet(false, true)) return false
        val elapsed = (now - start).coerceAtLeast(0)
        val outcome = if (elapsed > deadline) "timeout" else result
        emit(outcome, if (outcome == "timeout") deadline else elapsed)
        return true
    }
}

/**
 * 操作采集入口（设计6.2）：begin/progress/end三段式，operation统一走critical通道。
 *
 * begin快照开始时的epoch/revision/purposes：后续end永远携带开始时epoch（跨账户切换
 * 不重绑），purposes只能随快照缩小，禁用的用途不得经默认Draft加回。deadline定时器挂在
 * [Operations]构造传入的[CoroutineScope]上，与业务Job互不共用取消路径：业务取消不终止
 * 定时器（到点仍结算timeout），业务先完成则取消定时器（不再产生第二条终态）。总撤销
 * （策略失效/shutdown）时end照常结算，只是记录被禁采丢弃，不伪造正常shutdown。
 */
class Operations(
    internal val recorder: Recorder,
    internal val clock: Clock,
    private val scope: CoroutineScope,
) {
    fun begin(
        name: String,
        deadline: Long,
        fields: JsonObject = JsonObject(emptyMap()),
        context: Map<String, String> = emptyMap(),
    ): Operation {
        val startMono = clock.mono()
        val snapshot = recorder.beginSnapshot(clock.wall(), name)
        val operationId = UUID.randomUUID().toString()
        val operationContext = LinkedHashMap(context).apply { put(CONTEXT_OPERATION_ID, operationId) }
        // begin的fields是该name的专属身份键（如action/api_group/session_mode），字典要求
        // 每个phase都携带；Operation快照它们并合并进progress/end，保证end自包含。
        // 试图经fields覆盖公共键时整条start记录拒绝产出（计数，不静默裁剪放行），
        // 终态照常结算，end以剩余合法字段自包含产出。
        val overrideRejected = fields.keys.any { it in BEGIN_RESERVED_KEYS }
        if (overrideRejected) recorder.countRejectedOverride()
        val beginFields = if (overrideRejected) JsonObject(emptyMap()) else fields
        val operation = Operation(
            id = operationId,
            name = name,
            startMono = startMono,
            deadline = deadline,
            epoch = snapshot.epoch,
            purposes = snapshot.purposes,
            context = operationContext,
            beginFields = beginFields,
            operations = this,
        )
        if (!overrideRejected) {
            val startData = buildJsonObject {
                beginFields.forEach { (key, value) -> put(key, value) }
                put(PHASE_KEY, PHASE_START)
                put("deadline_ms", deadline)
            }
            val startDraft = Draft(
                name,
                KIND_OPERATION,
                CHANNEL_CRITICAL,
                startData,
                operationContext,
                snapshot.epoch,
                snapshot.purposes,
            )
            recorder.record(startDraft)
        }
        operation.attachTimer(
            scope.launch {
                delay(deadline.coerceAtLeast(0))
                operation.onDeadline()
            },
        )
        return operation
    }
}

/** 一次逻辑操作的句柄：终态唯一，progress永不终结；采集被禁不影响业务推进。 */
@Suppress("LongParameterList")
class Operation internal constructor(
    val id: String,
    private val name: String,
    private val startMono: Long,
    private val deadline: Long,
    private val epoch: String?,
    private val purposes: Set<String>,
    private val context: Map<String, String>,
    private val beginFields: JsonObject,
    private val operations: Operations,
) {
    private val clock = operations.clock
    private val recorder = operations.recorder

    private val terminal = Terminal(startMono, deadline) { outcome, durationMs -> emitEnd(outcome, durationMs) }

    /** end的self-contained载荷；timeout定时器路径可能为null（用默认值）。 */
    private val pendingEnd = AtomicReference<EndPayload?>()

    @Volatile private var settled = false

    @Volatile private var timerJob: Job? = null

    internal fun attachTimer(job: Job) {
        timerJob = job
    }

    /**
     * 业务终态：CAS裁决唯一end，返回是否由本次调用结算（晚到调用返回false且不产记录）。
     * 结算按单调时间；超deadline改判timeout。fields只允许本name专属键，试图覆盖公共字段
     * 时整条end记录拒绝产出（终态照常结算），由recorder计数，不静默裁剪放行。
     */
    fun end(
        result: String,
        stage: String = DEFAULT_STAGE,
        cause: String = DEFAULT_CAUSE,
        code: String = DEFAULT_CODE,
        fields: JsonObject = JsonObject(emptyMap()),
    ): Boolean {
        pendingEnd.set(EndPayload(result, stage, cause, code, fields))
        val settledNow = terminal.finish(result, clock.mono())
        if (settledNow) {
            settled = true
            timerJob?.cancel()
        }
        return settledNow
    }

    /**
     * 阶段进展：只记录，不触碰终态（设计6.2：progress不计算完成次数）。已结算后不再产出，
     * 避免在end事实之后出现progress事实；begin身份键与stage值域由Dictionary把关。
     */
    fun progress(stage: String) {
        if (settled) return
        val data = buildJsonObject {
            beginFields.forEach { (key, value) -> put(key, value) }
            put(PHASE_KEY, PHASE_PROGRESS)
            put("stage", stage)
        }
        recorder.record(Draft(name, KIND_OPERATION, CHANNEL_CRITICAL, data, context, epoch, purposes))
    }

    internal fun onDeadline() {
        terminal.finish(RESULT_TIMEOUT, clock.mono())
    }

    /**
     * Terminal唯一emit：begin身份键先放（保证end自包含），终态公共字段后放（不可被覆盖），
     * payload.fields最后合入（已在end入口拒绝过公共键）；timeout定时器路径无业务载荷时
     * 用默认值（stage/cause=unknown、code=none）。
     */
    private fun emitEnd(outcome: String, durationMs: Long) {
        val payload = pendingEnd.get()
        if (payload != null && payload.fields.keys.any { it in END_RESERVED_KEYS }) {
            recorder.countRejectedOverride()
            return
        }
        val data = buildJsonObject {
            beginFields.forEach { (key, value) -> put(key, value) }
            put(PHASE_KEY, PHASE_END)
            put("result", outcome)
            put("duration_ms", durationMs)
            put("stage", payload?.stage ?: DEFAULT_STAGE)
            put("cause", payload?.cause ?: DEFAULT_CAUSE)
            put("error_code", payload?.code ?: DEFAULT_CODE)
            payload?.fields?.forEach { (key, value) -> put(key, value) }
        }
        recorder.record(Draft(name, KIND_OPERATION, CHANNEL_CRITICAL, data, context, epoch, purposes))
    }

    private data class EndPayload(
        val result: String,
        val stage: String,
        val cause: String,
        val code: String,
        val fields: JsonObject,
    )
}

private const val PHASE_KEY = "phase"
