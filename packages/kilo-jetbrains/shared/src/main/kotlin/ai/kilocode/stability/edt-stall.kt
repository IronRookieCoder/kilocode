package ai.kilocode.stability

import java.util.Collections
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 观测到的卡顿区间阈值（设计10.3）：合并后持续≥2秒才算一个stall。 */
internal const val STALL_MIN_DURATION_MS = 2_000L

/** 后台在阻塞期间采集；复制全部帧，关闭区间前只保留原始内存证据。 */
class EdtStack(thread: Thread, frames: Array<StackTraceElement> = thread.stackTrace) {
    val name = thread.name
    val id = thread.threadId()
    val state = thread.state.name
    val frames: List<StackTraceElement> = Collections.unmodifiableList(frames.toList())

    fun text(): String = "\"$name\" #$id $state\n" + frames.joinToString("\n") { "\tat $it" }
}

/**
 * edt.stall合并器（设计10.3）：插件在本机合并valid样本的排队区间——同观测区间内仅合并
 * 明确相交或首尾相接且探针序号连续的阻塞区间；序号缺失、中断标记或observation_id变化
 * 打断合并，缺失部分不推断卡顿。区间终结时持续≥[STALL_MIN_DURATION_MS]才产出一条
 * edt.stall事实。线程纪律：由探针后台finalize线程单线程调用，无锁。
 */
class StallMerger(
    private val report: ((DiagnosticInput) -> String?)? = null,
    private val emit: (Draft) -> Unit,
) {
    private var window: Window? = null

    private class Window(
        val observationId: String,
        var lastSeq: Long,
        var startMono: Long,
        var endMono: Long,
        var stack: EdtStack?,
    )

    fun onValidSample(
        observationId: String,
        seq: Long,
        scheduledMonoMs: Long,
        completedMonoMs: Long,
        stack: EdtStack? = null,
    ) {
        val current = window
        val continues = current != null &&
            current.observationId == observationId &&
            current.lastSeq + 1 == seq &&
            scheduledMonoMs <= current.endMono // 相交或首尾相接
        if (continues) {
            current.endMono = maxOf(current.endMono, completedMonoMs)
            current.lastSeq = seq
            if (current.stack == null) current.stack = stack
        } else {
            closeWindow()
            window = Window(observationId, seq, scheduledMonoMs, completedMonoMs, stack)
        }
    }

    /** 观测区间终点（失焦/暂停/调度断层/探针关闭共用入口）；终结当前窗口。 */
    fun onObservationEnded() = closeWindow()

    private fun closeWindow() {
        val current = window ?: return
        window = null
        val duration = current.endMono - current.startMono
        if (duration < STALL_MIN_DURATION_MS) return
        val incident = current.stack?.let { stack ->
            report?.invoke(DiagnosticInput(
                severity = DiagnosticSeverity.WARN,
                component = "edt",
                message = "EDT stalled for ${duration}ms",
                attributes = mapOf("code" to "edt_stall", "observation_id" to current.observationId),
                payloads = mapOf("edt_stack" to stack::text),
                thread = stack.name,
                threadId = stack.id,
            ))
        }
        emit(
            Draft(
                "edt.stall",
                "sample",
                "critical",
                buildJsonObject {
                    put("duration_ms", duration)
                    put("observation_id", current.observationId)
                },
                context = incident?.let { mapOf("incident_id" to it) } ?: emptyMap(),
                purposes = setOf("metrics"),
            ),
        )
    }
}
