package ai.kilocode.cscloud

import kotlin.test.Test
import kotlin.test.assertEquals

class CsCloudRouteTest {

    @Test
    fun `maps session collection routes`() {
        assertEquals("/api/v1/conversations", CsCloudRoute.mapPath("/session"))
        assertEquals("/api/v1/conversations", CsCloudRoute.mapPath("/session/"))
    }

    @Test
    fun `maps session id routes`() {
        assertEquals("/api/v1/conversations/abc", CsCloudRoute.mapPath("/session/abc"))
        assertEquals("/api/v1/conversations/abc/status", CsCloudRoute.mapPath("/session/abc/status"))
    }

    @Test
    fun `maps prompt_async route`() {
        assertEquals(
            "/api/v1/conversations/abc/prompt/async",
            CsCloudRoute.mapPath("/session/abc/prompt_async"),
        )
    }

    @Test
    fun `maps message route`() {
        assertEquals(
            "/api/v1/conversations/abc/messages",
            CsCloudRoute.mapPath("/session/abc/message"),
        )
    }

    @Test
    fun `maps global routes`() {
        assertEquals("/api/v1/events", CsCloudRoute.mapPath("/global/event"))
        assertEquals("/api/v1/runtime/health", CsCloudRoute.mapPath("/global/health"))
    }

    @Test
    fun `maps permission routes`() {
        assertEquals("/api/v1/permissions", CsCloudRoute.mapPath("/permission"))
        assertEquals(
            "/api/v1/permissions/req-1/reply",
            CsCloudRoute.mapPath("/permission/req-1/reply"),
        )
    }

    @Test
    fun `maps question routes`() {
        assertEquals("/api/v1/questions", CsCloudRoute.mapPath("/question"))
        assertEquals(
            "/api/v1/questions/q-1",
            CsCloudRoute.mapPath("/question/q-1"),
        )
    }

    @Test
    fun `unknown routes pass through unchanged`() {
        assertEquals("/config/whatever", CsCloudRoute.mapPath("/config/whatever"))
    }
}
