package ai.kilocode.cscloud

import ai.kilocode.backend.app.SseEvent
import ai.kilocode.backend.cli.KiloCliDataParser
import ai.kilocode.log.KiloLog
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Owns one long-lived cs-cloud event stream. */
class CsCloudSseClient(
    private val http: OkHttpClient,
    private val base: String,
    private val workspace: Path?,
    private val log: KiloLog,
    private val onOpen: () -> Unit,
    private val onEvent: (SseEvent) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: (Throwable?, Int?) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    @Volatile private var source: EventSource? = null
    @Volatile private var closed = false

    fun start() {
        synchronized(lock) {
            if (closed || source != null) return
            val req = Request.Builder()
                .url("${base.trimEnd('/')}/api/v1/events")
                .header("Accept", "text/event-stream")
                .apply {
                    workspace?.let { header("X-Workspace-Directory", it.toAbsolutePath().normalize().toString()) }
                }
                .build()
            val factory = EventSources.createFactory(
                http.newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val next = factory.newEventSource(req, listener)
            source = next
            log.debug { "cs-cloud SSE connecting path=${req.url.encodedPath}" }
        }
    }

    fun close() {
        val next = synchronized(lock) {
            closed = true
            source.also { source = null }
        }
        next?.cancel()
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(src: EventSource, response: Response) {
            if (!isCurrent(src)) return
            onOpen()
        }

        override fun onEvent(src: EventSource, id: String?, type: String?, data: String) {
            if (!isCurrent(src) || !accept(data)) return
            val kind = type?.trim()?.takeIf { it.isNotEmpty() } ?: infer(data)
            onEvent(SseEvent(kind, data))
        }

        override fun onClosed(src: EventSource) {
            if (!clear(src)) return
            onClosed()
        }

        override fun onFailure(src: EventSource, t: Throwable?, response: Response?) {
            if (!clear(src)) return
            onFailure(t, response?.code)
        }
    }

    private fun isCurrent(src: EventSource): Boolean = synchronized(lock) {
        !closed && source === src
    }

    private fun clear(src: EventSource): Boolean = synchronized(lock) {
        if (source !== src) return false
        source = null
        true
    }

    /** Host file events are global; only forward events scoped to the active project. */
    private fun accept(data: String): Boolean {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return true
        val payload = root["payload"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val kind = infer(data)
        if (!kind.startsWith("host.")) return true
        val dir = sequenceOf(
            root["directory"]?.jsonPrimitive?.contentOrNull(),
            payload?.get("directory")?.jsonPrimitive?.contentOrNull(),
            payload?.get("properties")?.let { runCatching { it.jsonObject["directory"]?.jsonPrimitive?.contentOrNull() }.getOrNull() },
        ).filterNotNull().firstOrNull() ?: return true
        val rootPath = workspace?.toAbsolutePath()?.normalize() ?: return true
        val eventPath = runCatching { Path.of(dir).toAbsolutePath().normalize() }.getOrNull() ?: return false
        return eventPath == rootPath || eventPath.startsWith(rootPath)
    }

    private fun infer(data: String): String {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
        val payload = root?.get("payload")?.let { runCatching { it.jsonObject }.getOrNull() }
        return payload?.get("type")?.jsonPrimitive?.contentOrNull()
            ?: root?.get("type")?.jsonPrimitive?.contentOrNull()
            ?: KiloCliDataParser.extractEventType(data)
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()
