package ai.kilocode.stability

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun `protocol errors use only controlled wire fields and allowed purposes`() {
        Fixture().use { fixture ->
            fixture.operations.protocolError(
                ProtocolTransport.HTTP,
                ProtocolStage.DECODE,
                ProtocolCode.DECODE_FAILED,
            )
            fixture.operations.protocolError(
                ProtocolTransport.SSE,
                ProtocolStage.APPLY,
                ProtocolCode.APPLY_VIOLATION,
            )
            fixture.flush()

            val facts = fixture.facts().filter { it.name == "protocol.error" }
            assertEquals(2, facts.size)
            assertEquals(setOf("transport", "stage", "error_code"), facts[0].data.keys)
            assertEquals("http", facts[0].data.getValue("transport").jsonPrimitive.content)
            assertEquals("decode", facts[0].data.getValue("stage").jsonPrimitive.content)
            assertEquals("decode_failed", facts[0].data.getValue("error_code").jsonPrimitive.content)
            assertEquals("sse", facts[1].data.getValue("transport").jsonPrimitive.content)
            assertEquals("apply", facts[1].data.getValue("stage").jsonPrimitive.content)
            assertEquals("apply_violation", facts[1].data.getValue("error_code").jsonPrimitive.content)
            assertTrue(facts.all { it.purposes == setOf("metrics", "logs") })
        }
    }
}
