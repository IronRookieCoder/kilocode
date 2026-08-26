package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private object TestLog : KiloLog {
        override val isDebugEnabled = false
        override fun debug(block: () -> String) = Unit
        override fun info(msg: String) = Unit
        override fun warn(msg: String, t: Throwable?) = Unit
        override fun error(msg: String, t: Throwable?) = Unit
    }
}
