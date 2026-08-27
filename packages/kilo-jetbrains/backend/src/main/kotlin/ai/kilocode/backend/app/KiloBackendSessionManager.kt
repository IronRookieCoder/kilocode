package ai.kilocode.backend.app

import ai.kilocode.connection.BackendEvent
import ai.kilocode.connection.Transport
import ai.kilocode.log.ChatLogSummary
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.CloudSessionListDto
import ai.kilocode.rpc.dto.SessionDto
import ai.kilocode.rpc.dto.SessionListDto
import ai.kilocode.rpc.dto.SessionStatusDto
import ai.kilocode.rpc.dto.SessionStatusEventDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Session gateway that handles session CRUD and live status tracking
 * across all directories (workspace roots and worktrees).
 *
 * **Not an IntelliJ service** — owned by [KiloBackendAppService] which
 * calls [start] after the backend connection reaches [KiloAppState.Ready]
 * and [stop] on disconnect. The transport is guaranteed non-null between
 * start/stop — no defensive null checks in CRUD methods.
 *
 * `session.status` events are consumed directly from the events flow
 * passed to [start], keeping the live [statuses] map current.
 */
class KiloBackendSessionManager(
    private val cs: CoroutineScope,
    private val log: KiloLog,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Per-session directory overrides (sessionId → worktree path). */
    private val directories = ConcurrentHashMap<String, String>()

    /** Session directory cache populated while mapping sessions. */
    private val owned = ConcurrentHashMap<String, String>()

    private val _statuses = MutableStateFlow<Map<String, SessionStatusDto>>(emptyMap())
    val statuses: StateFlow<Map<String, SessionStatusDto>> = _statuses.asStateFlow()

    private var transport: Transport? = null
    private var watcher: Job? = null

    fun start(transport: Transport, events: SharedFlow<BackendEvent>) {
        this.transport = transport
        if (watcher?.isActive == true) return
        watcher = cs.launch {
            events.collect { event ->
                if (event.type == "session.status") {
                    val pair = parseStatusEvent(event.data)
                    if (pair != null) {
                        val prev = _statuses.value[pair.first]
                        _statuses.update { it + pair }
                        val total = _statuses.value.size
                        log.debug { "${ChatLogSummary.sid(pair.first)} evt=session.status ${ChatLogSummary.status(pair.second)}" }
                        if (pair.second.type != "busy") {
                            log.info(
                                "${ChatLogSummary.sid(pair.first)} kind=status route=session-map " +
                                    "${ChatLogSummary.status(pair.second)} prev=${prev?.type ?: "none"} total=$total bytes=${event.data.length}",
                            )
                        }
                    }
                }
            }
        }
        log.info("Session manager started")
    }

    fun stop() {
        val active = _statuses.value.filterValues { it.type != "idle" }
        if (active.isNotEmpty()) {
            log.warn("Session manager stopping with active sessions count=${active.size} statuses=${active.values.map { it.type }.distinct()}")
        }
        watcher?.cancel()
        watcher = null
        transport?.close()
        transport = null
        owned.clear()
        _statuses.value = emptyMap()
        log.info("Session manager stopped")
    }

    private fun requireTransport(): Transport =
        transport ?: throw IllegalStateException("Session manager not started")

    // ------ session CRUD ------

    suspend fun list(dir: String): SessionListDto {
        seed(dir)
        val raw = requireTransport().call("GET", "/session?" + query("directory" to dir, "roots" to "true"))
        val mapped = json.decodeFromString(ListSerializer(SessionDto.serializer()), raw)
        return sessionList(mapped)
    }

    suspend fun recent(dir: String, limit: Int): SessionListDto {
        seed(dir)
        val raw = requireTransport().call(
            "GET",
            "/session?" + query(
                "directory" to dir,
                "worktrees" to "true",
                "roots" to "true",
                "limit" to limit.toString(),
                "archived" to "false",
            ),
        )
        val mapped = json.decodeFromString(ListSerializer(SessionDto.serializer()), raw)
        return sessionList(mapped)
    }

    suspend fun create(dir: String): SessionDto {
        val t = requireTransport()
        log.info("Creating session: POST /session?directory=" + encode(dir))
        val raw = t.call("POST", "/session?" + query("directory" to dir), "{}")
        val dto = json.decodeFromString(SessionDto.serializer(), raw)
        val meta = if (log.isDebugEnabled) ChatLogSummary.dir(dir) else "kind=session"
        log.info("${ChatLogSummary.sid(dto.id)} kind=session $meta created=true")
        owned[dto.id] = dto.directory
        return dto
    }

    suspend fun get(id: String, dir: String): SessionDto {
        val raw = requireTransport().call("GET", "/session?" + query("directory" to dir))
        val all = json.decodeFromString(ListSerializer(SessionDto.serializer()), raw)
        return all.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Session $id not found")
    }

    suspend fun delete(id: String, dir: String) {
        requireTransport().call("DELETE", "/session/$id?" + query("directory" to dir))
        directories.remove(id)
        owned.remove(id)
    }

    suspend fun rename(id: String, dir: String, title: String): SessionDto {
        val raw = requireTransport().call(
            "PATCH",
            "/session/$id?" + query("directory" to dir),
            json.encodeToString(MapSerializer(String.serializer(), String.serializer()), mapOf("title" to title)),
        )
        val dto = json.decodeFromString(SessionDto.serializer(), raw)
        owned[dto.id] = dto.directory
        return dto
    }

    suspend fun cloudSessions(dir: String, cursor: String?, limit: Int, gitUrl: String?): CloudSessionListDto {
        val raw = requireTransport().call(
            "GET",
            "/kilo/cloud-sessions?" + query(
                listOfNotNull(
                    "directory" to dir,
                    cursor?.let { "cursor" to it },
                    "limit" to limit.toString(),
                    gitUrl?.let { "gitUrl" to it },
                ),
            ),
        )
        return json.decodeFromString(CloudSessionListDto.serializer(), raw)
    }

    suspend fun importCloudSession(id: String, dir: String): SessionDto {
        val raw = requireTransport().call(
            "POST",
            "/kilo/cloud/session/import?" + query("directory" to dir),
            json.encodeToString(MapSerializer(String.serializer(), String.serializer()), mapOf("sessionId" to id)),
        )
        val dto = json.decodeFromString(SessionDto.serializer(), raw)
        owned[dto.id] = dto.directory
        return dto
    }

    suspend fun seed(dir: String) {
        try {
            val raw = requireTransport().call("GET", "/session/status?" + query("directory" to dir))
            val mapped = json.decodeFromString(
                MapSerializer(String.serializer(), SessionStatusDto.serializer()),
                raw,
            )
            _statuses.update { it + mapped }
            val meta = if (log.isDebugEnabled) ChatLogSummary.dir(dir) else "kind=status"
            log.info("kind=status $meta seeded=" + mapped.size)
        } catch (e: Exception) {
            log.warn("kind=status dir=${ChatLogSummary.dir(dir)} seed=true failed message=${e.message}", e)
        }
    }

    // ------ worktree directory management ------

    fun setDirectory(id: String, dir: String) {
        directories[id] = dir
    }

    fun getDirectory(id: String, fallback: String): String =
        directories[id] ?: fallback

    fun sessionDirectory(id: String): String? =
        directories[id] ?: owned[id]

    // ------ helpers ------

    private fun sessionList(mapped: List<SessionDto>): SessionListDto {
        mapped.forEach { owned[it.id] = it.directory }
        val ids = mapped.map { it.id }.toSet()
        val relevant = _statuses.value.filterKeys { it in ids }
        return SessionListDto(mapped, relevant)
    }

    private fun parseStatusEvent(data: String): Pair<String, SessionStatusDto>? = try {
        val event = json.decodeFromString(SessionStatusEventDto.serializer(), data)
        event.sessionID to event.status
    } catch (e: Exception) {
        log.warn("session.status event decode failed: ${e.message}")
        null
    }

    private fun query(vararg params: Pair<String, String>) = query(params.toList())

    private fun query(params: List<Pair<String, String>>) =
        params.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
