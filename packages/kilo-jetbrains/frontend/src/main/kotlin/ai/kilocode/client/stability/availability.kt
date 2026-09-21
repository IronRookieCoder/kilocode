package ai.kilocode.client.stability

import ai.kilocode.stability.Clock
import ai.kilocode.stability.Draft
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** availability区间事实的固定形态（设计6.2 interval；字典登记的metrics-only出口）。 */
private const val NAME_AVAILABILITY = "availability"
private const val KIND_INTERVAL = "interval"
private const val CHANNEL_CRITICAL = "critical"
private const val PURPOSE_METRICS = "metrics"

/**
 * 一个workspace当前打开的活跃区间：[state]是打开时刻的最新状态，起止时刻取自双时钟
 * （mono判定时长、wall写入时间戳），关闭时一次性产出interval事实。
 */
private class OpenInterval(val beginMono: Long, val beginWall: Long, val state: String)

/**
 * M13项目活跃区间（brief Step 3）：active只计visible&&foreground，ready/connecting/
 * blocked/error都计活跃（非ready累计不可用由consumer派生）；每workspace至多一个打开
 * 的区间，update/tick关闭当前区间后按最新状态重建，保证区间不重叠。后台/睡眠不当
 * 不可用——不可见或非前台期间不打开区间，也不产出事实。
 *
 * update/close共用同一时刻时钟：前段end_timestamp与后段begin_timestamp共享边界，
 * 半开区间的时长恰好连续。tick按最新状态切片（设计6.2"每30秒及切换时记录不重叠
 * 区间"）；pause丢弃当前打开的整段且不产出——挂起/休眠/调度中断后无法确认真实闭合
 * 时刻，宁可丢弃也不生成巨大区间，下一个update/tick重建观察起点。
 *
 * 线程纪律：调用方串行化（VisibilityService单一后台consumer顺序apply）；内部再以
 * this互斥兜底。emit在时钟读取后同步执行一次，转发到Operations.record（非阻塞）。
 */
class Availability(private val clock: Clock, private val emit: (Draft) -> Unit) {

    private val open = HashMap<String, OpenInterval>()

    /** 一次观察快照：active=visible&&foreground；状态变化/失活都先关旧区间再按需重建。 */
    @Synchronized
    fun update(workspace: String, visible: Boolean, foreground: Boolean, state: String) {
        val current = open[workspace]
        if (!(visible && foreground)) {
            if (current != null) {
                open.remove(workspace)
                close(current, workspace)
            }
            return
        }
        if (current != null && current.state == state) return
        if (current != null) {
            open.remove(workspace)
            close(current, workspace)
        }
        open[workspace] = OpenInterval(clock.mono(), clock.wall(), state)
    }

    /** 30秒切片：逐workspace关闭打开的区间并按同一状态重建起点。无打开区间时no-op。 */
    @Synchronized
    fun tick() {
        val opened = open.toList()
        for ((workspace, interval) in opened) {
            close(interval, workspace)
            open[workspace] = OpenInterval(clock.mono(), clock.wall(), interval.state)
        }
    }

    /** 挂起/休眠/调度中断：丢弃未可信闭合的整段（不产出事实），重建观察起点。 */
    @Synchronized
    fun pause() {
        open.clear()
    }

    /** 关闭区间：begin/end取自各自时刻的时钟，时长按单调时钟且不为负（逐字brief片段）。 */
    private fun close(interval: OpenInterval, workspace: String) {
        val data = buildJsonObject {
            put("begin_timestamp", interval.beginWall)
            put("end_timestamp", clock.wall())
            put("duration_ms", (clock.mono() - interval.beginMono).coerceAtLeast(0))
            put("state", interval.state)
        }
        emit(
            Draft(
                NAME_AVAILABILITY,
                KIND_INTERVAL,
                CHANNEL_CRITICAL,
                data,
                context = mapOf("workspace_id" to workspace),
                purposes = setOf(PURPOSE_METRICS),
            ),
        )
    }
}
