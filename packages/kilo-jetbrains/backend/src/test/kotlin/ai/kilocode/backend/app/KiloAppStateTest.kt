package ai.kilocode.backend.app

import ai.kilocode.backend.app.AppData
import ai.kilocode.backend.app.KiloAppState
import ai.kilocode.backend.app.LoadError
import ai.kilocode.backend.app.LoadProgress
import ai.kilocode.backend.app.ProfileResult
import ai.kilocode.rpc.dto.ConfigDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KiloAppStateTest {

    @Test
    fun `default LoadProgress has all fields unloaded`() {
        val progress = LoadProgress()
        assertFalse(progress.config)
        assertEquals(ProfileResult.PENDING, progress.profile)
    }

    @Test
    fun `LoadProgress copy tracks individual completion`() {
        val p1 = LoadProgress()
        val p2 = p1.copy(config = true)
        assertTrue(p2.config)
        assertEquals(ProfileResult.PENDING, p2.profile)

        val p3 = p2.copy(profile = ProfileResult.LOADED)
        assertTrue(p3.config)
        assertEquals(ProfileResult.LOADED, p3.profile)
    }

    @Test
    fun `KiloAppState sealed subtypes are distinct`() {
        assertIs<KiloAppState.Disconnected>(KiloAppState.Disconnected)
        assertIs<KiloAppState.Connecting>(KiloAppState.Connecting)
        assertIs<KiloAppState.Loading>(KiloAppState.Loading(LoadProgress()))
        assertIs<KiloAppState.Error>(KiloAppState.Error("fail"))
    }

    @Test
    fun `KiloAppState Error with errors list`() {
        val errors = listOf(
          LoadError("config", status = 500, detail = "server error"),
          LoadError("profile", detail = "timeout"),
        )
        val state = KiloAppState.Error("Failed", errors = errors)
        assertEquals(2, state.errors.size)
        assertEquals("config", state.errors[0].resource)
        assertEquals(500, state.errors[0].status)
        assertNull(state.errors[1].status)
    }

    @Test
    fun `AppData construction`() {
        val cfg = ConfigDto(model = "test")
        val data =
          AppData(profile = null, config = cfg, warnings = emptyList())
        assertNull(data.profile)
        assertEquals(cfg, data.config)
        assertTrue(data.warnings.isEmpty())
    }

    @Test
    fun `LoadError with all fields`() {
        val err = LoadError(
          resource = "config",
          status = 503,
          detail = "Service Unavailable"
        )
        assertEquals("config", err.resource)
        assertEquals(503, err.status)
        assertEquals("Service Unavailable", err.detail)
    }

    @Test
    fun `LoadError with minimal fields`() {
        val err = LoadError(resource = "profile")
        assertNull(err.status)
        assertNull(err.detail)
    }

    @Test
    fun `ProfileResult enum values`() {
        assertEquals(3, ProfileResult.entries.size)
        assertTrue(
          ProfileResult.entries.containsAll(
            listOf(ProfileResult.PENDING, ProfileResult.LOADED, ProfileResult.NOT_LOGGED_IN)
        ))
    }
}
