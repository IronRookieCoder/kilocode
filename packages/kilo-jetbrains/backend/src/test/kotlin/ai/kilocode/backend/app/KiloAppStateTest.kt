package ai.kilocode.backend.app

import ai.kilocode.backend.app.AppData
import ai.kilocode.backend.app.KiloAppState
import ai.kilocode.backend.app.LoadError
import ai.kilocode.backend.app.LoadProgress
import ai.kilocode.backend.app.ProfileResult
import ai.kilocode.backend.app.loadFailureReason
import ai.kilocode.backend.app.loadTrigger
import ai.kilocode.rpc.dto.ConfigDto
import ai.kilocode.stability.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class KiloAppStateTest {

    @Test
    fun `default LoadProgress has all fields unloaded`() {
        val progress = LoadProgress()
        assertFalse(progress.config)
        assertFalse(progress.notifications)
        assertEquals(ProfileResult.PENDING, progress.profile)
    }

    @Test
    fun `LoadProgress copy tracks individual completion`() {
        val p1 = LoadProgress()
        val p2 = p1.copy(config = true)
        assertTrue(p2.config)
        assertFalse(p2.notifications)

        val p3 = p2.copy(notifications = true, profile = ProfileResult.LOADED)
        assertTrue(p3.config)
        assertTrue(p3.notifications)
        assertEquals(ProfileResult.LOADED, p3.profile)
    }

    @Test
    fun `KiloAppState sealed subtypes are distinct`() {
        assertIs<KiloAppState.Disconnected>(KiloAppState.Disconnected)
        assertIs<KiloAppState.Downloading>(KiloAppState.Downloading(42, "1.2.3", "darwin-arm64"))
        assertIs<KiloAppState.Connecting>(KiloAppState.Connecting)
        assertIs<KiloAppState.Loading>(KiloAppState.Loading(LoadProgress()))
        assertIs<KiloAppState.Error>(KiloAppState.Error("fail"))
    }

    @Test
    fun `KiloAppState Error with errors list`() {
        val errors = listOf(
          LoadError("config", status = 500, detail = "server error"),
          LoadError("notifications", detail = "timeout"),
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
          AppData(profile = null, config = cfg, notifications = emptyList())
        assertNull(data.profile)
        assertEquals(cfg, data.config)
        assertTrue(data.notifications.isEmpty())
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
        val err = LoadError(resource = "notifications")
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

    // ------ M02 backend.load outcomes（brief Step 4） ------

    @Test
    fun `load trigger maps initial and recovery`() {
        assertEquals("initial", loadTrigger(recover = false))
        assertEquals("recovery", loadTrigger(recover = true))
    }

    @Test
    fun `load failure reason maps first error resource`() {
        assertEquals("profile_error", loadFailureReason(listOf(LoadError("profile"))))
        assertEquals("config_error", loadFailureReason(listOf(LoadError("config"))))
        assertEquals("notifications_error", loadFailureReason(listOf(LoadError("notifications"))))
        assertEquals("other", loadFailureReason(listOf(LoadError("connection"))))
        assertEquals("other", loadFailureReason(emptyList()))
        // 首个失败资源决定归因（与captureLoad的resources次序一致）。
        assertEquals(
          "profile_error",
          loadFailureReason(listOf(LoadError("profile"), LoadError("config"))),
        )
    }

    @Test
    fun `backend load end carries trigger and reason in real facts`() {
        Fixture().use { fixture ->
            // 模拟M02的begin（trigger=initial）与业务失败end（reason=config_error）的线形状。
            val operation = fixture.operations.begin(
              "backend.load",
              30_000L,
              fields = buildJsonObject { put("trigger", loadTrigger(recover = false)) },
            )
            operation.end("failure", "load", "plugin", "other", fields = buildJsonObject {
                put("reason", loadFailureReason(listOf(LoadError("config"))))
            })
            fixture.flush()

            val ends = fixture.facts().filter {
                it.name == "backend.load" && it.data["phase"]?.jsonPrimitive?.content == "end"
            }
            assertEquals(1, ends.size)
            assertEquals("failure", ends.single().data["result"]?.jsonPrimitive?.content)
            assertEquals("config_error", ends.single().data["reason"]?.jsonPrimitive?.content)

            val starts = fixture.facts().filter {
                it.name == "backend.load" && it.data["phase"]?.jsonPrimitive?.content == "start"
            }
            assertEquals(1, starts.size)
            assertEquals("initial", starts.single().data["trigger"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `late load completion after timeout does not produce a second end`() {
        Fixture().use { fixture ->
            val operation = fixture.operations.begin(
              "backend.load",
              30_000L,
              fields = buildJsonObject { put("trigger", loadTrigger(recover = true)) },
            )
            assertTrue(operation.end("timeout", "load", "environment", fields = buildJsonObject {
                put("reason", "timeout")
            }))
            // 迟到的业务完成：CAS丢弃，不再产生第二条终态。
            assertFalse(operation.end("success", "load"))
            fixture.flush()

            val ends = fixture.facts().filter {
                it.name == "backend.load" && it.data["phase"]?.jsonPrimitive?.content == "end"
            }
            assertEquals(1, ends.size)
            assertEquals("timeout", ends.single().data["result"]?.jsonPrimitive?.content)
        }
    }
}
