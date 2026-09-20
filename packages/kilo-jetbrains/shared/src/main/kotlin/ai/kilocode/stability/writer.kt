package ai.kilocode.stability

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json

private const val CHANNEL_CRITICAL = "critical"
private const val SUFFIX_OPEN = ".open"
private const val SUFFIX_READY = ".ready"

/** 段文件名 = <run-id>-<segment-id>；segment id按通道单调，重启后新run_id天然不撞旧文件。 */
private const val DEFAULT_TICK_MS = 1_000L
private const val DEFAULT_MAX_SEGMENT_BYTES = 1024L * 1024
private const val DEFAULT_BATCH_SEAL_BYTES = 64L * 1024
private const val SEGMENT_MAX_RECORDS = 16

/** 通道最长封存延迟（设计7.1）：自首条写入起critical 30秒、diagnostic 300秒，以先到为准。 */
private const val CRITICAL_MAX_AGE_MS = 30_000L
private const val DIAGNOSTIC_MAX_AGE_MS = 300_000L

/** writer单批取出上限（批内序列化完成才release预算）；队列总量约束仍由queue把守。 */
private const val CLAIM_MAX_ITEMS = 128
private const val CLAIM_MAX_BYTES = 512 * 1024

/** 记录本体真实UTF-8编码上限（不含LF；设计6.1：writer落盘前再核对）。 */
private const val MAX_RECORD_BYTES = 32 * 1024
private const val IO_THREAD_NAME = "kilo-stability-writer-io"
private const val TICK_THREAD_NAME = "kilo-stability-writer-tick"
private const val STARTUP_TIMEOUT_SECONDS = 10L
private const val CLOSE_TIMEOUT_SECONDS = 10L

/** writer生命周期状态：CREATED→ACTIVE；前提不可验证→DISABLED（禁采+本地报告）；close→CLOSED。 */
enum class WriterState { CREATED, ACTIVE, DISABLED, CLOSED }

/** writer侧健康累计（设计7.1"记健康错误"）：write_error、入盘前判期丢弃、超32KiB丢弃。 */
data class WriterStats(val writeErrors: Long, val droppedPolicy: Long, val droppedOversize: Long)

/** 打开中的段：bytes含LF；records只在完整LF写完后递增（半行不得计入，救援按完整行取）。 */
private class Segment(
    val channel: String,
    val openPath: Path,
    val readyPath: Path,
    val file: FileChannel,
) {
    var records = 0
    var bytes = 0L
    var firstMonoMs = 0L
}

/**
 * 专用后台单writer（设计7.1/7.2）：消费Recorder队列、按当前Policy入盘前重判期、
 * 序列化为UTF-8无BOM、LF结尾的NDJSON并按阈值原子封存。
 *
 * 单writer纪律：全部文件操作只在自有IO线程执行；EDT永不触碰本类。后台定时器只唤醒
 * （tryLock去重），不直接多线程写；flush()提交同一IO线程并等待，封存全部非空段，
 * 仅限后台线程调用。writer.lock字节范围[0,1)持整个生命周期，锁文件不unlink/recreate。
 *
 * 封存阈值（设计7.1，具体值按真实事件率校准）：单文件预算[maxSegmentBytes]（下一条将
 * 突破时先封存旧文件再开新段）、[batchSealBytes]累计或16条（写入后判）、首条起
 * critical 30秒/diagnostic 300秒（定时唤醒时判）；空段不参与定时封存（段文件按首条
 * 成功写入才创建，天然无空文件）。
 */
// 参数列表 = 依赖（root/identity/recorder/policies/clock/storage）+ 校准旋钮（tick/两字节阈值）；
// 与设计7.1"阈值用真实事件率校准"一致，不拆分参数对象。
@Suppress("TooManyFunctions", "LongParameterList")
class Writer(
    root: Path,
    private val identity: ProducerIdentity,
    private val recorder: Recorder,
    private val policies: PolicyStore,
    private val clock: Clock,
    private val storage: Storage = Storage(root),
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val maxSegmentBytes: Long = DEFAULT_MAX_SEGMENT_BYTES,
    private val batchSealBytes: Long = DEFAULT_BATCH_SEAL_BYTES,
) {
    /** 禁采回调：A6接入Faults做本地限频报告；本类只保证每次禁用恰好通知一次。 */
    @Volatile var onDisabled: ((String) -> Unit)? = null

    @Volatile var state: WriterState = WriterState.CREATED
        private set

    @Volatile var disabledReason: String? = null
        private set

    private val writeErrors = AtomicLong(0)
    private val droppedPolicy = AtomicLong(0)
    private val droppedOversize = AtomicLong(0)
    private val segments = ConcurrentHashMap<String, Segment>()
    private val segmentIds = ConcurrentHashMap<String, AtomicLong>()
    private val waking = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val startup = CountDownLatch(1)
    private val json = Json { encodeDefaults = true }

    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, IO_THREAD_NAME).apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, TICK_THREAD_NAME).apply { isDaemon = true }
    }

    private var lockChannel: FileChannel? = null

    /** 启动：校验目录与权限、创建exchange锁文件、取得writer锁后才进入ACTIVE；失败即禁采。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        io.execute {
            try {
                storage.verifyLayout()
                storage.ensureExchangeLock()
                lockChannel = storage.acquireWriterLock()
                state = WriterState.ACTIVE
                ticker.scheduleWithFixedDelay({ wake() }, tickMs, tickMs, TimeUnit.MILLISECONDS)
            } catch (unverified: StorageUnverifiedException) {
                disable(unverified.reason)
            } catch (_: OverlappingFileLockException) {
                disable("writer.lock is already held inside this JVM")
            } catch (_: IOException) {
                disable("writer storage unavailable (io error during startup)")
            } finally {
                startup.countDown()
            }
        }
    }

    /**
     * 封存全部非空段并排空队列的barrier；仅在writer自有IO线程之外的后台线程调用
     * （设计7.1：EDT不写文件、不等待锁）。启动未完成或已停用时为no-op。
     */
    fun flush() {
        if (!started.get()) return
        startup.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (state != WriterState.ACTIVE) return
        try {
            io.submit { if (state == WriterState.ACTIVE) cycle(sealAll = true) }.get()
        } catch (_: RejectedExecutionException) {
            // 与close竞态：writer已停用即无事可封存。
        }
    }

    /** 有界收尾：停定时器→最后一次排空+封存→关IO线程→释放锁通道（锁文件保留在盘上）。 */
    fun close() {
        if (!stopping.compareAndSet(false, true)) return
        ticker.shutdownNow()
        if (started.get()) startup.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        runCatching {
            io.submit { if (state == WriterState.ACTIVE) cycle(sealAll = true) }
                .get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        io.shutdownNow()
        runCatching { lockChannel?.close() }
        state = WriterState.CLOSED
    }

    /** writer健康累计快照。 */
    fun stats(): WriterStats = WriterStats(writeErrors.get(), droppedPolicy.get(), droppedOversize.get())

    /** 定时器只唤醒：tryLock去重避免积压任务排队，实际循环仍在唯一IO线程执行。 */
    private fun wake() {
        if (!waking.compareAndSet(false, true)) return
        runCatching {
            io.execute {
                try {
                    if (state == WriterState.ACTIVE) cycle(sealAll = false)
                } finally {
                    waking.set(false)
                }
            }
        }.onFailure { waking.set(false) }
    }

    /** 唯一循环体：到期段封存→排空队列→（flush时）封存全部非空段。 */
    private fun cycle(sealAll: Boolean) {
        sealExpired()
        drainQueue()
        if (sealAll) sealAllNonEmpty()
    }

    /** 首条写入起超过通道最长延迟即封存；空段不参与（records==0）。 */
    private fun sealExpired() {
        val now = clock.mono()
        segments.values.toList().forEach { segment ->
            val maxAge = if (segment.channel == CHANNEL_CRITICAL) CRITICAL_MAX_AGE_MS else DIAGNOSTIC_MAX_AGE_MS
            if (segment.records > 0 && now - segment.firstMonoMs > maxAge) sealSegment(segment)
        }
    }

    /** 取批→逐条编码落盘→批完成后release预算（恰好一次）；通道故障时放弃本批其余记录。 */
    private fun drainQueue() {
        while (state == WriterState.ACTIVE) {
            val claim = recorder.tryClaim(CLAIM_MAX_ITEMS, CLAIM_MAX_BYTES) ?: return
            val aborted = try {
                recordsAborted(claim.records)
            } finally {
                claim.release()
            }
            if (aborted || claim.records.size < CLAIM_MAX_ITEMS) return
        }
    }

    /** 逐条写入；返回true表示当前通道故障，本批剩余记录不再尝试（逐条计入write_error）。 */
    private fun recordsAborted(records: List<QueuedRecord>): Boolean {
        var failed = false
        records.forEach { record ->
            if (failed) {
                writeErrors.incrementAndGet()
                return@forEach
            }
            val line = encodeLine(record.fact) ?: return@forEach
            if (!writeLine(record.channel, line)) failed = true
        }
        return failed
    }

    /**
     * 入盘前重判期（policy.kt契约：writer入盘前必须以当前时刻重新permit）：
     * 无有效策略或该记录用途已全部不被许可→丢弃并计数，绝不落盘；真实UTF-8编码后
     * 超过32KiB→丢弃并计数。返回null表示该记录不写。
     */
    @Suppress("ReturnCount")
    private fun encodeLine(fact: Fact): ByteArray? {
        val policy = policies.current()
        val permitted = policy?.permit(clock.wall(), fact.name) ?: emptySet()
        if (permitted.none { it in fact.purposes }) {
            droppedPolicy.incrementAndGet()
            return null
        }
        val bytes = (json.encodeToString(Fact.serializer(), fact) + "\n").encodeToByteArray()
        if (bytes.size - 1 > MAX_RECORD_BYTES) {
            droppedOversize.incrementAndGet()
            return null
        }
        return bytes
    }

    /** 单条落盘：1MiB预算预检（先封存旧段）→写循环→计数→16条/64KiB后检。失败保留.open。 */
    private fun writeLine(channel: String, bytes: ByteArray): Boolean {
        val segment = openSegmentFor(channel, bytes.size) ?: return false
        return try {
            storage.writeAll(segment.file, ByteBuffer.wrap(bytes))
            segment.bytes += bytes.size
            segment.records += 1
            if (segment.records == 1) segment.firstMonoMs = clock.mono()
            if (segment.records >= SEGMENT_MAX_RECORDS || segment.bytes >= batchSealBytes) sealSegment(segment)
            true
        } catch (_: IOException) {
            failSegment(segment)
            false
        }
    }

    /** 打开（或复用）当前段；无法验证→禁用采集，磁盘故障→计数；两种失败都不写本条。 */
    private fun openSegmentFor(channel: String, incomingBytes: Int): Segment? = try {
        segmentFor(channel, incomingBytes)
    } catch (unverified: StorageUnverifiedException) {
        disable(unverified.reason)
        null
    } catch (_: IOException) {
        writeErrors.incrementAndGet()
        null
    }

    /** 当前段容纳不下下一条时先封存旧段，再开新段；无段时创建。 */
    private fun segmentFor(channel: String, incomingBytes: Int): Segment {
        val current = segments[channel]
        if (current != null) {
            if (current.bytes + incomingBytes > maxSegmentBytes) sealSegment(current) else return current
        }
        return createSegment(channel)
    }

    private fun createSegment(channel: String): Segment {
        val dir = storage.channelDirectory(channel)
        val id = segmentIds.computeIfAbsent(channel) { AtomicLong() }.getAndIncrement()
        val open = dir.resolve(identity.runId + "-" + id + SUFFIX_OPEN)
        val file = storage.openSegment(open)
        val segment = Segment(
            channel,
            open,
            open.resolveSibling(open.fileName.toString().removeSuffix(SUFFIX_OPEN) + SUFFIX_READY),
            file,
        )
        segments[channel] = segment
        return segment
    }

    /**
     * brief Step 3顺序（不可交换）：force(true)→close→同目录原子改名.open→.ready。
     * 任一步失败都保留.open并记write_error；AtomicMoveNotSupportedException不降级为
     * 普通rename假装封存成功。
     */
    private fun sealSegment(segment: Segment) {
        segments.remove(segment.channel, segment)
        try {
            storage.force(segment.file)
            segment.file.close()
            storage.seal(segment.openPath, segment.readyPath)
        } catch (_: AtomicMoveNotSupportedException) {
            writeErrors.incrementAndGet()
            closeSegmentFile(segment)
        } catch (_: IOException) {
            writeErrors.incrementAndGet()
            closeSegmentFile(segment)
        }
    }

    /** 写失败：计数、停用该段（半行留在.open内，救援只取完整行），绝不计入records。 */
    private fun failSegment(segment: Segment) {
        writeErrors.incrementAndGet()
        segments.remove(segment.channel, segment)
        closeSegmentFile(segment)
    }

    private fun closeSegmentFile(segment: Segment) {
        runCatching { segment.file.close() }
    }

    private fun sealAllNonEmpty() {
        segments.values.toList().forEach { segment ->
            if (segment.records > 0) sealSegment(segment)
        }
    }

    /** 禁用采集：原因固定可见并通知一次（A6在回调里做本地限频报告），绝不静默继续。 */
    private fun disable(reason: String) {
        if (state == WriterState.DISABLED || state == WriterState.CLOSED) return
        disabledReason = reason
        state = WriterState.DISABLED
        onDisabled?.invoke(reason)
    }
}
