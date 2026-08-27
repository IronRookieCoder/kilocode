package ai.kilocode.cscloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Outcome of a control-plane health check. */
sealed class CsCloudHealthCheckOutcome {
    data object Healthy : CsCloudHealthCheckOutcome()
    data class Failed(
        val diagnosis: CsCloudHealthDiagnosis,
        val detail: String?,
    ) : CsCloudHealthCheckOutcome()
}

/**
 * Owns the cs-cloud connection lifecycle (design §4.2):
 * discovery → health check → SSE event stream → `ready`, with exponential-backoff
 * reconnection (1s, 2s, 4s, 8s, ... capped at 30s) and status notification for the frontend.
 *
 * [close] only cancels HTTP/SSE connections; it never invokes `cs-cloud stop` — the
 * daemon is left running so the plugin can reconnect on demand.
 */
class CsCloudConnectionService(
    private val scope: CoroutineScope,
    private val resolveEndpoint: () -> Result<CsCloudEndpoint>,
    private val checkHealth: suspend (CsCloudEndpoint) -> CsCloudHealthCheckOutcome,
    private val openSse: (CsCloudEndpoint, Path, CsCloudSseListener) -> CsCloudSseStream,
    private val currentWorkspace: () -> Path?,
    private val onEvent: (SseEvent) -> Unit = {},
    private val backoffMillis: (attempt: Int) -> Long = Companion::defaultBackoffMillis,
    private val notifier: CsCloudStatusNotifier = CsCloudStatusNotifier(),
) {

    private val status = AtomicReference(CsCloudConnectionStatus(CsCloudConnectionState.Disconnected))
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var stream: CsCloudSseStream? = null
    private var attempt = 0
    @Volatile
    private var reconnecting = false
    @Volatile
    private var disposed = false

    val currentStatus: CsCloudConnectionStatus
        get() = status.get()

    /** Starts the connection flow; safe to call again to force a fresh connect. */
    fun connect() {
        if (disposed) return
        reconnecting = false
        reconnectJob?.cancel()
        connectJob = scope.launch { doConnect() }
    }

    /**
     * Tears down HTTP/SSE connections and resets to [CsCloudConnectionState.Disconnected].
     * Never stops the cs-cloud daemon.
     */
    fun close() {
        if (disposed) return
        disposed = true
        reconnecting = false
        reconnectJob?.cancel()
        connectJob?.cancel()
        stream?.close()
        stream = null
        status.set(CsCloudConnectionStatus(CsCloudConnectionState.Disconnected))
        notifier.notify(currentStatus)
    }

    fun addStatusListener(listener: CsCloudStatusListener) = notifier.add(listener)

    fun removeStatusListener(listener: CsCloudStatusListener) = notifier.remove(listener)

    private suspend fun doConnect() {
        if (disposed) return
        setStatus(CsCloudConnectionStatus(CsCloudConnectionState.Discovering))
        val endpoint = resolveEndpoint().getOrElse {
            setStatus(CsCloudConnectionStatus(CsCloudConnectionState.Unavailable, detail = it.message))
            return
        }
        if (disposed) return
        setStatus(CsCloudConnectionStatus(CsCloudConnectionState.Connecting))
        when (val outcome = checkHealth(endpoint)) {
            is CsCloudHealthCheckOutcome.Healthy -> openEventStream(endpoint)
            is CsCloudHealthCheckOutcome.Failed -> {
                setStatus(CsCloudConnectionStatus(CsCloudConnectionState.Unavailable, outcome.diagnosis, outcome.detail))
                scheduleReconnect()
            }
        }
    }

    private suspend fun openEventStream(endpoint: CsCloudEndpoint) {
        val workspace = currentWorkspace()
        if (disposed || workspace == null) {
            setStatus(CsCloudConnectionStatus(CsCloudConnectionState.Unavailable, detail = "workspace is unavailable"))
            return
        }
        setStatus(CsCloudConnectionStatus(CsCloudConnectionState.Connecting))
        val handle = openSse(endpoint, workspace, listener)
        stream = handle
        handle.start()
    }

    private fun scheduleReconnect() {
        if (disposed || reconnecting) return
        reconnecting = true
        reconnectJob = scope.launch {
            try {
                val wait = backoffMillis(attempt)
                attempt++
                delay(wait)
                if (disposed) return@launch
                doConnect()
            } finally {
                reconnecting = false
            }
        }
    }

    private val listener = object : CsCloudSseListener {
        override fun onOpen() {
            val wasReconnect = attempt > 0
            attempt = 0
            if (disposed) return
            setStatus(CsCloudConnectionStatus(CsCloudConnectionState.Ready))
            if (wasReconnect) {
                // Signals the app layer to re-read the active session's messages,
                // status, and pending permissions/questions after a reconnection.
                this@CsCloudConnectionService.onEvent(SseEvent("session.reconnected", "{}"))
            }
        }

        override fun onEvent(event: SseEvent) {
            if (!disposed) this@CsCloudConnectionService.onEvent(event)
        }

        override fun onClosed() {
            if (!disposed) scheduleReconnect()
        }

        override fun onFailure(error: Throwable?, status: Int?) {
            if (disposed) return
            if (status == 401 || status == 403) {
                setStatus(
                    CsCloudConnectionStatus(
                        CsCloudConnectionState.Unavailable,
                        CsCloudHealthDiagnosis.CREDENTIALS_INVALID,
                        "cs-cloud stream authentication failed",
                    )
                )
            }
            scheduleReconnect()
        }
    }

    private fun setStatus(next: CsCloudConnectionStatus) {
        if (disposed && next.state != CsCloudConnectionState.Disconnected) return
        status.set(next)
        notifier.notify(next)
    }

    companion object {

        /** Backoff sequence 1s, 2s, 4s, 8s, 16s, ... capped at 30s. */
        fun defaultBackoffMillis(attempt: Int): Long =
            minOf(1_000L shl attempt.coerceIn(0, 5), 30_000L)

        /**
         * Production wiring: resolves via [resolver], checks health and opens SSE
         * through [clients], and reports events/status to [onEvent] / [notifier].
         */
        fun create(
            scope: CoroutineScope,
            resolver: CsCloudEndpointResolver,
            clients: (CsCloudEndpoint) -> CsCloudHttpClients,
            workspace: () -> Path?,
            onEvent: (SseEvent) -> Unit,
            notifier: CsCloudStatusNotifier = CsCloudStatusNotifier(),
        ): CsCloudConnectionService = CsCloudConnectionService(
            scope = scope,
            resolveEndpoint = resolver::resolve,
            checkHealth = { endpoint -> defaultHealthCheck(endpoint, clients(endpoint).plainClient) },
            openSse = { endpoint, path, listener -> CsCloudSseClient(endpoint, path, clients(endpoint).sseClient, listener) },
            currentWorkspace = workspace,
            onEvent = onEvent,
            notifier = notifier,
        )
    }
}

/**
 * Default health check against `/api/v1/runtime/health` (design §5.1):
 *  - HTTP 401/403  → [CsCloudHealthDiagnosis.CREDENTIALS_INVALID]
 *  - HTTP 503      → [CsCloudHealthDiagnosis.AGENT_NOT_READY]
 *  - network error → [CsCloudHealthDiagnosis.DAEMON_NOT_RUNNING]
 */
suspend fun defaultHealthCheck(endpoint: CsCloudEndpoint, http: OkHttpClient): CsCloudHealthCheckOutcome =
    withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint.base + "/api/v1/runtime/health")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val text = response.body?.string().orEmpty()
                        val health = runCatching { CsCloudHealthParser.parse(text) }.getOrNull()
                        if (health != null && health.healthy) {
                            CsCloudHealthCheckOutcome.Healthy
                        } else {
                            CsCloudHealthCheckOutcome.Failed(
                                CsCloudHealthDiagnosis.AGENT_NOT_READY,
                                "cs-cloud health check failed",
                            )
                        }
                    }
                    401, 403 -> CsCloudHealthCheckOutcome.Failed(
                        CsCloudHealthDiagnosis.CREDENTIALS_INVALID,
                        "cs-cloud API key was rejected",
                    )
                    503 -> CsCloudHealthCheckOutcome.Failed(
                        CsCloudHealthDiagnosis.AGENT_NOT_READY,
                        "cs-cloud agent is not ready",
                    )
                    else -> CsCloudHealthCheckOutcome.Failed(
                        CsCloudHealthDiagnosis.DAEMON_NOT_RUNNING,
                        "cs-cloud health check failed (HTTP ${response.code})",
                    )
                }
            }
        } catch (e: IOException) {
            CsCloudHealthCheckOutcome.Failed(
                CsCloudHealthDiagnosis.DAEMON_NOT_RUNNING,
                "cs-cloud daemon is not running or unreachable",
            )
        }
    }
