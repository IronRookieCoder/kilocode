package ai.kilocode.cscloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsCloudHealthTest {

    @Test
    fun `parses ok envelope into health dto`() {
        val health = CsCloudHealthParser.parse(
            """{"ok":true,"data":{"status":"ok","version":"1.2.3","uptime":42}}"""
        )
        assertTrue(health.healthy)
        assertEquals("1.2.3", health.version)
        assertEquals(42L, health.uptime)
    }

    @Test
    fun `non-ok status is reported unhealthy`() {
        val health = CsCloudHealthParser.parse(
            """{"ok":true,"data":{"status":"starting","version":"1.2.3"}}"""
        )
        assertFalse(health.healthy)
        assertEquals("1.2.3", health.version)
        assertNull(health.uptime)
    }

    @Test
    fun `error envelope maps to request exception`() {
        val error = assertFailsWith<CsCloudRequestException> {
            CsCloudHealthParser.parse(
                """{"ok":false,"error":{"code":"agent_not_ready","message":"agent is booting"}}"""
            )
        }
        assertEquals("agent_not_ready", error.code)
        assertEquals("agent is booting", error.message)
    }

    @Test
    fun `malformed json maps to invalid_health`() {
        val error = assertFailsWith<CsCloudRequestException> {
            CsCloudHealthParser.parse("not json")
        }
        assertEquals("invalid_health", error.code)
    }

    @Test
    fun `missing data maps to invalid_health`() {
        val error = assertFailsWith<CsCloudRequestException> {
            CsCloudHealthParser.parse("""{"ok":true}""")
        }
        assertEquals("invalid_health", error.code)
    }

    @Test
    fun `missing version maps to invalid_health`() {
        val error = assertFailsWith<CsCloudRequestException> {
            CsCloudHealthParser.parse("""{"ok":true,"data":{"status":"ok"}}""")
        }
        assertEquals("invalid_health", error.code)
    }
}
