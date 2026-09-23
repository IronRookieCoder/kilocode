package ai.kilocode.stability

import com.intellij.openapi.components.Service
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
private const val NAME_BRIDGED = "error.reported"
private const val CATEGORY_DIAGNOSTIC = "diagnostic"
private const val END_KIND_APP_CLOSE = "app_close"
private const val END_KIND_UNLOAD = "unload"
private const val PROFILE_DEFAULT = "default"
private const val RUN_PREFIX = "run-"
private const val PROVIDER_CS_CLOUD = "cs-cloud"
private const val PROVIDER_KILO_CLI = "kilo-cli"
private const val PROVIDER_UNKNOWN = "unknown"
private const val OUTBOX_DIR = "outbox"
private const val CONTROL_DIR = "control"
private const val CONTROL_FILE = "jetbrains.json"

/** 安全状态reason闭集（常量token，不含路径/凭据/JWT）。 */
private const val REASON_STARTING = "starting"
private const val REASON_OK = "ok"
private const val REASON_UNBOUNDED = "unbound"
private const val REASON_WRITER_DISABLED = "writer_disabled"
private const val REASON_INIT_FAILED = "init_failed"
private const val REASON_STOPPED_PREFIX = "stopped_"

private const val POLICY_POLL_MS = 30_000L
private const val HEALTH_POLL_MS = 5_000L
private const val RESOURCE_GAUGE_INTERVAL_MS = 30_000L
private const val WRITER_STARTUP_TIMEOUT_MS = 10_000L
private const val WRITER_STARTUP_POLL_MS = 20L

private fun defaultTelemetryHome(): Path =
    Path.of(System.getProperty("user.home"), ".costrict", "telemetry")

/**
 * writer启动等待（默认实现）：有界轮询CREATED→ACTIVE/DISABLED。经构造参数注入（测试用它
 * 停在"writer已创建、run未提交"的窗口内，覆盖stop vs activateRun竞态）。
 */
internal fun defaultAwaitActive(writer: Writer): Boolean {
    var waited = 0L
    while (writer.state == WriterState.CREATED && waited < WRITER_STARTUP_TIMEOUT_MS) {
        Thread.sleep(WRITER_STARTUP_POLL_MS)
        waited += WRITER_STARTUP_POLL_MS
    }
    return writer.state == WriterState.ACTIVE
}

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
 * 稳定性采集的App级轻服务（collector plan接口表）：采集run生命周期与unclean判定。
 *
 * start(side)幂等且立即返回（CAS一次，初始化全部在后台IO协程）。initialize流程：
 * 平台运行模式→恢复持久scope_id与device_id→固定环境快照→控制文件读取（PolicyStore，
 * 禁采也持续轮询）→有效许可时建立run：writer在`~/.costrict/telemetry/outbox/`打开IDE范围的
 * 单追加文件`<scope-id>.jsonl`（无登记目录、无producer.json、无锁文件、
 * 无.open/.ready状态机，§5.2/§3.1）→从scope文件判定前任run是否unclean（§7.3，
 * 在plugin.started之前消费，检测IO失败fail open不阻塞启动）→记一次plugin.started。
 * 无有效许可只保留控制读取与状态，不建立采集run；首次获许可建立新run并记一次
 * plugin.started；公共授权撤销即结束run并删除本IDE范围待交接文件（§8，不伪造plugin.shutdown
 * ——end_kind闭集只有app_close/unload），重开后以新run_id重建采集，设备ID与scope-id不变，
 * 文件名跨run和JVM稳定（runId与producerId变化不改名）。
 *
 * mode/side唯一来源是[PlatformRunMode]（IdeProductMode，与既有单体判定同一平台来源），
 * start(side)的调用方参数只作入口标注，绝不用于身份（不按"谁先start"推断单体/split）。
 * profile：只绑定双方确认的默认profile路径（`~/.costrict/telemetry`），自定义
 * data-dir/auth-path的cs-cloud在该契约建立前不受支持——表现为默认控制文件缺失，
 * 按第8章落入unbound占位策略（reason=unbound，默认不限制采集），绝不回退读其他profile。
 *
 * stop(kind)：CAS去重；先经收尾通道（准入仍开启时）记一次plugin.shutdown（许可有效时，
 * 由writer的最终有界排空落盘），再立即关闭准入并writer.close()有界收尾——绝不先close
 * 再record丢掉shutdown；绝不阻塞JVM停机（强杀不丢数据的保证不存在，见设计7.1）。
 * 生命周期kind取自平台真实回调（app_close/unload），不靠dispose()猜测。
 *
 * 线程纪律：writer/文件IO只在writer自有IO线程与本服务后台协程；record路径非阻塞可EDT调用。
 * 核心预热随构造在唯一后台IO协程完成；getter始终只返回纯内存入口，不等待初始化或文件IO。
 * 初始化前事实被关闭入口拒绝；激活前被长生命周期消费者捕获的standby引用
 * 在run建立后经[Recorder.forwardTo]直投活跃run
 * （F1），standby自身不再积压无人排空的事实。
 * 后台任务：许可watch（每[pollIntervalMs]，含初始及运行期撤销清理）、run激活时旧布局清理、
 * health摘要（run内每[HEALTH_POLL_MS]采样，生成节奏由Health按30秒及损失变化裁决）。
 */
@Service(Service.Level.APP)
@Suppress("TooManyFunctions", "LongParameterList")
class StabilityService private constructor(
    private val scope: CoroutineScope,
    private val modeSource: () -> RunMode,
    private val scopeStore: ScopeIdStore,
    private val telemetryHome: Path,
    private val deviceStore: DeviceIdStore,
    private val clock: Clock,
    private val pollIntervalMs: Long,
    private val awaitActiveHook: (Writer) -> Boolean,
) {

    /** 平台注入入口：light service按CoroutineScope构造（KiloBackendAppService同型）。 */
    constructor(scope: CoroutineScope) : this(
        scope,
        { PlatformRunMode.current() },
        platformScopeIdStore(),
        defaultTelemetryHome(),
        platformDeviceIdStore(),
        SystemClock,
        POLICY_POLL_MS,
        { writer -> defaultAwaitActive(writer) },
    )

    private val startedOnce = AtomicBoolean()
    private val stoppedOnce = AtomicBoolean()
    private val stateLock = Any()

    @Volatile private var policies: PolicyStore? = null
    @Volatile private var baseIdentity: ProducerIdentity? = null
    @Volatile private var activeRecorder: Recorder? = null
    @Volatile private var activeOperations: Operations? = null
    @Volatile private var activeFaults: Faults? = null
    @Volatile private var activeHealth: Health? = null
    @Volatile private var activeWriter: Writer? = null
    @Volatile private var activeBridge: AutoCloseable? = null
    @Volatile private var runActive = false
    /** IDE安装范围持久scope-id（后台初始化经[scopeStore]载入，实例内不变；文件名前缀）。 */
    @Volatile private var scopeId: String = ""
    @Volatile private var runFailure: String? = null
    @Volatile private var connectionProviderHint = PROVIDER_UNKNOWN
    @Volatile private var runMode: RunMode = RunMode("unknown", "unknown")
    private var activationJob: Job? = null
    private var healthJob: Job? = null
    private var resourceGaugeJob: Job? = null
    @Volatile private var pending = false

    private val standby = Recorder(clock)
    private val gateway = Operations(Recorder(clock), clock, scope) { activeOperations }
    private val standbyFaults = Faults(standby, clock)

    private val statusFlow = MutableStateFlow(
        Coverage("unknown", "unknown", PROFILE_DEFAULT, metrics = false, logs = false, reason = REASON_STARTING),
    )

    private val core = scope.async(Dispatchers.IO) {
        runCatching { prepare() }.onFailure {
            runFailure = REASON_INIT_FAILED
            runCatching { policies?.close() }
            if (!stoppedOnce.get()) setStatus(REASON_INIT_FAILED)
        }
    }

    /** 安全公开状态流。 */
    val status: StateFlow<Coverage> = statusFlow.asStateFlow()

    /**
     * 当前采集run的准入入口；run建立前立即返回关闭的standby，不读取文件或等待锁。
     * F1：激活前被捕获的standby引用在run建立后经[Recorder.forwardTo]直投活跃run，不再有
     * 无人排空的黑洞队列；run切换后旧run引用仍仅产出DISABLED。
     */
    val recorder: Recorder
        get() = activeRecorder ?: standby

    /** 稳定操作入口：每次begin/record读取当前run，已开始的Operation永远绑定原recorder。 */
    val operations: Operations
        get() = gateway

    /**
     * 当前采集run的安全异常入口（A6）；run建立前经关闭的standby，不触发持久化。
     * 激活前捕获的引用随run建立转发至活跃run
     * （与[recorder]同一[Recorder.forwardTo]机制），stop后同样保留已关闭引用。
     */
    val faults: Faults
        get() = activeFaults ?: standbyFaults

    /**
     * M24（C5）：插件自有资源token的唯一实例（本服务独有，绝不新建第二个计数器）。
     * 订阅/controller/editor三类真实所有者经它acquire/close；计数与采集许可无关——
     * 禁采期间token照常增减，只是gauge事实被recorder按既有准入丢弃。
     */
    val resources: Resources = Resources()

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
            healthJob?.cancel()
            resourceGaugeJob?.cancel()
            closeBridge()
            if (runActive) {
                if (permitted()) activeRecorder?.recordBatch(operationEvidence(
                    NAME_SHUTDOWN,
                    buildJsonObject {
                        put("end_kind", endKind)
                        activeWriter?.flushed?.let { put("last_flush_time", it) }
                    },
                    activeOperations?.snapshot()?.keys.orEmpty(),
                    activeRecorder?.limit("diagnostic.payload", 2)?.let { it > 0 } == true,
                ))
                activeRecorder?.close()
                activeWriter?.close()
                activeWriter = null
                runActive = false
            }
            // 关闭准入：保留已关闭的run recorder在recorder属性上（getter恒DISABLED，
            // 绝不在停机后经由standby重新打开普通record），standby本身也一并关闭。
            if (!runActive) activeRecorder?.close()
            // F1：先断开standby转发再关闭它，迟到的早捕获引用落回standby自身的关闭态准入。
            standby.forwardTo = null
            standby.close()
            runCatching { policies?.close() }
            setStatus(REASON_STOPPED_PREFIX + endKind)
        }
    }

    /** 与activateRun的身份冻结共用锁；冻结后到达的hint只作用于下一run。 */
    fun noteConnectionProvider(id: String) = synchronized(stateLock) {
        val provider = when (id) {
            PROVIDER_CS_CLOUD -> PROVIDER_CS_CLOUD
            PROVIDER_KILO_CLI -> PROVIDER_KILO_CLI
            else -> PROVIDER_UNKNOWN
        }
        connectionProviderHint = provider
        if (!runActive) baseIdentity = baseIdentity?.copy(connectionProvider = provider)
    }

    // ---- 启动与run生命周期 ------------------------------------------------------

    private suspend fun initialize() {
        if (stoppedOnce.get()) return
        core.await()
        if (stoppedOnce.get() || runFailure == REASON_INIT_FAILED) {
            runCatching { policies?.close() }
            return
        }
        activationJob = scope.launch(Dispatchers.IO) { activationLoop() }
        healthJob = scope.launch { healthLoop() }
        resourceGaugeJob = scope.launch { resourceGaugeLoop() }
    }

    private suspend fun activationLoop() {
        while (!stoppedOnce.get()) {
            stepActivation()
            delay(pollIntervalMs)
        }
    }

    /** 一次许可裁决：首次获许可建run；公共撤销即结束run（不伪造退出）；随后发布安全状态。
     * reason按状态机取值：run内=ok；run外=writer_disabled（启动失败粘滞，重试自愈）
     * 优先于unbound——存储不可验证是比"尚无绑定策略"更可行动的故障。
     * activateRun可能在等待窗口内被stop跨越，返回后若已裁决stop则不得再发布状态
     * （stopped_*由stop协程独占发布，status与实际运行态保持一致）。 */
    private fun stepActivation() {
        if (stoppedOnce.get()) return
        val permitted = permitted()
        if (!permitted || pending) deactivateRun()
        if (permitted && !pending && !runActive) activateRun()
        if (stoppedOnce.get()) return
        setStatus(currentIdleReason())
    }

    private fun currentIdleReason(): String = when {
        runActive -> currentRunReason()
        runFailure != null -> runFailure!!
        else -> REASON_UNBOUNDED
    }

    /** 新采集run：新run_id→新recorder/operations→安全预检与unclean判定→writer打开单追加文件→plugin.started一次。
     * 启动失败（存储不可验证）时关闭准入并保持禁采，下一个watch周期自动重试。
     * stop竞态：入口与等待返回后都复查stoppedOnce，提交序列之后F5再复查一次——stop裁决后
     * 绝不留活跃run，未提交的writer/recorder就地关闭（writer.close有界），status交由stop协程发布。 */
    @Suppress("ReturnCount")
    private fun activateRun() {
        if (stoppedOnce.get()) return
        val base = baseIdentity ?: return
        // 身份冻结的线性化点：与provider更新互斥；writer启动与等待均在锁外。
        val identity = synchronized(stateLock) {
            base.copy(
                runId = RUN_PREFIX + randomId(), mode = runMode.mode, side = runMode.side,
                connectionProvider = connectionProviderHint,
            )
        }
        val store = policies ?: return
        val recorder = Recorder(identity, store, clock)
        val diagnostics = Diagnostics(recorder, clock)
        activeRecorder = recorder
        activeOperations = Operations(recorder, clock, scope, diagnostics, null)
        // F1：standby接管点先行——从本run的recorder诞生起，激活前被长生命周期消费者捕获的
        // 引用即直投本run（启动窗口内的事实随writer ACTIVE后排空落盘），绝不滞留在无人
        // 排空的standby队列；启动失败路径随即断开，落回standby自身的关闭入口。
        standby.forwardTo = recorder
        val storage = Storage(outboxDir())
        val file = outboxDir().resolve(fileName())
        if (runCatching { storage.verifyLayout() }.isFailure) {
            standby.forwardTo = null
            recorder.close()
            runFailure = REASON_WRITER_DISABLED
            setStatus(REASON_WRITER_DISABLED)
            return
        }
        // §7.3/M22：必须在writer打开文件前判定，避免writer为追加补LF后将崩溃尾页误作
        // 前任shutdown；读取先经Storage拒绝链接/越界/权限不可验证目标，检测失败仍fail open，
        // 不阻塞采集启动（R15）。
        val drafts = recover(file, storage, recorder)
        // 追加协议布局（§5.2）：outbox下平铺单文件`<scope-id>.jsonl`，跨run与JVM稳定。
        val writer = Writer(outboxDir(), fileName(), identity, recorder, store, clock, storage = storage)
        writer.onDisabled = { setStatus(REASON_WRITER_DISABLED) }
        writer.start()
        // 等待轮询不可经取消打断（Thread.sleep），stop可能恰好落在此窗口内。
        val active = awaitActiveHook(writer)
        if (stoppedOnce.get()) {
            standby.forwardTo = null
            writer.close()
            recorder.close()
            return
        }
        if (!active) {
            // 保留已关闭的recorder在getter上：禁采期间record恒DISABLED，不经standby重新开口。
            standby.forwardTo = null
            recorder.close()
            runFailure = REASON_WRITER_DISABLED
            setStatus(REASON_WRITER_DISABLED)
            return
        }
        runFailure = null
        activeWriter = writer
        runActive = true
        clearLegacy()
        if (drafts.isNotEmpty()) recorder.recordBatch(drafts)
        recorder.record(startedDraft())
        // F5：stop落在最后预检与提交序列之间的微窗口——提交后复查裁决，命中即就地收尾
        // （recorder/writer的close幂等，与stop协程双路重入安全），绝不留下裁决后仍活跃的
        // run；stopped_*状态仍由stop协程独占发布。
        if (stoppedOnce.get()) {
            runActive = false
            closeBridge()
            recorder.close()
            writer.close()
            activeWriter = null
            return
        }
        // A6：安全异常入口与health摘要随run创建（去重缓存与计数随run生命周期绑定）。
        activeFaults = Faults(diagnostics)
        activeHealth = Health(recorder, writer, clock)
        installBridge(diagnostics, store)
    }

    /** 公共授权撤销：关准入→writer最后排空（失效事实按入盘前重判期丢弃），不记shutdown；
     * 随后§8清理本IDE范围待交接文件（不保留补报）。
     * 已关闭的recorder保留在getter上：撤销期间业务record恒DISABLED，不得换standby重新开口；
     * standby转发随run结束断开，重开后由activateRun重新接管。 */
    private fun deactivateRun() {
        standby.forwardTo = null
        closeBridge()
        activeRecorder?.close()
        activeWriter?.close() // 有界排空：撤销后重判期使剩余事实不入盘
        activeWriter = null
        synchronized(stateLock) {
            runActive = false
            baseIdentity = baseIdentity?.copy(connectionProvider = connectionProviderHint)
        }
        // §8：用户撤销/总开关关闭/公共过期——停采并清理待交接数据，不保留补报
        pending = runCatching {
            if (!Files.notExists(outboxDir(), LinkOption.NOFOLLOW_LINKS)) {
                Storage(outboxDir()).verifyLayout()
                Files.deleteIfExists(outboxDir().resolve(fileName()))
            }
        }.isFailure
        runFailure = if (pending) REASON_WRITER_DISABLED else null
    }

    /** 读取前任证据失败不阻断启动；消费者不支持v2时仍保留有界生命周期摘要。 */
    private fun recover(file: Path, storage: Storage, recorder: Recorder): List<Draft> = runCatching {
        UncleanDetector(file).detect(storage.read(file), recorder.limit("diagnostic.payload", 2) > 0)
    }.getOrDefault(emptyList())

    /** writer活动后才安装；后台drain调用完整诊断入口。 */
    private fun installBridge(diagnostics: Diagnostics, store: PolicyStore) {
        synchronized(stateLock) {
            if (stoppedOnce.get()) return
            val bridge = DiagnosticBridge.install { input ->
                if (!logsPermitted(store)) return@install
                diagnostics.report(input)
            }
            activeBridge = bridge
        }
    }

    /** 必须早于recorder/writer关闭；安装句柄自身可重复关闭。 */
    private fun closeBridge() {
        val bridge = synchronized(stateLock) {
            activeBridge
        }
        if (bridge == null) return
        bridge.close()
        DiagnosticBridge.await(bridge)
        synchronized(stateLock) {
            if (activeBridge === bridge) activeBridge = null
        }
    }

    /** writer启动在自有IO线程完成；等待逻辑见[defaultAwaitActive]（可注入）。 */

    // ---- 许可、状态与后台任务 ---------------------------------------------------

    /** 即时判期：公共策略与两用途共同有效（plugin.started为双用途name）才可承载采集run。 */
    private fun permitted(): Boolean = purposes().isNotEmpty()

    private fun purposes(): Set<String> {
        val policy = policies?.current() ?: return emptySet()
        return policy.permit(clock.wall(), NAME_STARTED)
    }

    private fun logsPermitted(store: PolicyStore): Boolean =
        PURPOSE_LOGS in store.current().permit(clock.wall(), NAME_BRIDGED, CATEGORY_DIAGNOSTIC)

    private fun currentRunReason(): String = REASON_OK

    private fun setStatus(reason: String) {
        val blocked = pending || runFailure == REASON_INIT_FAILED || reason == REASON_INIT_FAILED
        val purposes = if (blocked) emptySet() else purposes()
        statusFlow.value = Coverage(
            mode = runMode.mode,
            side = runMode.side,
            profile = PROFILE_DEFAULT,
            metrics = PURPOSE_METRICS in purposes,
            logs = PURPOSE_LOGS in purposes,
            reason = reason,
        )
    }

    /** health摘要后台循环（A6）：只在run活跃时生成；生成节奏与损失触发由Health.poll内部裁决。 */
    private suspend fun healthLoop() {
        while (!stoppedOnce.get()) {
            if (runActive) activeHealth?.let { health -> runCatching { health.poll() } }
            delay(HEALTH_POLL_MS)
        }
    }

    /**
     * M24（C5）资源gauge后台循环：run活跃时每[RESOURCE_GAUGE_INTERVAL_MS]产出
     * resource.snapshot三条（subscription/controller/editor各一条），只带[Resources.snapshot]
     * 当前值——绝不推导泄漏或JVM内存归属。禁采/run未建立时不产事实（recorder准入本就拒绝）。
     */
    private suspend fun resourceGaugeLoop() {
        while (!stoppedOnce.get()) {
            if (runActive) recordResourceSnapshot()
            delay(RESOURCE_GAUGE_INTERVAL_MS)
        }
    }

    private fun recordResourceSnapshot() {
        val recorder = activeRecorder ?: return
        resourceSnapshotDrafts(resources.snapshot()).forEach { draft -> recorder.record(draft) }
    }

    // ---- 后台核心初始化（文件IO始终在stateLock外；getter不参与） --------------------

    private fun prepare() {
        runMode = modeSource()
        scopeId = scopeStore.loadOrCreate()
        val identity = ProducerEnvironment.snapshot(runMode, deviceStore.loadOrCreate(), PROVIDER_UNKNOWN)
        val store = PolicyStore(controlPath(), { clock.wall() }, pollIntervalMs)
        policies = store
        synchronized(stateLock) {
            baseIdentity = identity.copy(connectionProvider = connectionProviderHint)
        }
        // stop不等待持久化；初始化迟到完成时仍须关闭新创建的策略轮询。
        if (stoppedOnce.get()) store.close()
    }

    // ---- 路径与草稿 -------------------------------------------------------------

    /** 追加协议outbox目录（§5.2）：`~/.costrict/telemetry/outbox/`，平铺单层jsonl事实文件。 */
    private fun outboxDir(): Path = telemetryHome.resolve(OUTBOX_DIR)

    /** 单一命名入口（§5.2）：IDE范围事实文件`<scope-id>.jsonl`。 */
    private fun fileName(): String = "$scopeId.jsonl"

    /** 删除平铺outbox内同scope的旧producer文件，不读取、合并或改名遗留数据。 */
    private fun clearLegacy() {
        val outbox = outboxDir()
        if (!Files.isDirectory(outbox)) return
        runCatching {
            Files.list(outbox).use { files ->
                files.filter { path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        path.fileName.toString().startsWith("$scopeId-pr-") &&
                        path.fileName.toString().endsWith(".jsonl")
                }.forEach { path -> runCatching { Files.deleteIfExists(path) } }
            }
        }
    }

    private fun controlPath(): Path = telemetryHome.resolve(CONTROL_DIR).resolve(CONTROL_FILE)

    private fun startedDraft(): Draft =
        Draft(NAME_STARTED, KIND_LIFECYCLE, CHANNEL_CRITICAL, JsonObject(emptyMap()))

    companion object {
        /** 测试工厂（KiloBackendAppService同型）：注入路径/时钟/scope-id/运行模式来源，不触平台。 */
        @Suppress("LongParameterList")
        internal fun create(
            scope: CoroutineScope,
            modeSource: () -> RunMode,
            scopeStore: ScopeIdStore,
            telemetryHome: Path,
            deviceStore: DeviceIdStore,
            clock: Clock,
            pollIntervalMs: Long,
            awaitActiveHook: (Writer) -> Boolean = ::defaultAwaitActive,
        ) = StabilityService(
            scope,
            modeSource,
            scopeStore,
            telemetryHome,
            deviceStore,
            clock,
            pollIntervalMs,
            awaitActiveHook,
        )
    }
}
