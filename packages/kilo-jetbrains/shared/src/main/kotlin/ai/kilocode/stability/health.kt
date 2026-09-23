package ai.kilocode.stability

import ai.kilocode.log.KiloLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NAME_HEALTH = "telemetry.health"
private const val KIND_HEALTH = "health"
private const val CHANNEL_CRITICAL = "critical"
private const val PURPOSE_METRICS = "metrics"
private const val PURPOSE_LOGS = "logs"
private val DUAL_PURPOSES = setOf(PURPOSE_METRICS, PURPOSE_LOGS)

/** health摘要生成周期（brief：每30秒）；损失变化不受该周期等待。 */
private const val DEFAULT_INTERVAL_MS = 30_000L

/** 写盘失败向独立本地日志限频输出的最小间隔（采集器错误只限频输出，设计11.1）。 */
private const val WARN_INTERVAL_MS = 60_000L

/** 固定安全模板：只含固定文本与数字，绝不携带异常文本/路径（设计11.1）。 */
private const val WARN_PREFIX = "stability collector write failures: +"
private const val WARN_MIDDLE = " write_error, cumulative "
private const val WARN_SUFFIX = " (records lost)"

/** 写盘失败告警的默认出口：独立KiloLog本地文本日志；平台未就绪时静默放弃，绝不递归record。 */
private val fallbackLog: KiloLog? by lazy { runCatching { KiloLog.create(Health::class.java) }.getOrNull() }

private fun defaultWarn(message: String) {
    runCatching { fallbackLog?.warn(message) }
}

/** 一次采样得到的原始累计读数（字段=telemetry.health的data闭集，设计第9章）；[Health.poll]把drop/write_error折算为自上一条事实的增量落盘。 */
private data class HealthSample(
    val drop: Long,
    val writeError: Long,
    val depthBytes: Int,
    val oldestAgeMs: Long,
    val reasons: Map<String, Long>,
    val quality: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("drop", drop)
        put("write_error", writeError)
        put("depth_bytes", depthBytes)
        put("oldest_age_ms", oldestAgeMs)
        reasons.forEach { (key, value) -> put(key, value) }
        put("quality", quality)
    }
}

/**
 * 采集健康（设计7.1/11.2，M16；§6.2增量协议）：把损失与队列积压转成telemetry.health事实。
 *
 * [sample]读取本run的原始累计drop（准入丢弃按原因累计：invalid/contention/capacity，
 * 加writer入盘前丢弃：expired/oversize/容量重写淘汰evicted）、累计write_error（writer磁盘
 * 失败）与depth_bytes/oldest_age_ms；落盘事实的drop/write_error是**自上一条health事实以来
 * 的增量**（§6.2），cs-cloud直接求和；run重启后增量自然从零起算（新Health实例基线为零）。
 *
 * 生成只在后台：[poll]由服务后台循环周期调用——距上次生成满[intervalMs]（默认30秒）
 * 生成一次；drop/write_error增量非零（损失变化）立即生成。写盘失败增量向独立KiloLog
 * 限频输出固定模板（[WARN_INTERVAL_MS]内至多一次），绝不重新record自身错误（无递归）。
 *
 * oldest_age_ms是轮询下界估计：队列持续非空的时长（清空即归零）；recorder/queue不暴露
 * 逐条入队时间，gauges按"至少积压了这么久"解读。Health不收集业务内容，只读计数与深度。
 */
class Health(
    private val recorder: Recorder,
    private val writer: Writer,
    private val clock: Clock,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val warn: (String) -> Unit = ::defaultWarn,
) {
    private var lastDrop = 0L
    private var lastWriteError = 0L
    private var lastGenerateMonoMs = clock.mono()
    private var lastWarnMonoMs = -WARN_INTERVAL_MS
    private var nonEmptySinceMonoMs = -1L
    private var baseline = emptyMap<String, Long>()

    /** 原始累计读数（观测用；data键集与telemetry.health一致，落盘事实则是[poll]折算的增量）；一次观测，同时推进积压年龄采样。 */
    fun snapshot(): JsonObject = sample().toJson()

    /**
     * 后台生成入口：采样→折算自上一条事实的增量→（写盘失败增量时）限频告警→到期或损失
     * 变化时记录增量事实。返回是否生成了一条事实。只读计数器与深度，绝不从告警路径调用record。
     */
    @Suppress("ReturnCount") // 未到周期或准入失败均不推进基线。
    internal fun poll(): Boolean {
        val nowMono = clock.mono()
        val sample = sample()
        val dropDelta = sample.drop - lastDrop
        val writeDelta = sample.writeError - lastWriteError
        if (writeDelta > 0) maybeWarn(writeDelta, sample.writeError, nowMono)
        val lossChanged = dropDelta > 0 || writeDelta > 0 ||
            sample.reasons.getValue("drop_failure") > baseline.getOrDefault("drop_failure", 0)
        if (!lossChanged && nowMono - lastGenerateMonoMs < intervalMs) return false
        val admission = recorder.record(
            Draft(
                NAME_HEALTH, KIND_HEALTH, CHANNEL_CRITICAL,
                buildJsonObject {
                    put("drop", dropDelta)          // 增量：cs-cloud直接求和（§6.2）
                    put("write_error", writeDelta)
                    put("depth_bytes", sample.depthBytes)
                    put("oldest_age_ms", sample.oldestAgeMs)
                    sample.reasons.forEach { (key, value) -> put(key, value - baseline.getOrDefault(key, 0)) }
                    put("quality", sample.quality)
                    writer.flushed?.let { put("last_flush_time", it) }
                },
                emptyMap(), null, DUAL_PURPOSES,
            ),
        )
        if (admission != Admission.QUEUED) return false
        lastDrop = sample.drop
        lastWriteError = sample.writeError
        lastGenerateMonoMs = nowMono
        baseline = sample.reasons
        return true
    }

    private fun sample(): HealthSample {
        val counters = recorder.health()
        val stats = writer.stats()
        val reasons = linkedMapOf(
            "drop_invalid" to counters.droppedInvalid,
            "drop_contention" to counters.droppedContention,
            "drop_capacity" to counters.droppedCapacity,
            "drop_policy" to counters.droppedPolicy + stats.droppedPolicy,
            "drop_oversize" to counters.droppedOversize + stats.droppedOversize,
            "drop_evicted" to counters.droppedEvicted + stats.droppedEvicted,
            "drop_failure" to counters.droppedFailure + stats.droppedFailure,
        )
        val drop = reasons.filterKeys { it != "drop_failure" }.values.sum()
        val depth = recorder.depth()
        return HealthSample(
            drop, stats.writeErrors, depth.bytes, oldestAgeMs(clock.mono(), depth.items), reasons,
            if (reasons.getValue("drop_failure") > 0) "degraded" else "good",
        )
    }

    /** 队列非空的持续时长下界；空队列归零（无积压）。 */
    private fun oldestAgeMs(nowMono: Long, items: Int): Long {
        var ageMs = (nowMono - nonEmptySinceMonoMs).coerceAtLeast(0L)
        if (items == 0) {
            nonEmptySinceMonoMs = -1L
            ageMs = 0L
        } else if (nonEmptySinceMonoMs < 0) {
            nonEmptySinceMonoMs = nowMono
            ageMs = 0L
        }
        return ageMs
    }

    /** 限频告警：固定模板加数字；[WARN_INTERVAL_MS]内至多一次。 */
    private fun maybeWarn(delta: Long, total: Long, nowMono: Long) {
        if (nowMono - lastWarnMonoMs < WARN_INTERVAL_MS) return
        lastWarnMonoMs = nowMono
        warn(WARN_PREFIX + delta + WARN_MIDDLE + total + WARN_SUFFIX)
    }
}
