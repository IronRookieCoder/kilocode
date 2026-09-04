package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Upper bound for waiting on the fake csc process or its termination inside a test. */
private const val AWAIT_STOPPED_MS = 10_000L

class CscCloudStarterTest {
    @Test
    fun `runs csc cloud start and reports success`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = Files.createTempDirectory("csc-bin")
        val script = bin.resolve("csc").toFile()
        script.writeText("#!/bin/sh\nexit 0\n")
        script.setExecutable(true)
        val env = System.getenv().toMutableMap().apply {
            put("PATH", "${bin.toAbsolutePath()}${File.pathSeparator}${get("PATH").orEmpty()}")
        }

        val result = CscCloudStarter(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList()).start()

        assertTrue(result.ok, "result=$result")
    }

    @Test
    fun `reports a friendly error when csc is missing everywhere`() = runBlocking {
        val env = mapOf("PATH" to Files.createTempDirectory("csc-empty").toString())

        val result = CscCloudStarter(env, TestLog, timeoutSeconds = 5, extraDirs = emptyList()).start()

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("csc is not installed"), "message=${result.message}")
        assertEquals(ConnectionErrorCode.CSC_NOT_INSTALLED, result.code)
        assertFalse(result.message.orEmpty().contains("restart the IDE"), "message=${result.message}")
    }

    @Test
    fun `finds csc in extra dirs when the IDE PATH misses it`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = Files.createTempDirectory("csc-extra")
        val script = bin.resolve("csc").toFile()
        script.writeText("#!/bin/sh\nexit 0\n")
        script.setExecutable(true)
        val env = mapOf("PATH" to Files.createTempDirectory("csc-empty").toString())

        val result = CscCloudStarter(env, TestLog, timeoutSeconds = 10, extraDirs = listOf(bin.toString())).start()

        assertTrue(result.ok, "result=$result")
    }

    @Test
    fun `reports csc output when csc exits non zero`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = Files.createTempDirectory("csc-fail-bin")
        val script = bin.resolve("csc").toFile()
        script.writeText("#!/bin/sh\necho 'boom' >&2\nexit 3\n")
        script.setExecutable(true)
        val env = System.getenv().toMutableMap().apply {
            put("PATH", "${bin.toAbsolutePath()}${File.pathSeparator}${get("PATH").orEmpty()}")
        }

        val result = CscCloudStarter(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList()).start()

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("boom"), "message=${result.message}")
    }

    @Test
    fun `reports the timeout and kills csc when the start hangs`() = runBlocking {
        if (isWindows()) return@runBlocking
        val env = mapOf("PATH" to fakeCsc(Files.createTempDirectory("csc-timeout-bin"), "exec sleep 30\n").toString())

        val result = CscCloudStarter(env, TestLog, timeoutSeconds = 1, extraDirs = emptyList()).start()

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("did not finish within 1s"), "message=${result.message}")
    }

    @Test
    fun `cancelling the start stops the csc process`() = runBlocking {
        if (isWindows()) return@runBlocking
        val dir = Files.createTempDirectory("csc-start-cancel")
        val pidFile = dir.resolve("csc.pid").toFile()
        val started = dir.resolve("started.marker").toFile()
        pidFile.deleteOnExit()
        started.deleteOnExit()
        // exec replaces the shell with sleep, so the recorded pid is the process the plugin spawns;
        // `touch` after the pid write means the pid file is complete once the marker shows up.
        val bin = fakeCsc(dir, "echo \$\$ > ${pidFile.absolutePath}\ntouch ${started.absolutePath}\nexec sleep 60\n")
        val env = mapOf("PATH" to bin.toString())

        val start = async { CscCloudStarter(env, TestLog, timeoutSeconds = 60, extraDirs = emptyList()).start() }
        withTimeout(AWAIT_STOPPED_MS) { while (!started.exists()) delay(20) }
        val pid = pidFile.readText().trim().toLong()
        assertTrue(ProcessHandle.of(pid).isPresent, "expected the fake csc process to be running")

        start.cancelAndJoin()

        withTimeout(AWAIT_STOPPED_MS) { while (ProcessHandle.of(pid).isPresent) delay(20) }
        assertFalse(ProcessHandle.of(pid).isPresent, "csc process $pid survived the cancellation")
    }

    /** Writes a fake `csc` script with [body] inside [dir] and returns [dir] as the lookup root. */
    private fun fakeCsc(dir: Path, body: String): Path {
        val script = dir.resolve("csc").toFile()
        script.writeText("#!/bin/sh\n$body")
        script.setExecutable(true)
        return dir
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private object TestLog : KiloLog {
        override val isDebugEnabled = false
        override fun debug(block: () -> String) = Unit
        override fun info(msg: String) = Unit
        override fun warn(msg: String, t: Throwable?) = Unit
        override fun error(msg: String, t: Throwable?) = Unit
    }
}
