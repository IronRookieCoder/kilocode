package ai.kilocode.stability

import ai.kilocode.KiloPlugin
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.platform.ide.productMode.IdeProductMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MODE_MONOLITH = "monolith"
private const val MODE_SPLIT = "split"
private const val SIDE_MONOLITH = "monolith"
private const val SIDE_FRONTEND = "frontend"
private const val SIDE_BACKEND = "backend"
private const val ENV_PROD = "prod"
private const val ENV_DEV = "dev"
private const val ENV_TEST = "test"
private const val PROVIDER_CS_CLOUD = "cs-cloud"
private const val PROVIDER_KILO_CLI = "kilo-cli"
private const val PROVIDER_UNKNOWN = "unknown"
private const val VALUE_UNKNOWN = "unknown"
private const val PRODUCER_PREFIX = "pr-"
private const val RUN_PREFIX = "run-"
private const val DEVICE_PREFIX = "device-"
private const val WORKSPACE_PREFIX = "ws-"
private const val RANDOM_ID_CHARS = 12
private const val DEVICE_SETTING_KEY = "ai.kilocode.stability.device.id"
private const val SCOPE_SETTING_KEY = "ai.kilocode.stability.scope.id"

/** 内部随机短ID：UUID去连字符取前12个十六进制字符。 */
internal fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(RANDOM_ID_CHARS)

/**
 * 运行模式（设计5.1/6.1的mode/side取值）。唯一来源是平台的[IdeProductMode]——
 * 与AdvancedSettingsUi等既有单体判定同一来源；绝不按"谁先调用start"推断，
 * 单体的frontend/backend双入口不会被误标成split。
 */
data class RunMode(val mode: String, val side: String)

/** 平台运行模式的唯一裁决入口；平台状态不可得时保守回落split/backend（失败关闭，不推定单体）。 */
object PlatformRunMode {
    fun current(): RunMode = runCatching {
        when {
            IdeProductMode.isMonolith -> RunMode(MODE_MONOLITH, SIDE_MONOLITH)
            IdeProductMode.isFrontend -> RunMode(MODE_SPLIT, SIDE_FRONTEND)
            else -> RunMode(MODE_SPLIT, SIDE_BACKEND)
        }
    }.getOrDefault(RunMode(MODE_SPLIT, SIDE_BACKEND))
}

private const val NANOS_PER_MS = 1_000_000L

/** 生产默认时钟：wall=UTC毫秒（判期/时间戳），mono=纳秒折算毫秒（单调，不受回拨影响）。 */
internal object SystemClock : Clock {
    override fun wall(): Long = System.currentTimeMillis()
    override fun mono(): Long = System.nanoTime() / NANOS_PER_MS
}

/**
 * 安装随机标识的持久化（设计5.2：device_id是随机安装标识，重装是否更换取决于IDE持久设置）。
 * 独立持久设置保存，绝不使用workspace路径hash；实现不得抛出。
 */
fun interface DeviceIdStore {
    fun loadOrCreate(): String
}

/**
 * 生产实现：PropertiesComponent独立键持久化；平台应用不可得（纯JVM环境）时退化为
 * 未持久化的随机值并保持静默——宁可本轮身份不持久，也绝不阻塞采集启动。
 */
fun platformDeviceIdStore(): DeviceIdStore = DeviceIdStore {
    runCatching {
        PropertiesComponent.getInstance().getValue(DEVICE_SETTING_KEY)
            ?: (DEVICE_PREFIX + randomId()).also { PropertiesComponent.getInstance().setValue(DEVICE_SETTING_KEY, it) }
    }.getOrElse { DEVICE_PREFIX + randomId() }
}

/**
 * IDE安装范围持久随机标识（设计5.2）：同一IDE多次启动共享，用于识别前任文件
 * （plugin.unclean判定）与同源清理归属；不同IDE安装互不相同。独立存于IDE配置目录，
 * 与device_id分开存储；无法持久化时抛出，由服务边界关闭采集，绝不生成临时scope。
 */
fun interface ScopeIdStore {
    fun loadOrCreate(): String
}

/**
 * 公开PathManager API定位IDE配置目录；首次scope在返回前同步落盘，不依赖设置保存或EDT。
 * 首次创建文件时复用旧PropertiesComponent中的有效scope，只迁移身份，不保存旧设置。
 * 路径只用于本地存储，绝不进入telemetry。文件实现通过ScopeIdStore保持可注入。
 */
fun platformScopeIdStore(): ScopeIdStore {
    val store by lazy {
        FileScopeIdStore(PathManager.getConfigDir()) {
            PropertiesComponent.getInstance().getValue(SCOPE_SETTING_KEY)
        }
    }
    return ScopeIdStore { store.loadOrCreate() }
}

/**
 * workspace到随机ID的项目生命周期映射（设计6.1：项目级随机映射，不用可猜测的路径hash）。
 * 映射只存在于内存；项目关闭时由调用方[forget]，之后同一token取得全新随机ID。
 */
class WorkspaceIds {
    private val ids = ConcurrentHashMap<String, String>()

    fun idFor(token: String): String = ids.computeIfAbsent(token) { WORKSPACE_PREFIX + randomId() }

    fun forget(token: String) {
        ids.remove(token)
    }
}

/**
 * 生产者环境快照（设计5.2/6.1）：ProducerIdentity公共字段的共同来源。
 * 全部平台读取经runCatching，取不到的值固定"unknown"（枚举闭集内绝不留空）；快照在run内不变。
 */
internal object ProducerEnvironment {

    fun snapshot(mode: RunMode, deviceId: String, connectionProvider: String): ProducerIdentity = ProducerIdentity(
        producerId = PRODUCER_PREFIX + randomId(),
        runId = RUN_PREFIX + randomId(),
        deviceId = deviceId,
        pluginVersion = runCatching { KiloPlugin.version() }.getOrNull() ?: VALUE_UNKNOWN,
        ideProduct = appInfo { it.build.productCode },
        ideBuild = appInfo { it.build.asString() },
        ideBuildMajor = appInfo { info -> "${info.majorVersion}.${info.minorVersion}" },
        osFamily = normalizeOs(System.getProperty("os.name")),
        arch = normalizeArch(System.getProperty("os.arch")),
        env = classifyEnv(),
        mode = mode.mode,
        side = mode.side,
        connectionProvider = normalizeProvider(connectionProvider),
    )

    private inline fun appInfo(extract: (ApplicationInfo) -> String): String =
        runCatching { extract(ApplicationInfo.getInstance()) }.getOrElse { VALUE_UNKNOWN }

    private fun normalizeOs(name: String?): String {
        val lower = (name ?: return VALUE_UNKNOWN).lowercase()
        return when {
            lower.contains("windows") -> "windows"
            lower.contains("mac") || lower.contains("darwin") -> "macos"
            lower.contains("linux") -> "linux"
            else -> VALUE_UNKNOWN
        }
    }

    private fun normalizeArch(arch: String?): String {
        val lower = (arch ?: return VALUE_UNKNOWN).lowercase()
        return when {
            lower.contains("aarch64") || lower.contains("arm64") -> "arm64"
            lower.contains("amd64") || lower.contains("x86_64") -> "x64"
            lower.contains("x86") -> "x86"
            else -> VALUE_UNKNOWN
        }
    }

    /** env分类：单元测试运行=test；IDE快照构建或插件版本缺失/SNAPSHOT=dev；其余=prod。 */
    private fun classifyEnv(): String {
        val unitTest = runCatching { ApplicationManager.getApplication()?.isUnitTestMode == true }.getOrDefault(false)
        if (unitTest) return ENV_TEST
        val version = runCatching { KiloPlugin.version() }.getOrNull()
        val devBuild = runCatching { ApplicationInfo.getInstance().build.isSnapshot }.getOrDefault(false)
        return if (devBuild || version.isNullOrEmpty() || version.contains("SNAPSHOT", ignoreCase = true)) {
            ENV_DEV
        } else {
            ENV_PROD
        }
    }

    private fun normalizeProvider(provider: String): String = when (provider) {
        PROVIDER_CS_CLOUD -> PROVIDER_CS_CLOUD
        PROVIDER_KILO_CLI -> PROVIDER_KILO_CLI
        else -> PROVIDER_UNKNOWN
    }
}
