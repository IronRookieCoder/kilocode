// kilocode_change - new file
package ai.kilocode.backend.app

import ai.kilocode.backend.cli.CliServer
import ai.kilocode.jetbrains.api.client.DefaultApi
import ai.kilocode.log.KiloLog
import com.intellij.openapi.extensions.ExtensionPointName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

interface KiloConnectionProvider {
    val id: String

    fun create(cs: CoroutineScope, reconnect: () -> Unit, log: KiloLog, timeout: Long): KiloConnection

    companion object {
        val EP_NAME: ExtensionPointName<KiloConnectionProvider> =
            ExtensionPointName.create("ai.kilocode.jetbrains.connectionProvider")
    }
}

interface KiloConnection {
    val state: StateFlow<ConnectionState>
    val events: SharedFlow<SseEvent>
    val api: DefaultApi?
    val apiClient: OkHttpClient?
    val target: ConnectionTarget?
    val appLoadApi: DefaultApi?
        get() = api
    val port: Int
        get() = 0

    suspend fun connect()
    suspend fun restart()
    suspend fun reinstall()
    fun shutdownForUnload()
    fun shutdownForAppClose()
    fun dispose()
}

/** Provider for the existing locally managed Kilo CLI connection. */
class KiloCliConnectionProvider(private val server: CliServer) : KiloConnectionProvider {
    override val id: String = "kilo-cli"

    override fun create(
        cs: CoroutineScope,
        reconnect: () -> Unit,
        log: KiloLog,
        timeout: Long,
    ): KiloConnection = KiloCliConnection(KiloConnectionService(cs, server, reconnect, log, timeout))
}

private class KiloCliConnection(private val delegate: KiloConnectionService) : KiloConnection {
    override val state: StateFlow<ConnectionState> get() = delegate.state
    override val events: SharedFlow<SseEvent> get() = delegate.events
    override val api: DefaultApi? get() = delegate.api
    override val apiClient: OkHttpClient? get() = delegate.apiClient
    override val appLoadApi: DefaultApi? get() = delegate.appLoadApi
    override val target: ConnectionTarget? get() = delegate.target
    override val port: Int get() = delegate.port
    override suspend fun connect() = delegate.connect()
    override suspend fun restart() = delegate.restart()
    override suspend fun reinstall() = delegate.reinstall()
    override fun shutdownForUnload() = delegate.dispose()
    override fun shutdownForAppClose() = delegate.dispose()
    override fun dispose() = delegate.dispose()
}
