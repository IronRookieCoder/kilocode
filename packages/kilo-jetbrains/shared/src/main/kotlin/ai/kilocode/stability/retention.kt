package ai.kilocode.stability

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

private val CHANNELS = listOf("critical", "diagnostic")
private const val CHANNEL_DIAGNOSTIC = "diagnostic"
private const val SUFFIX_OPEN = ".open"
private const val SUFFIX_READY = ".ready"
private const val SUFFIX_CLAIMED = ".claimed"
private const val SUFFIX_DONE = ".done"
private const val WRITER_LOCK_NAME = "writer.lock"
private const val EXCHANGE_LOCK_NAME = "exchange.lock"
private const val PRODUCER_JSON_NAME = "producer.json"
private const val REGISTRATION_SUFFIX = ".json"
private const val LOCK_RANGE_OFFSET = 0L
private const val LOCK_RANGE_SIZE = 1L

/** 每producer未交接文件的默认预算（设计7.4）：10MiB、24小时；测试经构造参数校准。 */
internal const val DEFAULT_MAX_BYTES = 10L * 1024 * 1024
private const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

/** 淘汰循环连续删除失败上限：超过即放弃本轮（Windows句柄占用等），下轮扫描重试。 */
private const val MAX_CONSECUTIVE_DELETE_FAILURES = 3

/**
 * 残留清理（设计7.4）：活跃源10MiB/24小时预算与旧producer残留的独立扫描。
 *
 * 锁纪律（7.2/7.4）：统一writer.lock→exchange.lock。活跃源清理的前提是本实例writer已持
 * writer.lock（[sweepOwnSource]只补拿exchange.lock）；旧源清理对他人writer.lock只做
 * **非阻塞试锁**（tryLock），拿不到即跳过——绝不持自己的writer锁等待另一个producer的锁。
 * `.claimed`/`.done`仅daemon可动，本类按后缀排除且绝不删除；锁文件只创建、从不unlink。
 *
 * 旧源清理前的校验链：登记文件（producer_id/outbox_path一致）→ 用户所有权 → 路径范围
 * （真实路径必须落在本IDE日志的v1根内，拒绝symlink/reparse越界）→ PID+process_start证据
 * （pid复用=启动时刻不一致可清；存活即跳过；证据不足跳过）。数据清空且确认无活跃writer
 * 后才移除producer.json与登记文件。
 *
 * ENOENT只说明另一方已认领/淘汰（竞争），一律按删除成功处理；跨JVM/跨语言（Go consumer）
 * 的锁互斥、进程死亡释放与休眠保留由G1平台矩阵验证。
 */
@Suppress("TooManyFunctions", "LongParameterList")
class Retention(
    private val root: Path,
    private val clock: Clock,
    private val producerId: String = root.fileName.toString(),
    private val v1Root: Path = root.parent ?: root,
    private val registrationsDir: Path = defaultRegistrationsDir(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val processes: ProcessIdentity = LiveProcesses,
) {

    /** 后台入口：活跃源预算清理 + 旧源独立扫描（禁采也执行）。 */
    fun sweep() {
        sweepOwnSource()
        sweepOldSources()
    }

    /**
     * 活跃源预算：到期文件先删，仍超10MiB则最旧diagnostic .ready优先、再最旧critical .ready，
     * exchange.lock内选择并删除。[writerActive]=true时前提是本实例writer已持writer.lock
     * （本方法只补拿exchange.lock，顺序writer→exchange）；false时writer已停，本方法对自身
     * 死数据也删过期.open（消费端救援会先核查pid/process_start，与exchange互斥）。
     * 返回预算是否满足；无可评估数据或exchange.lock被消费端持有时返回true（跳过本轮）。
     */
    fun sweepOwnSource(writerActive: Boolean = true): Boolean =
        when (val exchange = lockableExchange()) {
            null -> true
            else -> exchange.use { evictOverQuotaAndMeasure(writerActive) }
        }

    /** 无数据则无需锁（不在死根里新建锁文件）；有数据才试拿exchange.lock。 */
    private fun lockableExchange(): FileChannel? {
        if (dataFiles(root).isEmpty()) return null
        return tryAcquireLock(root.resolve(EXCHANGE_LOCK_NAME))
    }

    /** 删除到期文件后按预算逐个淘汰，返回剩余未交接量是否回到预算内。
     * 连续删除失败（Windows句柄占用/权限等，deleteIfExists静默失败）达
     * [MAX_CONSECUTIVE_DELETE_FAILURES]次即放弃本轮——否则同一候选被反复选中，
     * 持exchange.lock自旋会饿死consumer并卡死retentionLoop；下一轮每小时扫描再试。 */
    private fun evictOverQuotaAndMeasure(writerActive: Boolean): Boolean {
        deleteExpired(root, includeOpen = !writerActive)
        var consecutiveFailures = 0
        while (totalSize(dataFiles(root)) > maxBytes) {
            val candidate = evictionCandidate()
            val deleted = candidate != null && runCatching { Files.deleteIfExists(candidate) }.getOrDefault(false)
            consecutiveFailures = if (deleted) 0 else consecutiveFailures + 1
            if (candidate == null || consecutiveFailures >= MAX_CONSECUTIVE_DELETE_FAILURES) break
        }
        return totalSize(dataFiles(root)) <= maxBytes
    }

    /** 旧源独立扫描：本v1根下其他producer + 失效登记；无许可/禁采同样执行。 */
    fun sweepOldSources() {
        val v1Real = runCatching { v1Root.toRealPath() }.getOrNull() ?: return
        sweepOtherProducers(v1Real)
        sweepStaleRegistrations()
    }

    private fun sweepOtherProducers(v1Real: Path) {
        if (!Files.isDirectory(v1Root)) return
        Files.list(v1Root).use { files ->
            files.filter { path -> Files.isDirectory(path) }
                .filter { path -> path.fileName.toString() != producerId }
                .forEach { candidate -> sweepDeadProducer(candidate, v1Real) }
        }
    }

    /**
     * 单个旧producer：校验链（[verifiedDeadProducer]）全部通过才进入非阻塞
     * writer.lock→exchange.lock的过期数据删除；数据清空后移除元数据，锁文件保留。
     */
    private fun sweepDeadProducer(candidate: Path, v1Real: Path) {
        if (verifiedDeadProducer(candidate, v1Real)) cleanVerifiedDeadProducer(candidate)
    }

    /** 前提：校验链已通过。非阻塞writer.lock→exchange.lock，反向释放，绝不等待。 */
    private fun cleanVerifiedDeadProducer(candidate: Path) {
        val registrationFile = registrationsDir.resolve(candidate.fileName.toString() + REGISTRATION_SUFFIX)
        val writer = tryAcquireLock(candidate.resolve(WRITER_LOCK_NAME)) ?: return
        try {
            val exchange = tryAcquireLock(candidate.resolve(EXCHANGE_LOCK_NAME)) ?: return
            try {
                deleteExpired(candidate, includeOpen = true)
            } finally {
                runCatching { exchange.close() }
            }
            if (dataFiles(candidate).isEmpty()) {
                runCatching { Files.deleteIfExists(candidate.resolve(PRODUCER_JSON_NAME)) }
                runCatching { Files.deleteIfExists(registrationFile) }
            }
        } finally {
            runCatching { writer.close() }
        }
    }

    /** 校验链：登记一致/用户/范围/PID+启动时刻，任何一环不明即false（证据不足等待，不接管）。 */
    @Suppress("ReturnCount")
    private fun verifiedDeadProducer(candidate: Path, v1Real: Path): Boolean {
        val id = candidate.fileName.toString()
        val registration = readRegistration(registrationsDir.resolve(id + REGISTRATION_SUFFIX)) ?: return false
        if (registration.producerId != id) return false
        if (!samePath(registration.outboxPath, candidate)) return false
        if (!withinRealScope(candidate, v1Real)) return false
        if (!ownerIsCurrentUser(candidate)) return false
        val evidence = readProcessEvidence(candidate) ?: return false
        if (isSelfProcess(evidence.pid)) return false
        return when (val proof = processes.evidence(evidence.pid)) {
            PidEvidence.Gone -> true
            is PidEvidence.Alive -> proof.startMs != evidence.processStart
            PidEvidence.Unknown -> false
        }
    }

    /** 登记文件指向本v1根内但outbox目录已消失：登记为失效项，删除登记本身。 */
    private fun sweepStaleRegistrations() {
        if (!Files.isDirectory(registrationsDir)) return
        Files.list(registrationsDir).use { files ->
            files.filter { path -> path.fileName.toString().endsWith(REGISTRATION_SUFFIX) }
                .forEach { file ->
                    val registration = readRegistration(file) ?: return@forEach
                    val outbox = registration.outboxPath
                    if (insideByPath(outbox, v1Root) && !Files.isDirectory(outbox)) {
                        runCatching { Files.deleteIfExists(file) }
                    }
                }
        }
    }

    // ---- 文件与预算 ------------------------------------------------------------

    /**
     * 未交接数据文件清单：仅通道目录下的.open/.ready regular file。
     * .claimed/.done归daemon（不计入插件预算、绝不触碰），其余名字一律忽略。
     */
    private fun dataFiles(base: Path): List<Path> = CHANNELS.flatMap { channel ->
        val dir = base.resolve(channel)
        if (!Files.isDirectory(dir)) {
            emptyList()
        } else {
            Files.list(dir).use { files ->
                files.filter { path -> isDataFile(path) }.toList()
            }
        }
    }

    private fun isDataFile(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        val name = path.fileName.toString()
        return (name.endsWith(SUFFIX_OPEN) || name.endsWith(SUFFIX_READY)) &&
            !name.endsWith(SUFFIX_CLAIMED) && !name.endsWith(SUFFIX_DONE)
    }

    /** 到期删除：[includeOpen]仅对旧源为真（活跃源.open永不删）；ENOENT=竞争，不算错误。 */
    private fun deleteExpired(base: Path, includeOpen: Boolean) {
        val now = clock.wall()
        val suffixes = if (includeOpen) listOf(SUFFIX_OPEN, SUFFIX_READY) else listOf(SUFFIX_READY)
        dataFiles(base)
            .filter { file -> suffixes.any { file.fileName.toString().endsWith(it) } }
            .filter { file -> now - modifiedAt(file) > maxAgeMs }
            .forEach { file -> runCatching { Files.deleteIfExists(file) } }
    }

    /** 预算淘汰次序（设计7.4）：先最旧diagnostic .ready，diagnostic清空后再最旧critical .ready。 */
    private fun evictionCandidate(): Path? {
        val ready = dataFiles(root).filter { it.fileName.toString().endsWith(SUFFIX_READY) }
        val diagnostic = ready.filter { it.parent.fileName.toString() == CHANNEL_DIAGNOSTIC }
            .minByOrNull { modifiedAt(it) }
        val critical = ready.filter { it.parent.fileName.toString() != CHANNEL_DIAGNOSTIC }
            .minByOrNull { modifiedAt(it) }
        return diagnostic ?: critical
    }

    private fun totalSize(files: List<Path>): Long =
        files.sumOf { file -> runCatching { Files.size(file) }.getOrDefault(0L) }

    /** mtime读取失败按"永不到期"处理（保守，不误删证据不明文件）。 */
    private fun modifiedAt(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(Long.MAX_VALUE)

    // ---- 校验链 ----------------------------------------------------------------

    private class Registration(val producerId: String, val outboxPath: Path)

    private class SourceEvidence(val pid: Long, val processStart: Long)

    @Suppress("ReturnCount")
    private fun readRegistration(file: Path): Registration? {
        if (!Files.isRegularFile(file)) return null
        val element = parseJson(file) ?: return null
        val producerId = element.text("producer_id") ?: return null
        val outbox = element.text("outbox_path")?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        return Registration(producerId, outbox)
    }

    /** producer.json的pid/process_start：缺失、非正数或不可解析=启动身份不明（跳过清理）。 */
    @Suppress("ReturnCount")
    private fun readProcessEvidence(base: Path): SourceEvidence? {
        val element = parseJson(base.resolve(PRODUCER_JSON_NAME)) ?: return null
        val pid = element.long("pid") ?: return null
        val start = element.long("process_start") ?: return null
        if (pid <= 0 || start <= 0) return null
        return SourceEvidence(pid, start)
    }

    private fun parseJson(file: Path): JsonObject? = runCatching {
        Json.parseToJsonElement(Files.readString(file)) as? JsonObject
    }.getOrNull()

    /** 双方都存在时按真实路径比对（穿透symlink/reparse），否则按绝对归一化路径比对。 */
    private fun samePath(left: Path, right: Path): Boolean = runCatching {
        if (Files.exists(left) && Files.exists(right)) left.toRealPath() == right.toRealPath()
        else left.toAbsolutePath().normalize() == right.toAbsolutePath().normalize()
    }.getOrDefault(false)

    /** 范围核验：真实路径必须仍在v1根的真实路径之内（symlink/reparse越界即拒绝）。 */
    private fun withinRealScope(path: Path, rootReal: Path): Boolean = runCatching {
        path.toRealPath().startsWith(rootReal)
    }.getOrDefault(false)

    /** 不存在路径只能做字面范围比对（真实路径核验无从谈起，调用方不得据此删数据）。 */
    private fun insideByPath(path: Path, root: Path): Boolean =
        path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())

    /** 用户所有权：所有者名与当前用户互认（域名前缀可省，大小写不敏感）。 */
    private fun ownerIsCurrentUser(path: Path): Boolean {
        val owner = runCatching { Files.getOwner(path).name }.getOrNull()
        val user = System.getProperty("user.name")
        val bareOwner = owner?.substringAfterLast('\\')?.substringAfterLast('/')
        return owner != null && user != null &&
            (owner.equals(user, ignoreCase = true) || bareOwner.equals(user, ignoreCase = true))
    }

    private fun isSelfProcess(pid: Long): Boolean =
        runCatching { ProcessHandle.current().pid() == pid }.getOrDefault(false)

    // ---- 锁原语 ----------------------------------------------------------------

    /**
     * 非阻塞锁：文件只CREATE_NEW一次、已存在则原样打开（绝不unlink/recreate）；
     * tryLock失败（他人持有/同JVM重复/IO故障）即关闭通道返回null，调用方跳过本轮，绝不等待。
     */
    private fun tryAcquireLock(path: Path): FileChannel? {
        val channel = openLockChannel(path) ?: return null
        val locked = tryLockRange(channel)
        if (locked == null) runCatching { channel.close() }
        return if (locked == null) null else channel
    }

    private fun openLockChannel(path: Path): FileChannel? = runCatching {
        try {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)
        } catch (_: FileAlreadyExistsException) {
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
        }
    }.getOrNull()

    private fun tryLockRange(channel: FileChannel): FileLock? = try {
        channel.tryLock(LOCK_RANGE_OFFSET, LOCK_RANGE_SIZE, false)
    } catch (_: OverlappingFileLockException) {
        null
    } catch (_: IOException) {
        null
    }
}

/** 登记目录的默认约定路径（设计5.2）：`~/.costrict/telemetry/registrations`。 */
private fun defaultRegistrationsDir(): Path =
    Path.of(System.getProperty("user.home"), ".costrict", "telemetry", "registrations")

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
