@file:Suppress("UnstableApiUsage")

package ai.kilocode.client.app

import ai.kilocode.rpc.KiloWorkspaceRpcApi
import ai.kilocode.rpc.dto.ConfigTargetDto
import ai.kilocode.rpc.dto.DiffFileDto
import ai.kilocode.rpc.dto.FileSearchResultDto
import ai.kilocode.rpc.dto.KiloWorkspaceStateDto
import ai.kilocode.rpc.dto.KiloWorkspaceStatusDto
import ai.kilocode.rpc.dto.LoadErrorDto
import ai.kilocode.rpc.dto.ModelsWorkspaceDto
import ai.kilocode.rpc.dto.WorkspaceFileDto
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.components.Service
import ai.kilocode.log.KiloLog
import com.intellij.platform.project.ProjectId
import ai.kilocode.stability.Operations
import ai.kilocode.stability.StabilityService
import ai.kilocode.stability.rpc
import com.intellij.openapi.components.service
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-level service that manages [Workspace] instances keyed by directory.
 *
 * Multiple projects sharing the same directory share the same [Workspace]
 * and its state flow. Directory resolution handles split-mode where the
 * frontend sees a synthetic path that must be resolved to the real path
 * on the backend host.
 */
@Service(Service.Level.APP)
class KiloWorkspaceService internal constructor(
    private val cs: CoroutineScope,
    private val rpc: KiloWorkspaceRpcApi?,
    // 稳定性采集入口（C2/M19）：生产经平台构造器注入；null=采集不可用，业务照常（B2注入模式）。
    private val operations: Operations? = null,
) {
    /** Platform constructor — resolves RPC from the service container. */
    constructor(cs: CoroutineScope) : this(cs, null, service<StabilityService>().operations)

    companion object {
        private val LOG = KiloLog.create(KiloWorkspaceService::class.java)
        private val INIT = KiloWorkspaceStateDto(KiloWorkspaceStatusDto.PENDING)

        /** M19方法组受控词表（metrics"前后端通信"维度）：工作区数据与配置读写两组。 */
        private const val GROUP_WORKSPACE = "workspace"
        private const val GROUP_CONFIG = "config"
    }

    private val workspaces = ConcurrentHashMap<String, Workspace>()
    internal val localConfig = ConcurrentHashMap<String, ConfigTargetDto>()
    private val pendingLocal = ConcurrentHashMap.newKeySet<String>()
    private val pendingGlobal = AtomicBoolean(false)

    @Volatile
    internal var globalConfig: ConfigTargetDto? = null
        private set

    // ------ RPC helpers ------

    /**
     * M19（C2）：wrapper置于durable{}内每次实际调用处——durable重连重试重新执行lambda时
     * 各自形成独立attempt；直连RPC（split模式注入api）同样按次观测。长寿命Flow订阅
     * （[stream]/[workspace]的stateIn收集）不观测，绝不把整个订阅时长当RPC。
     */
    private suspend fun <T> call(group: String, block: suspend KiloWorkspaceRpcApi.() -> T): T {
        val api = rpc
        return if (api != null) {
            observed(group) { block(api) }
        } else {
            durable { observed(group) { block(KiloWorkspaceRpcApi.getInstance()) } }
        }
    }

    /** operations未注入时零开销直连；注入后每次实际调用一个rpc操作（成功仅metrics出口）。 */
    private suspend fun <T> observed(group: String, block: suspend () -> T): T {
        val observer = operations ?: return block()
        return observer.rpc(group) { block() }
    }

    private fun <T> stream(block: suspend KiloWorkspaceRpcApi.() -> Flow<T>): Flow<T> = flow {
        val api = rpc
        if (api != null) block(api).collect { emit(it) }
        else durable { block(KiloWorkspaceRpcApi.getInstance()).collect { emit(it) } }
    }

    // ------ Public API ------

    /**
     * Get or create a [Workspace] for [directory].
     *
     * Synchronous — returns immediately. The workspace's [Workspace.state]
     * flow starts streaming lazily when first collected. Multiple callers
     * for the same directory share the same instance.
     */
    fun workspace(directory: String): Workspace {
        val workspace = workspaces.getOrPut(directory) {
            LOG.info("Creating workspace for $directory")
            val state = stream { state(directory) }
                .stateIn(cs, SharingStarted.Eagerly, INIT)
            Workspace(directory, state, { reload(directory) }) { refreshConfigFiles(directory) }
        }
        // Refresh on every workspace access so config actions reflect file system changes.
        refreshLocalConfigTarget(directory)
        refreshGlobalConfigTarget()
        return workspace
    }

    /**
     * Resolve the real project directory from a hint path.
     *
     * In split-mode the frontend sees a synthetic path (e.g.
     * `/home/.cache/JetBrains/RemoteDev/...`). The backend resolves
     * it to the actual project root on the host.
     */
    suspend fun resolveProjectDirectory(projectId: ProjectId?, hint: String): String {
        return try {
            val resolved = call(GROUP_WORKSPACE) { resolveProjectDirectory(projectId, hint) }
            LOG.info("Resolved project directory: projectId=$projectId hint=$hint -> $resolved")
            resolved
        } catch (e: Exception) {
            LOG.warn("Failed to resolve directory, falling back to hint=$hint", e)
            hint
        }
    }

    /** Trigger a full reload of workspace data for [directory]. */
    fun reload(directory: String) {
        cs.launch {
            try {
                call(GROUP_WORKSPACE) { reload(directory) }
            } catch (e: Exception) {
                LOG.warn("workspace reload failed for $directory", e)
            }
        }
    }

    suspend fun models(directory: String): ModelsWorkspaceDto {
        return try {
            call(GROUP_WORKSPACE) { this.models(directory) }
        } catch (e: Exception) {
            LOG.warn("models settings lookup failed for directory=$directory", e)
            ModelsWorkspaceDto(errors = listOf(LoadErrorDto(resource = "models", detail = e.message)))
        }
    }

    suspend fun files(directory: String, path: String): List<WorkspaceFileDto> {
        return try {
            call(GROUP_WORKSPACE) { files(directory, path) }
        } catch (e: Exception) {
            LOG.warn("workspace file lookup failed for directory=$directory path=$path", e)
            emptyList()
        }
    }

    suspend fun searchFiles(directory: String, query: String, limit: Int = 50): FileSearchResultDto {
        LOG.debug { "workspace file search directory=$directory query=$query limit=$limit" }
        return try {
            call(GROUP_WORKSPACE) { searchFiles(directory, query, limit) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("workspace file search failed for directory=$directory query=$query", e)
            FileSearchResultDto()
        }
    }

    suspend fun gitChanges(directory: String): String? {
        return try {
            call(GROUP_WORKSPACE) { gitChanges(directory) }
        } catch (e: Exception) {
            LOG.warn("git changes lookup failed for directory=$directory", e)
            null
        }
    }

    /**
     * Committed branch changes vs the default-branch merge-base. Errors propagate so the diff editor
     * can surface a retry (a swallowed failure is indistinguishable from "no changes"); pass
     * [patches] = false on the badge path to fetch stats only and skip materializing patch text.
     */
    suspend fun branchDiff(directory: String, patches: Boolean = true): List<DiffFileDto> =
        call(GROUP_WORKSPACE) { branchDiff(directory, patches) }

    suspend fun branchName(directory: String): String? {
        return try {
            call(GROUP_WORKSPACE) { branchName(directory) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("branch name lookup failed for directory=$directory", e)
            null
        }
    }

    suspend fun openPath(directory: String, path: String, line: Int? = null, column: Int? = null, endLine: Int? = null): Boolean {
        val match = files(directory, path).firstOrNull() ?: return false
        return try {
            call(GROUP_WORKSPACE) { openFile(match.path, line, column, endLine) }
        } catch (e: Exception) {
            LOG.warn("workspace file open failed for path=${match.path}", e)
            false
        }
    }

    suspend fun openFile(path: String, line: Int? = null, column: Int? = null, endLine: Int? = null): Boolean {
        return try {
            call(GROUP_WORKSPACE) { openFile(path, line, column, endLine) }
        } catch (e: Exception) {
            LOG.warn("workspace file open failed for path=$path", e)
            false
        }
    }

    suspend fun localConfigTarget(directory: String): ConfigTargetDto? {
        return try {
            val target = call(GROUP_CONFIG) { this.localConfigTarget(directory) }
            localConfig[directory] = target
            target
        } catch (e: Exception) {
            LOG.warn("local config lookup failed for directory=$directory", e)
            localConfig[directory]
        }
    }

    suspend fun globalConfigTarget(): ConfigTargetDto? {
        return try {
            val target = call(GROUP_CONFIG) { this.globalConfigTarget() }
            globalConfig = target
            target
        } catch (e: Exception) {
            LOG.warn("global config lookup failed", e)
            globalConfig
        }
    }

    fun refreshLocalConfigTarget(directory: String): Job? {
        if (!pendingLocal.add(directory)) return null

        return cs.launch {
            try {
                localConfigTarget(directory)
            } finally {
                pendingLocal.remove(directory)
                ActivityTracker.getInstance().inc()
            }
        }
    }

    fun refreshGlobalConfigTarget(): Job? {
        if (!pendingGlobal.compareAndSet(false, true)) return null

        return cs.launch {
            try {
                globalConfigTarget()
            } finally {
                pendingGlobal.set(false)
                ActivityTracker.getInstance().inc()
            }
        }
    }

    fun refreshConfigFiles(directory: String): Job {
        return cs.launch {
            try {
                call(GROUP_CONFIG) { refreshConfigFiles(directory) }
                localConfigTarget(directory)
                globalConfigTarget()
            } catch (e: Exception) {
                LOG.warn("config file refresh failed for directory=$directory", e)
            } finally {
                ActivityTracker.getInstance().inc()
            }
        }
    }

    fun openLocalConfig(directory: String, done: (Boolean) -> Unit) {
        cs.launch {
            val ok = try {
                call(GROUP_CONFIG) { this.openLocalConfig(directory) }
            } catch (e: Exception) {
                LOG.warn("local config open failed for directory=$directory", e)
                false
            }
            done(ok)
        }
    }

    fun openGlobalConfig(done: (Boolean) -> Unit) {
        cs.launch {
            val ok = try {
                call(GROUP_CONFIG) { this.openGlobalConfig() }
            } catch (e: Exception) {
                LOG.warn("global config open failed", e)
                false
            }
            done(ok)
        }
    }

}
