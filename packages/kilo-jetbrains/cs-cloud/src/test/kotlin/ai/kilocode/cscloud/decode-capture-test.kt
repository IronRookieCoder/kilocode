package ai.kilocode.cscloud

import ai.kilocode.backend.app.HttpCapture
import ai.kilocode.jetbrains.api.client.DefaultApi
import ai.kilocode.jetbrains.api.model.Session1
import ai.kilocode.stability.Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class DecodeCaptureTest {
    @Test
    fun `metadata uses actual decoder input after the real cloud response adapter`() {
        MockWebServer().use { server ->
            val raw = """
                {"value":[{
                  "id":"ses_bad","slug":"bad","projectID":"prj","directory":"/repo",
                  "title":"bad","version":"1","time":"wrong"
                }]}
            """.trimIndent()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(raw))
            val base = server.url("/").toString().trimEnd('/')
            val clients = CsCloudHttpClients.create(CsCloudEndpoint(base, null))
            try {
                val capture = HttpCapture()
                val api = DefaultApi(base, capture.client(clients.apiClient))
                val error = assertFailsWith<SerializationException> { api.sessionList(directory = "/tmp/workspace") }
                Fixture().use { fixture ->
                    fixture.enableDiagnostics()
                    fixture.operations.report(capture.input("session.decode", error,
                        descriptor = ListSerializer(Session1.serializer()).descriptor,
                    ))
                    fixture.flush()
                    val incident = fixture.facts().single { it.name == "diagnostic.reported" }
                    assertEquals("$[0].time", incident.data.getValue("json_path").jsonPrimitive.content)
                    assertEquals("object", incident.data.getValue("expected_type").jsonPrimitive.content)
                    assertEquals("string", incident.data.getValue("actual_type").jsonPrimitive.content)
                    assertEquals(raw, fixture.payload("response"))
                }
            } finally {
                listOf(clients.apiClient, clients.sseClient, clients.healthClient, clients.favoritesClient).forEach {
                    it.dispatcher.executorService.shutdownNow()
                    it.connectionPool.evictAll()
                }
            }
        }
    }
}
