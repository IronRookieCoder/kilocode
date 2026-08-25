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
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

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

    @Test
    fun `cli connection has no session capabilities`() {
        val provider = TestProvider(AtomicInteger())
        val app = KiloBackendAppService.create(scope, TestServer, TestLog(), provider)

        assertNull(app.sessionCapabilities)
        app.dispose()
    }

    @Test
    fun `app service exposes provider capabilities unchanged`() {
        val capabilities = object : KiloSessionCapabilities {
            override suspend fun ensure(id: String, directory: String): CapabilityResult =
                CapabilityResult.Unavailable("test")

            override suspend fun release(id: String, reason: CapabilityReleaseReason) = Unit

            override suspend fun releaseAll(reason: CapabilityReleaseReason) = Unit
        }
        val provider = TestProvider(AtomicInteger(), capabilities)
        val app = KiloBackendAppService.create(scope, TestServer, TestLog(), provider)

        assertSame(capabilities, app.sessionCapabilities)
        app.dispose()
    }

    @Test
    fun `disabled cli runtime requires a connection provider`() {
        val error = assertFailsWith<IllegalStateException> {
            KiloBackendAppService.create(
                scope,
                TestServer,
                TestLog(),
                providers = emptyList(),
                runtime = false,
            )
        }

        assertEquals("Kilo CLI runtime is disabled and no connection provider is available", error.message)
    }

    private class TestProvider(
        private val calls: AtomicInteger,
        private val capabilities: KiloSessionCapabilities? = null,
    ) : KiloConnectionProvider {
        override val id = "test"
        override fun create(cs: CoroutineScope, reconnect: () -> Unit, log: KiloLog, timeout: Long): KiloConnection =
            object : KiloConnection {
                private val stateValue = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
                override val state: StateFlow<ConnectionState> = stateValue
                override val events: SharedFlow<SseEvent> = MutableSharedFlow()
                override val api: DefaultApi? = null
                override val apiClient: OkHttpClient? = null
                override val target: ConnectionTarget? = null
                override val capabilities: KiloSessionCapabilities? = this@TestProvider.capabilities
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
