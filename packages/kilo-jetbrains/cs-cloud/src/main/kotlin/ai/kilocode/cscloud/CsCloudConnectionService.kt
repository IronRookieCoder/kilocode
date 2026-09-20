package ai.kilocode.cscloud

import ai.kilocode.backend.app.ConnectionState
import ai.kilocode.backend.app.ConnectionTarget
import ai.kilocode.backend.app.KiloConnection
import ai.kilocode.backend.app.KiloSessionCapabilities
import ai.kilocode.backend.app.CapabilityReleaseReason
import ai.kilocode.cscloud.mcp.CsCloudMcpBridge
import ai.kilocode.cscloud.mcp.IdeMcpSessionFactory
import ai.kilocode.backend.app.SseEvent
import ai.kilocode.jetbrains.api.client.DefaultApi
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.CloudFavoriteActionResult
import ai.kilocode.rpc.dto.CloudFavoritesErrors
import ai.kilocode.rpc.dto.CloudFavoritesResult
import ai.kilocode.rpc.dto.CsCloudStartDto
import ai.kilocode.stability.ConnectionObservation
import ai.kilocode.stability.ConnectionReasons
import ai.kilocode.stability.ConnectionTriggers
import ai.kilocode.stability.Operations
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
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** M04 attempt受控stage（metrics 3.2：resolve/health/streams）。 */
private const val STAGE_RESOLVE = "resolve"
private const val STAGE_HEALTH = "health"
private const val STAGE_STREAMS = "streams"

/** 归因401/403为凭据问题（与Error状态error_code同一判定）。 */
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/** Connection to an already-running local cs-cloud daemon. */
@Suppress("LongParameterList")
class CsCloudConnectionService(
    private val cs: CoroutineScope,
    private val resolver: CsCloudEndpointResolver,
    private val log: KiloLog,
    private val timeout: Long = 30_000L,
    workspace: Path? = null,
    private val roots: () -> List<Path> = { listOfNotNull(workspace) },
    private val starter: suspend () -> CsCloudStartDto = { CsCloudStartDto(false, "cs-cloud starter is not configured") },
    private val installer: suspend () -> CsCloudStartDto = { CsCloudStartDto(false, "cs-cloud installer is not configured") },
    private val login: suspend () -> CsCloudStartDto = { CsCloudStartDto(false, "cs-cloud login is not configured") },
    /** 稳定性采集入口（B2）：生产由provider传入；null=采集不可用，业务照常。 */
    operations: Operations? = null,
) : KiloConnection {

    /**
     * M04/M05观测（brief）：所有变更发生在连接状态机的串行协程上下文（connect/
     * scheduleReconnect/schedulePoll协程）；跨SSE回调只投递（经scheduleReconnect单飞），
     * 绝不直接改观测字段。null=未接入采集。
     */
    private val observation: ConnectionObservation? = operations?.let(::ConnectionObservation)

    private val bridge: Lazy<CsCloudMcpBridge> = lazy {
        CsCloudMcpBridge(cs, { endpoint }, { clients?.apiClient }, { connectionEpoch }, IdeMcpSessionFactory.EP.extensionList.singleOrNull(), log)
    }
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _events = MutableSharedFlow<SseEvent>(extraBufferCapacity = 128)
    private var reconnect: Job? = null
    private var poll: Job? = null
    private var sse = emptyList<CsCloudSseClient>()
    private var clients: CsCloudClients? = null
    private var endpoint: CsCloudEndpoint? = null

    /**
     * Monotonically increasing connection generation: bumps on every successful (re)connection so
     * the MCP bridge can tell a daemon restart (same port, lost bindings) apart from a no-op and
     * re-bind leases. Null while no connection is established. Internal for tests.
     */
    @Volatile internal var connectionEpoch: Long? = null
        private set
    @Volatile private var disposed = false
    private var attempt = 0
    private val epochCounter = AtomicLong()

    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    override val events: SharedFlow<SseEvent> = _events.asSharedFlow()
    override val api: DefaultApi? get() = clients?.api
    override val apiClient: OkHttpClient? get() = clients?.apiClient
    override val target: ConnectionTarget? get() = endpoint?.let { ConnectionTarget(it.base) }
    override val port: Int get() = 0
    override val capabilities: KiloSessionCapabilities get() = bridge.value

    /**
     * 公有用户入口（KiloConnection业务接口不变）：trigger=initial。自动重试路径
     * （schedulePoll/scheduleReconnect）经内部trigger区分，绝不新建逻辑连接分母。
     */
    override suspend fun connect() = connect(ConnectionTriggers.INITIAL)

    private suspend fun connect(trigger: String) {
        if (disposed) return
        reconnect?.cancel()
        reconnect = null
        poll?.cancel()
        poll = null
        closeTransport()
        observation?.request(trigger)
        _state.value = ConnectionState.Discovering
        val attemptOp = observation?.attempt(STAGE_RESOLVE)
        val found = resolver.resolve().getOrElse { error ->
            attemptOp?.end("failure", STAGE_RESOLVE, connectionCause(error), errorCode(error) ?: "other")
            fail(error)
            schedulePoll()
            return
        }
        if (roots().isEmpty()) {
            val error = IllegalStateException("active JetBrains project root is unavailable")
            attemptOp?.end("failure", STAGE_RESOLVE, "environment", "other")
            fail(error)
            schedulePoll()
            return
        }
        endpoint = found
        _state.value = ConnectionState.Connecting
        val next = CsCloudHttpClients.create(found, roots)
        clients = next
        try {
            observation?.attempt(STAGE_HEALTH)
            checkHealth(next)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failAttempt(STAGE_HEALTH, connectionCause(error), errorCode(error) ?: "health_failed")
            closeTransport()
            fail(error)
            schedulePoll()
            return
        }
        openSse(next)
    }

    /** 用户显式重启：trigger=manual，取消旧逻辑操作并开新operation（brief）。 */
    override suspend fun restart() = connect(ConnectionTriggers.MANUAL)

    override suspend fun reinstall(): Nothing = throw CsCloudUnsupportedOperationException()

    override suspend fun startCsCloud(): CsCloudStartDto {
        val result = starter()
        if (result.ok) connect()
        // Every result of this call describes the start phase, standalone or via installCsc().
        return result.copy(stage = CsCloudStartDto.STAGE_START)
    }

    /** Install the csc CLI, then start the daemon so the fresh install takes effect. */
    override suspend fun installCsc(): CsCloudStartDto {
        val installed = installer()
        if (!installed.ok) return installed.copy(stage = CsCloudStartDto.STAGE_INSTALL)
        // startCsCloud() already tags its results with STAGE_START, so a caller can tell an
        // install failure from an install success whose daemon failed to come up.
        return startCsCloud()
    }

    /** Run `csc auth login` so the user can sign in to CoStrict in the browser. */
    override suspend fun loginCsCloud(): CsCloudStartDto = login()

    override suspend fun cloudFavorites(): CloudFavoritesResult =
        favoritesApi()?.list()
            ?: CloudFavoritesResult(ok = false, errorCode = CloudFavoritesErrors.UNAVAILABLE, errorMessage = "cs-cloud daemon is not connected")

    override suspend fun loadCloudFavorite(id: String): CloudFavoriteActionResult =
        favoritesApi()?.load(id)
            ?: CloudFavoriteActionResult(ok = false, errorCode = CloudFavoritesErrors.UNAVAILABLE, errorMessage = "cs-cloud daemon is not connected")

    override suspend fun unloadCloudFavorite(id: String): CloudFavoriteActionResult =
        favoritesApi()?.unload(id)
            ?: CloudFavoriteActionResult(ok = false, errorCode = CloudFavoritesErrors.UNAVAILABLE, errorMessage = "cs-cloud daemon is not connected")

    private fun favoritesApi(): CsCloudFavoritesApi? {
        val client = clients?.favoritesClient ?: return null
        val url = endpoint?.base ?: return null
        return CsCloudFavoritesApi(client, url)
    }

    override fun shutdownForUnload() = shutdown()

    override fun shutdownForAppClose() = shutdown()

    override fun dispose() {
        if (disposed) return
        disposed = true
        // 正常dispose：结算在途区间为cancelled，绝不生成断连事实（brief Step 5）。
        observation?.close()
        reconnect?.cancel()
        reconnect = null
        poll?.cancel()
        poll = null
        closeTransport()
        if (bridge.isInitialized()) cs.launch { bridge.value.releaseAll(CapabilityReleaseReason.SHUTDOWN) }
        _state.value = ConnectionState.Disconnected
    }

    /** 本轮attempt失败统一结算入口：result=failure，code缺省由调用方给安全码。 */
    private fun failAttempt(stage: String, cause: String, code: String) {
        observation?.attempt(stage)?.end("failure", stage, cause, code)
    }

    /**
     * 全部必需流打开才算连接成功（brief：健康200但缺流不能成功）。多流失败经同一
     * 单飞重连路径收口，断连reason只取实际观测词表。
     */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun openSse(current: CsCloudClients) {
        if (disposed) return
        _state.value = ConnectionState.Connecting
        val opened = CompletableDeferred<Unit>()
        val paths = roots().map { it.toAbsolutePath().normalize() }.distinct().sortedBy(Path::toString)
        if (paths.isEmpty()) {
            // 终局失败且无重试被调度：M04记result=failure（不被30s timeout吞掉）。
            observation?.failed(STAGE_STREAMS, "environment", "other")
            return fail(IllegalStateException("active JetBrains project root is unavailable"))
        }
        val count = AtomicInteger()
        val streams = paths.map { path ->
            CsCloudSseClient(
                http = current.sseClient,
                base = endpoint?.base ?: return,
                workspace = path,
                log = log,
                onOpen = { if (count.incrementAndGet() == paths.size) opened.complete(Unit) },
                onEvent = { event -> if (!disposed) _events.tryEmit(event) },
                onClosed = { scheduleReconnect(disconnectReason(paths.size)) },
                onFailure = { error, status ->
                    if (!opened.isCompleted) opened.completeExceptionally(error ?: CsCloudRequestException("sse_failed", "cs-cloud SSE failed (HTTP $status)", status ?: 0))
                    else scheduleReconnect(disconnectReason(paths.size))
                },
            )
        }
        sse = streams
        streams.forEach(CsCloudSseClient::start)
        try {
            withTimeout(timeout.coerceAtLeast(1_000L)) { opened.await() }
            if (!disposed) {
                // 全部必需流打开才算attempt成功与连接成功（brief：缺流不能成功）。
                observation?.attempt(STAGE_STREAMS)?.end("success", STAGE_STREAMS)
                attempt = 0
                // Bump before Connected so recovery (which runs on that transition) re-binds
                // leases against the current daemon generation, not a pre-restart epoch.
                connectionEpoch = epochCounter.incrementAndGet()
                _state.value = ConnectionState.Connected(0, "")
                observation?.connected()
            }
        } catch (error: TimeoutCancellationException) {
            failAttempt(STAGE_STREAMS, "network", "timeout")
            streams.forEach(CsCloudSseClient::close)
            sse = emptyList()
            fail(error)
            scheduleReconnect(ConnectionReasons.SSE_CLOSED)
        } catch (error: CancellationException) {
            streams.forEach(CsCloudSseClient::close)
            sse = emptyList()
            throw error
        } catch (error: Throwable) {
            if (!disposed) {
                failAttempt(STAGE_STREAMS, connectionCause(error), errorCode(error) ?: "sse_failed")
                streams.forEach(CsCloudSseClient::close)
                sse = emptyList()
                fail(error)
                scheduleReconnect(disconnectReason(paths.size))
            }
        }
    }

    /**
     * 单飞重连（连接状态机串行上下文的一部分）：进入即[ConnectionObservation.lost]——
     * ready后多流同败只开一个恢复区间（guard在单飞检查之后），ready前失败不开恢复。
     * reason只允许实际观测到的丢失原因（ConnectionReasons词表），绝不写daemon_restart。
     */
    private fun scheduleReconnect(reason: String) {
        if (disposed || reconnect?.isActive == true) return
        reconnect = cs.launch {
            reconnect = null
            observation?.lost(reason)
            val wait = (250L shl attempt.coerceAtMost(3)).coerceAtMost(2_000L)
            attempt = (attempt + 1).coerceAtMost(4)
            delay(wait)
            if (!isActive || disposed) return@launch
            endpoint ?: return@launch
            val bundle = clients ?: return@launch
            _state.value = ConnectionState.Connecting
            try {
                observation?.attempt(STAGE_HEALTH)
                checkHealth(bundle)
                closeSse()
                openSse(bundle)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                failAttempt(STAGE_HEALTH, connectionCause(error), errorCode(error) ?: "health_failed")
                fail(error)
                scheduleReconnect(ConnectionReasons.HEALTH_FAILED)
            }
        }
    }

    /**
     * Keeps re-attempting [connect] after a discovery or health failure so the plugin
     * auto-connects once the user starts the cs-cloud daemon (e.g. `csc cloud start`)
     * instead of requiring a manual Retry click.
     */
    private fun schedulePoll() {
        if (disposed || poll?.isActive == true) return
        poll = cs.launch {
            poll = null
            val wait = (250L shl attempt.coerceAtMost(3)).coerceAtMost(2_000L)
            attempt = (attempt + 1).coerceAtMost(4)
            delay(wait)
            if (!isActive || disposed) return@launch
            // 内部重试trigger：schedulePoll重入绝不begin新的逻辑操作（brief Step 4）。
            connect(ConnectionTriggers.RECOVERY)
        }
    }

    private fun fail(error: Throwable) {
        val cause = error.cause ?: error
        val detail = when (cause) {
            is CsCloudDiscoveryError -> cause.message
            is CsCloudRequestException -> "${cause.code}: ${cause.message} (HTTP ${cause.status})"
            else -> cause.message
        }
        _state.value = ConnectionState.Error(detail ?: "cs-cloud connection failed", detail, errorCode(error))
        log.warn("cs-cloud connection failed: ${detail ?: "unknown error"}", cause)
    }

    /** attempt end的cause词表（metrics 3.2）：只按provider实际观测到的失败类别归因。 */
    private fun connectionCause(error: Throwable): String {
        val cause = error.cause ?: error
        return when {
            cause is CsCloudDiscoveryError -> "environment"
            cause is CsCloudRequestException && cause.isCredentialRejection() -> "cs_cloud"
            cause is IOException -> "network"
            else -> "cs_cloud"
        }
    }

    /** 与Error状态一致的安全error_code（metrics 3.2连接词表）；未识别归other。 */
    private fun errorCode(error: Throwable): String? {
        val cause = error.cause ?: error
        return when {
            cause is CsCloudDiscoveryError.MissingUrl -> ConnectionErrorCode.CSC_NOT_INSTALLED
            cause is CsCloudRequestException && cause.isCredentialRejection() -> ConnectionErrorCode.UNAUTHORIZED
            cause is IOException -> ConnectionErrorCode.DAEMON_DOWN
            else -> null
        }
    }

    private fun CsCloudRequestException.isCredentialRejection(): Boolean =
        status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN

    /** 多根目录部分流丢失记partial_sse_failed，单流整体丢失记sse_closed（metrics 3.2）。 */
    private fun disconnectReason(streamCount: Int): String =
        if (streamCount > 1) ConnectionReasons.PARTIAL_SSE_FAILED else ConnectionReasons.SSE_CLOSED

    private fun shutdown() {
        if (disposed) return
        // 正常shutdown（unload/app close）：同dispose，无断连事实。
        observation?.close()
        reconnect?.cancel()
        reconnect = null
        poll?.cancel()
        poll = null
        closeTransport()
        if (bridge.isInitialized()) cs.launch { bridge.value.releaseAll(CapabilityReleaseReason.SHUTDOWN) }
        _state.value = ConnectionState.Disconnected
    }

    private fun closeTransport() {
        closeSse()
        val old = clients
        clients = null
        endpoint = null
        connectionEpoch = null
        old?.let {
            shutdown(it.apiClient)
            shutdown(it.sseClient)
            shutdown(it.healthClient)
            shutdown(it.favoritesClient)
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
