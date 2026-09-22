package ai.kilocode.stability

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json

private const val DEFAULT_TICK_MS = 1_000L

/** 事实文件预算（设计7.4）：每producer事实文件上限10MiB，写者后台重写淘汰最旧行。 */
private const val DEFAULT_MAX_FILE_BYTES = 10L * 1024 * 1024

/** 批flush字节阈值（设计7.1）：累计64KiB即flush。 */
private const val DEFAULT_BATCH_FLUSH_BYTES = 64L * 1024

/** 批flush条数阈值（设计7.1）：累计16条即flush，先于age deadline触发。 */
private const val BATCH_FLUSH_RECORDS = 16

/** 批flush最大延迟（设计7.1）：首条未fsync写入起30秒内flush+fsync一次。 */
private const val MAX_FLUSH_AGE_MS = 30_000L

/** 记录本体真实UTF-8编码上限（不含LF；设计6.1：writer落盘前再核对）。 */
private const val MAX_RECORD_BYTES = 32 * 1024
private const val IO_THREAD_NAME = "kilo-stability-writer-io"
private const val TICK_THREAD_NAME = "kilo-stability-writer-tick"
private const val STARTUP_TIMEOUT_SECONDS = 10L
private const val CLOSE_TIMEOUT_SECONDS = 10L

/** writer生命周期状态：CREATED→ACTIVE；前提不可验证→DISABLED（禁采+本地报告）；close→CLOSED。 */
enum class WriterState { CREATED, ACTIVE, DISABLED, CLOSED }

/** writer侧健康累计（设计7.1/7.4）：write_error、入盘前判期丢弃、超32KiB丢弃、容量重写淘汰行。 */
data class WriterStats(
    val writeErrors: Long,
    val droppedPolicy: Long,
    val droppedOversize: Long,
    val droppedEvicted: Long,
)

/**
 * 专用后台单writer（设计6.1/7.1/7.4）：消费Recorder队列、按当前Policy入盘前重判期、
 * 追加为UTF-8无BOM、LF结尾的单文件NDJSON（每producer事实文件`<scope-id>-<producer-id>.jsonl`）。
 *
 * 单writer纪律：全部文件操作只在自有IO线程执行；EDT永不触碰本类。后台定时器只唤醒
 * （tryLock去重），不直接多线程写；flush()提交同一IO线程并等待，排空并force未同步批次，
 * 仅限后台线程调用。无登记、无锁文件、无.open/.ready状态机（设计5.2/§3.1）。
 *
 * flush阈值（设计7.1/7.4）：累计16条或64KiB即flush；首条未fsync写入起最迟30秒flush+fsync
 * 一次，只有非空批次参与定时flush；diagnostic随critical同批写出，不设更长延迟。
 * 事实文件预算[maxFileBytes]：写前预检超限即后台重写——按完整行从尾部保留至预算内、
 * 原子替换后重开追加，被淘汰行计入[WriterStats.droppedEvicted]；文件被删时下次追加
 * 按原名重建，不视为错误。
 */
// 参数列表 = 依赖（root/fileName/identity/recorder/policies/clock/storage）+ 校准旋钮
// （tick/16条/两字节阈值/30秒）；与设计7.1"阈值用真实事件率校准"一致，不拆分参数对象。
@Suppress("TooManyFunctions", "LongParameterList")
class Writer(
    private val root: Path,
    private val fileName: String,
    private val identity: ProducerIdentity,
    private val recorder: Recorder,
    private val policies: PolicyStore,
    private val clock: Clock,
    private val storage: Storage = Storage(root),
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val batchFlushBytes: Long = DEFAULT_BATCH_FLUSH_BYTES,
    private val maxFlushAgeMs: Long = MAX_FLUSH_AGE_MS,
    private val batchFlushRecords: Int = BATCH_FLUSH_RECORDS,
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
    private val droppedEvicted = AtomicLong(0)
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

    private val file: Path = root.resolve(fileName)

    // 以下写会话状态只在writer自有IO线程访问（单写者纪律），无需同步。
    private var channel: FileChannel? = null
    private var fileBytes = 0L           // 本会话已写字节（重写/重开后对齐为当前文件大小）
    private var pendingSinceMonoMs = -1L // 首条未fsync写入的时刻；-1=无积压
    private var pendingRecords = 0
    private var pendingBytes = 0L

    /** 启动：校验目录与权限、打开追加句柄后才进入ACTIVE；失败即禁采。无锁、无exchange.lock。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        io.execute {
            try {
                storage.verifyLayout()
                reopen()
                state = WriterState.ACTIVE
                // F6（终审）：close的shutdownNow可能先于本启动任务到达——停机后不再排定时器；
                // 检查与调度之间的残余竞态按RejectedExecutionException就地吞掉（ticker已停，
                // 最终排空由close自己的io.submit屏障完成），绝不向已死executor反复投递。
                if (!stopping.get()) {
                    try {
                        ticker.scheduleWithFixedDelay({ wake() }, tickMs, tickMs, TimeUnit.MILLISECONDS)
                    } catch (_: RejectedExecutionException) {
                        // close已并发停掉ticker：无需定时器。
                    }
                }
            } catch (unverified: StorageUnverifiedException) {
                disable(unverified.reason)
            } catch (_: IOException) {
                disable("writer storage unavailable (io error during startup)")
            } finally {
                startup.countDown()
            }
        }
    }

    /**
     * 排空队列并force未同步批次的barrier；仅在writer自有IO线程之外的后台线程调用
     * （设计7.1：EDT不写文件、不等待锁）。启动未完成或已停用时为no-op。
     */
    fun flush() {
        if (!started.get()) return
        startup.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (state != WriterState.ACTIVE) return
        try {
            io.submit { if (state == WriterState.ACTIVE) drainAndFlush() }.get()
        } catch (_: RejectedExecutionException) {
            // 与close竞态：writer已停用即无事可排空。
        }
    }

    /** 有界收尾：停定时器→最后一次排空+force→关IO线程→关追加句柄。 */
    fun close() {
        if (!stopping.compareAndSet(false, true)) return
        ticker.shutdownNow()
        if (started.get()) startup.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        runCatching {
            io.submit { if (state == WriterState.ACTIVE) drainAndFlush() }
                .get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        io.shutdownNow()
        runCatching { channel?.close() }
        state = WriterState.CLOSED
    }

    /** writer健康累计快照。 */
    fun stats(): WriterStats =
        WriterStats(writeErrors.get(), droppedPolicy.get(), droppedOversize.get(), droppedEvicted.get())

    /** 定时器只唤醒：tryLock去重避免积压任务排队，实际循环仍在唯一IO线程执行。 */
    private fun wake() {
        if (!waking.compareAndSet(false, true)) return
        runCatching {
            io.execute {
                try {
                    if (state == WriterState.ACTIVE) cycle()
                } finally {
                    waking.set(false)
                }
            }
        }.onFailure { waking.set(false) }
    }

    /** 唯一定时循环体：文件被删即重建→排空队列→30秒积压到期flush。 */
    private fun cycle() {
        maybeReopenIfDeleted()
        drainQueue()
        maybeFlushByAge()
    }

    /** flush/close屏障体：排空队列后force未同步批次（有积压才force，空批次不同步）。 */
    private fun drainAndFlush() {
        maybeReopenIfDeleted()
        drainQueue()
        if (pendingRecords > 0) doFlush()
    }

    /** 测试驱动：同步执行一次定时循环（屏障返回即本轮已完整执行）；生产不可达。 */
    internal fun wakeForTest() {
        if (!started.get()) return
        startup.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (state != WriterState.ACTIVE) return
        runCatching { io.submit { if (state == WriterState.ACTIVE) cycle() }.get() }
    }

    /** 测试观测（R3可证伪flush用例）：当前是否存在已写入但未fsync的记录。 */
    internal fun pendingForTest(): Boolean = pendingRecords > 0

    /** 取批→逐条编码落盘→批完成后release预算（恰好一次）；写故障时放弃本批其余记录。 */
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

    /** 逐条写入；返回true表示当前写会话故障，本批剩余记录不再尝试（逐条计入write_error）。 */
    private fun recordsAborted(records: List<QueuedRecord>): Boolean {
        var failed = false
        records.forEach { record ->
            if (failed) {
                writeErrors.incrementAndGet()
                return@forEach
            }
            val line = encodeLine(record.fact) ?: return@forEach
            if (!writeLine(line)) failed = true
        }
        return failed
    }

    /**
     * 入盘前重判期（policy.kt契约：writer入盘前必须以当前时刻重新permit）：
     * 无有效策略或该记录用途已全部不被许可→丢弃并计数，绝不落盘；真实UTF-8编码后
     * 超过32KiB→丢弃并计数。返回null表示该记录不写。
     *
     * 已退役epoch的排队事实同样在此丢弃（设计8.1：插件观察到epoch更替即清空尚未写出
     * 的旧epoch事实，不改绑新epoch）——策略快照本身仍有效时purposes重判覆盖不到这批
     * 记录，必须显式对照[PolicyStore.retiredEpochs]；轮询窗口内已落盘的旧epoch记录
     * 由consumer按退役集合丢弃，不属于本守卫职责。
     */
    @Suppress("ReturnCount")
    private fun encodeLine(fact: Fact): ByteArray? {
        val policy = policies.current()
        if (fact.account_epoch in policies.retiredEpochs) {
            droppedPolicy.incrementAndGet()
            return null
        }
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

    /**
     * 单条落盘：写入前预检文件预算（超限先重写淘汰最旧行）→一行（含LF）一次write→
     * pending计数→16条/64KiB即flush。存储不可验证→禁用采集；磁盘故障→计数并丢弃本条
     * （句柄就地作废，下一条写入时按原名重建）。
     */
    private fun writeLine(bytes: ByteArray): Boolean {
        return try {
            if (fileBytes + bytes.size > maxFileBytes) rewrite(bytes.size)
            if (channel == null) reopen()
            val target = channel ?: return false // reopen已计数，本条放弃
            storage.writeAll(target, ByteBuffer.wrap(bytes))
            fileBytes += bytes.size
            if (pendingSinceMonoMs < 0) pendingSinceMonoMs = clock.mono()
            pendingRecords += 1
            pendingBytes += bytes.size
            if (pendingRecords >= batchFlushRecords || pendingBytes >= batchFlushBytes) doFlush()
            true
        } catch (unverified: StorageUnverifiedException) {
            disable(unverified.reason)
            false
        } catch (_: IOException) {
            writeErrors.incrementAndGet()
            runCatching { channel?.close() }
            channel = null
            false
        }
    }

    /** 打开（或重开）追加句柄并把[fileBytes]对齐为当前文件大小（重建后为0）。 */
    private fun reopen() {
        runCatching { channel?.close() }
        val open = storage.openAppend(file)
        channel = open
        terminateUnterminatedTail(open)
        fileBytes = runCatching { open.size() }.getOrDefault(0L)
    }

    /**
     * 追加前的行对齐（§7.2/§7.4）：崩溃或写故障可能留下无LF残页——旧协议从不在损坏尾后追加
     * （新run即新文件），追加协议必须先补一个LF把残页终止为独立残行（consumer按§7.2跳过），
     * 绝不让本会话的第一条记录拼接到残页上。空文件与已对齐文件不动作。
     */
    private fun terminateUnterminatedTail(channel: FileChannel) {
        if (fileEndsWithLf()) return
        storage.writeAll(channel, ByteBuffer.wrap(byteArrayOf(LF_BYTE)))
        storage.force(channel)
    }

    /**
     * 尾字节核验：只有确认文件以LF结尾（或为空）才免修。核验失败按"需要对齐"处理——
     * 误补一个LF至多留下空行（consumer按空白跳过），漏补则新事实与残页拼接丢一条记录。
     */
    private fun fileEndsWithLf(): Boolean = runCatching {
        FileChannel.open(file, StandardOpenOption.READ).use { readable ->
            val size = readable.size()
            if (size == 0L) return@runCatching true
            val tail = ByteBuffer.allocate(1)
            readable.read(tail, size - 1)
            tail.get(0) == LF_BYTE
        }
    }.getOrDefault(false)

    /** 文件被外部删除（§7.4）时关旧句柄；下次写入按原名重建，不视为错误。仅IO线程调用。 */
    private fun maybeReopenIfDeleted() {
        if (Files.exists(file)) return
        reopen()
    }

    /** 首条未fsync写入起[maxFlushAgeMs]内flush一次；只有非空批次参与（§7.1）。 */
    private fun maybeFlushByAge() {
        val since = pendingSinceMonoMs
        if (since < 0) return
        if (clock.mono() - since < maxFlushAgeMs) return
        doFlush()
    }

    /** fsync当前批次：force(true)成功才清零pending；失败计write_error并保留积压待下轮重试。 */
    private fun doFlush() {
        val open = channel ?: return
        try {
            storage.force(open)
            pendingRecords = 0
            pendingBytes = 0L
            pendingSinceMonoMs = -1L
        } catch (_: IOException) {
            writeErrors.incrementAndGet()
            runCatching { open.close() }
            channel = null
        }
    }

    /**
     * 容量重写（§7.4）：按完整行从尾部保留至[maxFileBytes]预算内（预留[reserveBytes]给
     * 紧随其后的追加行，保证本条写入后仍在预算内），经atomicWrite原子替换后重开追加；
     * 被淘汰的整行计入[droppedEvicted]，保留行内容绝不改写。尾部无LF的崩溃残页不参与
     * 保留（读取方§7.2本就跳过不完整行）。先行关旧句柄——Windows下打开中的文件无法被
     * 原子替换。替换失败由调用方按写故障计数，下次写入按现存文件重建句柄。
     */
    private fun rewrite(reserveBytes: Int) {
        val (kept, evicted) = tailWithinBudget(reserveBytes)
        runCatching { channel?.close() }
        channel = null
        storage.atomicWrite(file, kept)
        droppedEvicted.addAndGet(evicted.toLong())
        reopen()
    }

    /**
     * 读全部字节，从最后一段完整行起向首部按预算收纳；返回(保留字节, 淘汰整行数)。
     * 保留切片止于最后一个LF——无LF的崩溃残页被截断丢弃，既不进入重写结果（否则下一条
     * 追加会拼接在残页上），也不计入fileBytes预算。
     */
    private fun tailWithinBudget(reserveBytes: Int): Pair<ByteArray, Int> {
        if (!Files.exists(file)) return ByteArray(0) to 0
        val all = Files.readAllBytes(file)
        val starts = ArrayList<Int>()
        var start = 0
        for (index in all.indices) {
            if (all[index] == LF_BYTE) {
                starts.add(start)
                start = index + 1
            }
        }
        if (starts.isEmpty()) return ByteArray(0) to 0 // 无完整行：空文件或残页
        val budget = (maxFileBytes - reserveBytes).coerceAtLeast(0L)
        var keepFrom = starts.size
        var keptBytes = 0L
        for (position in starts.indices.reversed()) {
            val lineEnd = if (position + 1 < starts.size) starts[position + 1] else start
            val lineBytes = (lineEnd - starts[position]).toLong()
            if (keptBytes + lineBytes > budget) break
            keepFrom = position
            keptBytes += lineBytes
        }
        if (keepFrom == starts.size) return ByteArray(0) to starts.size
        return all.copyOfRange(starts[keepFrom], start) to keepFrom
    }

    /** 禁用采集：原因固定可见并通知一次（A6在回调里做本地限频报告），绝不静默继续。 */
    private fun disable(reason: String) {
        if (state == WriterState.DISABLED || state == WriterState.CLOSED) return
        disabledReason = reason
        state = WriterState.DISABLED
        onDisabled?.invoke(reason)
    }
}

/** writer单批取出上限（批内序列化完成才release预算）；队列总量约束仍由queue把守。 */
private const val CLAIM_MAX_ITEMS = 128
private const val CLAIM_MAX_BYTES = 512 * 1024

private const val LF_BYTE = '\n'.code.toByte()
