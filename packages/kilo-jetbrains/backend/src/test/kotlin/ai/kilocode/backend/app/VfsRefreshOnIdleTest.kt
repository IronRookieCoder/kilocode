package ai.kilocode.backend.app

import ai.kilocode.backend.testing.FakeCliServer
import ai.kilocode.backend.testing.MockCliServer
import ai.kilocode.backend.testing.TestLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that the backend triggers VFS refresh when a session transitions
 * from busy → idle, so that files written by csc directly to the project
 * directory are picked up by IntelliJ editors and the project tree.
 */
class VfsRefreshOnIdleTest {

    private val mock = MockCliServer()
    private val log = TestLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val apps = mutableListOf<KiloBackendAppService>()

    @AfterTest
    fun tearDown() {
        apps.forEach { it.dispose() }
        apps.clear()
        scope.cancel()
        mock.close()
    }

    private fun setup(): KiloBackendAppService {
        return KiloBackendAppService.create(scope, FakeCliServer(mock), log).also { apps.add(it) }
    }

    private suspend fun ready(app: KiloBackendAppService) {
        app.connect()
        withTimeout(10_000) {
            app.appState.first { it is KiloAppState.Ready }
        }
    }

    @Test
    fun `session idle triggers VFS refresh log`() = runBlocking {
        // Setup: create a session with a known directory
        mock.sessions = """[
            {"id":"ses_vfs","slug":"s","projectID":"p","directory":"/tmp/test-project","title":"T","version":"1","time":{"created":1,"updated":1}}
        ]"""
        val app = setup()
        ready(app)

        // Register the session directory so sessionDirectory() can find it
        app.sessions.setDirectory("ses_vfs", "/tmp/test-project")

        // Wait for SSE connection
        mock.awaitSseConnection(5_000)

        // Simulate session becoming busy then idle via SSE
        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_vfs","status":{"type":"busy","attempt":0,"message":"processing","next":0,"requestID":""}}}""",
        )
        delay(200)
        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_vfs","status":{"type":"idle","attempt":0,"message":"","next":0,"requestID":""}}}""",
        )
        delay(500)

        // Verify: the log should contain a VFS refresh entry
        val vfsLogs = log.messages.filter { it.contains("vfs-refresh") }
        assertTrue(vfsLogs.isNotEmpty(), "Expected VFS refresh log entries, got: ${log.messages}")
        assertTrue(vfsLogs.any { it.contains("ses_vfs") }, "Expected VFS refresh for ses_vfs")
    }

    @Test
    fun `session staying busy does not trigger VFS refresh`() = runBlocking {
        mock.sessions = """[
            {"id":"ses_busy","slug":"s","projectID":"p","directory":"/tmp/test-project","title":"T","version":"1","time":{"created":1,"updated":1}}
        ]"""
        val app = setup()
        ready(app)

        app.sessions.setDirectory("ses_busy", "/tmp/test-project")
        mock.awaitSseConnection(5_000)

        // Simulate session becoming busy (no idle transition)
        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_busy","status":{"type":"busy","attempt":0,"message":"processing","next":0,"requestID":""}}}""",
        )
        delay(300)

        // Verify: no VFS refresh log
        val vfsLogs = log.messages.filter { it.contains("vfs-refresh") }
        assertTrue(vfsLogs.isEmpty(), "Expected no VFS refresh log entries for busy session")
    }

    @Test
    fun `idle without known directory skips VFS refresh`() = runBlocking {
        mock.sessions = """[
            {"id":"ses_unknown","slug":"s","projectID":"p","directory":"/tmp/test-project","title":"T","version":"1","time":{"created":1,"updated":1}}
        ]"""
        val app = setup()
        ready(app)

        // Do NOT set directory for this session
        mock.awaitSseConnection(5_000)

        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_unknown","status":{"type":"busy","attempt":0,"message":"processing","next":0,"requestID":""}}}""",
        )
        delay(200)
        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_unknown","status":{"type":"idle","attempt":0,"message":"","next":0,"requestID":""}}}""",
        )
        delay(500)

        // Verify: no VFS refresh log (directory unknown)
        val vfsLogs = log.messages.filter { it.contains("vfs-refresh") }
        assertTrue(vfsLogs.isEmpty(), "Expected no VFS refresh when directory is unknown")
    }

    @Test
    fun `multiple idle transitions trigger multiple VFS refreshes`() = runBlocking {
        mock.sessions = """[
            {"id":"ses_multi","slug":"s","projectID":"p","directory":"/tmp/test-project","title":"T","version":"1","time":{"created":1,"updated":1}}
        ]"""
        val app = setup()
        ready(app)

        app.sessions.setDirectory("ses_multi", "/tmp/test-project")
        mock.awaitSseConnection(5_000)

        // First cycle: busy → idle
        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_multi","status":{"type":"busy","attempt":0,"message":"processing","next":0,"requestID":""}}}""",
        )
        delay(200)
        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_multi","status":{"type":"idle","attempt":0,"message":"","next":0,"requestID":""}}}""",
        )
        delay(500)

        // Second cycle: busy → idle
        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_multi","status":{"type":"busy","attempt":0,"message":"processing","next":0,"requestID":""}}}""",
        )
        delay(200)
        mock.pushEvent(
            "session.status",
            """{"type":"session.status","properties":{"sessionID":"ses_multi","status":{"type":"idle","attempt":0,"message":"","next":0,"requestID":""}}}""",
        )
        delay(500)

        // Verify: two VFS refresh log entries
        val vfsLogs = log.messages.filter { it.contains("vfs-refresh") && it.contains("directory=") }
        assertEquals(2, vfsLogs.size, "Expected two VFS refresh log entries, got: ${vfsLogs}")
    }
}
