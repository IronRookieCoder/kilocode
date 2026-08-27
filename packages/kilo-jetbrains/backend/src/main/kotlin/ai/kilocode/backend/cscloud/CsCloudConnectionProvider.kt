package ai.kilocode.backend.cscloud

import ai.kilocode.connection.BackendEvent
import ai.kilocode.connection.ConnectionProvider
import ai.kilocode.connection.ConnectionState
import ai.kilocode.connection.TransportFactory
import ai.kilocode.cscloud.CsCloudConnectionService
import ai.kilocode.cscloud.CsCloudConnectionState
import ai.kilocode.cscloud.CsCloudConnectionStatus
import ai.kilocode.cscloud.CsCloudEndpoint
import ai.kilocode.cscloud.CsCloudEndpointResolver
import ai.kilocode.cscloud.CsCloudHealthDiagnosis
import ai.kilocode.cscloud.CsCloudHttpClients
import ai.kilocode.cscloud.CsCloudStatusListener
import ai.kilocode.cscloud.CsCloudStatusNotifier
import ai.kilocode.cscloud.SseEvent
import ai.kilocode.log.KiloLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * Adapts the cs-cloud connection layer to the plugin's [ConnectionProvider]:
 *  - resolves the endpoint via [CsCloudEndpointResolver]
 *  - runs the [CsCloudConnectionService] state machine on [cs]
 *  - maps cs-cloud status transitions to [ConnectionState]
 *  - forwards normalized SSE events as [BackendEvent] (design §3.1)
 *  - exposes a [CsCloudTransportFactory] once connected
 *
 * The API key is never logged: only the base URL is exposed through
 * [ConnectionState.Connected].
 */
class CsCloudConnectionProvider(
    private val cs: CoroutineScope,
    private val workspace: () -> Path?,
    private val resolver: CsCloudEndpointResolver = CsCloudEndpointResolver(),
    private val log: KiloLog,
    private val onEvent: (BackendEvent) -> Unit = {},
    private val onDispose: () -> Unit = {},
) : ConnectionProvider {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    @Volatile
    private var endpoint: CsCloudEndpoint? = null

    private var service: CsCloudConnectionService? = null

    private val statusListener = CsCloudStatusListener { onStatus(it) }

    override fun transportFactory(): TransportFactory? =
        endpoint?.let { CsCloudTransportFactory(it, workspace) }

    override suspend fun connect() {
        val svc = service ?: createService().also { service = it }
        svc.addStatusListener(statusListener)
        svc.connect()
    }

    override suspend fun restart() {
        dispose()
        connect()
    }

    override fun dispose() {
        service?.close()
        service = null
        endpoint = null
        _state.value = ConnectionState.Disconnected
        onDispose()
    }

    private fun createService(): CsCloudConnectionService =
        CsCloudConnectionService.create(
            scope = cs,
            resolver = resolver,
            clients = { ep -> CsCloudHttpClients(ep, workspace) },
            workspace = workspace,
            onEvent = { event -> forward(event) },
            notifier = CsCloudStatusNotifier(),
        )

    private fun forward(event: SseEvent) {
        val backend = BackendEvent(event.type, event.data)
        _events.tryEmit(backend)
        onEvent(backend)
    }

    private fun onStatus(status: CsCloudConnectionStatus) {
        when (status.state) {
            CsCloudConnectionState.Disconnected -> {
                endpoint = null
                _state.value = ConnectionState.Disconnected
            }
            CsCloudConnectionState.Discovering,
            CsCloudConnectionState.Connecting,
            -> _state.value = ConnectionState.Connecting
            CsCloudConnectionState.Ready -> {
                val ep = resolver.resolve().getOrNull()
                endpoint = ep
                _state.value = if (ep != null) {
                    ConnectionState.Connected(ep.base)
                } else {
                    ConnectionState.Error("cs-cloud endpoint is not available")
                }
            }
            CsCloudConnectionState.Unavailable -> {
                endpoint = null
                val (message, details) = describe(status)
                _state.value = ConnectionState.Error(message, details)
            }
        }
    }

    private fun describe(status: CsCloudConnectionStatus): Pair<String, String?> {
        val detail = status.detail
        return when (status.diagnosis) {
            CsCloudHealthDiagnosis.CREDENTIALS_INVALID -> "cs-cloud credentials are invalid" to detail
            CsCloudHealthDiagnosis.AGENT_NOT_READY -> "cs-cloud agent is not ready" to detail
            CsCloudHealthDiagnosis.DAEMON_NOT_RUNNING -> "cs-cloud daemon is not running" to detail
            null -> "cs-cloud connection is unavailable" to detail
        }
    }
}
