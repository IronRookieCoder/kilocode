package ai.kilocode.client.stability

import ai.kilocode.stability.Clock
import ai.kilocode.stability.Draft
import ai.kilocode.stability.EdtStack
import ai.kilocode.stability.Operations
import ai.kilocode.stability.StallMerger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.UUID
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** edt.delay事实形态（字典）：sample/critical，仅metrics出口（METRICS_ONLY_NAMES）。 */
internal const val NAME_EDT_DELAY = "edt.delay"
private const val KIND_SAMPLE = "sample"
private const val CHANNEL_CRITICAL = "critical"
private const val PURPOSE_METRICS = "metrics"
private const val PURPOSE_LOGS = "logs"

/** edt.violation事实形态（字典）：diagnostic/critical，双用途出口（DUAL_PURPOSE_NAMES）。 */
private const val NAME_EDT_VIOLATION = "edt.violation"
private const val KIND_DIAGNOSTIC = "diagnostic"

/** validity受控词表（字典VALIDITIES逐字）：平台休眠通知经G1确认前生产只用unknown，不用suspended。 */
internal const val VALIDITY_VALID = "valid"
internal const val VALIDITY_SUSPENDED = "suspended"
internal const val VALIDITY_SCHEDULER_GAP = "scheduler_gap"
internal const val VALIDITY_UNKNOWN = "unknown"

/** 后台投递节奏与调度断层容差（brief建议值：正常1000ms、间隔>2000ms判scheduler_gap；G1校准项）。 */
internal const val PROBE_OFFER_PERIOD_MS = 1_000L
internal const val SCHEDULER_GAP_TOLERANCE_MS = 2_000L
private const val STALL_THRESHOLD_MS = 2_000L

/** edt.violation固定证据类别：只有插件自有显式断言边界用它，绝不由延迟时长推断。 */
internal const val EVIDENCE_PLATFORM_THREAD_ASSERTION = "platform_thread_assertion"

/** 一条在途探针样本：投递即定格区间ID与调度时刻，完成/作废都按它产出唯一一条事实。 */
private class PendingSample(val seq: Long, val scheduledMono: Long, val observationId: String) {
    val sampled = AtomicBoolean()
    val result = AtomicReference<Resolution?>()
    val emitted = AtomicBoolean()
    @Volatile var stack: EdtStack? = null
}

private data class Resolution(val time: Long, val validity: String)

/**
 * M20单JVM EDT探针状态机（brief C3/设计10.3）：至多一个在途样本。
 * - [offer]仅在启用且无pending时实际投递；序号只随实际投递递增（被拒offer不占号）。
 * - [complete]是EDT回调入口：只读完成时刻并CAS发布同序号样本的结果，Draft构造与record经
 *   [finalize]交后台——EDT上不做I/O、不做等待。序号不匹配（已作废区间的迟到回调）
 *   一律忽略，绝不在新观测区间按valid计（brief Step 3）。
 * - [interrupt]/[enabled](false)以给定validity作废当前pending（每条实际投递的样本
 *   恰好产出一条事实）并更换observation_id——每次失效都是连续观测区间的终点。
 * - 可选[stall]（设计10.3）：后台结算valid样本后喂给合并器；作废路径先结算已完成样本，
 *   再关闭窗口并轮换observation_id，由它本机合并出edt.stall事实（仅valid样本参与）。
 * 线程纪律：全部状态为短原子切换，无锁、无EDT等待；offer/作废来自后台，complete来自EDT。
 * 时间取[Clock.mono]（本run相对单调毫秒）；purposes仅metrics（字典METRICS_ONLY_NAMES）。
 */
class Probe(
    private val clock: Clock,
    private val emit: (Draft) -> Unit,
    private val finalize: (() -> Unit) -> Unit = { it() },
    private val stall: StallMerger? = null,
) {
    private val enabledFlag = AtomicBoolean(false)
    private val pending = AtomicReference<PendingSample?>()
    private val observationId = AtomicReference(randomObservationId())
    private val nextSeq = AtomicLong(0)
    private val thread = AtomicReference<Thread?>()

    /** 后台watchdog只在pending首次越过2秒时抓栈；EDT已恢复则丢弃该次证据。 */
    fun watch() {
        val sample = pending.get() ?: return
        if (clock.mono() - sample.scheduledMono < STALL_THRESHOLD_MS ||
            !sample.sampled.compareAndSet(false, true)
        ) return
        val known = thread.get()?.takeIf { it.isAlive }
        val stack = known?.let(::EdtStack) ?: Thread.getAllStackTraces().entries.singleOrNull { entry ->
            entry.value.any { it.className == "java.awt.EventDispatchThread" }
        }?.let { EdtStack(it.key, it.value) }
        if (pending.get() === sample && sample.result.get() == null) sample.stack = stack
    }

    /** 启停探针；关闭即作废当前pending（unknown——关闭原因无法归入休眠/断层等已确认类别）。 */
    fun enabled(value: Boolean) {
        if (!enabledFlag.compareAndSet(!value, value)) return
        if (!value) invalidate(VALIDITY_UNKNOWN)
    }

    /** 至多投递一次：已pending或未启用返回null。CAS成功才提交并消耗序号（仅实际投递递增）。 */
    fun offer(): Long? {
        var delivered: Long? = null
        while (delivered == null && enabledFlag.get() && pending.get() == null) {
            val candidate = nextSeq.get() + 1
            val sample = PendingSample(candidate, clock.mono(), observationId.get())
            if (pending.compareAndSet(null, sample)) {
                nextSeq.incrementAndGet()
                delivered = candidate
            }
        }
        return delivered
    }

    /** EDT回调（brief逐字接线invokeLater{complete(seq)}）：只读完成时刻，交后台构造并record。 */
    fun complete(seq: Long) {
        if (EventQueue.isDispatchThread()) thread.set(Thread.currentThread())
        val completedMono = clock.mono()
        val taken = pending.get()?.takeIf { it.seq == seq } ?: return
        if (!taken.result.compareAndSet(null, Resolution(completedMono, VALIDITY_VALID))) return
        finalize { resolve(taken) }
    }

    /** 后台唯一结算：disable也能消费EDT已发布结果，迟到的finalize仅为幂等no-op。 */
    private fun resolve(sample: PendingSample) {
        val result = sample.result.get() ?: return
        if (!sample.emitted.compareAndSet(false, true)) return
        pending.compareAndSet(sample, null)
        emit(sampleDraft(sample, result.time, result.validity))
        if (result.validity == VALIDITY_VALID) {
            stall?.onValidSample(sample.observationId, sample.seq, sample.scheduledMono, result.time, sample.stack)
        }
    }

    /** 以[validity]作废当前pending并更换observation_id（失焦/暂停/调度断层/关闭共用入口）。 */
    fun interrupt(validity: String) {
        invalidate(validity)
    }

    private fun invalidate(validity: String) {
        val taken = pending.getAndSet(null)
        if (taken != null) {
            taken.result.compareAndSet(null, Resolution(clock.mono(), validity))
            resolve(taken)
        }
        // 必须先结算已完成样本，再关闭窗口；随后到达的finalize不能重新打开旧窗口。
        stall?.onObservationEnded()
        observationId.set(randomObservationId())
    }

    /** edt.delay唯一载荷构造：六字段闭集与字典edt.delay逐字对应，duration不为负。 */
    private fun sampleDraft(sample: PendingSample, completedMono: Long, validity: String): Draft = Draft(
        NAME_EDT_DELAY,
        KIND_SAMPLE,
        CHANNEL_CRITICAL,
        buildJsonObject {
            put("observation_id", sample.observationId)
            put("probe_seq", sample.seq)
            put("scheduled_mono_ms", sample.scheduledMono)
            put("completed_mono_ms", completedMono)
            put("duration_ms", (completedMono - sample.scheduledMono).coerceAtLeast(0))
            put("validity", validity)
        },
        purposes = setOf(PURPOSE_METRICS),
    )
}

private fun randomObservationId(): String = UUID.randomUUID().toString()

private fun defaultDispatchToEdt(block: () -> Unit) {
    ApplicationManager.getApplication()?.invokeLater(block)
}

/**
 * M20单JVM探针所有者（brief Step 3/6，设计10.3"同一JVM仅一个探针所有者"）：app级轻服务
 * 持有唯一[Probe]。各项目VisibilityService经[setActive]推送"本项目任一面板可见且IDE前台"
 * 的贡献（B5并集在项目内、本类并集在项目间），全部贡献的并集驱动探针启停：
 * 任一贡献在→启用并启动后台循环（每[offerPeriodMs]至多一次offer）；最后一个贡献撤除→
 * 关闭（pending按unknown作废、区间轮换）。每tick先记录自身调度间隔，超过
 * [SCHEDULER_GAP_TOLERANCE_MS]判scheduler_gap并作废pending——后台调度器被饿死/休眠
 * 后样本时长不可信；断层tick随后的投递落在更换后的新观测区间。
 *
 * 线程纪律：[setActive]/[tick]/finalize进入同一个后台FIFO，contributors和StallMerger
 * 只由该消费者访问；EDT只入队和读取完成时刻，不做I/O或等待锁。
 */
@Service(Service.Level.APP)
@Suppress("LongParameterList")
internal class EdtProbeService internal constructor(
    private val cs: CoroutineScope,
    private val clock: Clock,
    private val operations: () -> Operations?,
    post: ((() -> Unit) -> Unit)? = null,
    private val dispatchToEdt: (() -> Unit) -> Unit = ::defaultDispatchToEdt,
    private val offerPeriodMs: Long = PROBE_OFFER_PERIOD_MS,
) {

    /** Platform constructor — resolves collaborators from the service container. */
    constructor(cs: CoroutineScope) : this(cs, FrontendClock, ::defaultOperations)

    /** 默认由单个后台消费者依次处理；注入的post也必须保持FIFO。 */
    private val tasks = Channel<() -> Unit>(Channel.UNLIMITED)
    private val submit: (() -> Unit) -> Unit = post ?: { tasks.trySend(it) }
    private val stall = StallMerger({ input -> operations()?.report(input) }, ::emit)
    private val probe = Probe(clock, ::emit, submit, stall)
    private val contributors = HashSet<Any>()
    private var loopJob: Job? = null

    @Volatile private var lastTickMono: Long = clock.mono()

    init {
        if (post == null) cs.launch {
            for (task in tasks) task()
        }.invokeOnCompletion { tasks.cancel() }
    }

    /** 项目consumer推送本项目贡献（visible&&foreground）；并集翻转驱动启停与循环生命周期。 */
    fun setActive(owner: Any, active: Boolean) = submit {
        val previous = contributors.isNotEmpty()
        if (active) contributors.add(owner) else contributors.remove(owner)
        val enabled = contributors.isNotEmpty()
        if (previous == enabled) return@submit
        probe.enabled(enabled)
        if (!enabled) {
            loopJob?.cancel()
            loopJob = null
            return@submit
        }
        lastTickMono = clock.mono()
        loopJob = cs.launch {
            while (isActive) {
                delay(offerPeriodMs)
                tick()
            }
        }
    }

    /** 一次后台tick（brief Step 3）：先记录调度间隔（超容差判scheduler_gap），再至多投递一个探针。 */
    internal fun tick() = submit {
        val now = clock.mono()
        val gap = now - lastTickMono
        lastTickMono = now
        if (gap > SCHEDULER_GAP_TOLERANCE_MS) probe.interrupt(VALIDITY_SCHEDULER_GAP)
        probe.watch()
        // brief逐字片段：offer成功才经invokeLater把complete(seq)送上EDT
        val seq = probe.offer() ?: return@submit
        dispatchToEdt { probe.complete(seq) }
    }

    private fun emit(draft: Draft) {
        operations()?.record(draft)
    }
}

/**
 * M20已确认线程违规的安全装配（brief Step 5）：operation必须是已登记操作token
 * （由调用边界传常量）、evidence固定为[EVIDENCE_PLATFORM_THREAD_ASSERTION]，事实只有
 * operation/evidence两个受控键，绝不携带断言原文（Faults规则：固定类别，无原始文本）。
 */
internal fun edtViolationDraft(operation: String): Draft = Draft(
    NAME_EDT_VIOLATION,
    KIND_DIAGNOSTIC,
    CHANNEL_CRITICAL,
    buildJsonObject {
        put("operation", operation)
        put("evidence", EVIDENCE_PLATFORM_THREAD_ASSERTION)
    },
    purposes = setOf(PURPOSE_METRICS, PURPOSE_LOGS),
)
