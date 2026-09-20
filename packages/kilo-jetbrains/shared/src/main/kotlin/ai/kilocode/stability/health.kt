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

/** 一次采样得到的累计快照（字段=telemetry.health的data闭集，设计第9章）。 */
private data class HealthSample(
    val drop: Long,
    val writeError: Long,
    val depthBytes: Int,
    val oldestAgeMs: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("drop", drop)
        put("write_error", writeError)
        put("depth_bytes", depthBytes)
        put("oldest_age_ms", oldestAgeMs)
    }
}

/**
 * 采集健康（设计7.1/11.2，M16）：把本run累计的损失与队列积压转成telemetry.health事实。
 *
 * [snapshot]输出本run累计drop（准入丢弃按原因累计：invalid/contention/capacity/quota，
 * 加writer入盘前丢弃：expired/oversize）、累计write_error（writer磁盘失败）与
 * depth_bytes/oldest_age_ms。计数永远是累计值（跨恢复不清零），consumer取相邻快照差值；
 * 首快照与重放规则由外部验证负责（brief Step 4）。
 *
 * 生成只在后台：[poll]由服务后台循环周期调用——距上次生成满[intervalMs]（默认30秒）
 * 生成一次；drop/write_error计数增量（损失变化）立即生成。写盘失败增量向独立KiloLog
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

    /** 本run累计快照（telemetry.health的data形状）；一次观测，同时推进积压年龄采样。 */
    fun snapshot(): JsonObject = sample().toJson()

    /**
     * 后台生成入口：采样→（写盘失败增量时）限频告警→到期或损失变化时记录累计事实。
     * 返回是否生成了一条事实。只读计数器与深度，绝不从告警路径调用record。
     */
    internal fun poll(): Boolean {
        val nowMono = clock.mono()
        val sample = sample()
        val writeDelta = sample.writeError - lastWriteError
        if (writeDelta > 0) maybeWarn(writeDelta, sample.writeError, nowMono)
        val lossChanged = sample.drop > lastDrop || writeDelta > 0
        if (!lossChanged && nowMono - lastGenerateMonoMs < intervalMs) return false
        recorder.record(
            Draft(NAME_HEALTH, KIND_HEALTH, CHANNEL_CRITICAL, sample.toJson(), emptyMap(), null, DUAL_PURPOSES),
        )
        lastDrop = sample.drop
        lastWriteError = sample.writeError
        lastGenerateMonoMs = nowMono
        return true
    }

    private fun sample(): HealthSample {
        val counters = recorder.health()
        val stats = writer.stats()
        val drop = counters.droppedInvalid + counters.droppedContention + counters.droppedCapacity +
            counters.droppedQuota + stats.droppedPolicy + stats.droppedOversize
        val depth = recorder.depth()
        return HealthSample(drop, stats.writeErrors, depth.bytes, oldestAgeMs(clock.mono(), depth.items))
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
