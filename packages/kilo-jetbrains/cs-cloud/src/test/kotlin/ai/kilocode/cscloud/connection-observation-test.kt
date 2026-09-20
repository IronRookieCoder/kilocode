package ai.kilocode.cscloud

import ai.kilocode.stability.ConnectionObservation
import ai.kilocode.stability.Fixture
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M04/M05逻辑连接、传输尝试与恢复（brief Step 1）：全部走真实Recorder/Writer（A4 Fixture），
 * 只读.ready还原事实。断言既看操作分母（connection恰好一个end），也看attempt诊断层
 * （每轮一个attempt）与恢复区间（多流同败只开一次、手动只改intervention）。
 */
class ConnectionObservationTest {

    private fun ends(facts: List<ai.kilocode.stability.Fact>, name: String) =
        facts.filter { it.name == name && it.data["phase"]?.jsonPrimitive?.content == "end" }

    private fun starts(facts: List<ai.kilocode.stability.Fact>, name: String) =
        facts.filter { it.name == name && it.data["phase"]?.jsonPrimitive?.content == "start" }

    /** brief Step 1逐字测试：三次attempt只有一个用户成功，逻辑分母不随重试增加。 */
    @Test
    fun `retries share logical denominator`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations)
            connection.request("initial")
            repeat(2) { connection.attempt("health").end("failure", "health", "network", "health_failed") }
            connection.attempt("streams").end("success", "streams")
            connection.connected()
            connection.connected()
            fixture.flush()
            val facts = fixture.facts().filter { it.data["phase"]?.jsonPrimitive?.content == "end" }
            assertEquals(1, facts.count { it.name == "connection" })
            assertEquals(3, facts.count { it.name == "connection.attempt" })
        }
    }

    /** 一轮endpoint/health/streams只有一个attempt：stage变化只progress，不新建分母。 */
    @Test
    fun `stage progression stays one attempt`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations)
            connection.request("initial")
            val round = connection.attempt("resolve")
            connection.attempt("health")
            connection.attempt("streams")
            round.end("success", "streams")
            connection.connected()
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(1, starts(facts, "connection.attempt").size)
            assertEquals(2, facts.count {
                it.name == "connection.attempt" &&
                    it.data["phase"]?.jsonPrimitive?.content == "progress"
            })
            assertEquals(1, ends(facts, "connection").size)
        }
    }

    /** attempt携带逻辑operation_id（同一旅程）与互异的attempt_id（传输层身份）。 */
    @Test
    fun `attempts link to the logical operation`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations)
            connection.request("initial")
            connection.attempt("resolve").end("failure", "resolve", "network", "daemon_down")
            connection.attempt("health").end("failure", "health", "network", "health_failed")
            fixture.flush()
            val facts = fixture.facts()
            val logicalId = starts(facts, "connection").single().context["operation_id"]
            val attemptStarts = starts(facts, "connection.attempt")
            assertEquals(2, attemptStarts.size)
            assertTrue(attemptStarts.all { it.context["operation_id"] == logicalId })
            assertEquals(2, attemptStarts.mapNotNull { it.context["attempt_id"] }.distinct().size)
        }
    }

    /** 多流同时失败只开一个恢复区间；恢复end携带intervention/attempts。 */
    @Test
    fun `simultaneous stream failures open one recovery`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations)
            connection.request("initial")
            connection.attempt("streams").end("success", "streams")
            connection.connected()
            connection.lost("sse_closed")
            connection.lost("partial_sse_failed")
            connection.attempt("health").end("failure", "health", "network", "health_failed")
            connection.attempt("streams").end("success", "streams")
            connection.connected()
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(1, starts(facts, "connection.recovery").size)
            assertEquals(1, ends(facts, "connection.recovery").size)
            assertEquals(1, facts.count { it.name == "connection.state_changed" })
            val recoveryEnd = ends(facts, "connection.recovery").single()
            assertEquals("automatic", recoveryEnd.data["intervention"]?.jsonPrimitive?.content)
            assertEquals(2, recoveryEnd.data["attempts"]?.jsonPrimitive?.content?.toInt())
        }
    }

    /** ready前失败是连接失败（M04），不是恢复（M05）：无恢复区间、无退化transition。 */
    @Test
    fun `failure before ready is not recovery`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations)
            connection.request("initial")
            connection.attempt("health").end("failure", "health", "network", "health_failed")
            connection.lost("sse_closed")
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(0, facts.count { it.name == "connection.recovery" })
            assertEquals(0, facts.count { it.name == "connection.state_changed" })
        }
    }

    /** 已有恢复区间的手动介入只改intervention，不另增一次恢复区间。 */
    @Test
    fun `manual request during recovery marks intervention without new interval`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations)
            connection.request("initial")
            connection.attempt("streams").end("success", "streams")
            connection.connected()
            connection.lost("sse_closed")
            connection.request("manual")
            connection.attempt("streams").end("success", "streams")
            connection.connected()
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(1, starts(facts, "connection.recovery").size)
            assertEquals("manual", ends(facts, "connection.recovery").single().data["intervention"]?.jsonPrimitive?.content)
        }
    }

    /** request(manual)取消旧逻辑操作（cancelled）并开新operation；在途attempt一并结算。 */
    @Test
    fun `manual request cancels the in flight journey and opens a new one`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations)
            connection.request("initial")
            connection.attempt("resolve")
            connection.request("manual")
            connection.attempt("streams").end("success", "streams")
            connection.connected()
            fixture.flush()
            val facts = fixture.facts()
            val connectionEnds = ends(facts, "connection")
            assertEquals(2, connectionEnds.size)
            assertEquals("cancelled", connectionEnds[0].data["result"]?.jsonPrimitive?.content)
            assertEquals("success", connectionEnds[1].data["result"]?.jsonPrimitive?.content)
            val attemptEnds = ends(facts, "connection.attempt")
            assertEquals("cancelled", attemptEnds[0].data["result"]?.jsonPrimitive?.content)
        }
    }

    /** 正常close不生成disconnect事实；在途区间按cancelled结算（成熟区间外）。 */
    @Test
    fun `close settles without disconnect facts`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations)
            connection.request("initial")
            connection.attempt("streams").end("success", "streams")
            connection.connected()
            connection.lost("sse_closed")
            connection.close()
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(1, facts.count { it.name == "connection.state_changed" })
            assertEquals("cancelled", ends(facts, "connection.recovery").single().data["result"]?.jsonPrimitive?.content)
            assertEquals(0, starts(facts, "connection.recovery").size - ends(facts, "connection.recovery").size)
        }
    }

    /** 逻辑op超时后网络才成功：CAS保证无第二条end，成功只落在attempt诊断层。 */
    @Test
    fun `success after logical timeout is diagnostic only`() {
        Fixture().use { fixture ->
            val connection = ConnectionObservation(fixture.operations, logicalDeadlineMs = 250L)
            connection.request("initial")
            connection.attempt("health").end("failure", "health", "network", "health_failed")
            // 真实deadline定时器（250ms）到点结算timeout：轮询等待终态落盘，5秒上界防挂。
            val deadline = System.currentTimeMillis() + 5_000
            var timeoutEnd: ai.kilocode.stability.Fact? = null
            while (timeoutEnd == null && System.currentTimeMillis() < deadline) {
                fixture.flush()
                timeoutEnd = ends(fixture.facts(), "connection").singleOrNull()
                if (timeoutEnd == null) Thread.sleep(50)
            }
            assertEquals("timeout", timeoutEnd?.data?.get("result")?.jsonPrimitive?.content)
            connection.attempt("streams").end("success", "streams")
            connection.connected()
            fixture.flush()
            assertEquals(1, ends(fixture.facts(), "connection").size)
        }
    }
}
