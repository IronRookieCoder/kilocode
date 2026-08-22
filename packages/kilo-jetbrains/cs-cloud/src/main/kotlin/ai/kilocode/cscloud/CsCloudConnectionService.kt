package ai.kilocode.cscloud

import ai.kilocode.backend.app.ConnectionState
import ai.kilocode.backend.app.ConnectionTarget
import ai.kilocode.backend.app.KiloConnection
import ai.kilocode.backend.app.SseEvent
import ai.kilocode.jetbrains.api.client.DefaultApi
import ai.kilocode.log.KiloLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Connection to an already-running local cs-cloud daemon. */
class CsCloudConnectionService(
    private val cs: CoroutineScope,
    private val resolver: CsCloudEndpointResolver,
    private val log: KiloLog,
    private val timeout: Long = 30_000L,
    workspace: Path? = null,
    private val roots: () -> List<Path> = { listOfNotNull(workspace) },
) : KiloConnection {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _events = MutableSharedFlow<SseEvent>(extraBufferCapacity = 128)
    private var reconnect: Job? = null
    private var sse = emptyList<CsCloudSseClient>()
    private var clients: CsCloudClients? = null
    private var endpoint: CsCloudEndpoint? = null
    @Volatile private var disposed = false
    private var attempt = 0

    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    override val events: SharedFlow<SseEvent> = _events.asSharedFlow()
    override val api: DefaultApi? get() = clients?.api
    override val apiClient: OkHttpClient? get() = clients?.apiClient
    override val target: ConnectionTarget? get() = endpoint?.let { ConnectionTarget(it.base) }
    override val port: Int get() = 0

    override suspend fun connect() {
        if (disposed) return
        reconnect?.cancel()
        reconnect = null
        closeTransport()
        attempt = 0
        _state.value = ConnectionState.Discovering
        val found = resolver.resolve().getOrElse { return fail(it) }
        if (roots().isEmpty()) return fail(IllegalStateException("active JetBrains project root is unavailable"))
        endpoint = found
        _state.value = ConnectionState.Connecting
        val next = CsCloudHttpClients.create(found, roots)
        clients = next
        try {
            checkHealth(next)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            closeTransport()
            fail(error)
            return
        }
        openSse(next)
    }

    override suspend fun restart() = connect()

    override suspend fun reinstall(): Nothing = throw CsCloudUnsupportedOperationException()

    override fun shutdownForUnload() = shutdown()

    override fun shutdownForAppClose() = shutdown()

    override fun dispose() {
        if (disposed) return
        disposed = true
        reconnect?.cancel()
        reconnect = null
        closeTransport()
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun openSse(current: CsCloudClients) {
        if (disposed) return
        _state.value = ConnectionState.Connecting
        val opened = CompletableDeferred<Unit>()
        val paths = roots().map { it.toAbsolutePath().normalize() }.distinct().sortedBy(Path::toString)
        if (paths.isEmpty()) return fail(IllegalStateException("active JetBrains project root is unavailable"))
        val count = AtomicInteger()
        val streams = paths.map { path ->
            CsCloudSseClient(
                http = current.sseClient,
                base = endpoint?.base ?: return,
                workspace = path,
                log = log,
                onOpen = { if (count.incrementAndGet() == paths.size) opened.complete(Unit) },
                onEvent = { event -> if (!disposed) _events.tryEmit(event) },
                onClosed = { scheduleReconnect() },
                onFailure = { error, status ->
                    if (!opened.isCompleted) opened.completeExceptionally(error ?: CsCloudRequestException("sse_failed", "cs-cloud SSE failed (HTTP $status)", status ?: 0))
                    else scheduleReconnect()
                },
            )
        }
        sse = streams
        streams.forEach(CsCloudSseClient::start)
        try {
            withTimeout(timeout.coerceAtLeast(1_000L)) { opened.await() }
            if (!disposed) {
                attempt = 0
                _state.value = ConnectionState.Connected(0, "")
            }
        } catch (error: TimeoutCancellationException) {
            stream.close()
            sse = null
            fail(error)
            scheduleReconnect()
        } catch (error: CancellationException) {
            stream.close()
            sse = null
            throw error
        } catch (error: Throwable) {
            if (!disposed) {
                streams.forEach(CsCloudSseClient::close)
                sse = emptyList()
                fail(error)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (disposed || reconnect?.isActive == true) return
        reconnect = cs.launch {
            reconnect = null
            val wait = (250L shl attempt.coerceAtMost(3)).coerceAtMost(2_000L)
            attempt = (attempt + 1).coerceAtMost(4)
            delay(wait)
            if (!isActive || disposed) return@launch
            endpoint ?: return@launch
            val bundle = clients ?: return@launch
            _state.value = ConnectionState.Connecting
            try {
                checkHealth(bundle)
                closeSse()
                openSse(bundle)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                fail(error)
                scheduleReconnect()
            }
        }
    }

    private fun fail(error: Throwable) {
        val cause = error.cause ?: error
        val detail = when (cause) {
            is CsCloudDiscoveryError -> cause.message
            is CsCloudRequestException -> "${cause.code}: ${cause.message} (HTTP ${cause.status})"
            else -> cause.message
        }
        _state.value = ConnectionState.Error(detail ?: "cs-cloud connection failed", detail)
        log.warn("cs-cloud connection failed: ${detail ?: "unknown error"}", cause)
    }

    private fun shutdown() {
        if (disposed) return
        reconnect?.cancel()
        reconnect = null
        closeTransport()
        _state.value = ConnectionState.Disconnected
    }

    private fun closeTransport() {
        closeSse()
        val old = clients
        clients = null
        endpoint = null
        old?.let {
            shutdown(it.apiClient)
            shutdown(it.sseClient)
            shutdown(it.healthClient)
        }
    }

    private fun closeSse() {
        sse.forEach(CsCloudSseClient::close)
        sse = emptyList()
    }

    private fun shutdown(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private suspend fun checkHealth(bundle: CsCloudClients) = withContext(Dispatchers.IO) {
        val base = endpoint?.base ?: throw IllegalStateException("cs-cloud endpoint is unavailable")
        val request = Request.Builder().url("$base/global/health").get().build()
        bundle.healthClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "cs-cloud health check failed: HTTP ${response.code}" }
            response.body?.string()
        }
    }
}
