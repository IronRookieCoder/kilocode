package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A4共享测试夹具（collector plan接口表）：真实临时目录、真实Recorder/PolicyStore/Writer，
 * 不替换I/O。可控时钟是明确的产品依赖（Clock），封存延迟阈值经它驱动；Storage故障钩子
 * 注入的是真实故障（关闭通道、占位目录），绝不伪造成功。
 *
 * flush()走writer的正常barrier：提交"排空+封存非空段"任务并等待完成，不暴露私有队列。
 * facts()只读取.ready文件；close()先结束writer再清理本次夹具目录。
 */
class Fixture(
    val tickMs: Long = 50L,
    val maxSegmentBytes: Long = 1024L * 1024,
    val batchSealBytes: Long = 64L * 1024,
    base: Path? = null,
    private val cleanOnClose: Boolean = true,
    autoStart: Boolean = true,
) : AutoCloseable {

    val base: Path = base ?: Files.createTempDirectory("stability-writer")
    val root: Path = this.base.resolve("outbox")
    val clock: Clock = FixtureClock()
    val storage: Storage = Storage(root)

    /** 控制文件必须先于PolicyStore落盘：其构造时同步读取，缺失即永久fail closed。 */
    private val controlFile: Path = this.base.resolve("control.json").apply { writeText(defaultControl()) }
    val policies: PolicyStore = PolicyStore(controlFile, { clock.wall() })
    val recorder: Recorder = Recorder(DEFAULT_IDENTITY, policies, clock)
    private val operationsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val operations: Operations = Operations(recorder, clock, operationsScope)

    /** M24（C5）：资源计数器与recorder同源注入真实所有者（controller/订阅/editor token）。 */
    val resources: Resources = Resources()
    val writer: Writer = Writer(
        root = root,
        identity = DEFAULT_IDENTITY,
        recorder = recorder,
        policies = policies,
        clock = clock,
        storage = storage,
        tickMs = tickMs,
        maxSegmentBytes = maxSegmentBytes,
        batchSealBytes = batchSealBytes,
    )

    private val closed = AtomicBoolean(false)

    init {
        if (autoStart) writer.start()
    }

    /** writer的正常停用/flush barrier：排空队列并封存全部非空段，返回即已落盘完成。 */
    fun flush() {
        writer.flush()
    }

    /** 只读取.ready（已封存不可变）文件，按通道与段名排序后逐行还原事实。 */
    fun facts(): List<Fact> = listReady(root).flatMap { file ->
        Files.readAllBytes(file).toString(Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> factJson.decodeFromString(Fact.serializer(), line) }
            .toList()
    }

    /** 可控时钟推进（单调与wall同步推进，策略判期与封存延迟共用同一时间线）。 */
    fun advanceClock(ms: Long) {
        (clock as FixtureClock).advance(ms)
    }

    /** 真实故障注入：下一次写入前先关闭通道，随后的write以ClosedChannelException失败。 */
    fun failNextWrite() {
        storage.beforeWrite = { channel ->
            storage.beforeWrite = null
            channel.close()
        }
    }

    /** 真实故障注入：下一次force前关闭通道，force以ClosedChannelException失败（数据未同步）。 */
    fun failNextForce() {
        storage.beforeForce = { channel ->
            storage.beforeForce = null
            channel.close()
        }
    }

    /** 真实故障注入：在.ready目标位置放目录，原子改名真实失败（非不支持原子改名的降级分支）。 */
    fun failNextRename() {
        storage.beforeMove = { _, ready ->
            storage.beforeMove = null
            Files.createDirectory(ready)
        }
    }

    /** 将控制文件改写为整体禁用并立即刷新策略快照（writer入盘前重判期应当拒绝）。 */
    fun expireControl() {
        base.resolve("control.json").writeText(disabledControl())
        policies.refresh()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { writer.close() }
        storage.beforeWrite = null
        storage.beforeForce = null
        storage.beforeMove = null
        runCatching { policies.close() }
        runCatching { operationsScope.cancel() }
        if (cleanOnClose) base.toFile().deleteRecursively()
    }

    /** 单调与UTC双指针的可控Clock：advance同步推进，测试内不为延迟阈值做真实睡眠等待。 */
    private class FixtureClock : Clock {
        private var monoMs = 0L
        override fun wall(): Long = WALL_BASE + monoMs
        override fun mono(): Long = monoMs
        fun advance(ms: Long) {
            monoMs += ms
        }
    }

    companion object {
        private val factJson = Json { encodeDefaults = true }
        private const val WALL_BASE = 1_790_000_000_000L

        private val DEFAULT_IDENTITY = ProducerIdentity(
            producerId = "pr-a4",
            runId = "run-a4",
            deviceId = "device-a4",
            pluginVersion = "1.0.0",
            ideProduct = "IU",
            ideBuild = "build-a4",
            ideBuildMajor = "2026.1",
            osFamily = "windows",
            arch = "x64",
            env = "test",
            mode = "monolith",
            side = "monolith",
            connectionProvider = "cs-cloud",
        )

        /** 真实wire形状（control-schema.json字段闭集），与A3测试同一形状。 */
        fun defaultControl(): String = buildJsonObject {
            put("schema_major", 1)
            put("revision", 12L)
            put("enabled", true)
            put("metrics_enabled", true)
            put("metrics_expires_at", 9_000_000_000_000L)
            put("metrics_allowed_categories", JsonArray(listOf("critical", "diagnostic").map { JsonPrimitive(it) }))
            put("logs_enabled", true)
            put("logs_expires_at", 9_000_000_000_000L)
            put("logs_allowed_categories", JsonArray(listOf("critical", "diagnostic").map { JsonPrimitive(it) }))
            put("account_epoch", "acct-a")
            put("account_state", "ready")
            put("expires_at", 9_000_000_000_000L)
            put("log_detail_rate_limit", buildJsonObject { put("per_fingerprint_max_per_minute", 3) })
        }.toString()

        private fun disabledControl(): String = buildJsonObject {
            put("schema_major", 1)
            put("revision", 13L)
            put("enabled", false)
            put("metrics_enabled", false)
            put("metrics_expires_at", 9_000_000_000_000L)
            put("metrics_allowed_categories", JsonArray(emptyList()))
            put("logs_enabled", false)
            put("logs_expires_at", 9_000_000_000_000L)
            put("logs_allowed_categories", JsonArray(emptyList()))
            put("account_epoch", "acct-a")
            put("account_state", "disabled")
            put("expires_at", 9_000_000_000_000L)
            put("log_detail_rate_limit", buildJsonObject { put("per_fingerprint_max_per_minute", 3) })
        }.toString()
    }
}

/** 只读.ready：按通道目录再按文件名排序，供断言顺序稳定。 */
internal fun listReady(root: Path): List<Path> = listBySuffix(root, ".ready")

/** 只读.open：故障用例据此断言.open保留未改名。 */
internal fun listOpen(root: Path): List<Path> = listBySuffix(root, ".open")

private fun listBySuffix(root: Path, suffix: String): List<Path> =
    listOf("critical", "diagnostic").flatMap { channel ->
        val dir = root.resolve(channel)
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { files ->
                files.filter { path -> path.toString().endsWith(suffix) && Files.isRegularFile(path) }
                    .sorted().toList()
            }
        }
    }
