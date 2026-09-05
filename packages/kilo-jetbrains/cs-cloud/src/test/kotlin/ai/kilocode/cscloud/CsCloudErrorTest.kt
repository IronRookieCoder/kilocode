package ai.kilocode.cscloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

class CsCloudErrorTest {

    @Test
    fun `parses flat agent error body with string code`() {
        val body = """
            {"backend":"csc","driver":"http","error":"CONFLICT",
             "message":"session is already processing a prompt","projectID":"prj_default"}
        """.trimIndent()
        val error = CsCloudRequestException.fromResponse(response(409), body)

        assertEquals("CONFLICT", error.code)
        assertEquals("session is already processing a prompt", error.message)
        assertEquals(409, error.status)
    }

    @Test
    fun `parses daemon envelope with nested error object`() {
        val body = """{"ok":false,"data":null,"error":{"code":"DUAL_OWNERSHIP","message":"event already owned"}}"""
        val error = CsCloudRequestException.fromResponse(response(409), body)

        assertEquals("DUAL_OWNERSHIP", error.code)
        assertEquals("event already owned", error.message)
    }

    @Test
    fun `falls back to reason phrase for non json body`() {
        val error = CsCloudRequestException.fromResponse(response(409), "gateway timeout")

        assertEquals("http_409", error.code)
        assertEquals("Conflict", error.message)
    }

    @Test
    fun `falls back to status code mapping without usable body`() {
        val error = CsCloudRequestException.fromResponse(response(401, "Unauthorized"), "not json")

        assertEquals("unauthorized", error.code)
        assertEquals("Unauthorized", error.message)
    }

    private fun response(code: Int, message: String = "Conflict"): Response = Response.Builder()
        .request(Request.Builder().url("http://127.0.0.1:1/x").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body("{}".toResponseBody("application/json".toMediaType()))
        .build()
}
