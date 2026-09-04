package ai.kilocode.backend.rpc

import ai.kilocode.backend.app.KiloAppState
import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.backend.app.KiloCliConnectionProvider
import ai.kilocode.backend.app.KiloConnection
import ai.kilocode.backend.app.KiloConnectionProvider
import ai.kilocode.backend.cli.CliServer
import ai.kilocode.backend.testing.FakeCliServer
import ai.kilocode.backend.testing.MockCliServer
import ai.kilocode.backend.testing.TestLog
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.ConfigPatchDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression pin for e18fc84fea: the frontend hides the legacy Reinstall action whenever
 * `KiloAppStateDto.providerId == "cs-cloud"`, so *every* path returning an app-state DTO must
 * stamp `KiloBackendAppService.providerId` — `state()` and `updateConfig()` alike. The original
 * bug stamped only `state()`, letting the updateConfig response re-show the Kilo-CLI recovery
 * menu on cs-cloud sessions.
 */
class KiloAppRpcApiProviderIdTest {

    private val mock = MockCliServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val apps = mutableListOf<KiloBackendAppService>()

    @AfterTest
    fun tearDown() {
        apps.forEach { it.dispose() }
        apps.clear()
        scope.cancel()
        mock.close()
    }

    @Test
    fun `state and updateConfig stamp the cs-cloud provider id`() = runBlocking {
        val api = KiloAppRpcApiImpl(appOverride = readyApp(FixedIdProvider("cs-cloud", mock)))

        assertEquals("cs-cloud", api.state().first { it.status == KiloAppStatusDto.READY }.providerId)
        assertEquals("cs-cloud", api.updateConfig(ConfigPatchDto(values = mapOf("model" to "test-model"))).providerId)
    }

    @Test
    fun `state stamps the kilo-cli fallback provider id`() = runBlocking {
        val api = KiloAppRpcApiImpl(appOverride = readyApp())

        assertEquals(KiloCliConnectionProvider(FakeCliServer(mock)).id, api.state().first { it.status == KiloAppStatusDto.READY }.providerId)
    }

    @Test
    fun `provider id mirrors the connection provider`() = runBlocking {
        assertEquals("cs-cloud", readyApp(FixedIdProvider("cs-cloud", mock)).providerId)
        assertEquals("kilo-cli", readyApp().providerId)
    }

    /** CLI-backed connection published under a foreign provider id, mimicking the cs-cloud provider. */
    private class FixedIdProvider(override val id: String, private val mock: MockCliServer) : KiloConnectionProvider {
        override fun create(cs: CoroutineScope, reconnect: () -> Unit, log: KiloLog, timeout: Long): KiloConnection =
            KiloCliConnectionProvider(FakeCliServer(mock)).create(cs, reconnect, log, timeout)
    }

    private suspend fun readyApp(provider: KiloConnectionProvider? = null): KiloBackendAppService {
        val server = FakeCliServer(mock)
        val app = if (provider == null) {
            KiloBackendAppService.create(scope, server, TestLog())
        } else {
            KiloBackendAppService.create(scope, server, TestLog(), provider)
        }
        app.connect()
        withTimeout(10_000) {
            app.appState.first { it is KiloAppState.Ready }
        }
        assertNotNull(app.appState.value as? KiloAppState.Ready)
        return app.also { apps.add(it) }
    }
}
