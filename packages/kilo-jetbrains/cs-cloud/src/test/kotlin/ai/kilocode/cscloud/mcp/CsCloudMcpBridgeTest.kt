package ai.kilocode.cscloud.mcp

import ai.kilocode.log.KiloLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CsCloudMcpBridgeTest {
    @Test
    fun `lease cancellation is not logged as a failure`() = runBlocking {
        val ready = CompletableDeferred<IdeMcpTransport>()
        val log = TestLog()

        assertFailsWith<CancellationException> {
            runLease(ready, "lease failed", log) {
                throw CancellationException("released")
            }
        }

        assertFailsWith<CancellationException> { ready.await() }
        assertEquals(0, log.warnings)
    }

    @Test
    fun `lease failure completes readiness and is logged`() = runBlocking {
        val ready = CompletableDeferred<IdeMcpTransport>()
        val log = TestLog()
        val error = IllegalStateException("failed")

        runLease(ready, "lease failed", log) { throw error }

        val failure = assertFailsWith<IllegalStateException> { ready.await() }
        assertEquals("failed", failure.message)
        assertEquals(1, log.warnings)
        assertSame(error, log.error)
    }

    private class TestLog : KiloLog {
        var warnings = 0
        var error: Throwable? = null
        override val isDebugEnabled = false
        override fun debug(block: () -> String) = Unit
        override fun info(msg: String) = Unit
        override fun warn(msg: String, t: Throwable?) {
            warnings++
            error = t
        }
        override fun error(msg: String, t: Throwable?) = Unit
    }
}
