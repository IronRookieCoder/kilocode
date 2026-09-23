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

/** 采集健康累计快照（AtomicLong取值合集；health.kt据此生成telemetry.health事件）。 */
data class RecorderHealth(
    val accepted: Long,
    val droppedInvalid: Long,
    val droppedContention: Long,
    val droppedCapacity: Long,
    val disabledPolicy: Long,
    val disabledShutdown: Long,
    val evictedDiagnostic: Long,
    val rejectedOverride: Long,
    val droppedEvicted: Long = 0,
    val droppedFailure: Long = 0,
    val droppedPolicy: Long = 0,
    val droppedOversize: Long = 0,
)

/** Operations.begin的开始时快照（设计6.2：跨账户切换保留开始时epoch；用途只能缩小）。 */
internal data class BeginSnapshot(val epoch: String?, val revision: Long?, val purposes: Set<String>)

/**
 * 非阻塞准入入口（设计7.1）：只做白名单校验、时间/ID、不可变快照与内存操作。
 *
 * record全路径不等待、不序列化完整JSON、不做文件/网络IO，可在EDT直接调用：
 * 1) closed即DISABLED；2) 取[PolicyStore.current]新鲜快照（每条记录重判，绝不缓存过期结论），
 * 无有效策略时该快照为unbound占位策略（设计第8章默认不限制采集），DISABLED只来自显式策略
 * 关闭两用途（含撤销、公共/用途过期）；3) [Dictionary.violations]结构性违规即DROPPED
 * （请求用途为空除外——它交给许可交集判为DISABLED）；4) 三方用途交集（Policy.permit ∩
 * Draft自带purposes ∩ Dictionary.purposes形态出口）为空即DISABLED；5) failure走无锁MPSC，
 * 低档位只tryLock、争用即DROPPED；6) seq按run/channel在准入前递增（不复用，出队按优先级）；
 * 7) 双维容量由[StabilityQueue]原子判定，batch校验与预留覆盖整个parent/chunks组。
 *
 * 健康计数全部走AtomicLong，丢弃不递归调用自身record。磁盘占用不设准入闸门：事实文件
 * 预算由writer按failure→critical→sample的整组保留顺序兜底。
 */
class Recorder private constructor(
    private val identity: ProducerIdentity?,
    private val policies: PolicyStore?,
    private val clock: Clock,
    @Volatile private var closed: Boolean,
) {
    constructor(identity: ProducerIdentity, policies: PolicyStore, clock: Clock) :
        this(identity, policies, clock, false)

    /** 初始化前的关闭入口：只分配内存，不读取平台、策略或持久身份；激活后可直接转发。 */
    internal constructor(clock: Clock) : this(null, null, clock, true)

    private val queue = StabilityQueue(
        TOTAL_MAX_ITEMS,
        TOTAL_MAX_BYTES,
        CRITICAL_RESERVED_ITEMS,
        CRITICAL_RESERVED_BYTES,
    )

    /**
     * F1（终审，standby捕获黑洞）：本recorder作为激活前standby时，run建立后由服务置位的
     * 转发目标（活跃run的recorder）。置位后[record]把事实原样转投目标——准入（策略/关闭/
     * 容量）与run身份全部由目标recorder自行裁决，fail closed语义不变；run结束/停机即清除。
     * 只是一次volatile读加委托调用，非阻塞、无IO，可EDT直接路径使用；活跃run的recorder
     * 恒为null，绝不形成转发链。
     */
    @Volatile internal var forwardTo: Recorder? = null

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
    private val droppedPolicy = AtomicLong(0)
    private val droppedEvicted = AtomicLong(0)
    private val droppedFailure = AtomicLong(0)

    fun record(draft: Draft): Admission = recordBatch(listOf(draft))

    /** 完整校验、单次双维预留、单个group发布；任何拒绝均不会发布部分parent/chunks。 */
    @Suppress("ReturnCount")
    fun recordBatch(drafts: List<Draft>): Admission {
        forwardTo?.let { return it.recordBatch(drafts) }
        val inputs = drafts.toList()
        if (inputs.isEmpty()) return Admission.DROPPED
        fun loss(counter: AtomicLong, admission: Admission): Admission {
            droppedFailure.addAndGet(inputs.count {
                priority(it.name, it.channel, it.data) == Priority.FAILURE
            }.toLong())
            counter.addAndGet(inputs.size.toLong())
            return admission
        }
        if (closed) return loss(disabledShutdown, Admission.DISABLED)
        val policy = policies?.current() ?: return loss(droppedPolicy, Admission.DISABLED)
        if (Dictionary.violations(inputs).any { it != PURPOSES_EMPTY }) {
            return loss(droppedInvalid, Admission.DROPPED)
        }
        val now = clock.wall()
        val facts = inputs.map { draft ->
            val schema = draft.schemaVersion.substringBefore('.').toIntOrNull() ?: 0
            val permitted = policy.permit(now, draft.name, category(draft.channel, draft.data), schema)
            val purposes = draft.purposes.intersect(permitted).intersect(Dictionary.purposes(draft.name, draft.data))
            if (purposes.isEmpty()) {
                disabledPolicy.addAndGet(inputs.size.toLong())
                return loss(droppedPolicy, Admission.DISABLED)
            }
            // 优先级claim不保证wire行的seq有序；seq仍按run/channel唯一递增分配。
            val counter = if (draft.channel == CHANNEL_CRITICAL) criticalSeq else diagnosticSeq
            val seq = counter.incrementAndGet()
            buildFact(draft, policy, purposes, seq, now)
        }
        if (!complete(facts)) return loss(droppedInvalid, Admission.DROPPED)
        val mono = clock.mono()
        val group = QueuedGroup(facts.map { fact ->
            QueuedRecord(fact, fact.channel, fact.seq, estimateBytes(fact), mono)
        })
        val outcome = queue.tryOffer(group)
        droppedEvicted.addAndGet(outcome.evicted.toLong())
        evictedDiagnostic.addAndGet(outcome.evictedDiagnostics.toLong())
        return when (outcome.offer) {
            QueueOffer.QUEUED -> {
                accepted.addAndGet(inputs.size.toLong())
                Admission.QUEUED
            }
            QueueOffer.FULL -> loss(droppedCapacity, Admission.DROPPED)
            QueueOffer.CONTENTION -> loss(droppedContention, Admission.DROPPED)
        }
    }

    /** 停止准入：closed后record一律DISABLED；已排队事实留给writer按A4流程处理。 */
    fun close() {
        closed = true
    }

    /** 健康累计快照；供health.kt生成telemetry.health，本类不递归记录自身丢弃。 */
    internal fun health(): RecorderHealth = RecorderHealth(
        accepted = accepted.get(),
        droppedInvalid = droppedInvalid.get(),
        droppedContention = droppedContention.get(),
        droppedCapacity = droppedCapacity.get(),
        disabledPolicy = disabledPolicy.get(),
        disabledShutdown = disabledShutdown.get(),
        evictedDiagnostic = evictedDiagnostic.get(),
        rejectedOverride = rejectedOverride.get(),
        droppedPolicy = droppedPolicy.get(),
        droppedEvicted = droppedEvicted.get(),
        droppedFailure = droppedFailure.get(),
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
        forwardTo?.let { return it.beginSnapshot(now, name) }
        val policy = if (closed) null else policies?.current()
        return policy?.let { BeginSnapshot(it.epoch, it.revision, it.permit(now, name, CHANNEL_CRITICAL)) }
            ?: BeginSnapshot(null, null, emptySet())
    }

    /** 详情配额每次取新策略；关闭日志/类别或quota=0时不产生详情及其补报摘要。 */
    internal fun limit(name: String, schema: Int = 1): Int {
        forwardTo?.let { return it.limit(name, schema) }
        val policy = if (closed) null else policies?.current()
        val permitted = policy != null && "logs" in policy.permit(clock.wall(), name, "diagnostic", schema)
        return if (permitted) policy.limit else 0
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
    ): Fact {
        val identity = requireNotNull(identity)
        return Fact(
            schema_version = draft.schemaVersion,
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
}

/** 详情按字段形态归类，不能通过把channel伪装成critical绕过诊断许可。 */
internal fun category(channel: String, data: JsonObject): String =
    if ("message" in data || "frames" in data) "diagnostic" else channel

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
