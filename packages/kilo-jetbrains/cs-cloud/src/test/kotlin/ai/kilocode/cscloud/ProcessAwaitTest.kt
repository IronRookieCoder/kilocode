package ai.kilocode.cscloud

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cancellation/timeout behaviour of the process wait helpers, on a fake [Process] so the
 * scenarios run on every OS (the real-process variants live in [CscInstallerTest] and
 * [CscCloudStarterTest], which need POSIX shell scripts and are skipped on Windows).
 */
class ProcessAwaitTest {

    @Test
    fun `awaitExitOrTimeout returns true once the process exits`() = runBlocking {
        val proc = FakeProcess(exitAfterMillis = 50)

        assertTrue(proc.awaitExitOrTimeout(5), "a finished process must count as exited")
        assertEquals(0, proc.exitValue())
        assertFalse(proc.destroyed, "a clean exit must not terminate the process")
    }

    @Test
    fun `awaitExitOrTimeout returns false when the timeout elapses first`() = runBlocking {
        val proc = FakeProcess(exitAfterMillis = null)

        assertFalse(proc.awaitExitOrTimeout(1), "a hanging process must hit the timeout")
        assertFalse(proc.destroyed, "the timeout path stops the process itself (destroyForcibly at the call site)")
    }

    @Test
    fun `awaitExitOrTimeout surfaces cancellation instead of blocking until the child exits`() = runBlocking {
        val proc = FakeProcess(exitAfterMillis = null)
        val wait = async(Dispatchers.Default) { proc.awaitExitOrTimeout(60) }

        wait.cancelAndJoin()

        assertTrue(proc.isAliveForTest, "cancel must leave stopping the process to the caller")
        proc.terminate(graceMillis = 50)
        assertTrue(proc.destroyed, "the caller stops the process on cancellation")
    }

    @Test
    fun `terminate escalates to destroyForcibly when the child ignores the graceful stop`() {
        val stubborn = FakeProcess(exitAfterMillis = null)

        stubborn.terminate(graceMillis = 50)

        assertTrue(stubborn.destroyed)
        assertTrue(stubborn.forceKilled, "a child that ignores destroy() must be force-killed")
    }

    @Test
    fun `terminate does not force kill a child that reacts to the graceful stop`() {
        val polite = FakeProcess(exitAfterMillis = 20)

        polite.terminate(graceMillis = 5_000)

        assertTrue(polite.destroyed)
        assertFalse(polite.forceKilled, "a child that dies on destroy() must not be force-killed")
    }
}

/**
 * [Process] stand-in whose `exitValue` flips to 0 after [exitAfterMillis] (never when null).
 * `Process.waitFor(timeout, unit)` is inherited from [Process] and polls `exitValue`, so the
 * helpers under test exercise their real polling logic against this fake.
 */
private class FakeProcess(private val exitAfterMillis: Long?) : Process() {
    private val startedAtNanos = System.nanoTime()
    private var forceKilledElsewhere = false

    var destroyed = false
        private set
    var forceKilled = false
        private set

    val isAliveForTest: Boolean get() = !hasExited

    private val elapsedMillis: Long
        get() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    private val hasExited: Boolean
        get() = forceKilledElsewhere || (exitAfterMillis != null && elapsedMillis >= exitAfterMillis)

    override fun exitValue(): Int {
        if (!hasExited) throw IllegalThreadStateException("still running")
        return 0
    }

    override fun waitFor(): Int {
        while (!hasExited) Thread.sleep(20)
        return 0
    }

    override fun destroy() {
        destroyed = true
    }

    override fun destroyForcibly(): Process {
        destroyed = true
        forceKilled = true
        forceKilledElsewhere = true
        return this
    }

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
}
