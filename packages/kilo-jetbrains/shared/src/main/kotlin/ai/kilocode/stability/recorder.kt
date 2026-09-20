package ai.kilocode.stability

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 队列容量（设计7.1）：全部通道合计2000条且4MiB，先到者为准。 */
private const val TOTAL_MAX_ITEMS = 2000
private const val TOTAL_MAX_BYTES = 4 * 1024 * 1024

/** critical预留（设计7.1）：400条与20%字节；diagnostic至多使用扣除预留后的余量。 */
private const val CRITICAL_RESERVED_ITEMS = 400
private const val CRITICAL_RESERVED_BYTES = (TOTAL_MAX_BYTES + 4) / 5

private const val CHANNEL_CRITICAL = "critical"

/** record()的准入结论：QUEUED仅表示入内存，不保证落盘；DISABLED=采集被关闭；DROPPED=容量/争用/违规。 */
enum class Admission { QUEUED, DROPPED, DISABLED }

/**
 * 插件运行环境身份快照（Fact公共字段的生产者侧来源，设计6.1）。
 * 进程生命周期内不变；字段校验与长度边界由[Dictionary.violations]在record时统一把关。
 */
@Suppress("LongParameterList")
data class ProducerIdentity(
    val producerId: String,
    val runId: String,
    val deviceId: String,
    val pluginVersion: String,
    val ideProduct: String,
    val ideBuild: String,
    val ideBuildMajor: String,
    val osFamily: String,
    val arch: String,
    val env: String,
    val mode: String,
    val side: String,
    val connectionProvider: String,
)

/** 队列深度快照（health摘要的depth_bytes/条数来源；含已claim未release的记录）。 */
internal data class QueueDepth(
    val items: Int,
    val bytes: Int,
    val diagnosticItems: Int,
    val diagnosticBytes: Int,
)

/** 采集健康累计快照（AtomicLong取值合集；后续health.kt据此生成telemetry.health事件）。 */
data class RecorderHealth(
    val accepted: Long,
    val droppedInvalid: Long,
    val droppedContention: Long,
    val droppedCapacity: Long,
    val disabledPolicy: Long,
    val disabledShutdown: Long,
    val evictedDiagnostic: Long,
    val rejectedOverride: Long,
)

/** Operations.begin的开始时快照（设计6.2：跨账户切换保留开始时epoch；用途只能缩小）。 */
internal data class BeginSnapshot(val epoch: String?, val revision: Long?, val purposes: Set<String>)

/**
 * 非阻塞准入入口（设计7.1）：只做白名单校验、时间/ID、不可变快照与内存操作。
 *
 * record全路径不等待、不序列化完整JSON、不做文件/网络IO，可在EDT直接调用：
 * 1) closed即DISABLED；2) 取[PolicyStore.current]新鲜快照（每条记录重判，绝不缓存过期结论），
 * 无有效策略即DISABLED（fail closed）；3) [Dictionary.violations]结构性违规即DROPPED
 * （请求用途为空除外——它交给许可交集判为DISABLED）；4) 三方用途交集（Policy.permit ∩
 * Draft自带purposes ∩ Dictionary.purposes形态出口）为空即DISABLED；5) 生产者锁只
 * tryLock，争用即DROPPED；6) seq按run/channel在准入前递增（丢弃也产生seq空洞，不复用）；
 * 7) 双维容量由[StabilityQueue]原子判定。
 *
 * 健康计数全部走AtomicLong，丢弃不递归调用自身record。
 */
class Recorder(
    private val identity: ProducerIdentity,
    private val policies: PolicyStore,
    private val clock: Clock,
) {
    private val queue = StabilityQueue(
        TOTAL_MAX_ITEMS,
        TOTAL_MAX_BYTES,
        CRITICAL_RESERVED_ITEMS,
        CRITICAL_RESERVED_BYTES,
    )

    /** 生产路径的互斥临界区由[StabilityQueue]的tryLock提供；本类不另持锁，不产生二次等待。 */
    @Volatile private var closed = false

    private val criticalSeq = AtomicLong(0)
    private val diagnosticSeq = AtomicLong(0)

    private val accepted = AtomicLong(0)
    private val droppedInvalid = AtomicLong(0)
    private val droppedContention = AtomicLong(0)
    private val droppedCapacity = AtomicLong(0)
    private val disabledPolicy = AtomicLong(0)
    private val disabledShutdown = AtomicLong(0)
    private val evictedDiagnostic = AtomicLong(0)
    private val rejectedOverride = AtomicLong(0)

    /** seq按producer+run+channel单调递增；在队列tryLock临界区内调用，丢弃也产生空洞不复用。 */
    private fun nextSeq(channel: String): Long =
        if (channel == CHANNEL_CRITICAL) criticalSeq.incrementAndGet() else diagnosticSeq.incrementAndGet()

    @Suppress("ReturnCount")
    fun record(draft: Draft): Admission {
        if (closed) {
            disabledShutdown.incrementAndGet()
            return Admission.DISABLED
        }
        val policy = policies.current()
        if (policy == null) {
            disabledPolicy.incrementAndGet()
            return Admission.DISABLED
        }
        val violations = Dictionary.violations(draft)
        if (violations.any { it != PURPOSES_EMPTY }) {
            droppedInvalid.incrementAndGet()
            return Admission.DROPPED
        }
        val now = clock.wall()
        val permitted = policy.permit(now, draft.name)
        val purposes = buildSet {
            draft.purposes.forEach { purpose ->
                if (purpose in permitted && purpose in Dictionary.purposes(draft.name, draft.data)) add(purpose)
            }
        }
        if (purposes.isEmpty()) {
            disabledPolicy.incrementAndGet()
            return Admission.DISABLED
        }

        val channel = draft.channel
        // 生产者临界区=队列锁：seq分配、Fact构建、字节估算与准入判定在同一tryLock内完成，
        // 保证同通道队列顺序与seq顺序一致；争用时factory不被调用，不消耗seq。
        val outcome = queue.tryOffer {
            val seq = nextSeq(channel)
            val fact = buildFact(draft, policy, purposes, seq, now)
            QueuedRecord(fact, channel, seq, estimateBytes(fact), clock.mono())
        }
        return when (outcome.offer) {
            QueueOffer.QUEUED -> {
                accepted.incrementAndGet()
                if (outcome.evictedDiagnostics > 0) evictedDiagnostic.addAndGet(outcome.evictedDiagnostics.toLong())
                Admission.QUEUED
            }
            QueueOffer.FULL -> {
                droppedCapacity.incrementAndGet()
                if (outcome.evictedDiagnostics > 0) evictedDiagnostic.addAndGet(outcome.evictedDiagnostics.toLong())
                Admission.DROPPED
            }
            QueueOffer.CONTENTION -> {
                droppedContention.incrementAndGet()
                Admission.DROPPED
            }
        }
    }

    /** 停止准入：shutdown后record一律DISABLED；已排队事实留给writer按A4流程处理，不伪造shutdown。 */
    fun close() {
        closed = true
    }

    /** 健康累计快照；供后续health.kt生成telemetry.health，本类不递归记录自身丢弃。 */
    internal fun health(): RecorderHealth = RecorderHealth(
        accepted = accepted.get(),
        droppedInvalid = droppedInvalid.get(),
        droppedContention = droppedContention.get(),
        droppedCapacity = droppedCapacity.get(),
        disabledPolicy = disabledPolicy.get(),
        disabledShutdown = disabledShutdown.get(),
        evictedDiagnostic = evictedDiagnostic.get(),
        rejectedOverride = rejectedOverride.get(),
    )

    internal fun depth(): QueueDepth = QueueDepth(
        queue.depthItems,
        queue.depthBytes,
        queue.diagnosticDepthItems,
        queue.diagnosticDepthBytes,
    )

    /** writer（A4）的取出入口：批次仍计入内存预算直至[StabilityQueue.Claim.release]。 */
    internal fun tryClaim(maxItems: Int, maxBytes: Int): StabilityQueue.Claim? =
        queue.tryClaim(maxItems, maxBytes)

    /** Operations.begin的开始时快照：当前epoch/revision与该name的即时许可（可为空集）。 */
    internal fun beginSnapshot(now: Long, name: String): BeginSnapshot {
        val policy = if (closed) null else policies.current()
        return policy?.let { BeginSnapshot(it.epoch, it.revision, it.permit(now, name)) }
            ?: BeginSnapshot(null, null, emptySet())
    }

    /** Operation.fields试图覆盖公共/终态字段时由operation.kt调用计数（记录本体拒绝产出）。 */
    internal fun countRejectedOverride() {
        rejectedOverride.incrementAndGet()
    }

    /** 补齐公共身份字段为完整wire记录（设计6.1）；epoch允许Draft携带开始时快照覆盖当前值。 */
    private fun buildFact(
        draft: Draft,
        policy: Policy,
        purposes: Set<String>,
        seq: Long,
        timestamp: Long,
    ): Fact = Fact(
        event_id = UUID.randomUUID().toString(),
        timestamp = timestamp,
        producer_id = identity.producerId,
        run_id = identity.runId,
        channel = draft.channel,
        seq = seq,
        account_epoch = draft.epoch ?: policy.epoch,
        policy_revision = policy.revision,
        purposes = purposes,
        device_id = identity.deviceId,
        plugin_version = identity.pluginVersion,
        ide_product = identity.ideProduct,
        ide_build = identity.ideBuild,
        ide_build_major = identity.ideBuildMajor,
        os_family = identity.osFamily,
        arch = identity.arch,
        env = identity.env,
        mode = identity.mode,
        side = identity.side,
        connection_provider = identity.connectionProvider,
        kind = draft.kind,
        name = draft.name,
        context = draft.context,
        data = draft.data,
    )
}

/**
 * 32KiB/队列字节的保守上界（设计7.1）：不序列化完整JSON、不在EDT做真实编码。
 * 保留字符串按UTF-8字节×2覆盖转义开销，再叠加字段/数组结构开销与信封常量；
 * 精确32KiB由writer落盘前以真实UTF-8编码核对。
 */
private fun estimateBytes(fact: Fact): Int =
    ENVELOPE_BYTES +
        estimateObject(fact.data) +
        fact.context.entries.sumOf { (key, value) ->
            boundText(key) + boundText(value) + FIELD_OVERHEAD_BYTES
        }

private fun estimateObject(data: JsonObject): Int =
    data.entries.sumOf { (key, value) ->
        boundText(key) + FIELD_OVERHEAD_BYTES + estimateValue(value)
    }

private fun estimateValue(value: JsonElement): Int = when (value) {
    is JsonPrimitive -> boundText(if (value.isString) value.content else value.toString())
    is JsonArray -> ARRAY_OVERHEAD_BYTES + value.sumOf { item -> estimateValue(item) }
    is JsonObject -> value.entries.sumOf { (key, item) ->
        boundText(key) + FIELD_OVERHEAD_BYTES + estimateValue(item)
    }
}

private fun boundText(text: String): Int = text.encodeToByteArray().size * ESCAPE_FACTOR

/** 信封常量：26个公共字段名+固定值（schema_version/source/各类ID上界）的宽松合计。 */
private const val ENVELOPE_BYTES = 2048

/** 每个键值对的JSON结构开销（引号、冒号、逗号的保守值）。 */
private const val FIELD_OVERHEAD_BYTES = 8

/** 数组括号与分隔符开销。 */
private const val ARRAY_OVERHEAD_BYTES = 8

/** 字符串转义开销系数：合法字符串无控制字符，转义至多每字符1字节，×2必然覆盖。 */
private const val ESCAPE_FACTOR = 2
