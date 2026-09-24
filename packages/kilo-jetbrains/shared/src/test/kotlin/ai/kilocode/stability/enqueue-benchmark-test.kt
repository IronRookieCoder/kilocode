package ai.kilocode.stability

import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.test.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * G1-local 入队基准（设计14章/G1 Step 5"入队100,000次"的本机指示性采样）。
 *
 * 默认关闭：仅当环境变量 KILO_STABILITY_BENCHMARK=1 时执行，常规测试套件中为空操作；
 * 不含任何时序断言，绝不因本机抖动导致默认套件失败。
 *
 * 口径：对有效许可（Fixture 默认控制文件）的 Recorder 连续 record() 预热 20,000 次后
 * 计量 100,000 次，单次耗时仅包住 record() 调用本身（策略判期+字典校验+队列准入）。
 * 为使计量反映准入路径而非容量拒绝，生产节拍受队列深度水位约束（>1024 条时短暂让出，
 * 真实 writer 以真实磁盘 I/O 排空），结果同时打印 QUEUED/DROPPED/DISABLED 计数。
 *
 * 该数据是单机指示值：契约口径的 P99<1ms 判定须在标定环境（校准硬件、无测试夹具开销）
 * 由 script/stability-acceptance.ps1 驱动的外部实验另行取证，本输出不得作为该断言的证据。
 */
class EnqueueBenchmarkTest {

    @Test
    fun `enqueue admission latency percentiles printout`() {
        if (System.getenv("KILO_STABILITY_BENCHMARK") == null) return

        val warmupRuns = 20_000
        val measuredRuns = 100_000
        val highWaterItems = 1_024

        Fixture(autoStart = true).use { fixture ->
            val recorder = fixture.recorder
            // 与QueueTest同形的合法 rpc end 草稿（准入路径真实执行字典校验与用途交集）。
            val data = buildJsonObject {
                put("phase", "end")
                put("api_group", "profile")
                put("result", "success")
                put("duration_ms", 5)
                put("stage", "probe")
                put("cause", "unknown")
                put("error_code", "none")
            }
            val draft = Draft("rpc", "operation", "critical", data)

            fun pace() {
                while (recorder.depth().items > highWaterItems) {
                    LockSupport.parkNanos(10_000L)
                }
            }

            repeat(warmupRuns) {
                pace()
                recorder.record(draft)
            }

            val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
            val gcBefore = gcBeans.sumOf { it.collectionCount }
            val threadsBefore = Thread.activeCount()
            val latencies = LongArray(measuredRuns)
            var queued = 0L
            var dropped = 0L
            var disabled = 0L

            for (index in 0 until measuredRuns) {
                pace()
                val start = System.nanoTime()
                when (recorder.record(draft)) {
                    Admission.QUEUED -> queued++
                    Admission.DROPPED -> dropped++
                    Admission.DISABLED -> disabled++
                }
                latencies[index] = System.nanoTime() - start
            }
            val gcAfter = gcBeans.sumOf { it.collectionCount }
            val threadsAfter = Thread.activeCount()

            latencies.sort()
            fun percentileLatency(fraction: Double): Long {
                val rank = ceil(fraction * measuredRuns).toInt().coerceIn(1, measuredRuns)
                return latencies[rank - 1]
            }
            fun micros(nanos: Long): String = "%.3f".format(Locale.ROOT, nanos / 1_000.0)

            println("[enqueue-benchmark] samples=$measuredRuns (warmup=$warmupRuns)")
            println("[enqueue-benchmark] P50=${micros(percentileLatency(0.50))}us " +
                "P95=${micros(percentileLatency(0.95))}us " +
                "P99=${micros(percentileLatency(0.99))}us " +
                "max=${micros(latencies[measuredRuns - 1])}us")
            println("[enqueue-benchmark] admission QUEUED=$queued DROPPED=$dropped DISABLED=$disabled")
            println("[enqueue-benchmark] gc_collections_delta=${gcAfter - gcBefore} " +
                "threads_before=$threadsBefore threads_after=$threadsAfter")
        }
    }
}
