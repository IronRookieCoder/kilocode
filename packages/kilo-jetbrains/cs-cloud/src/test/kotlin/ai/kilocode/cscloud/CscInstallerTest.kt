package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
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

/** Upper bound for waiting on the fake npm process or its termination inside a test. */
private const val AWAIT_STARTED_MS = 10_000L

class CscInstallerTest {
    @Test
    fun `reports a friendly error when no package manager is available`() = runBlocking {
        val env = mapOf("PATH" to Files.createTempDirectory("csc-no-npm").toString())

        val result = CscInstaller(env, TestLog, timeoutSeconds = 5, extraDirs = emptyList()).install()

        assertFalse(result.ok)
        assertEquals(ConnectionErrorCode.NPM_NOT_FOUND, result.code)
        assertTrue(result.message.orEmpty().contains("package manager"), "message=${result.message}")
    }

    @Test
    fun `installs csc via npm found on the PATH`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = Files.createTempDirectory("csc-install-bin")
        val script = bin.resolve("npm").toFile()
        script.writeText("#!/bin/sh\nexit 0\n")
        script.setExecutable(true)
        val env = mapOf("PATH" to bin.toString())

        val result = CscInstaller(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList()).install()

        assertTrue(result.ok, "result=$result")
        assertTrue(result.message.orEmpty().contains("npm"), "message=${result.message}")
    }

    @Test
    fun `reports the package manager output when install fails`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = Files.createTempDirectory("csc-install-fail")
        val script = bin.resolve("npm").toFile()
        script.writeText("#!/bin/sh\necho 'ERESOLVE unable to resolve dependency tree' >&2\nexit 1\n")
        script.setExecutable(true)
        val env = mapOf("PATH" to bin.toString())

        val result = CscInstaller(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList()).install()

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("ERESOLVE"), "message=${result.message}")
    }

    @Test
    fun `reports the timeout and kills npm when the install hangs`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = fakeNpm(Files.createTempDirectory("csc-timeout-bin"), "exec sleep 30\n")
        val env = mapOf("PATH" to bin.toString())

        val result = CscInstaller(env, TestLog, timeoutSeconds = 1, extraDirs = emptyList()).install()

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("did not finish within 1s"), "message=${result.message}")
    }

    @Test
    fun `cancelling the install stops the package manager process`() = runBlocking {
        if (isWindows()) return@runBlocking
        val dir = Files.createTempDirectory("csc-install-cancel")
        val pidFile = dir.resolve("npm.pid").toFile()
        val started = dir.resolve("started.marker").toFile()
        pidFile.deleteOnExit()
        started.deleteOnExit()
        // exec replaces the shell with sleep, so the recorded pid is the process the plugin spawns;
        // `touch` after the pid write means the pid file is complete once the marker shows up.
        val bin = fakeNpm(dir, "echo \$\$ > ${pidFile.absolutePath}\ntouch ${started.absolutePath}\nexec sleep 60\n")
        val env = mapOf("PATH" to bin.toString())

        val install = async { CscInstaller(env, TestLog, timeoutSeconds = 60, extraDirs = emptyList()).install() }
        withTimeout(AWAIT_STARTED_MS) { while (!started.exists()) delay(20) }
        val pid = pidFile.readText().trim().toLong()
        assertTrue(ProcessHandle.of(pid).isPresent, "expected the fake npm process to be running")

        install.cancelAndJoin()

        withTimeout(AWAIT_STARTED_MS) { while (ProcessHandle.of(pid).isPresent) delay(20) }
        assertFalse(ProcessHandle.of(pid).isPresent, "npm process $pid survived the cancellation")
    }

    /** Writes a fake `npm` script with [body] inside [dir] and returns [dir] as the lookup root. */
    private fun fakeNpm(dir: java.nio.file.Path, body: String): java.nio.file.Path {
        val script = dir.resolve("npm").toFile()
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
