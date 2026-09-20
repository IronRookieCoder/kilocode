package ai.kilocode.stability

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.readText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** 设计第8章：只支持冻结的control-schema v1，未知major按无有效公共策略处理，不推定允许。 */
private const val SUPPORTED_MAJOR = 1

/** 设计第8章：插件后台最多每30秒读取一次控制文件。 */
private const val POLL_INTERVAL_MS = 30_000L

/** account_epoch的长度边界（control-schema.json minLength 1/maxLength 64；按UTF-8字节从严执行）。 */
private const val EPOCH_MAX_BYTES = 64

/** 日志诊断详情限频的取值边界（control-schema.json：0..60，0表示不采诊断详情）。 */
private const val RATE_LIMIT_MAX = 60

/** 冻结的wire字段闭集（control-schema.json required；additionalProperties为false）。 */
private val CONTROL_FIELDS = setOf(
    "schema_major", "revision", "enabled",
    "metrics_enabled", "metrics_expires_at",
    "logs_enabled", "logs_expires_at",
    "account_epoch", "account_state", "expires_at",
    "metrics_allowed_categories", "logs_allowed_categories",
    "log_detail_rate_limit",
)

private val ACCOUNT_STATES = setOf("pending", "ready", "disabled")
private val CATEGORIES = setOf("critical", "diagnostic")
private const val CATEGORY_CRITICAL = "critical"
private const val CATEGORY_DIAGNOSTIC = "diagnostic"

/**
 * diagnostic类别承载详情形态的事件name（设计第9/11.2章：error/protocol/violation的详情）；
 * 其余name与全部计数形态按critical类别放行。与[Dictionary]登记集求交，未登记name不放行。
 */
private val DETAIL_NAMES = setOf("error.uncaught", "error.reported", "protocol.error", "edt.violation")

private val REGISTERED_NAMES = Dictionary.names.toSet()

/**
 * 单个用途的内部许可（内部模型，非协议字段）：[enabled]为用途独立开关，[expires]为该用途
 * 独立截止（UTC毫秒），[names]为该用途允许的事件name集合。wire字段（metrics_enabled、
 * metrics_expires_at、*_allowed_categories）到内部名的翻译由[PolicyStore]完成。
 */
data class Permit(val enabled: Boolean, val expires: Long, val names: Set<String>)

/**
 * 一次控制文件读取得到的不可变策略快照（设计第8章）：公共策略加两个用途的独立许可。
 *
 * 许可=用户许可∩公共策略∩事件类别∩用途策略∩开始时用途；用途只能缩小，[permit]只回答
 * 当前策略允许哪些用途，与Draft自带purposes的交集由recorder完成，禁用后不得经默认Draft加回。
 * 公共过期与各用途过期的较早值即该用途的有效截止，不取另一个用途的截止。
 */
data class Policy(
    val major: Int,
    val revision: Long,
    val enabled: Boolean,
    val epoch: String,
    val state: String,
    val expires: Long,
    val metrics: Permit?,
    val logs: Permit?,
) {
    /**
     * 即时判期：事件[name]在时刻[now]（UTC毫秒）允许的用途集合。
     * 每次record及writer入盘前都必须以当前时刻重新调用，不得缓存上一次结论等下一次轮询。
     */
    fun permit(now: Long, name: String): Set<String> {
        val commonOpen = major == SUPPORTED_MAJOR && enabled && state == STATE_READY && now < expires
        if (!commonOpen) return emptySet()
        return buildSet {
            if (metrics?.let { it.enabled && now < it.expires && name in it.names } == true) add(PURPOSE_METRICS)
            if (logs?.let { it.enabled && now < it.expires && name in it.names } == true) add(PURPOSE_LOGS)
        }
    }
}

private const val PURPOSE_METRICS = "metrics"
private const val PURPOSE_LOGS = "logs"
private const val STATE_READY = "ready"

/**
 * 控制文件读取与原子快照（设计第8章）。
 *
 * cs-cloud原子写`~/.costrict/telemetry/control/jetbrains.json`，本类后台最多每[POLL_INTERVAL_MS]
 * 读取一次并原子替换不可变快照；JSON解析与权限验证不发生在record热路径，record只需
 * [current]快照加[Policy.permit]即时判期，到期不等待下一次轮询，也不依赖文件mtime。
 *
 * fail closed：文件缺失、不可读、畸形、未知major或字段越界时[current]立即返回null
 * （构造时同步读取一次，不等下一次轮询）；用途块缺失或畸形只关闭该用途（设计第8章），
 * 公共字段与log_detail_rate_limit缺失、类型错误、越界则整份文件无效。
 *
 * 账户代际（设计8.1）：观察到epoch更替即把旧epoch永久退役（同账户重登也分配新epoch），
 * 已退役epoch回写只会继续fail closed，直到发布全新epoch；[retiredEpochs]供producer
 * 清空旧epoch的排队事实，sealed混合epoch文件由consumer逐行结算。
 *
 * 时钟防护：[current]与[refresh]维护单调上调的时钟下沿（租期上界），已观测到到期的
 * 许可在时钟回跳后不会复活；回跳之外的判期仍以调用方传入的[now]为准。
 */
class PolicyStore(
    private val path: Path,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    private val lock = Any()
    private val floorMs = AtomicLong(Long.MIN_VALUE)
    private val retired = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var snapshot: Policy? = null
    @Volatile private var currentEpoch: String? = null
    @Volatile private var closed = false

    private val poller: Thread = Thread({ pollLoop() }, "kilo-stability-policy-poll").apply { isDaemon = true }

    init {
        refresh()
        poller.start()
    }

    /** 永久退役的epoch集合（设计8.1保守丢弃），producer据此清空旧epoch内存，不得复活。 */
    val retiredEpochs: Set<String> get() = synchronized(lock) { retired.toSet() }

    /**
     * 最新策略快照；无有效策略时为null（公共策略缺失、过期或未知major，关闭两种上传用途）。
     * 每条记录与每次入盘前都必须重新调用：本方法即时推进时钟下沿并对已到期用途做禁用快照，
     * 到期判定不等下一次轮询。
     */
    fun current(): Policy? {
        raiseFloor()
        return visible(snapshot ?: return null, floorMs.get())
    }

    /** 立即重读控制文件并原子替换快照；后台轮询之外的显式刷新入口。 */
    fun refresh() {
        synchronized(lock) {
            raiseFloor()
            snapshot = adoptParsed()
        }
    }

    /** 停止后台轮询；已装入的快照仍可读取。 */
    fun close() {
        closed = true
        poller.interrupt()
    }

    private fun pollLoop() {
        while (!closed) {
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!closed) refresh()
        }
    }

    /** 读文件、解析并按代际规则收养；必须在[lock]内调用，任何失败都fail closed为null。 */
    private fun adoptParsed(): Policy? {
        val parsed = readControlText()
            ?.let { text -> parsePolicy(text) }
            // 已退役epoch的回写不得复活（同账户重登也分配新epoch，设计8.1）。
            ?.takeIf { policy -> policy.epoch !in retired }
        if (parsed == null) return null
        retirePrevious(parsed)
        currentEpoch = parsed.epoch
        return visible(parsed, floorMs.get())
    }

    private fun readControlText(): String? = try {
        path.readText()
    } catch (_: IOException) {
        null
    }

    /** 观察到epoch更替即永久退役旧epoch（设计8.1），producer据此清空旧epoch内存。 */
    private fun retirePrevious(parsed: Policy) {
        val previous = currentEpoch
        if (previous != null && parsed.epoch != previous) retired.add(previous)
    }

    /** 时钟下沿只升不降：作为租期上界，保证时钟回跳不复活已观测到期的许可。 */
    private fun raiseFloor() {
        floorMs.updateAndGet { previous -> maxOf(previous, now()) }
    }

    /** 返回按租期上界折算后的快照：公共或用途已观测到期时以禁用快照呈现，未到期时原样返回。 */
    private fun visible(policy: Policy, floor: Long): Policy {
        val commonGone = policy.expires <= floor
        val metrics = clampLease(policy.metrics, floor, commonGone)
        val logs = clampLease(policy.logs, floor, commonGone)
        return if (metrics === policy.metrics && logs === policy.logs) {
            policy
        } else {
            policy.copy(metrics = metrics, logs = logs)
        }
    }

    private fun clampLease(lease: Permit?, floor: Long, commonGone: Boolean): Permit? = when {
        lease == null -> null
        commonGone || lease.expires <= floor -> lease.copy(enabled = false)
        else -> lease
    }
}

/** 解析出的公共字段；schema_major已收敛为受支持的[SUPPORTED_MAJOR]，无需保留。 */
private data class CommonFields(
    val revision: Long,
    val enabled: Boolean,
    val epoch: String,
    val state: String,
    val expires: Long,
)

/**
 * wire到内部模型的翻译（纯函数，供PolicyStore在锁内调用）。
 * 任一公共字段缺失、类型错误、越界，出现未知键或整份JSON无法解析为对象时返回null；
 * 单用途块的问题只把该用途折叠为null（缺策略即关闭该用途）。
 */
private fun parsePolicy(text: String): Policy? {
    val root = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
    return root
        ?.takeIf { json -> json.keys.all { key -> key in CONTROL_FIELDS } && validRateLimit(json) }
        ?.let { json -> parseCommon(json)?.let { fields -> policyOf(json, fields) } }
}

/** 已通过公共校验的wire对象到[Policy]的组装；单用途块在此折叠为null许可。 */
private fun policyOf(root: JsonObject, fields: CommonFields): Policy = Policy(
    major = SUPPORTED_MAJOR,
    revision = fields.revision,
    enabled = fields.enabled,
    epoch = fields.epoch,
    state = fields.state,
    expires = fields.expires,
    metrics = permitOf(root, "metrics_enabled", "metrics_expires_at", "metrics_allowed_categories"),
    logs = permitOf(root, "logs_enabled", "logs_expires_at", "logs_allowed_categories"),
)

/** 公共字段逐项严格校验；任何一项不合法整份文件无效。 */
@Suppress("ReturnCount")
private fun parseCommon(root: JsonObject): CommonFields? {
    val major = root.long("schema_major") ?: return null
    if (major != SUPPORTED_MAJOR.toLong()) return null
    val revision = root.long("revision")?.takeIf { value -> value >= 0 } ?: return null
    val enabled = root.boolean("enabled") ?: return null
    val epoch = root.text("account_epoch")?.takeIf { value -> value.isNotEmpty() } ?: return null
    if (epoch.encodeToByteArray().size > EPOCH_MAX_BYTES) return null
    val state = root.text("account_state")?.takeIf { value -> value in ACCOUNT_STATES } ?: return null
    val expires = root.long("expires_at")?.takeIf { value -> value >= 0 } ?: return null
    return CommonFields(revision, enabled, epoch, state, expires)
}

/** 单用途wire块：任一字段缺失或畸形即该用途缺策略，只关闭该用途，不扩大到整体。 */
private fun permitOf(root: JsonObject, enabledKey: String, expiresKey: String, categoriesKey: String): Permit? {
    val enabled = root.boolean(enabledKey)
    val expires = root.long(expiresKey)?.takeIf { value -> value >= 0 }
    val categories = root.categories(categoriesKey)
    return if (enabled != null && expires != null && categories != null) {
        Permit(enabled, expires, allowedNames(categories))
    } else {
        null
    }
}

/** 事件类别到name集合的投影：critical放行全部登记name，diagnostic仅放行详情形态name。 */
private fun allowedNames(categories: Set<String>): Set<String> = buildSet {
    if (CATEGORY_CRITICAL in categories) addAll(REGISTERED_NAMES)
    if (CATEGORY_DIAGNOSTIC in categories) addAll(DETAIL_NAMES)
}

/** log_detail_rate_limit按schema严格校验：对象且per_fingerprint_max_per_minute为0..60整数。 */
private fun validRateLimit(root: JsonObject): Boolean {
    val value = ((root["log_detail_rate_limit"] as? JsonObject)
        ?.get("per_fingerprint_max_per_minute") as? JsonPrimitive)?.longOrNull
    return value != null && value >= 0 && value <= RATE_LIMIT_MAX
}

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content

/** 类别数组：字符串闭集、去重、至多两项（critical/diagnostic）；畸形返回null。 */
private fun JsonObject.categories(key: String): Set<String>? {
    val values = (this[key] as? JsonArray)
        ?.map { element -> (element as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        ?: return null
    val categories = values.filterNotNull().toSet()
    val wellFormed = values.size == categories.size &&
        values.size <= CATEGORIES.size &&
        categories.all { it in CATEGORIES }
    return if (wellFormed) categories else null
}
