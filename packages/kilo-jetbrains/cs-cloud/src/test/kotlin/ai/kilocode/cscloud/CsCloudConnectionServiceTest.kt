package ai.kilocode.cscloud

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CsCloudConnectionServiceTest {

    private val workspace: Path = Files.createTempDirectory("cscloud-svc-ws")

    private class FakeSse : CsCloudSseStream {
        var started = false
        var closed = false
        lateinit var listener: CsCloudSseListener
        override fun start() {
            started = true
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeSseFactory {
        var last: FakeSse? = null
        fun create(endpoint: CsCloudEndpoint, path: Path, listener: CsCloudSseListener): FakeSse =
            FakeSse().also {
                it.listener = listener
                last = it
            }
    }

    private class RecordingListener : CsCloudStatusListener {
        val statuses = mutableListOf<CsCloudConnectionStatus>()
        override fun onStatusChanged(status: CsCloudConnectionStatus) {
            statuses.add(status)
        }
    }

    private class Harness(
        val service: CsCloudConnectionService,
        val listener: RecordingListener,
        val sseFactory: FakeSseFactory,
        val events: MutableList<SseEvent>,
        private val resolveCountRef: () -> Int,
    ) {
        fun resolveCount(): Int = resolveCountRef()

        fun lastSse(): FakeSse = sseFactory.last!!
    }

    private fun harness(
        scope: CoroutineScope,
        resolve: () -> Result<CsCloudEndpoint> = { Result.success(CsCloudEndpoint("http://127.0.0.1:3012", "key")) },
        health: suspend (CsCloudEndpoint) -> CsCloudHealthCheckOutcome = {
            CsCloudHealthCheckOutcome.Healthy
        },
    ): Harness {
        val listener = RecordingListener()
        val sseFactory = FakeSseFactory()
        val events = mutableListOf<SseEvent>()
        var resolveCount = 0
        val h = Harness(
            service = CsCloudConnectionService(
                scope = scope,
                resolveEndpoint = {
                    resolveCount++
                    resolve()
                },
                checkHealth = health,
                openSse = { endpoint, path, sseListener -> sseFactory.create(endpoint, path, sseListener) },
                currentWorkspace = { workspace },
                onEvent = { events.add(it) },
                backoffMillis = CsCloudConnectionService::defaultBackoffMillis,
                notifier = CsCloudStatusNotifier().also { it.add(listener) },
            ),
            listener = listener,
            sseFactory = sseFactory,
            events = events,
            resolveCountRef = { resolveCount },
        )
        return h
    }

    @Test
    fun `reaches ready and notifies listeners when healthy`() = runTest {
        val h = harness(this)
        h.service.connect()
        advanceUntilIdle()
        h.lastSse().listener.onOpen()
        advanceUntilIdle()

        assertEquals(CsCloudConnectionState.Ready, h.service.currentStatus.state)
        val states = h.listener.statuses.map { it.state }
        assertTrue(CsCloudConnectionState.Discovering in states)
        assertTrue(CsCloudConnectionState.Connecting in states)
        assertEquals(CsCloudConnectionState.Ready, states.last())
    }

    @Test
    fun `discovery failure lands in unavailable without retry`() = runTest {
        val h = harness(this, resolve = { Result.failure(CsCloudDiscoveryError.MissingKey()) })
        h.service.connect()
        advanceUntilIdle()

        assertEquals(CsCloudConnectionState.Unavailable, h.service.currentStatus.state)
        assertEquals(1, h.resolveCount())
        assertNull(h.service.currentStatus.diagnosis)
    }

    @Test
    fun `401 health check maps to credentials invalid`() = runTest {
        val h = harness(
            this,
            health = { CsCloudHealthCheckOutcome.Failed(CsCloudHealthDiagnosis.CREDENTIALS_INVALID, "rejected") },
        )
        h.service.connect()
        advanceUntilIdle()

        assertEquals(CsCloudConnectionState.Unavailable, h.service.currentStatus.state)
        assertEquals(CsCloudHealthDiagnosis.CREDENTIALS_INVALID, h.service.currentStatus.diagnosis)
    }

    @Test
    fun `503 health check maps to agent not ready`() = runTest {
        val h = harness(
            this,
            health = { CsCloudHealthCheckOutcome.Failed(CsCloudHealthDiagnosis.AGENT_NOT_READY, "booting") },
        )
        h.service.connect()
        advanceUntilIdle()

        assertEquals(CsCloudConnectionState.Unavailable, h.service.currentStatus.state)
        assertEquals(CsCloudHealthDiagnosis.AGENT_NOT_READY, h.service.currentStatus.diagnosis)
    }

    @Test
    fun `network failure maps to daemon not running`() = runTest {
        val h = harness(
            this,
            health = { CsCloudHealthCheckOutcome.Failed(CsCloudHealthDiagnosis.DAEMON_NOT_RUNNING, "refused") },
        )
        h.service.connect()
        advanceUntilIdle()

        assertEquals(CsCloudConnectionState.Unavailable, h.service.currentStatus.state)
        assertEquals(CsCloudHealthDiagnosis.DAEMON_NOT_RUNNING, h.service.currentStatus.diagnosis)
    }

    @Test
    fun `sse disconnect reconnects with exponential backoff`() = runTest {
        val h = harness(this)
        h.service.connect()
        advanceUntilIdle()
        h.lastSse().listener.onOpen()
        advanceUntilIdle()
        assertEquals(1, h.resolveCount())

        h.lastSse().listener.onClosed()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, h.resolveCount(), "no reconnect before the 1s backoff elapses")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, h.resolveCount(), "first reconnect fires after 1s")
    }

    @Test
    fun `reconnect backoff doubles on repeated failures`() = runTest {
        val h = harness(this)
        h.service.connect()
        advanceUntilIdle()
        h.lastSse().listener.onOpen()
        advanceUntilIdle()

        h.lastSse().listener.onClosed()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.resolveCount())

        h.lastSse().listener.onClosed()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, h.resolveCount(), "second reconnect waits the full 2s backoff")

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, h.resolveCount())
    }

    @Test
    fun `reconnected stream emits session_reconnected signal`() = runTest {
        val h = harness(this)
        h.service.connect()
        advanceUntilIdle()
        h.lastSse().listener.onOpen()
        advanceUntilIdle()
        assertTrue(h.events.none { it.type == "session.reconnected" })

        h.lastSse().listener.onClosed()
        advanceTimeBy(1_000)
        runCurrent()
        h.lastSse().listener.onOpen()
        advanceUntilIdle()

        assertTrue(h.events.any { it.type == "session.reconnected" })
    }

    @Test
    fun `close tears down the stream and resets to disconnected`() = runTest {
        val h = harness(this)
        h.service.connect()
        advanceUntilIdle()
        h.lastSse().listener.onOpen()
        advanceUntilIdle()

        h.service.close()
        assertTrue(h.lastSse().closed)
        assertEquals(CsCloudConnectionState.Disconnected, h.service.currentStatus.state)
    }

    @Test
    fun `removed listener stops receiving notifications`() = runTest {
        val h = harness(this)
        h.service.removeStatusListener(h.listener)
        h.service.connect()
        advanceUntilIdle()
        assertEquals(0, h.listener.statuses.size)
    }

    @Test
    fun `new sse stream is created per reconnect`() = runTest {
        val h = harness(this)
        h.service.connect()
        advanceUntilIdle()
        val first = h.lastSse()
        h.lastSse().listener.onOpen()
        advanceUntilIdle()

        h.lastSse().listener.onClosed()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(h.lastSse() !== first)
    }
}
