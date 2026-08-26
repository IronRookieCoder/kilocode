package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.nio.file.Path

/** Lifecycle of an open SSE event stream, as seen by the connection service. */
interface CsCloudSseStream {
    fun start()
    fun close()
}

/** SSE stream callbacks delivered on OkHttp's callback thread. */
interface CsCloudSseListener {
    fun onOpen()
    fun onEvent(event: SseEvent)
    fun onClosed()
    fun onFailure(error: Throwable?, status: Int?)
}

/**
 * SSE client for the cs-cloud event stream. Connects to `/api/v1/events` with
 * `X-Workspace-Directory` and `Authorization: Bearer <key>`, normalizes incoming
 * events into [SseEvent], and never lets a malformed or unknown event block the stream.
 */
class CsCloudSseClient(
    private val endpoint: CsCloudEndpoint,
    private val workspace: Path,
    private val http: OkHttpClient,
    private val listener: CsCloudSseListener,
    private val onUnknown: (String) -> Unit = {},
) : CsCloudSseStream {

    private var source: EventSource? = null
    @Volatile
    private var closed = false

    override fun start() {
        val request = Request.Builder()
            .url(endpoint.base + "/api/v1/events")
            .header("X-Workspace-Directory", workspace.toAbsolutePath().normalize().toString())
            .apply { endpoint.key?.let { header("Authorization", "Bearer $it") } }
            .build()
        source = EventSources.createFactory(http).newEventSource(request, listenerAdapter)
    }

    override fun close() {
        closed = true
        source?.cancel()
        source = null
    }

    private val listenerAdapter = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            if (!closed) listener.onOpen()
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (closed) return
            val event = CsCloudSseNormalizer.normalize(type, data, workspace)
            if (event == null) {
                onUnknown("cs-cloud: dropping event type='$type' payload=${data.take(120)}")
                return
            }
            listener.onEvent(event)
        }

        override fun onClosed(eventSource: EventSource) {
            if (!closed) listener.onClosed()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            if (closed) return
            listener.onFailure(t, response?.code)
        }
    }
}

/**
 * Normalizes raw SSE messages into [SseEvent] (design §2.1.2):
 *  - the SSE `event:` field wins when present
 *  - flat events carry `type` at the root
 *  - `GlobalEvent` messages nest the real type under `payload.type`
 *  - `host.file.*` events are only emitted when addressed to [workspace]
 * Returns null for unknown/malformed/out-of-scope payloads; never throws.
 */
object CsCloudSseNormalizer {
    private val json = Json { ignoreUnknownKeys = true }

    fun normalize(eventField: String?, data: String, workspace: Path): SseEvent? {
        val type = inferType(eventField, data) ?: return null
        if (type.startsWith("host.file.") && !belongsToWorkspace(data, workspace)) return null
        return SseEvent(type, data)
    }

    fun inferType(eventField: String?, data: String): String? {
        eventField?.takeIf { it.isNotBlank() }?.let { return it }
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        val rootType = root["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        // `event` is the GlobalEvent wrapper; the real type lives under payload.type.
        if (rootType != null && rootType != "event") return rootType
        root["payload"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return rootType
    }

    /** True when the host-file event targets [workspace] (either its `directory` or `payload.directory`). */
    fun belongsToWorkspace(data: String, workspace: Path): Boolean {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return true
        val directory = (root["directory"] ?: root["payload"]?.jsonObject?.get("directory"))
            ?.jsonPrimitive?.contentOrNull ?: return true
        val target = directory.replace('\\', '/').trimEnd('/').lowercase()
        val current = workspace.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/').lowercase()
        return target == current || target.startsWith("$current/")
    }
}
