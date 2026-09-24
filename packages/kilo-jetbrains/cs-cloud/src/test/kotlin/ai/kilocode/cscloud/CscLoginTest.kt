package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.stability.Fact
import ai.kilocode.stability.Fixture
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CscLoginTest {
    @Test
    fun `reports a friendly error when csc is missing`() = runBlocking {
        Fixture().use { fixture ->
            val env = mapOf("PATH" to Files.createTempDirectory("csc-no-login").toString())

            val result = CscLogin(env, TestLog, timeoutSeconds = 5, extraDirs = emptyList(), operations = fixture.operations).login()

            assertFalse(result.ok)
            assertEquals(ConnectionErrorCode.CSC_NOT_INSTALLED, result.code)
            fixture.flush()
            // M08明确缺失阻断：blocked终态在probe阶段落地（brief Step 4，绝不顺序触发两分支）。
            val ready = fixture.facts().filter { it.name == "credentials.ready" }
            assertTrue(ready.any { it.data["phase"]?.jsonPrimitive?.content == "start" }, "missing start fact: ${ready.map { it.data }}")
            val end = ready.single { it.data["phase"]?.jsonPrimitive?.content == "end" }
            assertEquals("blocked", end.data["result"]?.jsonPrimitive?.content)
            assertEquals("probe", end.data["stage"]?.jsonPrimitive?.content)
            assertEquals("csc_not_installed", end.data["error_code"]?.jsonPrimitive?.content)
        }
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
        Fixture().use { fixture ->
            val bin = Files.createTempDirectory("csc-login-pending")
            val script = bin.resolve("csc").toFile()
            // Mimics the real command blocking until OAuth completes: it exits on its own shortly after.
            script.writeText("#!/bin/sh\nsleep 3\nexit 0\n")
            script.setExecutable(true)
            val env = mapOf("PATH" to bin.toString())

            val result = CscLogin(env, TestLog, timeoutSeconds = 1, extraDirs = emptyList(), operations = fixture.operations).login()

            // 迁移brief Step 1逐字断言：ok=true只表示流程还在继续，不能映射为凭据就绪。
            assertTrue(result.ok, "result=$result")
            fixture.flush()
            val success = fixture.facts().filter {
                it.name == "credentials.ready" && it.data["result"]?.jsonPrimitive?.content == "success"
            }
            assertTrue(success.isEmpty())
            // 同时要求start事实与正确的timeout终态存在——未埋点时不得误绿（brief Step 1）。
            factsUntil(fixture) { facts -> ends(facts, "credentials.ready").isNotEmpty() }
            fixture.flush()
            val ready = fixture.facts().filter { it.name == "credentials.ready" }
            assertTrue(ready.any { it.data["phase"]?.jsonPrimitive?.content == "start" }, "missing start fact: ${ready.map { it.data }}")
            val end = ends(ready).single()
            assertEquals("timeout", end.data["result"]?.jsonPrimitive?.content)
            assertTrue(
                end.data["stage"]?.jsonPrimitive?.content == "wait" || end.data["stage"]?.jsonPrimitive?.content == "unknown",
                "stage=${end.data["stage"]}",
            )
            assertTrue(ready.none { it.data["phase"]?.jsonPrimitive?.content == "end" && it.data["result"]?.jsonPrimitive?.content == "success" })
        }
    }

    private fun ends(facts: List<Fact>, name: String? = null) =
        facts.filter { (name == null || it.name == name) && it.data["phase"]?.jsonPrimitive?.content == "end" }

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

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private object TestLog : KiloLog {
        override val isDebugEnabled = false
        override fun debug(block: () -> String) = Unit
        override fun info(msg: String) = Unit
        override fun warn(msg: String, t: Throwable?) = Unit
        override fun error(msg: String, t: Throwable?) = Unit
    }
}
