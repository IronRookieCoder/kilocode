package ai.kilocode.jetbrains.mock

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One HTTP exchange seen by [FakeCsCloudDaemon], kept for test-side assertions. */
data class RecordedRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
    val atMs: Long,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** A scripted HTTP response served by the mock daemon. */
data class MockResponse(val status: Int, val body: String, val contentType: String = "application/json") {
    companion object {
        fun ok(body: String = "{}") = MockResponse(200, body)
        fun error(status: Int, code: String, message: String) =
            MockResponse(status, """{"ok":false,"error":{"code":"$code","message":"$message"}}""")
    }
}

/** A cs-cloud favorite row (daemon-side facade shape, mirrors rpc dto CloudFavoriteItem). */
data class FavoriteFixture(
    val id: String,
    val name: String,
    val itemType: String, // skill | agent | command | mcp
    val status: String,   // Active | Downloaded | Cloud | Unloaded
    val slug: String = id,
    val description: String = "$name description",
) {
    fun json(): String = buildString {
        append("{\"id\":\"").append(esc(id)).append('"')
        append(",\"slug\":\"").append(esc(slug)).append('"')
        append(",\"name\":\"").append(esc(name)).append('"')
        append(",\"description\":\"").append(esc(description)).append('"')
        append(",\"itemType\":\"").append(esc(itemType)).append('"')
        append(",\"status\":\"").append(esc(status)).append('"')
        append(",\"localPath\":null}")
    }
}

/** One step of the scripted SSE event stream replayed to each new `/api/v1/events` connection. */
sealed interface SseStep {
    /** Write one raw SSE data frame (a full GlobalEvent JSON document). */
    data class Event(val json: String) : SseStep

    /** Pause before the next step. */
    data class Pause(val millis: Long) : SseStep

    /** Close every active SSE stream; the daemon keeps accepting reconnects. */
    data object BreakConnection : SseStep
}

/** Behavior of `POST /api/v1/conversations/{id}/abort`. */
enum class AbortBehavior(val status: Int) {
    Ok(200),
    NotFound(404),
    Gone(410),
}

/**
 * The response script injected into [FakeCsCloudDaemon] per test case: status overrides,
 * SSE event sequences, favorites fixtures and catalog side effects. Scenario content is
 * test semantics (spec §13) — keep it in sync with the cs-cloud protocol chapters of the
 * design specs.
 */
class Scenario {
    /** Highest-priority scripted responses; matched in insertion order. */
    class Override(val method: String, val pathRegex: Regex, val responder: (RecordedRequest) -> MockResponse)

    val overrides = CopyOnWriteArrayList<Override>()

    /** `/api/v1/runtime/health`; default healthy daemon. */
    @Volatile var health: MockResponse = MockResponse.ok(healthyBody()) ; private set

    /** Whether `/api/v1/events` accepts connections (false = connection refused by shutdown). */
    @Volatile var sseAccept: Boolean = true

    /** Steps replayed on every new SSE connection, before the stream is held open. */
    val sseScript = CopyOnWriteArrayList<SseStep>()

    /** Artificial delay before favorites list responses (B9/B15 busy-state scenarios). */
    @Volatile var favoritesDelayMs: Long = 0

    /** `POST /api/v1/conversations/{id}/abort` behavior (R-1: 404/410 must stay silent success). */
    @Volatile var abort: AbortBehavior = AbortBehavior.Ok

    /** Favorites rows served by `GET /api/v1/agents/favorites`. */
    val favorites = CopyOnWriteArrayList<FavoriteFixture>()

    /**
     * G3 状态化目录：when true, a received favorites `load` POST also makes the catalog
     * endpoints (`/api/v1/agents/commands`, `/api/v1/agents/session-modes`) serve the
     * freshly enabled entry (U6.6 启用即生效).
     */
    @Volatile var catalogSideEffects: Boolean = false

    /** Favorites currently marked loaded by POSTed load/unload receipts (daemon bookkeeping). */
    val loadedFavorites: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun reset() {
        overrides.clear()
        health = MockResponse.ok(healthyBody())
        sseAccept = true
        sseScript.clear()
        favoritesDelayMs = 0
        abort = AbortBehavior.Ok
        favorites.clear()
        catalogSideEffects = false
        loadedFavorites.clear()
    }

    /** Serve [responder] for any [method] request whose path matches [pathRegex]. */
    fun override(method: String, pathRegex: Regex, responder: (RecordedRequest) -> MockResponse) {
        overrides.add(Override(method, pathRegex, responder))
    }

    fun override(method: String, pathRegex: Regex, response: MockResponse) =
        override(method, pathRegex) { response }

    /** Health returns HTTP [status] with an `{ok:false,error}` envelope. */
    fun failHealth(status: Int, code: String, message: String) {
        health = MockResponse.error(status, code, message)
    }

    /** Broadcast [steps] to every current and future SSE connection, before being held open. */
    fun playOnConnect(vararg steps: SseStep) {
        sseScript.addAll(steps)
    }

    companion object {
        /** The daemon-side health envelope consumed by `CsCloudHealth.parseHealth`. */
        fun healthyBody(version: String = "1.0.0-test", capabilities: List<String> = listOf("agents", "favorites")): String =
            """{"ok":true,"data":{"status":"ok","version":"$version","capabilities":${capabilities.joinToString(",", "[", "]") { "\"$it\"" }}}}"""

        /** The raw models catalog; `CsCloudRoute.responseInterceptor` normalizes it plugin-side.
         *  Billing rates ride on the daemon response the way the real daemon injects them. */
        fun modelsBody(): String =
            """
            {"connected":[
              {"id":"costrict","name":"Costrict Cloud","default_model":"coder-pro",
               "models":{"coder-pro":{"id":"coder-pro","name":"Coder Pro","creditConsumption":3.0,"creditDiscount":1.0},"coder-lite":{"id":"coder-lite","name":"Coder Lite","creditConsumption":0.5,"creditDiscount":1.0}}}
            ]}
            """.trimIndent()

        /**
         * Session modes catalog. `mode` and `options` are required by the generated
         * `Agent` DTO — omitting them fails the whole workspace load (agents resource).
         */
        fun sessionModesBody(extraModes: List<String> = emptyList()): String {
            val base = listOf("build" to "Build", "plan" to "Plan")
            val modes = base + extraModes.map { it to it.replaceFirstChar(Char::uppercase) }
            return modes.joinToString(",", "[", "]") { (id, name) ->
                """{"id":"$id","name":"$name","mode":"primary","options":{},"description":"$name mode"}"""
            }
        }

        /** Commands catalog; [extraCommands] appear once the G3 stateful catalog is armed. */
        fun commandsBody(extraCommands: List<String> = emptyList()): String {
            val base = listOf("review" to "Review current changes")
            val commands = base + extraCommands.map { it to "Loaded favorite command $it" }
            return commands.joinToString(",", "[", "]") { (name, description) ->
                """{"name":"$name","description":"$description","source":"project"}"""
            }
        }

        fun configBody(): String = """{"ok":true,"data":{}}"""

        fun runtimePathBody(directory: String): String = """{"path":"${esc(directory)}","separator":"\\"}"""
    }
}

/**
 * Builders for the SSE event documents the plugin consumes (`KiloCliDataParser.parseChatEvent`
 * handles the GlobalEvent wrapper `{directory, payload:{type, properties}}`).
 */
object CsEvents {
    private val counter = AtomicLong(0)

    fun newId(prefix: String): String = "$prefix-${counter.incrementAndGet()}"

    /** The GlobalEvent wrapper every daemon event uses. */
    fun global(directory: String, type: String, properties: String): String =
        """{"directory":"${esc(directory)}","payload":{"type":"$type","properties":$properties}}"""

    fun sessionStatus(directory: String, sessionId: String, type: String, message: String? = null): String {
        val extra = message?.let { ""","message":"${esc(it)}"""" } ?: ""
        return global(directory, "session.status", """{"sessionID":"$sessionId","status":{"type":"$type"$extra}}""")
    }

    fun sessionIdle(directory: String, sessionId: String): String =
        global(directory, "session.idle", """{"sessionID":"$sessionId"}""")

    /** Streaming text delta (U3.2): the field carries the appended fragment. */
    fun partDelta(directory: String, sessionId: String, messageId: String, partId: String, delta: String, field: String = "text"): String =
        global(
            directory,
            "message.part.delta",
            """{"sessionID":"$sessionId","messageID":"$messageId","partID":"$partId","field":"$field","delta":"${esc(delta)}"}""",
        )

    /** A full message part snapshot (assistant text / tool card / reasoning). */
    fun partUpdated(directory: String, sessionId: String, part: String): String =
        global(directory, "message.part.updated", """{"sessionID":"$sessionId","part":$part}""")

    fun textPart(partId: String, messageId: String, text: String): String =
        """{"id":"$partId","messageID":"$messageId","sessionID":"$messageId","type":"text","text":"${esc(text)}"}"""

    /** Assistant message snapshot; role feeds the normalizer's role table. */
    fun messageUpdated(directory: String, messageId: String, sessionId: String, role: String = "assistant"): String =
        global(
            directory,
            "message.updated",
            """{"sessionID":"$sessionId","info":{"id":"$messageId","sessionID":"$sessionId","role":"$role","time":{"created":1.0}}}""",
        )

    /** Permission card (U3.4-U3.6); [properties] must contain id/sessionID/permission. */
    fun permissionAsked(directory: String, properties: String): String =
        global(directory, "permission.asked", properties)

    fun permissionProperties(
        requestId: String,
        sessionId: String,
        permission: String = "edit",
        patterns: List<String> = listOf("**/*.kt"),
        metadataPath: String? = null,
        always: List<String> = emptyList(),
    ): String {
        val meta = metadataPath?.let { ""","metadata":{"path":"${esc(it)}"}""" } ?: ""
        val patternsJson = patterns.joinToString(",", "[", "]") { "\"${esc(it)}\"" }
        val alwaysJson = always.joinToString(",", "[", "]") { "\"${esc(it)}\"" }
        return """{"id":"$requestId","sessionID":"$sessionId","permission":"$permission","patterns":$patternsJson,"always":$alwaysJson$meta}"""
    }

    /** Question card (U3.7) with single-select options. */
    fun questionAsked(directory: String, requestId: String, sessionId: String, question: String, options: List<String>): String {
        val optionJson = options.joinToString(",", "[", "]") {
            """{"label":"${esc(it)}","description":"Option ${esc(it)}"}"""
        }
        return global(
            directory,
            "question.asked",
            """{"id":"$requestId","sessionID":"$sessionId","questions":[{"question":"${esc(question)}","header":"Confirm","options":$optionJson,"multiple":false,"custom":true}]}""",
        )
    }

    /** Host file event consumed by workspace refresh / the code-review report watcher (U3.8/U7.4). */
    fun hostFileUpdated(directory: String, path: String): String =
        global(directory, "host.file.updated", """{"path":"${esc(path)}"}""")

    /** Session snapshot (history restore, title sync). */
    fun sessionUpdated(directory: String, sessionJson: String): String =
        global(directory, "session.updated", """{"info":$sessionJson}""")

    /** Session object served by `/api/v1/conversations` (parseSessionObject shape). */
    fun sessionJson(id: String, directory: String, title: String): String =
        """{"id":"$id","projectID":"fixture","directory":"${esc(directory)}","title":"${esc(title)}","version":"1.0.0-test","time":{"created":1.0,"updated":1.0}}"""
}

/** Minimal JSON string escaping for hand-built payloads. */
fun esc(value: String): String = buildString(value.length) {
    for (ch in value) when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
    }
}
