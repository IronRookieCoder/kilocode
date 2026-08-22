package ai.kilocode.cscloud

import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CsCloudRouteTest {
    @Test
    fun `rewrites control plane routes and preserves request details`() {
        val cases = listOf(
            Triple("POST", "/session", "/api/v1/conversations"),
            Triple("POST", "/session/s_1/prompt_async", "/api/v1/conversations/s_1/prompt/async"),
            Triple("GET", "/session/s_1/message", "/api/v1/conversations/s_1/messages"),
            Triple("GET", "/global/event", "/api/v1/events"),
            Triple("POST", "/permission/1", "/api/v1/permissions/1"),
            Triple("POST", "/question/1", "/api/v1/questions/1"),
            Triple("GET", "/global/health", "/api/v1/runtime/health"),
            Triple("GET", "/agent", "/api/v1/agents/session-modes"),
        )
        cases.forEach { (method, path, expected) ->
            val body = if (method == "GET") null else "{\"prompt\":\"hello\"}"
            val request = Request.Builder()
                .url("http://127.0.0.1:8080$path?directory=%2Ftmp%2Fworkspace&keep=one&keep=two")
                .method(method, body?.toRequestBody())
                .build()
            val rewritten = CsCloudRoute.rewrite(request)

            assertEquals(expected, rewritten.url.encodedPath)
            assertEquals(listOf("one", "two"), rewritten.url.queryParameterValues("keep"))
            assertEquals(null, rewritten.url.queryParameter("directory"))
            assertEquals(Path.of("/tmp/workspace").toAbsolutePath().normalize().toString(), rewritten.header("X-Workspace-Directory"))
            if (body != null) {
                val buffer = Buffer()
                rewritten.body!!.writeTo(buffer)
                assertEquals(body, buffer.readUtf8())
            }
        }
    }

    @Test
    fun `session routes require a workspace directory`() {
        val request = Request.Builder().url("http://127.0.0.1:8080/session").post("{}".toRequestBody()).build()
        assertFailsWith<IllegalArgumentException> { CsCloudRoute.rewrite(request) }
    }

    @Test
    fun `preserves endpoint prefix and rejects workspaces outside active roots`() {
        val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val request = Request.Builder()
            .url("http://127.0.0.1:8080/bridge/session".toHttpUrl().newBuilder().addQueryParameter("directory", root.resolve("project").toString()).build())
            .post("{}".toRequestBody())
            .build()

        val rewritten = CsCloudRoute.rewrite(request, "/bridge", listOf(root))
        assertEquals("/bridge/api/v1/conversations", rewritten.url.encodedPath)

        val outside = Request.Builder()
            .url("http://127.0.0.1:8080/bridge/session".toHttpUrl().newBuilder().addQueryParameter("directory", root.resolveSibling("outside").toString()).build())
            .post("{}".toRequestBody())
            .build()
        val error = assertFailsWith<IllegalArgumentException> {
            CsCloudRoute.rewrite(outside, "/bridge", listOf(root))
        }
        assertTrue(error.message.orEmpty().contains("outside"))
    }

    private fun String.toRequestBody() = okhttp3.RequestBody.create(null, this)
}
