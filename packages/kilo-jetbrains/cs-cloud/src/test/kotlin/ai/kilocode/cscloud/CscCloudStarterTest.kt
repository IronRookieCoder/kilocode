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




    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private object TestLog : KiloLog {
        override val isDebugEnabled = false
        override fun debug(block: () -> String) = Unit
        override fun info(msg: String) = Unit
        override fun warn(msg: String, t: Throwable?) = Unit
        override fun error(msg: String, t: Throwable?) = Unit
    }
}
