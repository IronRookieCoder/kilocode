package ai.kilocode.stability

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PURPOSE_METRICS = "metrics"
private const val PURPOSE_LOGS = "logs"
private const val KIND_LIFECYCLE = "lifecycle"
private const val CHANNEL_CRITICAL = "critical"
private const val NAME_STARTED = "plugin.started"
private const val NAME_SHUTDOWN = "plugin.shutdown"
private const val END_KIND_APP_CLOSE = "app_close"
private const val END_KIND_UNLOAD = "unload"
private const val PROFILE_DEFAULT = "default"
private const val RUN_PREFIX = "run-"
private const val PROVIDER_CS_CLOUD = "cs-cloud"
private const val PROVIDER_KILO_CLI = "kilo-cli"
private const val PROVIDER_UNKNOWN = "unknown"
private const val TELEMETRY_DIR = "costrict-telemetry"
private const val V1_DIR = "v1"
private const val CONTROL_DIR = "control"
private const val CONTROL_FILE = "jetbrains.json"
private const val REGISTRATIONS_DIR = "registrations"

/** 安全状态reason闭集（常量token，不含路径/凭据/JWT）。 */
private const val REASON_STARTING = "starting"
private const val REASON_OK = "ok"
private const val REASON_NO_POLICY = "no_policy"
private const val REASON_WRITER_DISABLED = "writer_disabled"
private const val REASON_INIT_FAILED = "init_failed"
private const val REASON_OUTBOX_FULL = "outbox_full"
private const val REASON_STOPPED_PREFIX = "stopped_"

private const val POLICY_POLL_MS = 30_000L
private const val RETENTION_INTERVAL_MS = 3_600_000L
private const val WRITER_STARTUP_TIMEOUT_MS = 10_000L
private const val WRITER_STARTUP_POLL_MS = 20L

private fun defaultTelemetryHome(): Path =
    Path.of(System.getProperty("user.home"), ".costrict", "telemetry")

/**
 * 安全公开状态（接口表）：mode/side/profile/两用途开关/原因。只含常量与布尔值，
 * 绝不携带JWT、路径或账户内容；profile在v1恒为default（见[StabilityService] KDoc）。
 */
data class Coverage(
    val mode: String,
    val side: String,
    val profile: String,
    val metrics: Boolean,
    val logs: Boolean,
    val reason: String,
)

/**
 * 稳定性采集的App级轻服务（collector plan接口表）：producer登记、采集run生命周期与后台残留清理。
 *
 * start(side)幂等且立即返回（CAS一次，初始化全部在后台IO协程）。initialize流程：
 * 平台运行模式→控制文件读取（PolicyStore，禁采也持续轮询）→恢复持久device_id→固定环境快照→
 * 有效许可时取得writer.lock（writer.start内）→原子写producer.json与registration→
 * 记一次plugin.started。无有效许可只保留控制读取、状态与旧源残留清理，不创建任何
 * critical/diagnostic业务事实文件，也不登记；首次获许可建立新run并记一次plugin.started；
 * 公共授权撤销即结束run（不伪造plugin.shutdown——end_kind闭集只有app_close/unload），
 * 重开后以新run_id重建采集，设备ID不变。
 *
 * mode/side唯一来源是[PlatformRunMode]（IdeProductMode，与既有单体判定同一平台来源），
 * start(side)的调用方参数只作入口标注，绝不用于身份（不按"谁先start"推断单体/split）。
 * profile：v1只绑定双方确认的默认profile路径（`~/.costrict/telemetry`），自定义
 * data-dir/auth-path的cs-cloud在该契约建立前不受支持——表现为默认控制文件缺失，
 * 按第8章首次无策略fail closed（reason=no_policy），绝不回退读其他profile。
 *
 * stop(kind)：CAS去重；先经收尾通道（准入仍开启时）记一次plugin.shutdown（许可有效时，
 * 由writer的最终有界排空落盘），再立即关闭准入并writer.close()有界收尾——绝不先close
 * 再record丢掉shutdown；绝不阻塞JVM停机（强杀不丢数据的保证不存在，见设计7.1）。
 * 生命周期kind取自平台真实回调（app_close/unload），不靠dispose()猜测。
 *
 * 线程纪律：writer/文件IO只在writer自有IO线程与本服务后台协程；record路径非阻塞可EDT调用。
 * 后台任务：许可watch（每[pollIntervalMs]）、残留清理（启动即扫+每小时，禁采也执行）。
 */
@Service(Service.Level.APP)
@Suppress("TooManyFunctions", "LongParameterList")
class StabilityService private constructor(
    private val scope: CoroutineScope,
    private val modeSource: () -> RunMode,
    private val logDirProvider: () -> Path,
    private val telemetryHome: Path,
    private val deviceStore: DeviceIdStore,
    private val clock: Clock,
    private val pollIntervalMs: Long,
) {

    /** 平台注入入口：light service按CoroutineScope构造（KiloBackendAppService同型）。 */
    constructor(scope: CoroutineScope) : this(
        scope,
        { PlatformRunMode.current() },
        { PathManager.getLogDir() },
        defaultTelemetryHome(),
        platformDeviceIdStore(),
        SystemClock,
        POLICY_POLL_MS,
    )

    private val startedOnce = AtomicBoolean()
    private val stoppedOnce = AtomicBoolean()
    private val stateLock = Any()

    @Volatile private var policies: PolicyStore? = null
    @Volatile private var baseIdentity: ProducerIdentity? = null
    @Volatile private var standby: Pair<Recorder, Operations>? = null
    @Volatile private var activeRecorder: Recorder? = null
    @Volatile private var activeOperations: Operations? = null
    @Volatile private var activeWriter: Writer? = null
    @Volatile private var runActive = false
    @Volatile private var metadataWritten = false
    @Volatile private var outboxFull = false
    @Volatile private var runFailure: String? = null
    @Volatile private var connectionProviderHint = PROVIDER_UNKNOWN
    @Volatile private var runMode: RunMode = RunMode("unknown", "unknown")
    private var activationJob: Job? = null
    private var retentionJob: Job? = null

    private val statusFlow = MutableStateFlow(
        Coverage("unknown", "unknown", PROFILE_DEFAULT, metrics = false, logs = false, reason = REASON_STARTING),
    )

    /** 安全公开状态流。 */
    val status: StateFlow<Coverage> = statusFlow.asStateFlow()

    /** 当前采集run的准入入口；run建立前经惰性standby（无策略时record恒DISABLED，fail closed）。 */
    val recorder: Recorder
        get() = activeRecorder ?: lazyStandby().first

    /** 当前采集run的操作入口；与[recorder]同源，run切换后旧引用仅产出DISABLED。 */
    val operations: Operations
        get() = activeOperations ?: lazyStandby().second

    /**
     * 幂等启动（立即返回）。 [side]是入口标注（frontend工具窗/backend app初始化），仅用于
     * 调用方自述；身份里的mode/side一律取平台运行模式来源（KDoc），单体双入口重复start
     * 仍只有一个writer，该参数不参与任何判定。
     */
    @Suppress("UnusedParameter")
    fun start(side: String) {
        if (!startedOnce.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            runCatching { initialize() }.onFailure { setStatus(REASON_INIT_FAILED) }
        }
    }

    /**
     * 有界收尾（CAS去重，重复调用no-op）：[kind]来自平台生命周期回调（app_close/unload）。
     * 许可有效时先经收尾通道记一次plugin.shutdown，再关准入并writer.close()（有界flush）。
     */
    fun stop(kind: String) {
        if (!stoppedOnce.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            val endKind = if (kind == END_KIND_APP_CLOSE) END_KIND_APP_CLOSE else END_KIND_UNLOAD
            activationJob?.cancel()
            retentionJob?.cancel()
            if (runActive) {
                if (permitted()) activeRecorder?.record(shutdownDraft(endKind))
                activeRecorder?.close()
                activeWriter?.close()
                activeWriter = null
                runActive = false
            }
            // 关闭准入：保留已关闭的run recorder在recorder属性上（getter恒DISABLED，
            // 绝不在停机后经由standby重新打开普通record），standby本身也一并关闭。
            if (!runActive) activeRecorder?.close()
            runCatching { standby?.first?.close() }
            runCatching { policies?.close() }
            setStatus(REASON_STOPPED_PREFIX + endKind)
        }
    }

    /** 后端连接提供方归因（best effort，run快照固定前调用才生效；其余值归unknown）。 */
    fun noteConnectionProvider(id: String) {
        connectionProviderHint = when (id) {
            PROVIDER_CS_CLOUD -> PROVIDER_CS_CLOUD
            PROVIDER_KILO_CLI -> PROVIDER_KILO_CLI
            else -> PROVIDER_UNKNOWN
        }
    }

    // ---- 启动与run生命周期 ------------------------------------------------------

    private fun initialize() {
        if (stoppedOnce.get()) return
        runMode = modeSource()
        ensureCore()
        if (stoppedOnce.get()) {
            runCatching { policies?.close() }
            return
        }
        activationJob = scope.launch { activationLoop() }
        retentionJob = scope.launch { retentionLoop() }
    }

    private suspend fun activationLoop() {
        while (!stoppedOnce.get()) {
            stepActivation()
            delay(pollIntervalMs)
        }
    }

    /** 一次许可裁决：首次获许可建run；公共撤销即结束run（不伪造退出）；随后发布安全状态。
     * reason按状态机取值：run内=ok/outbox_full；run外=writer_disabled（启动失败粘滞，重试自愈）
     * 优先于no_policy——存储不可验证是比"无策略"更可行动的故障。 */
    private fun stepActivation() {
        if (stoppedOnce.get()) return
        val permitted = permitted()
        when {
            permitted && !runActive -> activateRun()
            !permitted && runActive -> deactivateRun()
            else -> Unit
        }
        setStatus(currentIdleReason())
    }

    private fun currentIdleReason(): String = when {
        runActive -> currentRunReason()
        runFailure != null -> runFailure!!
        else -> REASON_NO_POLICY
    }

    /** 新采集run：新run_id→新recorder/operations→writer持锁→（每实例一次）登记→plugin.started一次。
     * 启动失败（存储不可验证/锁被占）时关闭准入并保持禁采，下一个watch周期自动重试。 */
    private fun activateRun() {
        val base = ensureCore()
        val store = policies ?: return
        val identity = base.copy(runId = RUN_PREFIX + randomId(), mode = runMode.mode, side = runMode.side)
        val recorder = Recorder(identity, store, clock)
        activeRecorder = recorder
        activeOperations = Operations(recorder, clock, scope)
        val root = v1Root().resolve(identity.producerId)
        val storage = Storage(root)
        val writer = Writer(root, identity, recorder, store, clock, storage = storage)
        writer.onDisabled = { setStatus(REASON_WRITER_DISABLED) }
        writer.start()
        if (!awaitActive(writer)) {
            // 保留已关闭的recorder在getter上：禁采期间record恒DISABLED，不经standby重新开口。
            recorder.close()
            runFailure = REASON_WRITER_DISABLED
            setStatus(REASON_WRITER_DISABLED)
            return
        }
        runFailure = null
        activeWriter = writer
        runActive = true
        outboxFull = false
        writeMetadataOnce(storage, root)
        recorder.record(startedDraft())
    }

    /** 公共授权撤销：关准入→writer最后排空（失效事实按入盘前重判期丢弃），不记shutdown。
     * 已关闭的recorder保留在getter上：撤销期间业务record恒DISABLED，不得换standby重新开口。 */
    private fun deactivateRun() {
        activeRecorder?.close()
        activeWriter?.close()
        activeWriter = null
        runActive = false
        outboxFull = false
    }

    /** writer启动在自有IO线程完成；有界等待CREATED→ACTIVE/DISABLED。 */
    private fun awaitActive(writer: Writer): Boolean {
        var waited = 0L
        while (writer.state == WriterState.CREATED && waited < WRITER_STARTUP_TIMEOUT_MS) {
            Thread.sleep(WRITER_STARTUP_POLL_MS)
            waited += WRITER_STARTUP_POLL_MS
        }
        return writer.state == WriterState.ACTIVE
    }

    /** producer.json与登记文件只在首个成功run写一次（pid/process_start描述本JVM实例）。 */
    private fun writeMetadataOnce(storage: Storage, root: Path) {
        if (metadataWritten) return
        val identity = baseIdentity ?: return
        runCatching {
            val producer = Producer(identity, root, registrationsDir(), storage)
            producer.writeProducerJson()
            producer.writeRegistration()
            metadataWritten = true
        }
    }

    // ---- 许可、状态与后台清理 ---------------------------------------------------

    /** 即时判期：公共策略与两用途共同有效（plugin.started为双用途name）才可承载采集run。 */
    private fun permitted(): Boolean = purposes().isNotEmpty()

    private fun purposes(): Set<String> {
        val policy = policies?.current() ?: return emptySet()
        return policy.permit(clock.wall(), NAME_STARTED)
    }

    private fun currentRunReason(): String = if (outboxFull) REASON_OUTBOX_FULL else REASON_OK

    private fun setStatus(reason: String) {
        val purposes = purposes()
        statusFlow.value = Coverage(
            mode = runMode.mode,
            side = runMode.side,
            profile = PROFILE_DEFAULT,
            metrics = PURPOSE_METRICS in purposes,
            logs = PURPOSE_LOGS in purposes,
            reason = reason,
        )
    }

    /** 启动即扫+每小时独立扫描（禁采也执行）；预算不足置outbox_full，由下一轮状态发布。 */
    private suspend fun retentionLoop() {
        while (!stoppedOnce.get()) {
            sweepOnce()
            delay(RETENTION_INTERVAL_MS)
        }
    }

    private fun sweepOnce() {
        val identity = baseIdentity ?: return
        val root = v1Root().resolve(identity.producerId)
        val retention = Retention(
            root = root,
            clock = clock,
            producerId = identity.producerId,
            v1Root = v1Root(),
            registrationsDir = registrationsDir(),
        )
        val withinQuota = runCatching { retention.sweepOwnSource(writerActive = runActive) }.getOrDefault(true)
        outboxFull = runActive && !withinQuota
        runCatching { retention.sweepOldSources() }
    }

    // ---- 惰性核心（控制读取与standby准入，run建立前record恒fail closed） --------

    private fun ensureCore(): ProducerIdentity = synchronized(stateLock) {
        val store = policies
            ?: PolicyStore(controlPath(), { clock.wall() }, pollIntervalMs).also { policies = it }
        val identity = baseIdentity
            ?: ProducerEnvironment.snapshot(runMode, deviceStore.loadOrCreate(), connectionProviderHint)
                .also { baseIdentity = it }
        standby
            ?: Recorder(identity, store, clock)
                .let { recorder -> recorder to Operations(recorder, clock, scope) }
                .also { standby = it }
        identity
    }

    private fun lazyStandby(): Pair<Recorder, Operations> {
        ensureCore()
        val pair = standby ?: error("stability standby recorder unavailable")
        // 与stop竞争的迟到访问：服务已停时返回关闭态recorder，record恒DISABLED。
        if (stoppedOnce.get()) pair.first.close()
        return pair
    }

    // ---- 路径与草稿 -------------------------------------------------------------

    private fun v1Root(): Path = logDirProvider().resolve(TELEMETRY_DIR).resolve(V1_DIR)

    private fun registrationsDir(): Path = telemetryHome.resolve(REGISTRATIONS_DIR)

    private fun controlPath(): Path = telemetryHome.resolve(CONTROL_DIR).resolve(CONTROL_FILE)

    private fun startedDraft(): Draft =
        Draft(NAME_STARTED, KIND_LIFECYCLE, CHANNEL_CRITICAL, JsonObject(emptyMap()))

    private fun shutdownDraft(endKind: String): Draft = Draft(
        NAME_SHUTDOWN,
        KIND_LIFECYCLE,
        CHANNEL_CRITICAL,
        buildJsonObject { put("end_kind", endKind) },
    )

    companion object {
        /** 测试工厂（KiloBackendAppService同型）：注入路径/时钟/运行模式来源，不触平台。 */
        @Suppress("LongParameterList")
        internal fun create(
            scope: CoroutineScope,
            modeSource: () -> RunMode,
            logDirProvider: () -> Path,
            telemetryHome: Path,
            deviceStore: DeviceIdStore,
            clock: Clock,
            pollIntervalMs: Long,
        ) = StabilityService(
            scope,
            modeSource,
            logDirProvider,
            telemetryHome,
            deviceStore,
            clock,
            pollIntervalMs,
        )
    }
}
