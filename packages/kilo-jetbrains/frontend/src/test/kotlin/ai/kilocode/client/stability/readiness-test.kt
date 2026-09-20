package ai.kilocode.client.stability

import ai.kilocode.stability.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
