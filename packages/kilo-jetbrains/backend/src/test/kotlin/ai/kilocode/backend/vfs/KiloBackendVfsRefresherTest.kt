package ai.kilocode.backend.vfs

import ai.kilocode.backend.testing.TestLog
import ai.kilocode.connection.BackendEvent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class KiloBackendVfsRefresherTest {

    private fun refresher(
        scope: kotlinx.coroutines.test.TestScope,
        events: MutableSharedFlow<BackendEvent>,
        sessionDirectory: (String) -> String?,
        refreshed: MutableList<List<Path>>,
    ): KiloBackendVfsRefresher =
        KiloBackendVfsRefresher(
            cs = scope,
            events = events,
            sessionDirectory = sessionDirectory,
            log = TestLog(),
            refresh = { refreshed.add(it) },
        )

    @Test
    fun `host file event refreshes path and directory`() = runTest {
        val events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 16)
        val refreshed = mutableListOf<List<Path>>()
        val refresher = refresher(this, events, { null }, refreshed)
        refresher.start()
        advanceUntilIdle() // collector must be collecting before emit, otherwise replay=0 drops the value

        events.emit(BackendEvent("host.file.write", """{"path":"C:/proj/src/A.kt","directory":"C:/proj"}"""))
        advanceUntilIdle()

        assertEquals(listOf(listOf(Path.of("C:/proj/src/A.kt"), Path.of("C:/proj"))), refreshed)
        refresher.stop()
    }

    @Test
    fun `host file event reads paths nested under payload`() = runTest {
        val events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 16)
        val refreshed = mutableListOf<List<Path>>()
        val refresher = refresher(this, events, { null }, refreshed)
        refresher.start()
        advanceUntilIdle()

        events.emit(BackendEvent("host.file.write", """{"payload":{"path":"C:/proj/src/B.kt"}}"""))
        advanceUntilIdle()

        assertEquals(listOf(listOf(Path.of("C:/proj/src/B.kt"))), refreshed)
        refresher.stop()
    }

    @Test
    fun `session idle event refreshes session directory`() = runTest {
        val events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 16)
        val refreshed = mutableListOf<List<Path>>()
        val refresher = refresher(this, events, { id -> if (id == "s1") "C:/proj/.kilo/sessions/s1" else null }, refreshed)
        refresher.start()
        advanceUntilIdle()

        events.emit(BackendEvent("session.status", """{"sessionID":"s1","status":{"type":"idle"}}"""))
        advanceUntilIdle()

        assertEquals(listOf(listOf(Path.of("C:/proj/.kilo/sessions/s1"))), refreshed)
        refresher.stop()
    }

    @Test
    fun `non idle session status and unrelated events do not refresh`() = runTest {
        val events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 16)
        val refreshed = mutableListOf<List<Path>>()
        val refresher = refresher(this, events, { "C:/proj/.kilo/sessions/s1" }, refreshed)
        refresher.start()
        advanceUntilIdle()

        events.emit(BackendEvent("session.status", """{"sessionID":"s1","status":{"type":"busy"}}"""))
        events.emit(BackendEvent("message.part.delta", """{"sessionID":"s1","text":"hi"}"""))
        events.emit(BackendEvent("host.file.write", "not json"))
        advanceUntilIdle()

        assertTrue(refreshed.isEmpty(), "unexpected refreshes: $refreshed")
        refresher.stop()
    }
}
