package ai.kilocode.stability

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val KIND_SAMPLE = "sample"
private const val CHANNEL_CRITICAL = "critical"
private const val PURPOSE_METRICS = "metrics"

/** resource.snapshot（字典）：三类插件自有资源的当前持有数量，metrics-only出口。 */
internal const val NAME_RESOURCE_SNAPSHOT = "resource.snapshot"

/** 资源kind闭集（字典RESOURCES逐字）：subscription/controller/editor，绝无第四类。 */
internal val RESOURCE_KINDS = listOf("subscription", "controller", "editor")

/**
 * M24资源数量（C5，brief verbatim类）：插件自有资源token的计数器。
 *
 * acquire即计数并返回token；token仅首次close减计数（AutoCloseable幂等）——重复close、
 * 重复dispose、取消订阅后的晚到释放都不负数。三类独立计数：subscription/controller/editor，
 * 未登记kind直接抛出（getValue），绝不静默扩类。snapshot只读当前值，绝不推导泄漏或
 * JVM内存归属；由StabilityService每30秒经[resourceSnapshotDrafts]产出resource.snapshot。
 */
class Resources {
    private val counts = listOf("subscription", "controller", "editor")
        .associateWith { AtomicLong() }

    fun acquire(kind: String): AutoCloseable {
        val count = counts.getValue(kind)
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        count.incrementAndGet()
        return AutoCloseable { if (closed.compareAndSet(false, true)) count.decrementAndGet() }
    }

    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }
}

/**
 * 30秒gauge循环的一次完整载荷：每个kind恰一条resource.snapshot草稿，只带当前值。
 * 固定kind顺序保证事实稳定可比对；草稿经真实Recorder准入（Dictionary把关闭集）。
 */
internal fun resourceSnapshotDrafts(snapshot: Map<String, Long>): List<Draft> = RESOURCE_KINDS.map { kind ->
    Draft(
        NAME_RESOURCE_SNAPSHOT,
        KIND_SAMPLE,
        CHANNEL_CRITICAL,
        buildJsonObject {
            put("resource", kind)
            put("count", snapshot[kind] ?: 0L)
        },
        purposes = setOf(PURPOSE_METRICS),
    )
}
