package ai.kilocode.backend.workspace

import ai.kilocode.backend.app.KiloBackendSessionManager
import ai.kilocode.connection.BackendEvent
import ai.kilocode.connection.TransportFactory
import ai.kilocode.log.KiloLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-directory [KiloBackendWorkspace] instances.
 *
 * **Not an IntelliJ service** — owned by [ai.kilocode.backend.app.KiloBackendAppService],
 * which calls [start] after the app reaches Ready and [stop] on shutdown.
 */
class KiloBackendWorkspaceManager(
    private val cs: CoroutineScope,
    private val sessions: KiloBackendSessionManager,
    private val log: KiloLog,
) {
    private val workspaces = ConcurrentHashMap<String, KiloBackendWorkspace>()

    private var transportFactory: TransportFactory? = null
    private var events: SharedFlow<BackendEvent>? = null

    fun start(transportFactory: TransportFactory, events: SharedFlow<BackendEvent>) {
        this.transportFactory = transportFactory
        this.events = events
        log.info("Workspace manager started")
    }

    fun stop() {
        workspaces.values.forEach(KiloBackendWorkspace::stop)
        workspaces.clear()
        transportFactory = null
        events = null
        log.info("Workspace manager stopped")
    }

    fun get(directory: String): KiloBackendWorkspace {
        val factory = transportFactory
            ?: throw IllegalStateException("Workspace manager not started")
        val events = events
            ?: throw IllegalStateException("Workspace manager not started")
        return workspaces.computeIfAbsent(directory) {
            KiloBackendWorkspace(
                directory = directory,
                cs = cs,
                transport = factory.create(),
                events = events,
                sessions = sessions,
                log = log,
            )
        }
    }
}
