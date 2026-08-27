package ai.kilocode.backend.app

import ai.kilocode.connection.BackendEvent
import ai.kilocode.connection.ConnectionProvider
import ai.kilocode.connection.ConnectionState
import ai.kilocode.connection.TransportFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transitional no-op [ConnectionProvider] used until the cs-cloud connector
 * module registers a real provider.
 *
 * Keeps the plugin loadable and reports an honest error state; no transport
 * is ever created. Replace by wiring the cs-cloud provider into
 * [KiloBackendAppService].
 */
internal class UnconfiguredConnectionProvider : ConnectionProvider {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<BackendEvent> = _events

    override fun transportFactory(): TransportFactory? = null

    override suspend fun connect() {
        _state.value = ConnectionState.Error(
            message = "No connection provider configured",
            details = "The original Kilo CLI connection layer was removed; waiting for the cs-cloud connector.",
        )
    }

    override suspend fun restart() {
        connect()
    }

    override fun dispose() {
        _state.value = ConnectionState.Disconnected
    }
}
