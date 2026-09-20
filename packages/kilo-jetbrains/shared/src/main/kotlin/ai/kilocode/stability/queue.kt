package ai.kilocode.stability

import java.util.Collections
import java.util.concurrent.locks.ReentrantLock

private const val CHANNEL_CRITICAL = "critical"
private const val CHANNEL_DIAGNOSTIC = "diagnostic"

/**
 * 队列中的一条记录：[fact]为入队时补齐公共身份字段的完整wire记录，[estimatedBytes]是
 * 保守字节上界（recorder计算，writer真实编码后再核对32KiB），[enqueuedMonoMs]用于
 * claim时跨通道取最老记录。不可变：入队后任何一方不得改写。
 */
internal class QueuedRecord(
    val fact: Fact,
    val channel: String,
    val seq: Long,
    val estimatedBytes: Int,
    val enqueuedMonoMs: Long,
)

/** 单次入队结果：QUEUED入队；FULL双维容量不足（critical已驱逐全部diagnostic仍不够）；CONTENTION锁争用。 */
internal enum class QueueOffer { QUEUED, FULL, CONTENTION }

internal data class QueueOfferOutcome(val offer: QueueOffer, val evictedDiagnostics: Int) {
    companion object {
        val CONTENTION = QueueOfferOutcome(QueueOffer.CONTENTION, 0)
    }
}

/**
 * 有界内存队列（设计7.1）：critical与diagnostic分通道FIFO，双维容量原子维护。
 *
 * 容量模型：全部通道合计[maxItems]条且[maxBytes]字节，先到者为准；[reservedItems]与
 * [reservedBytes]是critical的预留（diagnostic至多使用扣除预留后的余量），critical可使用
 * 全部容量。diagnostic触顶直接拒绝，不驱逐任何记录；critical需空间时先驱逐最老
 * diagnostic（仅diagnostic），驱逐后仍不足则拒绝新记录并交由recorder计数。
 *
 * 锁纪律：生产与writer路径都只[tryWithProducerLock]（tryLock），争用立即放弃——生产侧
 * 返回CONTENTION由recorder计为丢弃，绝不阻塞EDT或业务线程；锁内只有内存操作。
 * writer经[tryClaim]取出的记录在[Claim.release]前仍计入全部预算，防止批次在队列外
 * 无界积压（序列化完成后再release，真实编码由writer执行）。
 */
internal class StabilityQueue(
    private val maxItems: Int,
    private val maxBytes: Int,
    private val reservedItems: Int,
    private val reservedBytes: Int,
) {
    private val lock = ReentrantLock()
    private val critical = ArrayDeque<QueuedRecord>()
    private val diagnostic = ArrayDeque<QueuedRecord>()

    private var totalItems = 0
    private var totalBytes = 0
    private var diagnosticItems = 0
    private var diagnosticBytes = 0

    /** 当前内存深度（含已被claim、尚未release的记录）；供health摘要读取，允许读取时的微小滞后。 */
    val depthItems: Int get() = totalItems
    val depthBytes: Int get() = totalBytes
    val diagnosticDepthItems: Int get() = diagnosticItems
    val diagnosticDepthBytes: Int get() = diagnosticBytes

    /**
     * 生产路径入口：tryLock成功后在锁内调用[factory]完成seq分配、Fact构建与字节估算，
     * 并随即按通道做准入判定（同一次临界区，保证队列顺序与seq顺序一致）；
     * [factory]不得阻塞或做IO。争用时不调用factory，返回CONTENTION。
     */
    internal fun tryOffer(factory: () -> QueuedRecord): QueueOfferOutcome =
        tryWithProducerLock {
            val record = factory()
            if (record.channel == CHANNEL_CRITICAL) offerCritical(record) else offerDiagnostic(record)
        } ?: QueueOfferOutcome.CONTENTION

    /**
     * writer路径：tryLock成功时按[enqueuedMonoMs]取最老记录，至多[maxItems]条且
     * [maxBytes]字节（首条总是取出，避免单条超限记录永久堵塞批次）；空队列或争用返回null。
     */
    internal fun tryClaim(maxItems: Int, maxBytes: Int): Claim? = tryWithProducerLock {
        val claimed = ArrayList<QueuedRecord>()
        var bytes = 0
        while (true) {
            val next = claimable(claimed.size, bytes, maxItems, maxBytes) ?: break
            removeOldest()
            claimed += next
            bytes += next.estimatedBytes
        }
        if (claimed.isEmpty()) null else Claim(claimed)
    }

    /** 批次下一条可取的最老记录；条数到顶或再取将超字节预算时返回null终止批次。 */
    private fun claimable(claimed: Int, bytes: Int, maxItems: Int, maxBytes: Int): QueuedRecord? =
        peekOldest()?.takeIf { next ->
            claimed < maxItems && (claimed == 0 || bytes + next.estimatedBytes <= maxBytes)
        }

    /**
     * 在生产者锁内执行[block]；争用返回null不等待。inline以便tryOffer/tryClaim/测试共用，
     * block内禁止任何阻塞调用（文件、网络、其他锁）。
     */
    internal inline fun <T> tryWithProducerLock(block: () -> T): T? {
        if (!lock.tryLock()) return null
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun offerCritical(record: QueuedRecord): QueueOfferOutcome {
        var evicted = 0
        while (totalItems + 1 > maxItems || totalBytes + record.estimatedBytes > maxBytes) {
            val victim = diagnostic.removeFirstOrNull()
                ?: return QueueOfferOutcome(QueueOffer.FULL, evicted)
            totalItems -= 1
            totalBytes -= victim.estimatedBytes
            diagnosticItems -= 1
            diagnosticBytes -= victim.estimatedBytes
            evicted += 1
        }
        critical.addLast(record)
        totalItems += 1
        totalBytes += record.estimatedBytes
        return QueueOfferOutcome(QueueOffer.QUEUED, evicted)
    }

    private fun offerDiagnostic(record: QueuedRecord): QueueOfferOutcome {
        val fits = diagnosticItems + 1 <= maxItems - reservedItems &&
            diagnosticBytes + record.estimatedBytes <= maxBytes - reservedBytes &&
            totalItems + 1 <= maxItems &&
            totalBytes + record.estimatedBytes <= maxBytes
        if (!fits) return QueueOfferOutcome(QueueOffer.FULL, 0)
        diagnostic.addLast(record)
        totalItems += 1
        totalBytes += record.estimatedBytes
        diagnosticItems += 1
        diagnosticBytes += record.estimatedBytes
        return QueueOfferOutcome(QueueOffer.QUEUED, 0)
    }

    /** 查看最老记录（不移出）；跨通道按[QueuedRecord.enqueuedMonoMs]比较，平局critical优先。 */
    private fun peekOldest(): QueuedRecord? {
        val headCritical = critical.firstOrNull()
        val headDiagnostic = diagnostic.firstOrNull()
        return when {
            headCritical == null -> headDiagnostic
            headDiagnostic == null -> headCritical
            headDiagnostic.enqueuedMonoMs < headCritical.enqueuedMonoMs -> headDiagnostic
            else -> headCritical
        }
    }

    /** 取最老记录并移出deque（预算不变：取出的记录仍计入内存直至release）。 */
    private fun removeOldest() {
        val headCritical = critical.firstOrNull()
        val headDiagnostic = diagnostic.firstOrNull()
        when {
            headCritical == null -> diagnostic.removeFirst()
            headDiagnostic == null -> critical.removeFirst()
            headDiagnostic.enqueuedMonoMs < headCritical.enqueuedMonoMs -> diagnostic.removeFirst()
            else -> critical.removeFirst()
        }
    }

    /**
     * 已取出的批次：[records]仍计入全部内存预算，writer序列化写入完成后必须调用[release]
     * 释放预算。release短暂lock()（writer线程可等待，生产者不受影响——生产者只tryLock）。
     */
    internal inner class Claim internal constructor(private val claimed: List<QueuedRecord>) {
        val records: List<QueuedRecord> = Collections.unmodifiableList(claimed)

        fun release() {
            lock.lock()
            try {
                claimed.forEach { record ->
                    totalItems -= 1
                    totalBytes -= record.estimatedBytes
                    if (record.channel == CHANNEL_DIAGNOSTIC) {
                        diagnosticItems -= 1
                        diagnosticBytes -= record.estimatedBytes
                    }
                }
            } finally {
                lock.unlock()
            }
        }
    }
}
