package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CscLoginTest {
    @Test
    fun `reports a friendly error when csc is missing`() = runBlocking {
        val env = mapOf("PATH" to Files.createTempDirectory("csc-no-login").toString())

        val result = CscLogin(env, TestLog, timeoutSeconds = 5, extraDirs = emptyList()).login()

        assertFalse(result.ok)
        assertEquals(ConnectionErrorCode.CSC_NOT_INSTALLED, result.code)
    }

    @Test
    fun `succeeds when csc auth login exits zero`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = Files.createTempDirectory("csc-login-bin")
        val script = bin.resolve("csc").toFile()
        script.writeText("#!/bin/sh\necho 'Signed in as user@example.com'\nexit 0\n")
        script.setExecutable(true)
        val env = mapOf("PATH" to bin.toString())

        val result = CscLogin(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList()).login()

        assertTrue(result.ok, "result=$result")
        assertTrue(result.message.orEmpty().contains("Signed in"), "message=${result.message}")
    }

    @Test
    fun `reports the csc output when login fails`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = Files.createTempDirectory("csc-login-fail")
        val script = bin.resolve("csc").toFile()
        script.writeText("#!/bin/sh\necho 'authentication cancelled' >&2\nexit 1\n")
        script.setExecutable(true)
        val env = mapOf("PATH" to bin.toString())

        val result = CscLogin(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList()).login()

        assertFalse(result.ok)
        assertTrue(result.message.orEmpty().contains("authentication cancelled"), "message=${result.message}")
    }

    @Test
    fun `returns ok while the browser flow is still pending after the timeout`() = runBlocking {
        if (isWindows()) return@runBlocking
        val bin = Files.createTempDirectory("csc-login-pending")
        val script = bin.resolve("csc").toFile()
        // Mimics the real command blocking until OAuth completes: it exits on its own shortly after.
        script.writeText("#!/bin/sh\nsleep 3\nexit 0\n")
        script.setExecutable(true)
        val env = mapOf("PATH" to bin.toString())

        val result = CscLogin(env, TestLog, timeoutSeconds = 1, extraDirs = emptyList()).login()

        assertTrue(result.ok, "result=$result")
        assertTrue(result.message.orEmpty().contains("browser"), "message=${result.message}")
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
