package ai.kilocode.client.stability

import ai.kilocode.stability.Fixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

class ReadinessTest {

    @Test fun `input alone is not readiness`() {
        Fixture().use { fixture ->
            val ready = Readiness(fixture.operations, emptyMap())
            ready.update(true, true, true, false, true)
            fixture.flush()
            assertEquals(0, fixture.facts().count {
                it.name == "plugin.readiness" && it.data["phase"]?.jsonPrimitive?.content == "end"
            })
            ready.update(true, true, true, true, true)
            ready.update(true, true, true, true, true)
            fixture.flush()
            assertEquals(1, fixture.facts().count {
                it.name == "plugin.readiness" && it.data["result"]?.jsonPrimitive?.content == "success"
            })
        }
    }

    @Test fun `blocked ends once with registered reason`() {
        Fixture().use { fixture ->
            val ready = Readiness(fixture.operations, emptyMap())
            ready.update(true, true, true, false, true, blocked = "migration_required")
            ready.update(true, true, true, true, true, blocked = "migration_required")
            fixture.flush()
            val ends = fixture.facts().filter {
                it.name == "plugin.readiness" && it.data["phase"]?.jsonPrimitive?.content == "end"
            }
            assertEquals(1, ends.size)
            assertEquals("blocked", ends.single().data["result"]?.jsonPrimitive?.content)
            assertEquals("migration_required", ends.single().data["reason"]?.jsonPrimitive?.content)
            assertEquals("environment", ends.single().data["cause"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `blocked after success does not produce a second end`() {
        Fixture().use { fixture ->
            val ready = Readiness(fixture.operations, emptyMap())
            ready.update(true, true, true, true, true)
            ready.update(true, false, true, true, true, blocked = "credentials_missing")
            fixture.flush()
            val ends = fixture.facts().filter {
                it.name == "plugin.readiness" && it.data["phase"]?.jsonPrimitive?.content == "end"
            }
            assertEquals(1, ends.size)
            assertEquals("success", ends.single().data["result"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `deadline settled denominator reports settled so a reactivation can start a new one`() {
        Fixture().use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                // 短deadline真实驱动onDeadline定时器路径（不伪造终态），生产构造缺省60秒不变。
                val readiness = Readiness(fixture.operations, emptyMap(), deadlineMs = 100L)
                assertFalse(readiness.settled, "an in-flight denominator is not settled")
                var waited = 0L
                while (!readiness.settled && waited < 10_000) {
                    Thread.sleep(20)
                    waited += 20
                }
                // F7（终审）：deadline定时器先到的timeout结算同样算已结算——此前自有ended标志
                // 看不到定时器路径，Watch会把这次结算当在途，吞掉一次真实重激活。
                assertTrue(readiness.settled, "deadline settlement must surface through isSettled")
                // Watch.activate据此判定：上一分母已结算→真实重激活创建新分母（新begin）。
                val reactivated = Readiness(fixture.operations, emptyMap(), deadlineMs = 60_000L)
                assertFalse(reactivated.settled, "the fresh denominator starts in flight")
                fixture.flush()
                val ends = fixture.facts().filter {
                    it.name == "plugin.readiness" && it.data["phase"]?.jsonPrimitive?.content == "end"
                }
                assertEquals(1, ends.size)
                assertEquals("timeout", ends.single().data["result"]?.jsonPrimitive?.content)
                assertEquals(
                    2,
                    fixture.facts().count {
                        it.name == "plugin.readiness" && it.data["phase"]?.jsonPrimitive?.content == "start"
                    },
                    "two denominators: the deadline-settled one and the fresh reactivation",
                )
            } finally {
                scope.cancel()
            }
        }
    }
}
