// kilocode_change - new file
package ai.kilocode.backend.app

import ai.kilocode.log.KiloLog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

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
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
                val fs = LocalFileSystem.getInstance()
                val file = fs.refreshAndFindFileByPath(path.toString())
                    ?: fs.refreshAndFindFileByPath(root.toString())
                if (file == null) {
                    log.debug { "VFS refresh skipped: path and workspace not found path=$path" }
                    return@executeOnPooledThread
                }
                ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) {
                        runCatching { file.refresh(true, true) }
                            .onFailure { log.warn("VFS refresh failed path=$path", it) }
                    }
                }, ModalityState.nonModal())
            } catch (error: Exception) {
                // VFS failures must not affect session/app state. They are actionable diagnostics
                // for the log, while the next host event can still schedule another refresh.
                log.warn("VFS refresh failed path=$path", error)
            }
        }
    }

}
