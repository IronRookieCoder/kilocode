package ai.kilocode.connection

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable contract for the agent backend connection.
 *
 * The backend application layer (app state, sessions, chat) depends only on
 * this interface; the concrete transport is supplied by a connection module
 * (for example the Costrict cs-cloud connector). Implementations own the
 * process/endpoint lifecycle, the event stream, and transport creation.
 */
interface ConnectionProvider {

    /** Connection lifecycle state exposed to the application layer. */
    val state: StateFlow<ConnectionState>

    /** Backend event stream (server-sent events normalized by the connector). */
    val events: SharedFlow<BackendEvent>

    /**
     * Transport factory bound to the current connection, or null when
     * disconnected. Each created [Transport] must be closed by its caller.
     */
    fun transportFactory(): TransportFactory?

    /** Establish the connection. Serialized by the caller (app service mutex). */
    suspend fun connect()

    /** Tear down and re-establish the connection. Serialized by the caller. */
    suspend fun restart()

    /** Release all resources. Must not block the caller (may run on EDT at shutdown). */
    fun dispose()
}

/** Connection states surfaced by [ConnectionProvider.state]. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val endpoint: String) : ConnectionState
    data class Error(val message: String, val details: String? = null) : ConnectionState
}

/** A single event delivered on [ConnectionProvider.events]. */
data class BackendEvent(val type: String, val data: String)
