package ai.kilocode.cscloud

import ai.kilocode.backend.cli.KiloCliDataParser
import ai.kilocode.jetbrains.api.client.DefaultApi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CsCloudRouteTest {
    @Test
    fun `normalizes cs cloud model catalog for the kilo backend`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{
            "connected": [{
                "default_model": "default",
                "id": "firstParty",
                "models": {"deepseek-v4-flash": {"id": "deepseek-v4-flash", "name": "DeepSeek", "capabilities": {}}},
                "name": "CoStrict",
                "source": "config"
            }]
        }"""))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(CsCloudRoute.responseInterceptor()).build()

        try {
            val response = client.newCall(Request.Builder().url(server.url("/api/v1/agents/models")).build()).execute()
            val data = KiloCliDataParser.parseProviders(response.body!!.string())

            assertEquals("firstParty", data.providers.single().id)
            assertEquals("deepseek-v4-flash", data.providers.single().models.keys.single())
            assertEquals(listOf("firstParty"), data.connected)
            assertEquals(mapOf("build" to "firstParty/default"), data.defaults)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `stubs provider auth without hitting the cs cloud daemon`() {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(CsCloudRoute.interceptor()).build()

        try {
            val response = client.newCall(
                Request.Builder().url(server.url("/provider/auth?directory=%2Ftmp%2Fworkspace")).build()
            ).execute()

            assertEquals(200, response.code)
            assertEquals(emptyMap(), KiloCliDataParser.parseProviderAuth(response.body!!.string()))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rewrites control plane routes and preserves request details`() {
        val cases = listOf(
            Triple("POST", "/session", "/api/v1/conversations"),
            Triple("POST", "/session/s_1/prompt_async", "/api/v1/conversations/s_1/prompt/async"),
            Triple("GET", "/session/s_1/message", "/api/v1/conversations/s_1/messages"),
            Triple("POST", "/conversations", "/api/v1/conversations"),
            Triple("POST", "/conversations/s_1/revert", "/api/v1/conversations/s_1/revert"),
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
            if (path.startsWith("/session") || path.startsWith("/conversations")) {
                assertEquals("kilo-jetbrains", rewritten.header("X-Session-Client"))
            }
            if (body != null) {
                val buffer = Buffer()
                rewritten.body!!.writeTo(buffer)
                assertEquals(body, buffer.readUtf8())
            }
        }
    }

    @Test
    fun `normalizes csc conversations for the generated client`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""[{
            "id":"ses_1","session_id":"ses_1","directory":"/tmp/workspace",
            "title":"Work","time":{"created":1,"updated":2},"model":"CoStrict-GLM-5-Local"
        }]"""))
        server.start()
        val client = OkHttpClient.Builder()
            .addInterceptor(CsCloudRoute.interceptor())
            .addInterceptor(CsCloudRoute.responseInterceptor())
            .build()

        try {
            val api = DefaultApi(server.url("/").toString().trimEnd('/'), client)
            val item = api.sessionList(directory = "/tmp/workspace").single()

            assertEquals("CoStrict-GLM-5-Local", item.model?.id)
            assertEquals("", item.model?.providerID)
            assertEquals("", item.projectID)
            assertEquals("", item.version)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `normalizes csc conversation creation response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{
            "session_id":"ses_new","cwd":"/tmp/workspace","status":"starting","created_at":123
        }"""))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor(CsCloudRoute.responseInterceptor()).build()

        try {
            val response = client.newCall(Request.Builder().url(server.url("/api/v1/conversations")).build()).execute()
            val item = KiloCliDataParser.parseSession(response.body!!.string())

            assertEquals("ses_new", item.id)
            assertEquals("/tmp/workspace", item.directory)
            assertEquals(123.0, item.time.created)
        } finally {
            server.shutdown()
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
