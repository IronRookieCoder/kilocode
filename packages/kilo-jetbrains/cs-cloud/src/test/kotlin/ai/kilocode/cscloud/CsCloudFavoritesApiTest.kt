package ai.kilocode.cscloud

import ai.kilocode.rpc.dto.CloudFavoritesErrors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsCloudFavoritesApiTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = CsCloudFavoritesApi(
        OkHttpClient.Builder().addInterceptor(CsCloudRoute.responseInterceptor()).build(),
        server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `list parses bare array`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a1","slug":"my-skill","name":"My Skill","description":"d","itemType":"skill",""" +
                    """"status":"Active","localPath":"C:\\s\\my-skill"}]""",
            ),
        )
        val result = api().list()
        assertTrue(result.ok)
        assertEquals(1, result.items.size)
        assertEquals("Active", result.items[0].status)
        assertEquals("/api/v1/agents/favorites", server.takeRequest().path)
    }

    @Test
    fun `list tolerates unknown fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a1","slug":"s","name":"n","itemType":"command","status":"Cloud","version":"2"}]""",
            ),
        )
        val result = api().list()
        assertTrue(result.ok)
        assertEquals("Cloud", result.items.single().status)
    }

    @Test
    fun `list maps 401 to UNAUTHORIZED`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"AUTH_REQUIRED","message":"not authenticated"}}"""),
        )
        val result = api().list()
        assertFalse(result.ok)
        assertEquals(CloudFavoritesErrors.UNAUTHORIZED, result.errorCode)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `load parses passthrough action body`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"item":{"id":"a1","slug":"my-skill","name":"My Skill",""" +
                    """"itemType":"skill","status":"Active"}}""",
            ),
        )
        val result = api().load("my-skill")
        assertTrue(result.ok)
        assertEquals("Active", result.item?.status)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/agents/favorites/my-skill/load", request.path)
    }

    @Test
    fun `unload maps 404 to NOT_FOUND`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val result = api().unload("gone")
        assertFalse(result.ok)
        assertEquals(CloudFavoritesErrors.NOT_FOUND, result.errorCode)
        assertNull(result.item)
    }
}
