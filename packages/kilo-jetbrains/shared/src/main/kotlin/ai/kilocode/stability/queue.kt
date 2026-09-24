package ai.kilocode.stability

import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 分片的data/context incident ID必须一致，并具有同一incident父记录与完整重组元数据。 */
internal fun complete(facts: List<Fact>): Boolean {
    val chunks = facts.filter { it.name == "diagnostic.payload" }
    if (chunks.any { it.data["incident_id"] != JsonPrimitive(it.context["incident_id"]) }) return false
    return chunks.groupBy { it.context["incident_id"] }.all { (id, parts) ->
        id != null && facts.any {
            it.context["incident_id"] == id &&
                (it.name in setOf("diagnostic.reported", "plugin.unclean", "plugin.shutdown") ||
                    it.name.startsWith("error."))
        } && parts.groupBy { it.data["payload_kind"] }.all { (_, rows) ->
            val count = (rows.first().data["chunk_count"] as? JsonPrimitive)?.intOrNull
            count == rows.size &&
                rows.map { (it.data["chunk_index"] as? JsonPrimitive)?.intOrNull }.toSet() == rows.indices.toSet() &&
                listOf("chunk_count", "encoding", "original_bytes", "sha256", "truncated").all { key ->
                    rows.all { it.data[key] == rows.first().data[key] }
                }
        }
    }
}

internal enum class Priority { FAILURE, CRITICAL, SAMPLE }

private val SAMPLES = setOf("telemetry.health", "edt.delay", "edt.stall", "resource.snapshot", "render.apply")
private val SEVERITIES = setOf("warn", "error")

/** 优先级只用于内存和保留策略，不改变两个wire channel。 */
internal fun priority(name: String, channel: String, data: JsonObject): Priority = when {
    name.startsWith("error.") || name.startsWith("diagnostic.") ||
        name == "protocol.error" || name == "edt.violation" ||
        (data["phase"] as? JsonPrimitive)?.content == "end" ||
        (data["severity"] as? JsonPrimitive)?.content?.lowercase() in SEVERITIES -> Priority.FAILURE
    name in SAMPLES || channel == "diagnostic" -> Priority.SAMPLE
    else -> Priority.CRITICAL
}

internal class QueuedRecord(
    val fact: Fact,
    val channel: String,
    val seq: Long,
    val estimatedBytes: Int,
    val enqueuedMonoMs: Long,
)

/** 一次原子准入单位；state只允许QUEUED→CLAIMED或QUEUED→EVICTED。 */
internal class QueuedGroup(records: List<QueuedRecord>) {
    @Volatile var records: List<QueuedRecord> = Collections.unmodifiableList(records.toList())
        private set
    val priority = records.minOf { priority(it.fact.name, it.channel, it.fact.data) }
    val items = records.size
    val bytes = records.sumOf { it.estimatedBytes.toLong() }
    val diagnostics = records.count { it.channel == "diagnostic" }
    val diagnosticBytes = records.filter { it.channel == "diagnostic" }.sumOf { it.estimatedBytes.toLong() }
    val mono = records.first().enqueuedMonoMs
    private val state = AtomicInteger(0)

    fun claim(): Boolean = state.compareAndSet(0, 1)
    fun evict(): Boolean = state.compareAndSet(0, 2)
    fun available(): Boolean = state.get() == 0
    fun clear() { records = emptyList() }
    fun fits(items: Int, bytes: Long): Boolean = this.items <= items && this.bytes <= bytes
}

internal enum class QueueOffer { QUEUED, FULL, CONTENTION }

internal data class QueueOfferOutcome(
    val offer: QueueOffer,
    val evictedDiagnostics: Int,
    val evicted: Int = evictedDiagnostics,
) {
    companion object { val CONTENTION = QueueOfferOutcome(QueueOffer.CONTENTION, 0) }
}

/** 全部维度在一个CAS中预留/释放，不存在只预留条数或只预留字节的中间状态。 */
private data class Budget(
    val items: Int = 0,
    val bytes: Long = 0,
    val samples: Int = 0,
    val sampleBytes: Long = 0,
    val diagnostics: Int = 0,
    val diagnosticBytes: Long = 0,
) {
    fun change(group: QueuedGroup, sign: Int): Budget = Budget(
        items + sign * group.items,
        bytes + sign * group.bytes,
        samples + if (group.priority == Priority.SAMPLE) sign * group.items else 0,
        sampleBytes + if (group.priority == Priority.SAMPLE) sign * group.bytes else 0,
        diagnostics + sign * group.diagnostics,
        diagnosticBytes + sign * group.diagnosticBytes,
    )
}

/**
 * failure通过MPSC发布，不获取producer锁。低档位保留tryLock队列；并行淘汰索引与claim
 * 通过group的CAS争夺所有权，因此已经claim的记录不会被淘汰。被淘汰的墓碑立即清空记录
 * 引用，下次低档位操作清理deque。claim到release期间仍占预算；producer全程只有内存操作。
 */
internal class StabilityQueue(
    private val maxItems: Int,
    private val maxBytes: Int,
    private val reservedItems: Int,
    private val reservedBytes: Int,
) {
    private val lock = ReentrantLock()
    private val failure = ConcurrentLinkedQueue<QueuedGroup>()
    private val critical = ArrayDeque<QueuedGroup>()
    private val sample = ArrayDeque<QueuedGroup>()
    private val criticalIndex = ConcurrentLinkedQueue<QueuedGroup>()
    private val sampleIndex = ConcurrentLinkedQueue<QueuedGroup>()
    private val budget = AtomicReference(Budget())

    init {
        require(maxItems > 0 && maxBytes > 0)
        require(reservedItems in 0..maxItems && reservedBytes in 0..maxBytes)
    }

    val depthItems: Int get() = budget.get().items
    val depthBytes: Int get() = budget.get().bytes.toInt()
    val diagnosticDepthItems: Int get() = budget.get().diagnostics
    val diagnosticDepthBytes: Int get() = budget.get().diagnosticBytes.toInt()

    internal fun tryOffer(factory: () -> QueuedRecord): QueueOfferOutcome = tryOffer(QueuedGroup(listOf(factory())))

    internal fun tryOffer(group: QueuedGroup): QueueOfferOutcome {
        if (group.priority == Priority.FAILURE) return offer(group)
        return tryWithProducerLock { offer(group) } ?: QueueOfferOutcome.CONTENTION
    }

    @Suppress("ReturnCount") // 准入失败尽早返回，预留成功后才发布。
    private fun offer(group: QueuedGroup): QueueOfferOutcome {
        // 永远不为不可能装下的分组驱逐现有事实。
        if (group.items > maxItems || group.bytes > maxBytes) return QueueOfferOutcome(QueueOffer.FULL, 0)
        var evicted = 0
        var diagnostics = 0
        while (!reserve(group)) {
            val victim = if (group.priority == Priority.SAMPLE) null else
                evict(sampleIndex) ?: if (group.priority == Priority.FAILURE) evict(criticalIndex) else null
            if (victim == null) return QueueOfferOutcome(QueueOffer.FULL, diagnostics, evicted)
            evicted += victim.items
            diagnostics += victim.diagnostics
        }
        if (group.priority == Priority.FAILURE) {
            failure.add(group)
            return QueueOfferOutcome(QueueOffer.QUEUED, diagnostics, evicted)
        }
        // 只有持producer锁的路径访问两个deque；索引在预留之后发布。
        val deque = if (group.priority == Priority.SAMPLE) sample else critical
        val index = if (group.priority == Priority.SAMPLE) sampleIndex else criticalIndex
        deque.removeAll { !it.available() }
        deque.addLast(group)
        index.add(group)
        return QueueOfferOutcome(QueueOffer.QUEUED, diagnostics, evicted)
    }

    @Suppress("ReturnCount")
    private fun reserve(group: QueuedGroup): Boolean {
        while (true) {
            val before = budget.get()
            // 减法比较避免条数加法溢出；bytes为Long，单组来自有界Int估算。
            if (group.items > maxItems - before.items || group.bytes > maxBytes - before.bytes) return false
            if (group.priority == Priority.SAMPLE &&
                (group.items > maxItems - reservedItems - before.samples ||
                    group.bytes > maxBytes - reservedBytes - before.sampleBytes)) return false
            if (budget.compareAndSet(before, before.change(group, 1))) return true
        }
    }

    private fun evict(index: ConcurrentLinkedQueue<QueuedGroup>): QueuedGroup? {
        while (true) {
            val group = index.poll() ?: return null
            if (!group.evict()) continue
            group.clear()
            release(group)
            return group
        }
    }

    private fun release(group: QueuedGroup) {
        budget.updateAndGet { it.change(group, -1) }
    }

    /** failure先取，低档位按入队时间合并；首组可超过批阈值以保证incident永不拆开。 */
    internal fun tryClaim(maxItems: Int, maxBytes: Int): Claim? {
        val groups = ArrayList<QueuedGroup>()
        var items = 0
        var bytes = 0L
        fun fits(group: QueuedGroup) = items == 0 || group.fits(maxItems - items, maxBytes - bytes)
        while (true) {
            val group = failure.peek() ?: break
            if (!fits(group)) return Claim(groups)
            failure.poll()
            check(group.claim())
            groups += group
            items += group.items
            bytes += group.bytes
        }
        tryWithProducerLock {
            while (true) {
                val group = oldest()?.takeIf(::fits) ?: break
                val deque = if (group.priority == Priority.SAMPLE) sample else critical
                deque.removeFirst()
                val index = if (group.priority == Priority.SAMPLE) sampleIndex else criticalIndex
                index.remove(group)
                if (group.claim()) {
                    groups += group
                    items += group.items
                    bytes += group.bytes
                }
            }
        }
        return if (groups.isEmpty()) null else Claim(groups)
    }

    private fun oldest(): QueuedGroup? {
        while (sample.firstOrNull()?.available() == false) sample.removeFirst()
        while (critical.firstOrNull()?.available() == false) critical.removeFirst()
        val first = sample.firstOrNull()
        val second = critical.firstOrNull()
        return if (first != null && (second == null || first.mono < second.mono)) first else second
    }

    internal inline fun <T> tryWithProducerLock(block: () -> T): T? {
        if (!lock.tryLock()) return null
        return try { block() } finally { lock.unlock() }
    }

    internal inner class Claim internal constructor(groups: List<QueuedGroup>) {
        val groups: List<QueuedGroup> = Collections.unmodifiableList(groups.toList())
        val records: List<QueuedRecord> = Collections.unmodifiableList(groups.flatMap { it.records })
        private val released = AtomicBoolean(false)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            groups.forEach { group ->
                group.clear()
                release(group)
            }
        }
    }
}
