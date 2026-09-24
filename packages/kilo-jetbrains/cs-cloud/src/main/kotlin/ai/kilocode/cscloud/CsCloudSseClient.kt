package ai.kilocode.cscloud

import ai.kilocode.backend.app.SseEvent
import ai.kilocode.backend.cli.KiloCliDataParser
import ai.kilocode.log.KiloLog
import ai.kilocode.stability.Operations
import ai.kilocode.stability.DiagnosticInput
import ai.kilocode.stability.ErrorClassifier
import kotlinx.coroutines.CancellationException
import ai.kilocode.stability.ProtocolCode
import ai.kilocode.stability.ProtocolStage
import ai.kilocode.stability.ProtocolTransport
import ai.kilocode.stability.protocolError
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    // 稳定性采集入口（C2/M15）：生产由CsCloudConnectionService传入；null=采集不可用，业务照常。
    private val operations: Operations? = null,
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
            if (!isCurrent(src)) return
            val result = accept(data)
            if (!result.accepted) return
            val kind = type?.trim()?.takeIf { it.isNotEmpty() } ?: result.kind
            onEvent(SseEvent(kind, data, result.observed))
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

    private data class Acceptance(val accepted: Boolean, val observed: Boolean = false, val kind: String = "unknown")
    private data class Envelope(val kind: String, val directory: String?)

    /** Host file events are global; only forward events scoped to the active project. */
    private fun accept(data: String): Acceptance {
        val envelope = runCatching { decode(data) }.getOrElse {
            if (it is CancellationException) throw it
            val error = it
            // M15（C2）：真实SSE解码失败记一次protocol.error后照旧容忍转发（既有行为）。
            // 信封及其type/directory字段由同一解码边界处理一次；正常新增可选字段被
            // 容忍；失败原文经诊断许可过滤后落盘，observed防止下游再重复计数。
            val observed = operations?.let {
                val info = ErrorClassifier.classify(error)
                val input = DiagnosticInput.error("sse.decode", info.code, error,
                    attributes = info.attributes() + mapOf(
                        "method" to "GET", "route" to "/api/v1/events",
                        "http_status" to "200", "content_type" to "text/event-stream",
                    ),
                    payloads = mapOf("response" to { data }),
                )
                val incident = it.report(input)
                it.protocolError(
                    ProtocolTransport.SSE, ProtocolStage.DECODE, ProtocolCode.DECODE_FAILED,
                    input.context + ("incident_id" to incident),
                )
                true
            } ?: false
            return Acceptance(accepted = true, observed = observed, kind = KiloCliDataParser.extractEventType(data))
        }
        val kind = envelope.kind
        val dir = envelope.directory ?: return Acceptance(accepted = true, kind = kind)
        val rootPath = workspace?.toAbsolutePath()?.normalize() ?: return Acceptance(accepted = true, kind = kind)
        val eventPath = runCatching { Path.of(dir).toAbsolutePath().normalize() }.getOrNull() ?: run {
            // M15（C2）：host事件的directory违反可解析路径约束（apply违规）；既有丢弃行为不变。
            val observed = operations?.let {
                it.protocolError(ProtocolTransport.SSE, ProtocolStage.APPLY, ProtocolCode.APPLY_VIOLATION)
                true
            } ?: false
            return Acceptance(accepted = false, observed = observed)
        }
        return Acceptance(accepted = eventPath == rootPath || eventPath.startsWith(rootPath), kind = kind)
    }

    /** All envelope reads share one failure boundary, including legal JSON with illegal field shapes. */
    private fun decode(data: String): Envelope {
        val root = json.parseToJsonElement(data).jsonObject
        val payload = root["payload"]?.jsonObject
        val outer = root.text("type")
        val inner = payload?.text("type")
        val kind = inner ?: outer ?: KiloCliDataParser.extractEventType(data)
        if (!kind.startsWith("host.")) return Envelope(kind, null)
        val directories = listOf(
            root.text("directory"), payload?.text("directory"),
            (payload ?: root)["properties"]?.jsonObject?.text("directory"),
        )
        return Envelope(kind, directories.firstOrNull { it != null })
    }
}

private fun JsonObject.text(key: String): String? {
    val value = get(key) ?: return null
    if (value is JsonPrimitive && value.isString) return value.content
    throw SerializationException("SSE envelope field must be a string")
}
