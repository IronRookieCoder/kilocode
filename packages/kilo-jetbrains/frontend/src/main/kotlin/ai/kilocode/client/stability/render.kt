package ai.kilocode.client.stability

import ai.kilocode.stability.Clock
import ai.kilocode.stability.Draft
import ai.kilocode.stability.Recorder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** render.apply事实形态（字典）：sample/critical，仅metrics出口（METRICS_ONLY_NAMES）。 */
internal const val NAME_RENDER_APPLY = "render.apply"

/** component受控来源：计时只覆盖前端渲染应用层，不做自由文本组件名。 */
internal const val RENDER_COMPONENT_FRONTEND = "frontend"

private const val KIND_SAMPLE = "sample"
private const val CHANNEL_CRITICAL = "critical"
private const val PURPOSE_METRICS = "metrics"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILURE = "failure"
private const val BUCKET_SINGLE = "1"
private const val BUCKET_SMALL = "2-5"
private const val BUCKET_MEDIUM = "6-20"
private const val BUCKET_LARGE = "21-100"
private const val BUCKET_HUGE = "100+"

/** batch_size_bucket五桶边界（字典BATCH_BUCKETS逐字；brief Step 2片段的同一值域）。 */
private const val BUCKET_SINGLE_MAX = 1
private const val BUCKET_SMALL_MAX = 5
private const val BUCKET_MEDIUM_MAX = 20
private const val BUCKET_LARGE_MAX = 100

/**
 * batch_size_bucket五桶（字典BATCH_BUCKETS逐字；brief Step 2片段的值域）。
 * 阈值间的开区间按顺序自然成立（2-5/6-20/21-100），未匹配值（含非法size）与其余一样落"100+"。
 */
internal fun renderBatchBucket(size: Int): String = when {
    size == BUCKET_SINGLE_MAX -> BUCKET_SINGLE
    size <= BUCKET_SMALL_MAX -> BUCKET_SMALL
    size <= BUCKET_MEDIUM_MAX -> BUCKET_MEDIUM
    size <= BUCKET_LARGE_MAX -> BUCKET_LARGE
    else -> BUCKET_HUGE
}

/**
 * M21合并批次渲染应用计时（C4）：[apply]恰好包住一次真实fire——开始于合并后批次进入
 * 模型处理，结束于同步模型/组件监听更新完成；150ms批等待发生在flushNow之前，不在计时
 * 内，repaint排队也不代表像素绘制完成（不等待绘制）。
 *
 * 采样规则（brief Step 2）：result初值success，业务异常设failure后原样重抛（try/catch/
 * finally保留业务异常传播）；failure始终critical且sample_rate=1.0——失败的渲染绝不因
 * 采样被剔除（never sampled out）；success按确定性均匀采样（步长=round(1/rate)的系统
 * 采样，可控且可复现）挑选，保留样本携带实际生效rate与真实duration_ms。rate默认1.0
 * （不采样剔除）；调整rate必须保留sample_rate字段（消费方按result区分成功/失败分布，
 * 成功不逐次写日志，字典仅metrics出口）。
 *
 * 线程纪律：apply只在EDT由SessionUpdateQueue.flushNow调用（一个批次只在这一层计时，
 * queue与controller绝不重复计时）；record非阻塞（设计7.1），EDT无等待。无每Token落盘：
 * 一个合并批次恰至多一条render.apply事实。
 */
class Render(
    private val clock: Clock,
    private val recorder: Recorder,
    private val rate: Double = 1.0,
) {
    /** 成功样本的系统采样步长：>=1.0全保留；<=0.0全剔除（0L哨兵，绝不产出非法sample_rate）。 */
    private val successStride: Long = when {
        rate >= 1.0 -> 1L
        rate > 0.0 -> maxOf(1L, (1.0 / rate).roundToLong())
        else -> 0L
    }

    private val successes = AtomicLong(0)

    /**
     * 计时并采样一次合并批次的应用。业务异常经[RESULT_FAILURE]记录后原样重抛；
     * finally里统一结算时长——异常路径同样产出failure事实（sample_rate=1.0）。
     */
    // catch(Throwable)是brief裁决形态：任何业务异常（含Error）都先设failure再原样重抛，
    // 绝不允许业务异常绕过观测终态，也绝不吞掉异常改变传播行为。
    @Suppress("TooGenericExceptionCaught")
    fun apply(size: Int, component: String, block: () -> Unit) {
        val startMono = clock.mono()
        var result = RESULT_SUCCESS
        try {
            block()
        } catch (error: Throwable) {
            result = RESULT_FAILURE
            throw error
        } finally {
            finish(size, component, result, (clock.mono() - startMono).coerceAtLeast(0))
        }
    }

    private fun finish(size: Int, component: String, result: String, durationMs: Long) {
        if (result != RESULT_FAILURE) {
            val stride = successStride
            // 成功按系统采样步长挑选（第n次成功n%stride==0时保留）；stride<=0（rate<=0）全剔除。
            if (stride <= 0L || successes.incrementAndGet() % stride != 0L) return
            record(size, component, result, durationMs, rate.coerceIn(MIN_RATE, MAX_RATE))
            return
        }
        // failure永不采样剔除：无论rate如何，恒以sample_rate=1.0产出。
        record(size, component, result, durationMs, NEVER_SAMPLED_OUT_RATE)
    }

    /** render.apply唯一载荷构造：五字段闭集与字典render.apply逐字对应，duration不为负。 */
    private fun record(size: Int, component: String, result: String, durationMs: Long, sampleRate: Double) {
        recorder.record(
            Draft(
                NAME_RENDER_APPLY,
                KIND_SAMPLE,
                CHANNEL_CRITICAL,
                buildJsonObject {
                    put("duration_ms", durationMs)
                    put("result", result)
                    put("component", component)
                    put("batch_size_bucket", renderBatchBucket(size))
                    put("sample_rate", sampleRate)
                },
                purposes = setOf(PURPOSE_METRICS),
            ),
        )
    }
}

/** 字典RATE值域(0,1]；sample_rate=1.0是failure的固定值（失败绝不采样剔除）。 */
private const val NEVER_SAMPLED_OUT_RATE = 1.0
private const val MIN_RATE = 0.0
private const val MAX_RATE = 1.0
