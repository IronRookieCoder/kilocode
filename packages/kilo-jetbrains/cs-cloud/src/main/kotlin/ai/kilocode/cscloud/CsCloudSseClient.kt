package ai.kilocode.cscloud

import ai.kilocode.backend.app.SseEvent
import ai.kilocode.backend.cli.KiloCliDataParser
import ai.kilocode.log.KiloLog
import ai.kilocode.stability.Draft
import ai.kilocode.stability.Operations
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

/** M15（C2）protocol.error固定枚举（metrics 3.2/设计第9章词表）。 */
private const val PROTOCOL_ERROR = "protocol.error"
private const val PROTOCOL_STAGE_DECODE = "decode"
private const val PROTOCOL_STAGE_APPLY = "apply"
private const val PROTOCOL_CODE_DECODE_FAILED = "decode_failed"
private const val PROTOCOL_CODE_APPLY_VIOLATION = "apply_violation"

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
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrElse {
            // M15（C2）：真实SSE解码失败记一次protocol.error后照旧容忍转发（既有行为）。
            // 同事件在infer()里的二次解析不重复计数（它不产事实）；正常新增可选字段被
            // ignoreUnknownKeys正常忽略，绝不走这里；原始响应正文不入事实。
            protocolError(PROTOCOL_STAGE_DECODE, PROTOCOL_CODE_DECODE_FAILED)
            return true
        }
        val payload = root["payload"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val kind = infer(data)
        if (!kind.startsWith("host.")) return true
        val dir = sequenceOf(
            root["directory"]?.jsonPrimitive?.contentOrNull(),
            payload?.get("directory")?.jsonPrimitive?.contentOrNull(),
            payload?.get("properties")?.let { runCatching { it.jsonObject["directory"]?.jsonPrimitive?.contentOrNull() }.getOrNull() },
        ).filterNotNull().firstOrNull() ?: return true
        val rootPath = workspace?.toAbsolutePath()?.normalize() ?: return true
        val eventPath = runCatching { Path.of(dir).toAbsolutePath().normalize() }.getOrNull() ?: run {
            // M15（C2）：host事件的directory违反可解析路径约束（apply违规）；既有丢弃行为不变。
            protocolError(PROTOCOL_STAGE_APPLY, PROTOCOL_CODE_APPLY_VIOLATION)
            return false
        }
        return eventPath == rootPath || eventPath.startsWith(rootPath)
    }

    /**
     * M15（C2，指标"是否读不懂服务返回的数据"）：协议/状态约束违规的最小计数事实
     * （transport/stage/error_code全部受控枚举），经Operations统一准入写入同一Recorder。
     * 仅在真实违规处调用；与Faults的M14计数互不重复——本事实即该故障的采集形态，
     * 不再对同一故障走error.reported。
     */
    private fun protocolError(stage: String, code: String) {
        operations?.record(
            Draft(
                PROTOCOL_ERROR,
                "diagnostic",
                "critical",
                buildJsonObject {
                    put("transport", "sse")
                    put("stage", stage)
                    put("error_code", code)
                },
            ),
        )
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
