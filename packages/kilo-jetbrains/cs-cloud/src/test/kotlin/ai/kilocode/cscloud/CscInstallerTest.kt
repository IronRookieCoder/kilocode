package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.stability.Fact
import ai.kilocode.stability.Fixture
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

/** Upper bound for waiting on the fake npm process or its termination inside a test. */
private const val AWAIT_STARTED_MS = 10_000L

class CscInstallerTest {
    @Test
    fun `reports a friendly error when no package manager is available`() = runBlocking {
        Fixture().use { fixture ->
            val env = mapOf("PATH" to Files.createTempDirectory("csc-no-npm").toString())

            val result = CscInstaller(env, TestLog, timeoutSeconds = 5, extraDirs = emptyList(), operations = fixture.operations).install()

            assertFalse(result.ok)
            assertEquals(ConnectionErrorCode.NPM_NOT_FOUND, result.code)
            assertTrue(result.message.orEmpty().contains("package manager"), "message=${result.message}")
            fixture.flush()
            // M06无包管理器=缺少前置条件的blocked，而非技术失败（metrics 1.2）。
            val install = fixture.facts().filter { it.name == "csc.install" }
            assertTrue(install.any { it.data["phase"]?.jsonPrimitive?.content == "start" }, "missing start fact: ${install.map { it.data }}")
            val end = ends(install).single()
            assertEquals("blocked", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("discover", end.data["stage"]?.jsonPrimitive?.content)
            assertEquals("npm_not_found", end.data["error_code"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `installs csc via npm found on the PATH`() = runBlocking {
        if (isWindows()) return@runBlocking
        Fixture().use { fixture ->
            val bin = Files.createTempDirectory("csc-install-bin")
            val script = bin.resolve("npm").toFile()
            script.writeText("#!/bin/sh\nexit 0\n")
            script.setExecutable(true)
            // 命令成功且工具可发现才success：可发现的csc由同一发现路径（findCsc）确认。
            val csc = bin.resolve("csc").toFile()
            csc.writeText("#!/bin/sh\nexit 0\n")
            csc.setExecutable(true)
            val env = mapOf("PATH" to bin.toString())

            val result = CscInstaller(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList(), operations = fixture.operations).install()

            assertTrue(result.ok, "result=$result")
            assertTrue(result.message.orEmpty().contains("npm"), "message=${result.message}")
            fixture.flush()
            val install = fixture.facts().filter { it.name == "csc.install" }
            assertTrue(install.any { it.data["phase"]?.jsonPrimitive?.content == "start" }, "missing start fact: ${install.map { it.data }}")
            val start = install.single { it.data["phase"]?.jsonPrimitive?.content == "start" }
            assertEquals("npm", start.data["package_manager"]?.jsonPrimitive?.content)
            val end = ends(install).single()
            assertEquals("success", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("discover", end.data["stage"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `exit zero without a discoverable csc does not end install success`() = runBlocking {
        if (isWindows()) return@runBlocking
        Fixture().use { fixture ->
            val bin = Files.createTempDirectory("csc-install-ghost")
            val script = bin.resolve("npm").toFile()
            script.writeText("#!/bin/sh\nexit 0\n")
            script.setExecutable(true)
            val env = mapOf("PATH" to bin.toString())

            val result = CscInstaller(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList(), operations = fixture.operations).install()

            // 业务结果保持不变（installCsc随后照常start）；但遥测不得把exit 0当安装成功。
            assertTrue(result.ok, "result=$result")
            fixture.flush()
            val install = fixture.facts().filter { it.name == "csc.install" }
            assertTrue(
                ends(install).none { it.data["result"]?.jsonPrimitive?.content == "success" },
                "exit 0 without discoverability must not end success: ${install.map { it.data }}",
            )
            val end = ends(install).single()
            assertEquals("failure", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("discover", end.data["stage"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `reports the package manager output when install fails`() = runBlocking {
        if (isWindows()) return@runBlocking
        Fixture().use { fixture ->
            val bin = Files.createTempDirectory("csc-install-fail")
            val script = bin.resolve("npm").toFile()
            script.writeText("#!/bin/sh\necho 'ERESOLVE unable to resolve dependency tree' >&2\nexit 1\n")
            script.setExecutable(true)
            val env = mapOf("PATH" to bin.toString())

            val result = CscInstaller(env, TestLog, timeoutSeconds = 10, extraDirs = emptyList(), operations = fixture.operations).install()

            assertFalse(result.ok)
            assertTrue(result.message.orEmpty().contains("ERESOLVE"), "message=${result.message}")
            fixture.flush()
            val end = ends(fixture.facts().filter { it.name == "csc.install" }).single()
            assertEquals("failure", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("exit", end.data["stage"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `reports the timeout and kills npm when the install hangs`() = runBlocking {
        if (isWindows()) return@runBlocking
        Fixture().use { fixture ->
            val bin = fakeNpm(Files.createTempDirectory("csc-timeout-bin"), "exec sleep 30\n")
            val env = mapOf("PATH" to bin.toString())

            val result = CscInstaller(env, TestLog, timeoutSeconds = 1, extraDirs = emptyList(), operations = fixture.operations).install()

            assertFalse(result.ok)
            assertTrue(result.message.orEmpty().contains("did not finish within 1s"), "message=${result.message}")
            // 安装deadline=真实timeoutSeconds业务等待：正确timeout终态必须存在。
            factsUntil(fixture) { facts -> ends(facts.filter { it.name == "csc.install" }).isNotEmpty() }
            fixture.flush()
            val end = ends(fixture.facts().filter { it.name == "csc.install" }).single()
            assertEquals("timeout", end.data["result"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `cancelling the install stops the package manager process`() = runBlocking {
        if (isWindows()) return@runBlocking
        Fixture().use { fixture ->
            val dir = Files.createTempDirectory("csc-install-cancel")
            val pidFile = dir.resolve("npm.pid").toFile()
            val started = dir.resolve("started.marker").toFile()
            pidFile.deleteOnExit()
            started.deleteOnExit()
            // exec replaces the shell with sleep, so the recorded pid is the process the plugin spawns;
            // `touch` after the pid write means the pid file is complete once the marker shows up.
            val bin = fakeNpm(dir, "echo \$\$ > ${pidFile.absolutePath}\ntouch ${started.absolutePath}\nexec sleep 60\n")
            val env = mapOf("PATH" to bin.toString())

            val install = async { CscInstaller(env, TestLog, timeoutSeconds = 60, extraDirs = emptyList(), operations = fixture.operations).install() }
            withTimeout(AWAIT_STARTED_MS) { while (!started.exists()) delay(20) }
            val pid = pidFile.readText().trim().toLong()
            assertTrue(ProcessHandle.of(pid).isPresent, "expected the fake npm process to be running")

            install.cancelAndJoin()

            withTimeout(AWAIT_STARTED_MS) { while (ProcessHandle.of(pid).isPresent) delay(20) }
            assertFalse(ProcessHandle.of(pid).isPresent, "npm process $pid survived the cancellation")
            fixture.flush()
            val end = ends(fixture.facts().filter { it.name == "csc.install" }).single()
            assertEquals("cancelled", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("user", end.data["cause"]?.jsonPrimitive?.content)
        }
    }

    private fun ends(facts: List<Fact>) =
        facts.filter { it.data["phase"]?.jsonPrimitive?.content == "end" }

    /** timeout终态由Operation定时器在真实时间上结算：轮询flush直到出现（B2测试同型）。 */
    private fun factsUntil(fixture: Fixture, timeoutMs: Long = 10_000, predicate: (List<Fact>) -> Boolean): List<Fact> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var facts = emptyList<Fact>()
        while (System.currentTimeMillis() < deadline) {
            fixture.flush()
            facts = fixture.facts()
            if (predicate(facts)) return facts
            Thread.sleep(50)
        }
        error("observation condition not met within ${timeoutMs}ms; facts=${facts.map { it.name to it.data.toString() }}")
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
