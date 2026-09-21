package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.stability.Fact
import ai.kilocode.stability.Fixture
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Upper bound for waiting on the fake csc process or its termination inside a test. */
private const val AWAIT_STOPPED_MS = 10_000L

/**
 * M07观测（brief Step 3）：csc.start由CsCloudConnectionService协调（begin+健康确认），
 * 测试在此扮演服务的begin角色，直接驱动真实[CscCloudStarter]结算spawn/exit终态；
 * exit 0只是健康阶段的入场券，测试内不做健康确认，因此绝不允许出现success终态。
 */
class CscCloudStarterTest {
    @Test
    fun `runs csc cloud start and reports success`() = runBlocking {
        if (isWindows()) return@runBlocking
        Fixture().use { fixture ->
            val bin = Files.createTempDirectory("csc-bin")
            val script = bin.resolve("csc").toFile()
            script.writeText("#!/bin/sh\nexit 0\n")
            script.setExecutable(true)
            val env = System.getenv().toMutableMap().apply {
                put("PATH", "${bin.toAbsolutePath()}${File.pathSeparator}${get("PATH").orEmpty()}")
            }
            val operation = fixture.operations.begin("csc.start", AWAIT_STOPPED_MS)

            val result = CscCloudStarter(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList()).start(operation)

            assertTrue(result.ok, "result=$result")
            fixture.flush()
            val start = fixture.facts().filter { it.name == "csc.start" }
            // exit 0但未经健康确认：不得等全部streams（M04），也不能只依据Dto.ok（brief）。
            assertTrue(start.any { it.data["phase"]?.jsonPrimitive?.content == "start" }, "missing start fact: ${start.map { it.data }}")
            assertTrue(
                start.none { it.data["phase"]?.jsonPrimitive?.content == "end" },
                "exit 0 alone must not settle csc.start: ${start.map { it.data }}",
            )
            assertTrue(start.any { it.data["phase"]?.jsonPrimitive?.content == "progress" && it.data["stage"]?.jsonPrimitive?.content == "health" })
        }
    }

    @Test
    fun `reports a friendly error when csc is missing everywhere`() = runBlocking {
        Fixture().use { fixture ->
            val env = mapOf("PATH" to Files.createTempDirectory("csc-empty").toString())
            val operation = fixture.operations.begin("csc.start", AWAIT_STOPPED_MS)

            val result = CscCloudStarter(env, TestLog, timeoutSeconds = 5, extraDirs = emptyList()).start(operation)

            assertFalse(result.ok)
            assertTrue(result.message.orEmpty().contains("csc is not installed"), "message=${result.message}")
            assertEquals(ConnectionErrorCode.CSC_NOT_INSTALLED, result.code)
            assertFalse(result.message.orEmpty().contains("restart the IDE"), "message=${result.message}")
            fixture.flush()
            // csc缺失=缺少前置条件的blocked终态（metrics 1.2），code=csc_not_installed。
            val start = fixture.facts().filter { it.name == "csc.start" }
            assertTrue(start.any { it.data["phase"]?.jsonPrimitive?.content == "start" }, "missing start fact: ${start.map { it.data }}")
            val end = ends(start).single()
            assertEquals("blocked", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("spawn", end.data["stage"]?.jsonPrimitive?.content)
            assertEquals("csc_not_installed", end.data["error_code"]?.jsonPrimitive?.content)
        }
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
        Fixture().use { fixture ->
            val bin = Files.createTempDirectory("csc-fail-bin")
            val script = bin.resolve("csc").toFile()
            script.writeText("#!/bin/sh\necho 'boom' >&2\nexit 3\n")
            script.setExecutable(true)
            val env = System.getenv().toMutableMap().apply {
                put("PATH", "${bin.toAbsolutePath()}${File.pathSeparator}${get("PATH").orEmpty()}")
            }
            val operation = fixture.operations.begin("csc.start", AWAIT_STOPPED_MS)

            val result = CscCloudStarter(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList()).start(operation)

            assertFalse(result.ok)
            assertTrue(result.message.orEmpty().contains("boom"), "message=${result.message}")
            fixture.flush()
            val start = fixture.facts().filter { it.name == "csc.start" }
            val end = ends(start).single()
            assertEquals("failure", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("exit", end.data["stage"]?.jsonPrimitive?.content)
            assertEquals("other", end.data["error_code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `reports the timeout and kills csc when the start hangs`() = runBlocking {
        if (isWindows()) return@runBlocking
        Fixture().use { fixture ->
            val env = mapOf("PATH" to fakeCsc(Files.createTempDirectory("csc-timeout-bin"), "exec sleep 30\n").toString())
            // deadline长于业务等待：业务timeout终态确定性地先于观测定时器结算。
            val operation = fixture.operations.begin("csc.start", 60_000)

            val result = CscCloudStarter(env, TestLog, timeoutSeconds = 1, extraDirs = emptyList()).start(operation)

            assertFalse(result.ok)
            assertTrue(result.message.orEmpty().contains("did not finish within 1s"), "message=${result.message}")
            fixture.flush()
            val end = ends(fixture.facts().filter { it.name == "csc.start" }).single()
            assertEquals("timeout", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("exit", end.data["stage"]?.jsonPrimitive?.content)
            assertEquals("timeout", end.data["error_code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `cancelling the start stops the csc process`() = runBlocking {
        if (isWindows()) return@runBlocking
        Fixture().use { fixture ->
            val dir = Files.createTempDirectory("csc-start-cancel")
            val pidFile = dir.resolve("csc.pid").toFile()
            val started = dir.resolve("started.marker").toFile()
            pidFile.deleteOnExit()
            started.deleteOnExit()
            // exec replaces the shell with sleep, so the recorded pid is the process the plugin spawns;
            // `touch` after the pid write means the pid file is complete once the marker shows up.
            val bin = fakeCsc(dir, "echo \$\$ > ${pidFile.absolutePath}\ntouch ${started.absolutePath}\nexec sleep 60\n")
            val env = mapOf("PATH" to bin.toString())
            val operation = fixture.operations.begin("csc.start", 60_000)

            val start = async { CscCloudStarter(env, TestLog, timeoutSeconds = 60, extraDirs = emptyList()).start(operation) }
            withTimeout(AWAIT_STOPPED_MS) { while (!started.exists()) delay(20) }
            val pid = pidFile.readText().trim().toLong()
            assertTrue(ProcessHandle.of(pid).isPresent, "expected the fake csc process to be running")

            start.cancelAndJoin()

            withTimeout(AWAIT_STOPPED_MS) { while (ProcessHandle.of(pid).isPresent) delay(20) }
            assertFalse(ProcessHandle.of(pid).isPresent, "csc process $pid survived the cancellation")
            fixture.flush()
            val end = ends(fixture.facts().filter { it.name == "csc.start" }).single()
            assertEquals("cancelled", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("user", end.data["cause"]?.jsonPrimitive?.content)
        }
    }

    private fun ends(facts: List<Fact>) =
        facts.filter { it.data["phase"]?.jsonPrimitive?.content == "end" }

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
