package ai.kilocode.cscloud

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CsCloudEnvelopeTest {

    @Test
    fun `unwraps data payload on ok`() {
        val data = CsCloudEnvelope.unwrap("""{"ok":true,"data":{"version":"1.0"}}""", 200).jsonObject
        assertEquals("1.0", data["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `missing data unwraps to null element`() {
        assertEquals(
            kotlinx.serialization.json.JsonNull,
            CsCloudEnvelope.unwrap("""{"ok":true}""", 200),
        )
    }

    @Test
    fun `error envelope throws request exception`() {
        val error = assertFailsWith<CsCloudRequestException> {
            CsCloudEnvelope.unwrap(
                """{"ok":false,"error":{"code":"unauthorized","message":"bad key"}}""",
                401,
            )
        }
        assertEquals("unauthorized", error.code)
        assertEquals("bad key", error.message)
        assertEquals(401, error.status)
    }

    @Test
    fun `malformed body throws invalid_response`() {
        val error = assertFailsWith<CsCloudRequestException> {
            CsCloudEnvelope.unwrap("garbage", 200)
        }
        assertEquals("invalid_response", error.code)
    }

    @Test
    fun `unwrapOrNull passes non-envelope bodies through`() {
        assertNull(CsCloudEnvelope.unwrapOrNull("""{"a":1}""", 200))
    }

    @Test
    fun `unwrapOrNull unwraps envelope bodies`() {
        assertNotNull(CsCloudEnvelope.unwrapOrNull("""{"ok":true,"data":5}""", 200))
    }
}
