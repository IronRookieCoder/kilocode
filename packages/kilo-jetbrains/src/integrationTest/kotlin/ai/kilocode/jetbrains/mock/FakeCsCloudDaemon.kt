package ai.kilocode.jetbrains.mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Scripted in-process mock of the cs-cloud daemon (spec §3.1).
 *
 * Integration tests redirect `~/.costrict/cs-cloud/server_url` at [baseUrl], so the plugin's
 * entire network boundary lands here. Serves the *mapped* paths (what `CsCloudRoute.interceptor`
 * forwards): `/api/v1/runtime/health`, `/api/v1/events` (SSE), the `/api/v1/agents` catalog
 * family, `/api/v1/conversations…`, `/api/v1/permissions…`, `/api/v1/questions…`.
 *
 * Zero new dependencies: JDK `com.sun.net.httpserver.HttpServer`; SSE uses chunked
 * `text/event-stream` frames. The port is fixed (high convention value) because the
 * "restart mock → plugin retries the same address" scenarios (M16.3) depend on it.
 */
class FakeCsCloudDaemon(
    private val port: Int = DEFAULT_PORT,
    /** Workspace roots of other Costrict clients on this machine (read from recent_workspaces.json). */
    private val foreignRoots: Set<String> = emptySet(),
) {
    /** When set, every recorded request is appended here (mock fidelity debugging). */
    private val requestLogPath: Path? =
        System.getProperty("kilo.integrationTest.mock.requestLog")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }

    companion object {
        /** High fixed port; overridable via `kilo.integrationTest.mock.port`. */
        const val DEFAULT_PORT = 49187

        fun portFromProperty(): Int =
            System.getProperty("kilo.integrationTest.mock.port")?.trim()?.toIntOrNull() ?: DEFAULT_PORT

        private const val SSE_RETRY_HINT = "retry: 500\n\n"
    }

    /** Every exchange seen by the daemon, in arrival order. */
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Per-test response script; reset between tests via [resetScenario]. */
    val scenario = Scenario()

    /** Conversations created through the conversations CRUD surface (history restore, U4.x). */
    val conversations = CopyOnWriteArrayList<String>()

    private val server = AtomicReference<HttpServer>(null)
    private val executor = AtomicReference<ExecutorService>(null)
    private val sinks = CopyOnWriteArrayList<SseSink>()
    private val conversationCounter = AtomicLong()
    private val catalogResponses = CopyOnWriteArrayList<String>()

    val baseUrl: String get() = "http://127.0.0.1:$port"

    val isRunning: Boolean get() = server.get() != null

    fun start(): FakeCsCloudDaemon {
        check(server.get() == null) { "FakeCsCloudDaemon already started" }
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        http.createContext("/") { exchange -> handle(exchange) }
        // A thread pool is mandatory: the JDK default executor handles every exchange on the
        // single start() dispatcher thread, so one blocked SSE handler would starve all
        // subsequent requests (they queue unaccepted and the client hangs silently).
        val pool = Executors.newCachedThreadPool()
        executor.set(pool)
        http.executor = pool
        http.start()
        server.set(http)
        return this
    }

    fun stop() {
        val http = server.getAndSet(null) ?: return
        breakSseConnections()
        http.stop(0)
        executor.getAndSet(null)?.shutdownNow()
    }

    /** Clear the scenario (not the request log) so each test starts from the happy path. */
    fun resetScenario() = scenario.reset()

    // ------------------------------------------------------------------
    // Recording & awaiting
    // ------------------------------------------------------------------

    fun requests(method: String, pathPrefix: String): List<RecordedRequest> =
        requests.filter { it.method == method && it.path.startsWith(pathPrefix) }

    /** Poll until a matching request arrives or the timeout elapses; fails the test when absent. */
    fun awaitRequest(method: String, pathPrefix: String, timeoutMs: Long): RecordedRequest =
        awaitNewRequest(method, pathPrefix, beforeCount = -1, timeoutMs = timeoutMs)

    /**
     * Poll until the [beforeCount]-th-next matching request arrives. Pass the count observed
     * before triggering the action under test so stale records never satisfy the await.
     */
    fun awaitNewRequest(method: String, pathPrefix: String, beforeCount: Int, timeoutMs: Long): RecordedRequest {
        val deadline = System.currentTimeMillis() + timeoutMs
        // beforeCount < 0 is the "no prior count taken" sentinel: wait for the first match.
        val waitFrom = if (beforeCount < 0) 0 else beforeCount
        while (true) {
            val matching = requests(method, pathPrefix)
            if (matching.size > waitFrom) return matching[waitFrom]
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "Expected $method $pathPrefix (after $beforeCount prior hits) within ${timeoutMs}ms; recorded: " +
                        requests.joinToString { "${it.method} ${it.path}" },
                )
            }
            Thread.sleep(50)
        }
    }

    fun awaitNoRequest(method: String, pathPrefix: String, settleMs: Long = 1_000) {
        Thread.sleep(settleMs)
        val found = requests(method, pathPrefix)
        check(found.isEmpty()) { "Expected no $method $pathPrefix but recorded ${found.size}: ${found.firstOrNull()?.path}" }
    }

    /**
     * U2.7/M15: the plugin must scope its own requests to the active project root — a header
     * inside [workspaceRoot] must be the root itself (no subdirectory leak, no `..` escape).
     * Headers pointing at other machine roots belong to other Costrict clients (e.g. the dev
     * IDE, which reconnects to the mock while server_url is redirected) and are tolerated.
     */
    fun assertWorkspaceHeaderEquals(workspaceRoot: String) {
        val offending = requests.filter {
            val v = it.header("X-Workspace-Directory") ?: return@filter false
            v != workspaceRoot && (v.startsWith(workspaceRoot) || v.contains(".."))
        }
        check(offending.isEmpty()) {
            "X-Workspace-Directory violation: expected $workspaceRoot but got " +
                offending.joinToString { "${it.method} ${it.path} -> ${it.header("X-Workspace-Directory")}" }
        }
    }

    /** Catalog payloads actually served (for the U6.6 启用即生效 assertion). */
    fun servedCatalogResponses(): List<String> = catalogResponses.toList()

    // ------------------------------------------------------------------
    // SSE control
    // ------------------------------------------------------------------

    /** Push one event JSON to every connected SSE client right now. */
    fun broadcast(eventJson: String) {
        checkNotNull(server.get()) { "daemon is not running" }
        sinks.forEach { it.send(eventJson) }
    }

    /** Close every active SSE stream; the daemon keeps accepting reconnects (M16.x). */
    fun breakSseConnections() {
        sinks.toList().forEach(SseSink::close)
    }

    fun activeSseConnections(): Int = sinks.count(SseSink::isOpen)

    // ------------------------------------------------------------------
    // G3 状态化目录
    // ------------------------------------------------------------------

    /** Catalog entries contributed by currently loaded favorites (U6.6). */
    private fun loadedCommandFavorites(): List<String> =
        if (!scenario.catalogSideEffects) emptyList()
        else scenario.favorites.filter { it.id in scenario.loadedFavorites && it.itemType == "command" }.map { it.name }

    private fun loadedModeFavorites(): List<String> =
        if (!scenario.catalogSideEffects) emptyList()
        else scenario.favorites.filter { it.id in scenario.loadedFavorites && it.itemType == "agent" }.map { it.name }

    // ------------------------------------------------------------------
    // Request dispatch
    // ------------------------------------------------------------------

    private fun handle(exchange: HttpExchange) {
        val recorded = record(exchange)
        try {
            // Scripted overrides win over everything else (failures, 401, envelopes…).
            scenario.overrides.firstOrNull { it.method == recorded.method && it.pathRegex.containsMatchIn(recorded.path) }
                ?.let { respond(exchange, it.responder(recorded)) ; return }

            val path = recorded.path
            when {
                path == "/api/v1/runtime/health" -> respond(exchange, scenario.health)
                path == "/api/v1/events" -> serveSse(exchange, recorded)
                path == "/api/v1/agents/models" -> respond(exchange, MockResponse.ok(Scenario.modelsBody()))
                path == "/api/v1/agents/session-modes" ->
                    respond(exchange, MockResponse.ok(Scenario.sessionModesBody(loadedModeFavorites())))
                path == "/api/v1/agents/commands" -> {
                    val body = Scenario.commandsBody(loadedCommandFavorites())
                    catalogResponses.add(body)
                    respond(exchange, MockResponse.ok(body))
                }
                path == "/api/v1/agents/config" -> respond(exchange, MockResponse.ok(Scenario.configBody()))
                path == "/api/v1/runtime/path" ->
                    respond(exchange, MockResponse.ok(Scenario.runtimePathBody(recorded.header("X-Workspace-Directory") ?: "")))
                path == "/api/v1/agents/favorites" && recorded.method == "GET" -> serveFavorites(exchange)
                path.matches(Regex("/api/v1/agents/favorites/[^/]+/load")) && recorded.method == "POST" ->
                    serveFavoriteAction(exchange, recorded, load = true)
                path.matches(Regex("/api/v1/agents/favorites/[^/]+/unload")) && recorded.method == "POST" ->
                    serveFavoriteAction(exchange, recorded, load = false)
                path == "/api/v1/conversations" && recorded.method == "POST" -> serveConversationCreate(exchange, recorded)
                path == "/api/v1/conversations" && recorded.method == "GET" -> serveConversationList(exchange)
                path.startsWith("/api/v1/conversations/") -> serveConversationDetail(exchange, recorded)
                path.startsWith("/api/v1/permissions") -> serveListOrReceipt(exchange, recorded)
                path.startsWith("/api/v1/questions") -> serveListOrReceipt(exchange, recorded)
                else -> respond(exchange, MockResponse.ok("{}"))
            }
        } catch (broken: Exception) {
            // Client disconnected (e.g. SSE break) — nothing to answer.
            runCatching { exchange.close() }
        }
    }

    private fun record(exchange: HttpExchange): RecordedRequest {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val recorded = RecordedRequest(
            method = exchange.requestMethod.uppercase(),
            path = exchange.requestURI.rawPath,
            query = exchange.requestURI.rawQuery.orEmpty().split('&')
                .filter { it.contains('=') }
                .associate { it.substringBefore('=').let(UriDecode::invoke) to UriDecode(it.substringAfter('=')) },
            headers = exchange.requestHeaders.mapValues { it.value.joinToString(",") },
            body = body,
            atMs = System.currentTimeMillis(),
        )
        requests.add(recorded)
        requestLogPath?.let { path ->
            runCatching {
                Files.writeString(
                    path, "${recorded.method} ${recorded.path}\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                )
            }
        }
        return recorded
    }

    private fun respond(exchange: HttpExchange, response: MockResponse) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "${response.contentType}; charset=utf-8")
        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }

    private fun serveSse(exchange: HttpExchange, recorded: RecordedRequest) {
        if (!scenario.sseAccept || !isRunning) {
            exchange.close() // aborts the connection — surfaces as SSE failure/retry plugin-side
            return
        }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0) // chunked; the stream stays open
        val sink = SseSink(exchange, exchange.responseBody)
        sinks.add(sink)
        try {
            sink.sendRaw(SSE_RETRY_HINT)
            for (step in scenario.sseScript) {
                if (sink.isClosed) return
                when (step) {
                    is SseStep.Event -> sink.send(step.json)
                    is SseStep.Pause -> Thread.sleep(step.millis)
                    SseStep.BreakConnection -> { sink.close(); return }
                }
            }
            sink.drainUntilClosed() // hold the stream open; broadcast() feeds it
        } finally {
            sinks.remove(sink)
        }
    }

    private fun serveFavorites(exchange: HttpExchange) {
        if (scenario.favoritesDelayMs > 0) Thread.sleep(scenario.favoritesDelayMs)
        val body = scenario.favorites.joinToString(",", "[", "]") { it.json() }
        respond(exchange, MockResponse.ok(body))
    }

    private fun serveFavoriteAction(exchange: HttpExchange, recorded: RecordedRequest, load: Boolean) {
        val id = recorded.path.substringBeforeLast('/').substringAfterLast('/')
        if (load) scenario.loadedFavorites.add(id) else scenario.loadedFavorites.remove(id)
        // Receipt mirrors CsCloudFavoritesApi.ActionBody: {success, item}.
        val item = scenario.favorites.firstOrNull { it.id == id }
        val updated = item?.let { it.copy(status = if (load) "Active" else "Unloaded") }?.json() ?: "null"
        respond(exchange, MockResponse.ok("""{"success":true,"item":$updated}"""))
    }

    private fun serveConversationCreate(exchange: HttpExchange, recorded: RecordedRequest) {
        val id = "conv-${conversationCounter.incrementAndGet()}"
        val directory = recorded.header("X-Workspace-Directory") ?: ""
        val sessionJson = CsEvents.sessionJson(id, directory, title = "Fixture Session")
        conversations.add(sessionJson)
        respond(exchange, MockResponse.ok(sessionJson))
    }

    private fun serveConversationList(exchange: HttpExchange) {
        respond(exchange, MockResponse.ok(conversations.joinToString(",", "[", "]") { it }))
    }

    private fun serveConversationDetail(exchange: HttpExchange, recorded: RecordedRequest) {
        val rest = recorded.path.removePrefix("/api/v1/conversations/")
        val id = rest.substringBefore('/')
        when {
            rest == id && recorded.method == "GET" ->
                respond(exchange, MockResponse.ok(conversations.firstOrNull { it.contains("\"id\":\"$id\"") } ?: "{}"))
            rest == id && recorded.method == "DELETE" -> {
                conversations.removeIf { it.contains("\"id\":\"$id\"") }
                respond(exchange, MockResponse.ok("true"))
            }
            rest == id -> respond(exchange, MockResponse.ok(conversations.firstOrNull { it.contains("\"id\":\"$id\"") } ?: "{}"))
            rest.endsWith("/prompt/async") && recorded.method == "POST" -> {
                // The daemon accepted the run; replay scripts are pushed via broadcast().
                respond(exchange, MockResponse.ok("{}"))
            }
            rest.endsWith("/abort") && recorded.method == "POST" ->
                respond(exchange, MockResponse(scenario.abort.status, "{}"))
            rest.endsWith("/messages") && recorded.method == "GET" -> respond(exchange, MockResponse.ok("[]"))
            else -> respond(exchange, MockResponse.ok("{}"))
        }
    }

    /** `GET` returns the pending list (empty); anything else is a reply receipt — recorded only. */
    private fun serveListOrReceipt(exchange: HttpExchange, recorded: RecordedRequest) {
        if (recorded.method == "GET") respond(exchange, MockResponse.ok("[]")) else respond(exchange, MockResponse.ok("{}"))
    }
}

/** One open SSE client connection. */
private class SseSink(private val exchange: HttpExchange, private val out: OutputStream) {
    private val queue: BlockingQueue<String> = LinkedBlockingQueue()
    private val closed = AtomicBoolean(false)

    val isOpen: Boolean get() = !closed.get()

    val isClosed: Boolean get() = closed.get()

    fun send(eventJson: String) = sendRaw("data: $eventJson\n\n")

    fun sendRaw(frame: String) {
        if (closed.get()) return
        queue.add(frame)
    }

    /** Write frames as they arrive; returns when [close] was called. */
    fun drainUntilClosed() {
        while (!closed.get()) {
            val frame = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            runCatching {
                out.write(frame.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }.onFailure {
                closed.set(true)
                exchange.close()
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            out.close()
            exchange.close()
        }
    }
}

private object UriDecode {
    operator fun invoke(value: String): String =
        java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
}
