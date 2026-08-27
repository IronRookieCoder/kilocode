package ai.kilocode.backend.vfs

import ai.kilocode.connection.BackendEvent
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.SessionStatusEventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Refreshes the IntelliJ VFS when the agent writes files (design §2.1.6):
 *  - `host.file.*` events refresh the reported file paths plus their workspace
 *  - a `session.status` idle event refreshes the session's directory so the
 *    project tree, editors and diff views pick up the final on-disk state
 */
class KiloBackendVfsRefresher(
    private val cs: CoroutineScope,
    private val events: SharedFlow<BackendEvent>,
    private val sessionDirectory: (String) -> String?,
    private val log: KiloLog,
    private val refresh: (List<Path>) -> Unit = VfsRefresh::refresh,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = cs.launch {
            events.collect { event -> handle(event) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun handle(event: BackendEvent) {
        when {
            event.type.startsWith("host.file.") -> refreshFiles(event.data)
            event.type == "session.status" -> refreshOnIdle(event.data)
        }
    }

    private fun refreshFiles(data: String) {
        val paths = filePaths(data)
        if (paths.isEmpty()) return
        log.debug { "vfs-refresh host.file paths=${paths.size}" }
        refresh(paths)
    }

    private fun refreshOnIdle(data: String) {
        val event = runCatching { json.decodeFromString(SessionStatusEventDto.serializer(), data) }.getOrNull()
            ?: return
        if (event.status.type != "idle") return
        val dir = sessionDirectory(event.sessionID) ?: return
        log.debug { "vfs-refresh session idle dir=$dir" }
        refresh(listOf(Path.of(dir)))
    }

    private fun filePaths(data: String): List<Path> {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        val payload = root["payload"]?.jsonObject
        val result = mutableListOf<Path>()
        val path = stringValue(root["path"] ?: payload?.get("path"))
        val directory = stringValue(root["directory"] ?: payload?.get("directory"))
        path?.takeIf { it.isNotBlank() }?.let { result.add(Path.of(it)) }
        directory?.takeIf { it.isNotBlank() }?.let { result.add(Path.of(it)) }
        return result
    }

    private fun stringValue(element: JsonElement?): String? =
        runCatching { element?.jsonPrimitive?.contentOrNull }.getOrNull()
}
