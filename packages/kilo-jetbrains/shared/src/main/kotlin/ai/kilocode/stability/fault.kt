package ai.kilocode.stability

import java.io.IOException
import java.io.UncheckedIOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val KIND_DIAGNOSTIC = "diagnostic"
private const val CHANNEL_CRITICAL = "critical"
private const val CHANNEL_DIAGNOSTIC = "diagnostic"
private const val NAME_HANDLED = "error.reported"
private const val NAME_UNCAUGHT = "error.uncaught"
private const val CONTEXT_FAULT_ID = "fault_id"
private const val PURPOSE_METRICS = "metrics"
private const val PURPOSE_LOGS = "logs"

/** 异常类别受控词表（metrics文档3.2）；映射只看异常类型，绝不看message/cause文本。 */
private const val CLASS_LINKAGE = "linkage_error"
private const val CLASS_NO_CLASS_DEF = "no_class_def_found"
private const val CLASS_IO = "io_error"
private const val CLASS_TIMEOUT = "timeout_exception"
private const val CLASS_JSON_PARSE = "json_parse"
private const val CLASS_NPE = "npe"
private const val CLASS_ILLEGAL_STATE = "illegal_state"
private const val CLASS_OTHER = "other"

/** 详情限频（设计11.1）：每fingerprint每分钟最多3份，窗口按wall时钟分钟切分。 */
private const val DETAILS_PER_WINDOW = 3
private const val WINDOW_MS = 60_000L

/** 白名单帧数上限（brief片段take(5)，与字典frames列表maxItems一致）。 */
private const val MAX_FRAMES = 5

/** 限频键表容量（brief实现选择1024个）：满后新fingerprint归固定overflow摘要，不无限分配。 */
private const val DEFAULT_MAX_RATE_KEYS = 1024

/** 同fault去重缓存容量：超出按FIFO淘汰，内存有界；缓存与run生命周期绑定（实例随run创建）。 */
private const val MAX_DEDUP_ENTRIES = 1024

/** 表满后的固定overflow摘要fingerprint（安全常量，非原始数据）。 */
private const val OVERFLOW_FINGERPRINT = "overflow"

/** 固定安全模板：message只含受控枚举与数字，绝不截取异常首行（设计11.1）。 */
private const val MESSAGE_DETAIL_PREFIX = "fault detail: error_class="
private const val MESSAGE_SUMMARY_PREFIX = "fault summary: error_class="
private const val MESSAGE_SUMMARY_SUFFIX = " suppressed="
private const val MESSAGE_OVERFLOW_PREFIX = "fault summary: rate limit overflow suppressed="

/**
 * 单个fingerprint的分钟窗口状态：详情配额、溢出计数与摘要所需的安全属性。
 * fingerprint同一 ⇒ 异常类同一（fingerprint输入含类名），error_class随之确定。
 */
private class WindowState(
    val fingerprint: String,
    val frames: List<String>,
    val errorClass: String,
) {
    var window = -1L
    var details = 0
    var extra = 0L
}

/** 计数事实载荷（critical最小计数形态的字段集，设计第9章）。 */
private data class CountFact(
    val name: String,
    val faultId: String,
    val errorClass: String,
    val handled: Boolean,
    val fingerprint: String,
    val component: String,
)

/**
 * 安全异常采集（设计11.1/6.2）：[report]把一次故障拆成两条事实——critical最小计数
 * （仅metrics出口，不受详情限频影响）与diagnostic固定详情（仅logs出口）；两个event_id
 * 经context的fault_id关联。安全约定：详情message只用固定模板加受控枚举与数字，fingerprint
 * 只哈希插件类/方法帧（无文件名、行号、原message、cause或完整堆栈），原始异常文本绝不入事实。
 *
 * 去重：fault_id在故障边界生成并由调用方跨重复报告传递，同fault只计一次；去重缓存有界
 * （FIFO淘汰）且随本实例与run同生命周期。限频：每fingerprint每分钟至多[DETAILS_PER_WINDOW]
 * 份详情，超出次数在下一窗口由后续[report]惰性冲刷为一条count=N的固定摘要（仅logs出口，
 * 不计入异常指标；无后续报告则不生成——本类无后台线程）。限频键表容量[maxRateKeys]
 * （生产默认1024），满后新fingerprint的计数照常、详情归固定overflow摘要。
 *
 * 传播语义：CancellationException排除（不采集不传播）；VirtualMachineError/ThreadDeath
 * 等致命错误原样重抛（不因采集吞掉）；NoClassDefFoundError/LinkageError可观测。
 * 线程安全：report可任意线程调用；去重与限频状态在同一锁内维护，准入仍由recorder把守。
 */
@Suppress("TooManyFunctions")
class Faults(
    private val recorder: Recorder,
    private val clock: Clock,
    maxRateKeys: Int = DEFAULT_MAX_RATE_KEYS,
) {
    private val rateKeys = maxRateKeys.coerceAtLeast(1)
    private val lock = Any()
    private val seenFaults = LinkedHashMap<String, Unit>()
    private val windows = LinkedHashMap<String, WindowState>()

    /** 有待冲刷摘要的最早窗口（无则MAX，避免逐report全表扫描）。 */
    private var earliestPendingWindow = Long.MAX_VALUE
    private var overflowWindow = -1L
    private var overflowExtra = 0L

    fun report(error: Throwable, component: String, handled: Boolean, fault: String = UUID.randomUUID().toString()) {
        if (error is CancellationException) return
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        val frames = safeFrames(error)
        val fingerprint = fingerprintOf(error, frames)
        val errorClass = errorClassOf(error)
        val window = clock.wall() / WINDOW_MS
        val name = if (handled) NAME_HANDLED else NAME_UNCAUGHT
        synchronized(lock) {
            flushDueLocked(window)
            if (!markSeenLocked(fault)) return
            emitCount(
                CountFact(
                    name = name,
                    faultId = fault,
                    errorClass = errorClass,
                    handled = handled,
                    fingerprint = fingerprint,
                    component = component,
                ),
            )
            val state = windows[fingerprint]
            if (state != null) {
                tallyLocked(state, window, fault)
            } else {
                admitKeyLocked(window, fingerprint, frames, errorClass, fault)
            }
        }
    }

    /** 白名单帧（brief原文片段）：仅插件类#方法、至多[MAX_FRAMES]帧，不含文件名/行号/消息。 */
    private fun safeFrames(error: Throwable): List<String> =
        error.stackTrace.asSequence()
            .filter { it.className.startsWith("ai.kilocode.") }
            .map { "${it.className}#${it.methodName}" }
            .take(MAX_FRAMES).toList()

    private fun fingerprintOf(error: Throwable, frames: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest((error.javaClass.name + "\n" + frames.joinToString("\n")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 异常类别映射（metrics文档3.2受控值）；NCDFE先于LinkageError（子类），取消已在入口排除。 */
    private fun errorClassOf(error: Throwable): String = when (error) {
        is NoClassDefFoundError -> CLASS_NO_CLASS_DEF
        is LinkageError -> CLASS_LINKAGE
        is UncheckedIOException -> CLASS_IO
        is IOException -> CLASS_IO
        is TimeoutException -> CLASS_TIMEOUT
        is SerializationException -> CLASS_JSON_PARSE
        is NullPointerException -> CLASS_NPE
        is IllegalStateException -> CLASS_ILLEGAL_STATE
        else -> CLASS_OTHER
    }

    /** 去重缓存有界（FIFO淘汰）；返回false表示同fault重复报告，不再计数。 */
    private fun markSeenLocked(fault: String): Boolean {
        if (seenFaults.containsKey(fault)) return false
        seenFaults[fault] = Unit
        val iterator = seenFaults.keys.iterator()
        while (seenFaults.size > MAX_DEDUP_ENTRIES && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
        return true
    }

    /** 计数事实（critical，仅metrics）：限频绝不改变异常次数（设计11.1）。 */
    private fun emitCount(fact: CountFact) {
        val data = buildJsonObject {
            put("fault_id", fact.faultId)
            put("error_class", fact.errorClass)
            put("handled", fact.handled)
            put("fingerprint", fact.fingerprint)
            put("component", fact.component)
        }
        val draft = Draft(
            fact.name,
            KIND_DIAGNOSTIC,
            CHANNEL_CRITICAL,
            data,
            mapOf(CONTEXT_FAULT_ID to fact.faultId),
            null,
            setOf(PURPOSE_METRICS),
        )
        recorder.record(draft)
    }

    private fun admitKeyLocked(
        window: Long,
        fingerprint: String,
        frames: List<String>,
        errorClass: String,
        fault: String,
    ) {
        if (windows.size < rateKeys) {
            val state = WindowState(fingerprint, frames.toList(), errorClass)
            state.window = window
            windows[fingerprint] = state
            emitDetail(state, fault)
            state.details = 1
        } else {
            tallyOverflowLocked(window)
        }
    }

    private fun tallyLocked(state: WindowState, window: Long, fault: String) {
        if (state.window != window) {
            state.window = window
            state.details = 0
        }
        if (state.details < DETAILS_PER_WINDOW) {
            state.details += 1
            emitDetail(state, fault)
        } else {
            state.extra += 1
            if (state.window < earliestPendingWindow) earliestPendingWindow = state.window
        }
    }

    private fun tallyOverflowLocked(window: Long) {
        if (overflowWindow != window) {
            overflowWindow = window
            overflowExtra = 0
        }
        overflowExtra += 1
    }

    /** 冲刷上一窗口的摘要：per-fingerprint一条count=N，overflow归固定fingerprint；均仅logs。 */
    private fun flushDueLocked(window: Long) {
        if (overflowExtra > 0 && overflowWindow < window) {
            emitDetailBranch(
                OVERFLOW_FINGERPRINT,
                emptyList(),
                MESSAGE_OVERFLOW_PREFIX + overflowExtra,
                overflowExtra,
                null,
            )
            overflowExtra = 0
        }
        if (earliestPendingWindow >= window) return
        windows.values.forEach { state ->
            if (state.extra > 0 && state.window < window) {
                emitSummary(state, state.extra)
                state.extra = 0
            }
        }
        earliestPendingWindow = windows.values
            .filter { it.extra > 0 }
            .minOfOrNull { it.window } ?: Long.MAX_VALUE
    }

    /** 详情：固定模板message、白名单帧、count=1；context携带fault_id与计数事实关联。 */
    private fun emitDetail(state: WindowState, fault: String) {
        val message = MESSAGE_DETAIL_PREFIX + state.errorClass
        emitDetailBranch(state.fingerprint, state.frames, message, 1L, fault)
    }

    /** 摘要：额外次数在下一窗口生成一条count=N的固定安全摘要（仅logs，不进异常指标）。 */
    private fun emitSummary(state: WindowState, count: Long) {
        val message = MESSAGE_SUMMARY_PREFIX + state.errorClass + MESSAGE_SUMMARY_SUFFIX + count
        emitDetailBranch(state.fingerprint, state.frames, message, count, null)
    }

    private fun emitDetailBranch(
        fingerprint: String,
        frames: List<String>,
        message: String,
        count: Long,
        faultId: String?,
    ) {
        val data = buildJsonObject {
            put("message", message)
            putJsonArray("frames") { frames.forEach { frame -> add(frame) } }
            put("fingerprint", fingerprint)
            put("count", count)
        }
        val context = faultId?.let { mapOf(CONTEXT_FAULT_ID to it) } ?: emptyMap()
        val draft = Draft(
            NAME_HANDLED,
            KIND_DIAGNOSTIC,
            CHANNEL_DIAGNOSTIC,
            data,
            context,
            null,
            setOf(PURPOSE_LOGS),
        )
        recorder.record(draft)
    }
}
