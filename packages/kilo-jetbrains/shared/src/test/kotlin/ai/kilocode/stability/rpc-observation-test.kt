package ai.kilocode.stability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * M19（C2）：单次前端RPC尝试的观测语义。
 *
 * 取消不计故障且原样传播；成功仅metrics出口且start/end配对自包含；失败结算后重抛；
 * 重试是独立attempt（各自的start/end与operation_id）；真实deadline传原值；
 * api_group受控词表由Dictionary把关（非法组不产事实、业务不受影响）。
 */
class RpcObservationTest {

    @Test
    fun `coroutine recovered exception remains the same incident after RPC context unwinds`() = runTest {
        Fixture().use { fixture ->
            fixture.enableDiagnostics()
            val error = assertFailsWith<IllegalStateException> {
                fixture.operations.rpc("session") {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { error("recent unavailable") }
                }
            }
            fixture.operations.report(DiagnosticInput.error("session.recent", error = error))
            fixture.flush()
            assertEquals(1, fixture.facts().count { it.name == "diagnostic.reported" })
        }
    }

    @Test
    fun `typed rpc failure writes one correlated incident before original exception escapes`() = runTest {
        Fixture().use { fixture ->
            enable(fixture)
            val error = HttpFailure(404, "missing recent sessions")
            assertSame(error, assertFailsWith<HttpFailure> {
                fixture.operations.rpc("session") { throw error }
            })
            fixture.flush()
            val facts = fixture.facts()
            val end = ends(fixture).single()
            val incident = facts.single { it.name == "diagnostic.reported" }
            assertEquals("agent_core", end.data.getValue("cause").jsonPrimitive.content)
            assertEquals("not_found", end.data.getValue("error_code").jsonPrimitive.content)
            assertEquals(end.context["operation_id"], incident.context["operation_id"])
            assertTrue(facts.filter { it.name == "diagnostic.payload" }.all { it.context["operation_id"] == end.context["operation_id"] })
            assertTrue(facts.joinToString().contains("missing recent sessions"))
            assertTrue(facts.joinToString().contains("RpcObservationTest"))
        }
    }

    @Test
    fun `explicit report and mirror of one caught throwable reuse the original incident`() = runTest {
        Fixture().use { fixture ->
            enable(fixture)
            val error = java.net.ConnectException("refused")
            val bridge = DiagnosticBridge.install { fixture.operations.report(it) }
            try {
                assertFailsWith<java.net.ConnectException> {
                    fixture.operations.rpc("workspace") {
                        fixture.operations.report(DiagnosticInput.error("workspace", error = error, payloads = mapOf("request" to { "request payload" })))
                        DiagnosticBridge.publish(DiagnosticInput.error("workspace", error = error))
                        throw error
                    }
                }
                DiagnosticBridge.await(bridge)
                fixture.flush()
                assertEquals(1, fixture.facts().count { it.name == "diagnostic.reported" })
                assertEquals(1, fixture.facts().count { it.name == "error.reported" })
                assertEquals(ends(fixture).single().context["operation_id"], fixture.facts().single { it.name == "diagnostic.reported" }.context["operation_id"])
            } finally {
                bridge.close()
            }
        }
    }

    private fun rpcFacts(fixture: Fixture) = fixture.facts().filter { it.name == "rpc" }

    private fun ends(fixture: Fixture) = rpcFacts(fixture).filter {
        it.data["phase"]?.jsonPrimitive?.content == "end"
    }

    private fun starts(fixture: Fixture) = rpcFacts(fixture).filter {
        it.data["phase"]?.jsonPrimitive?.content == "start"
    }

    // ---------- Step 1 verbatim：取消仍传播且不计故障 ----------

    @Test
    fun `rpc cancellation is preserved`() = runTest {
        Fixture().use { fixture ->
            assertFailsWith<CancellationException> {
                fixture.operations.rpc("session") { throw CancellationException("closed") }
            }
            fixture.flush()
            val end = fixture.facts().single {
                it.name == "rpc" && it.data["phase"]?.jsonPrimitive?.content == "end"
            }
            assertEquals("cancelled", end.data.getValue("result").jsonPrimitive.content)
        }
    }

    @Test
    fun `cancellation settles cancelled with user cause and no failure`() = runTest {
        Fixture().use { fixture ->
            assertFailsWith<CancellationException> {
                fixture.operations.rpc("session") { throw CancellationException("closed") }
            }
            fixture.flush()
            val end = ends(fixture).single()
            assertEquals("cancelled", end.data.getValue("result").jsonPrimitive.content)
            assertEquals("rpc", end.data.getValue("stage").jsonPrimitive.content)
            assertEquals("user", end.data.getValue("cause").jsonPrimitive.content)
            assertTrue(ends(fixture).none { it.data.getValue("result").jsonPrimitive.content == "failure" })
        }
    }

    @Test
    fun `success records one paired metrics-only operation with api group`() = runTest {
        Fixture().use { fixture ->
            val value = fixture.operations.rpc("profile") { 42 }
            assertEquals(42, value)
            fixture.flush()
            assertEquals(1, starts(fixture).size)
            val end = ends(fixture).single()
            val start = starts(fixture).single()
            assertEquals("profile", start.data.getValue("api_group").jsonPrimitive.content)
            assertEquals("profile", end.data.getValue("api_group").jsonPrimitive.content)
            assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
            assertEquals("rpc", end.data.getValue("stage").jsonPrimitive.content)
            assertEquals(start.context["operation_id"], end.context["operation_id"])
            assertTrue(end.data.getValue("duration_ms").jsonPrimitive.long >= 0)
            // M19/全局约束：成功RPC仅metrics用途，不逐条生成logs。
            assertEquals(setOf("metrics"), end.purposes)
        }
    }

    @Test
    fun `failure is settled once and rethrown`() = runTest {
        Fixture().use { fixture ->
            assertFailsWith<IllegalStateException> {
                fixture.operations.rpc("workspace") { error("boom") }
            }
            fixture.flush()
            val end = ends(fixture).single()
            assertEquals("failure", end.data.getValue("result").jsonPrimitive.content)
            assertEquals("rpc", end.data.getValue("stage").jsonPrimitive.content)
            assertEquals("unknown", end.data.getValue("cause").jsonPrimitive.content)
            assertEquals("other", end.data.getValue("error_code").jsonPrimitive.content)
        }
    }

    @Test
    fun `retries are separate attempts`() = runTest {
        Fixture().use { fixture ->
            var attempts = 0
            runCatching { fixture.operations.rpc("session") { attempts++; error("first") } }
            fixture.operations.rpc("session") { attempts++; "ok" }
            fixture.flush()
            assertEquals(2, attempts)
            assertEquals(2, starts(fixture).size)
            assertEquals(2, ends(fixture).size)
            val ids = rpcFacts(fixture).mapNotNull { it.context["operation_id"] }.toSet()
            assertEquals(2, ids.size)
        }
    }

    @Test
    fun `real deadline is carried as the original value`() = runTest {
        Fixture().use { fixture ->
            fixture.operations.rpc("other", 480_000) { "done" }
            fixture.flush()
            val start = starts(fixture).single()
            assertEquals(480_000L, start.data.getValue("deadline_ms").jsonPrimitive.long)
            assertEquals("other", start.data.getValue("api_group").jsonPrimitive.content)
        }
    }

    @Test
    fun `api group is closed vocabulary enforced by dictionary`() = runTest {
        Fixture().use { fixture ->
            // 非法组不产事实（Dictionary拒绝），业务调用本身照常返回。
            assertEquals("ran", fixture.operations.rpc("bogus") { "ran" })
            fixture.flush()
            assertTrue(rpcFacts(fixture).isEmpty())
        }
    }
}
