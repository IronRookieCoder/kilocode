// kilocode_change - new file
package ai.kilocode.backend.app

import ai.kilocode.backend.cli.CliServer
import ai.kilocode.backend.testing.TestLog
import ai.kilocode.jetbrains.api.client.DefaultApi
import ai.kilocode.log.KiloLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KiloConnectionProviderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `selected provider handles connect`() = runBlocking {
        val calls = AtomicInteger()
        val provider = TestProvider(calls)
        val app = KiloBackendAppService.create(scope, TestServer, TestLog(), provider)

        app.connect()

        assertEquals(1, calls.get())
        app.dispose()
    }

    @Test
    fun `connection target normalizes trailing slash`() {
        assertEquals("http://localhost:1234/api/v1", ConnectionTarget("http://localhost:1234/api/v1/").base)
    }

    private class TestProvider(private val calls: AtomicInteger) : KiloConnectionProvider {
        override val id = "test"
        override fun create(cs: CoroutineScope, reconnect: () -> Unit, log: KiloLog, timeout: Long): KiloConnection =
            object : KiloConnection {
                private val stateValue = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
                override val state: StateFlow<ConnectionState> = stateValue
                override val events: SharedFlow<SseEvent> = MutableSharedFlow()
                override val api: DefaultApi? = null
                override val apiClient: OkHttpClient? = null
                override val target: ConnectionTarget? = null
                override suspend fun connect() { calls.incrementAndGet() }
                override suspend fun restart() = Unit
                override suspend fun reinstall() = Unit
                override fun shutdownForUnload() = Unit
                override fun shutdownForAppClose() = Unit
                override fun dispose() = Unit
            }
    }

    private object TestServer : CliServer {
        override var forceExtract = false
        override fun process(): Process? = null
        override suspend fun init(onProgress: (ai.kilocode.backend.cli.CliDownload) -> Unit, onResolved: () -> Unit) =
            CliServer.State.Error("unused")
        override fun exited(proc: Process) = Unit
        override fun stop() = Unit
        override fun dispose() = Unit
    }
}
