package ai.kilocode.stability

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ErrorClassifierTest {
    private class EqualError : IllegalStateException("same") {
        override fun hashCode() = 1
        override fun equals(other: Any?) = other is EqualError
    }

    @Test
    fun `distinct throwables with equal values keep separate HTTP status and incidents`() {
        val first = EqualError()
        val second = EqualError()
        ErrorClassifier.observe(first, 401)
        ErrorClassifier.observe(second, 404)
        assertEquals(401, ErrorClassifier.classify(first).status)
        Fixture().use { fixture ->
            fixture.enableDiagnostics()
            fixture.operations.report(DiagnosticInput.error("shared", error = first))
            fixture.operations.report(DiagnosticInput.error("shared", error = second))
            fixture.flush()
            assertEquals(2, fixture.facts().count { it.name == "diagnostic.reported" })
        }
    }
    @Serializable private data class Project(val name: String)
    @Serializable private data class Session(val projectID: Project)

    @Test
    fun `typed HTTP status maps without parsing message text`() {
        mapOf(401 to "unauthorized", 403 to "forbidden", 404 to "not_found", 429 to "rate_limited", 500 to "server_error", 503 to "server_error").forEach { (status, code) ->
            val info = ErrorClassifier.classify(HttpFailure(status, "unrelated message HTTP 200"))
            assertEquals(status, info.status)
            assertEquals(code, info.code)
            assertEquals(if (status == 401 || status == 403) "user" else "agent_core", info.cause)
        }
    }

    @Test
    fun `transport timeout cancellation linkage and unknown have stable codes`() {
        listOf(
            ConnectException("refused") to ("network" to "connect_failed"),
            SocketException("reset") to ("network" to "socket_failed"),
            SocketTimeoutException("read") to ("network" to "timeout"),
            java.io.InterruptedIOException("timeout") to ("network" to "timeout"),
            TimeoutException("deadline") to ("network" to "timeout"),
            CancellationException("closed") to ("user" to "cancelled"),
            NoClassDefFoundError("missing") to ("plugin" to "linkage_error"),
            IllegalStateException("HTTP 401 in unrelated text") to ("unknown" to "other"),
        ).forEach { (error, expected) ->
            val info = ErrorClassifier.classify(error)
            assertEquals(expected.first, info.cause)
            assertEquals(expected.second, info.code)
            assertEquals(error.javaClass.name, info.type)
        }
    }

    @Test
    fun `real serializer failure retains project ID JSON path and token types`() {
        val error = kotlin.test.assertFailsWith<SerializationException> {
            Json.decodeFromString<Session>("""{"projectID":"prj_string"}""")
        }
        val info = ErrorClassifier.classify(error)
        assertEquals("plugin", info.cause)
        assertEquals("decode_failed", info.code)
        assertEquals("$.projectID", info.path)
        assertEquals("object", info.expected)
        assertEquals("string", info.actual)
    }
}
