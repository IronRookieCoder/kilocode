// kilocode_change - new file
package ai.kilocode.backend.app

import ai.kilocode.log.KiloLog
import ai.kilocode.stability.Operation
import ai.kilocode.stability.Operations
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

internal const val IDE_OPERATION_NAME = "ide.operation"
internal const val IDE_OPERATION_DEADLINE_MS = 30_000L

private const val OPERATION_VFS_REFRESH = "vfs_refresh"
private const val STAGE_REFRESH = "refresh"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILURE = "failure"
private const val RESULT_CANCELLED = "cancelled"
private const val CAUSE_IDE = "ide"
private const val CAUSE_ENVIRONMENT = "environment"
private const val CAUSE_USER = "user"
private const val CODE_REFRESH = "refresh_failed"
private const val CODE_PATH_NOT_FOUND = "path_not_found"

/**
 * M23 vfs_refresh观测句柄（C5）：一次已调度的路径刷新对应一个操作。begin在真实处理器内部的
 * 调度点（前端调用方与本类绝不双计）；end只在postRunnable完成信号——`file.refresh`在EDT回调里
 * 真正完成后结算success，绝不在refresh(true,...)调度返回时提前success。异步链路上的早退
 * （项目已关闭→cancelled；路径与工作区都未找到→failure=environment）与真实刷新异常
 * （→failure=ide）各自结算唯一终态（Operation的CAS保证晚到信号不二次end）。
 * operations为null（采集未接入）时全程no-op，业务照常。
 */
internal class VfsRefreshObservation(operations: Operations?) {
    private val operation: Operation? = operations?.begin(
        IDE_OPERATION_NAME,
        IDE_OPERATION_DEADLINE_MS,
        buildJsonObject { put("operation", OPERATION_VFS_REFRESH) },
    )

    fun succeeded() {
        operation?.end(RESULT_SUCCESS, STAGE_REFRESH)
    }

    fun failed(cause: String, code: String) {
        operation?.end(RESULT_FAILURE, STAGE_REFRESH, cause, code)
    }

    fun cancelled() {
        operation?.end(RESULT_CANCELLED, STAGE_REFRESH, CAUSE_USER)
    }
}

/**
 * Refreshes IntelliJ's VFS after cs-cloud writes files in the active project.
 *
 * File-system work is deliberately split across IntelliJ's pooled-thread and EDT boundaries:
 * locating the file is done off the EDT, while the retained virtual file is refreshed on the EDT.
 * An event outside [root] is ignored before any filesystem operation is scheduled.
 */
class KiloBackendWorkspaceRefresh(
    private val project: Project,
    workspaceRoot: String,
    private val log: KiloLog = KiloLog.create(KiloBackendWorkspaceRefresh::class.java),
    // M23（C5）：采集入口来源（生产为StabilityService，测试注入fixture）；null时本次不记录。
    private val operations: Operations? = null,
) {
    companion object {
        internal fun paths(root: Path, event: SseEvent): List<Path> {
            val values = when {
                event.type.startsWith("host.file.") -> fields(event.data, listOf("path", "new_path", "old_path", "from", "to"))
                event.type == "session.idle" -> fields(event.data, listOf("directory", "workspace", "path"))
                    .take(1)
                    .ifEmpty { listOf(root.toString()) }
                else -> return emptyList()
            }
            return values.mapNotNull { value ->
                val target = runCatching { canonical(value) }.getOrNull() ?: return@mapNotNull null
                target.takeIf { it == root || it.startsWith(root) }
            }.distinct()
        }

        private fun fields(data: String, names: List<String>): List<String> = objects(data).flatMap { obj ->
            names.mapNotNull { key -> obj[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }
        }

        /** Return the root object plus nested payload/properties objects used by both event dialects. */
        private fun objects(data: String): List<JsonObject> {
            val root = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
            val payload = root["payload"]?.obj()
            val properties = root["properties"]?.obj()
            val nested = payload?.get("properties")?.obj()
            return listOfNotNull(root, payload, properties, nested).distinct()
        }

        private fun JsonElement.obj() = runCatching { jsonObject }.getOrNull()

        private fun canonical(value: String): Path {
            val path = Path.of(value).toAbsolutePath().normalize()
            return if (Files.exists(path)) runCatching { path.toRealPath() }.getOrDefault(path) else path
        }
    }

    constructor(project: Project, workspaceRoot: Path, log: KiloLog = KiloLog.create(KiloBackendWorkspaceRefresh::class.java)) :
        this(project, workspaceRoot.toString(), log)
    private val root = canonical(workspaceRoot)

    /** Consume one normalized backend event. */
    fun handle(event: SseEvent) {
        if (project.isDisposed) return
        paths(root, event).forEach(::schedule)
    }

    /** Alias useful for event collectors that model handlers as acceptors. */
    fun accept(event: SseEvent) = handle(event)

    fun onEvent(event: SseEvent) = handle(event)

    private fun schedule(path: Path) {
        // M23（C5）：调度点开一个vfs_refresh操作，postRunnable完成信号结算（见VfsRefreshObservation）。
        val refresh = VfsRefreshObservation(operations)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) {
                refresh.cancelled()
                return@executeOnPooledThread
            }
            try {
                val fs = LocalFileSystem.getInstance()
                val file = fs.refreshAndFindFileByPath(path.toString())
                    ?: fs.refreshAndFindFileByPath(root.toString())
                if (file == null) {
                    refresh.failed(CAUSE_ENVIRONMENT, CODE_PATH_NOT_FOUND)
                    log.debug { "VFS refresh skipped: path and workspace not found path=$path" }
                    return@executeOnPooledThread
                }
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) {
                        refresh.cancelled()
                        return@invokeLater
                    }
                    runCatching { file.refresh(true, true) }
                        .onSuccess { refresh.succeeded() }
                        .onFailure {
                            refresh.failed(CAUSE_IDE, CODE_REFRESH)
                            log.warn("VFS refresh failed path=$path", it)
                        }
                }, ModalityState.nonModal())
            } catch (error: Exception) {
                refresh.failed(CAUSE_IDE, CODE_REFRESH)
                // VFS failures must not affect session/app state. They are actionable diagnostics
                // for the log, while the next host event can still schedule another refresh.
                log.warn("VFS refresh failed path=$path", error)
            }
        }
    }

}
