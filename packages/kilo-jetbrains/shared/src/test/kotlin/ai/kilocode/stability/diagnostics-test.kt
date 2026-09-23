package ai.kilocode.stability

import java.io.PrintWriter
import java.io.StringWriter
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

class DiagnosticsTest {
    @Test
    fun `metrics category rejection does not discard permitted diagnostic logs`() {
        Fixture().use { fixture ->
            enable(fixture, "metrics_allowed_categories" to JsonArray(listOf(JsonPrimitive("diagnostic"))))
            Diagnostics(fixture.recorder, fixture.clock).report(DiagnosticInput.error("shared", error = IllegalStateException("body")))
            fixture.flush()
            assertEquals(1, fixture.facts().count { it.name == "diagnostic.reported" })
            assertTrue(fixture.facts().none { it.channel == "critical" })
        }
    }

    @Test
    fun `repeated redactor failures respect quota and retain summary counts`() {
        Fixture().use { fixture ->
            enable(fixture)
            val diagnostics = Diagnostics(fixture.recorder, fixture.clock, redactor = { error("raw-redactor-failure") })
            repeat(10) { diagnostics.report(DiagnosticInput.error("shared", error = IllegalStateException("raw-error"), context = mapOf("fault_id" to "fault-$it"))) }
            fixture.advanceClock(61_000)
            diagnostics.report(DiagnosticInput.error("shared", context = mapOf("fault_id" to "fault-0")))
            fixture.flush()
            assertEquals(3, fixture.facts().count { it.name == "diagnostic.redaction_failed" })
            assertEquals(1, fixture.facts().count { it.data["count"]?.jsonPrimitive?.longOrNull == 7L })
            assertFalse(fixture.facts().joinToString().contains("raw-"))
        }
    }

    @Test
    fun `adapter and direct reports share dedup in a v2 run`() {
        Fixture().use { fixture ->
            enable(fixture)
            val diagnostics = Diagnostics(fixture.recorder, fixture.clock)
            Faults(diagnostics).report(IllegalStateException("full text"), "shared", false, "fault-fixed")
            diagnostics.report(DiagnosticInput.error("shared", error = IllegalStateException("duplicate"), context = mapOf("fault_id" to "fault-fixed")))
            fixture.flush()
            assertEquals(1, fixture.facts().count { it.name == "diagnostic.reported" })
            assertEquals(1, fixture.facts().count { it.name == "error.uncaught" })
            assertEquals("full text", payload(fixture.facts(), "message"))
        }
    }

    @Test
    fun `credential attribute values and scalar count metadata are redacted`() {
        Fixture().use { fixture ->
            enable(fixture)
            Diagnostics(fixture.recorder, fixture.clock).report(DiagnosticInput.error(
                "token=component-secret", error = IllegalStateException("failed"),
                attributes = mapOf("Authorization" to "Bearer header-secret", "token" to "attribute-secret"),
                context = mapOf("operation_id" to "token=context-secret"),
            ))
            fixture.flush()
            assertTrue(fixture.facts().any { it.name == "diagnostic.reported" })
            assertFalse(fixture.facts().joinToString().contains("-secret"))
        }
    }

    @Test
    fun `fatal supplier and redactor errors preserve identity and emit only minimal parent`() {
        listOf(true, false).forEach { supplier ->
            Fixture().use { fixture ->
                enable(fixture)
                val failure = OutOfMemoryError("secret")
                val diagnostics = Diagnostics(fixture.recorder, fixture.clock, redactor = { if (!supplier) throw failure; Redacted(it, false) })
                assertSame(failure, assertFailsWith<OutOfMemoryError> { diagnostics.report(DiagnosticInput.error("shared", payloads = mapOf("request" to { throw failure }))) })
                fixture.flush()
                assertEquals("error.uncaught", fixture.facts().single().name)
                assertFalse(fixture.facts().joinToString().contains("secret"))
            }
        }
    }

    @Test
    fun `incident reconstructs complete printStackTrace and shares fault id`() {
        Fixture().use { fixture ->
            enable(fixture)
            val error = IllegalStateException("decode failed\noriginal message", IllegalArgumentException("C:\\Users\\alice\\input.json"))
            error.addSuppressed(java.io.IOException("suppressed failure"))
            error.stackTrace = arrayOf(StackTraceElement("ai.kilocode.Parser", "read", "Parser.kt", 42), StackTraceElement("com.intellij.Application", "run", "Application.java", 123))
            val expected = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val id = Diagnostics(fixture.recorder, fixture.clock).report(DiagnosticInput.error("shared", error = error, context = mapOf("operation_id" to "op-1")))
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(expected, payload(facts, "stack"))
            assertEquals(error.message, payload(facts, "message"))
            assertTrue(facts.all { it.context["incident_id"] == id && it.context["fault_id"] == id })
            val parent = facts.single { it.name == "diagnostic.reported" }
            assertEquals("op-1", parent.context["operation_id"])
            assertEquals(1L, parent.data["suppressed_count"]?.jsonPrimitive?.long)
            assertFalse(parent.data.getValue("truncated").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `all payload kinds survive and credentials are removed before chunking`() {
        Fixture().use { fixture ->
            enable(fixture)
            val values = listOf("path", "headers", "request", "response", "event").associateWith { { "C:\\Users\\alice\\file\nAuthorization: Bearer secret-$it\nvalue" } }
            Diagnostics(fixture.recorder, fixture.clock).report(DiagnosticInput.error("shared", error = IllegalStateException("token=secret-message"), payloads = values))
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(7, facts.filter { it.name == "diagnostic.payload" }.map { it.data["payload_kind"] }.distinct().size)
            assertFalse(facts.joinToString().contains("secret-"))
            values.keys.forEach { assertTrue(payload(facts, it).contains("alice")) }
        }
    }

    @Test
    fun `redaction failure emits exactly one raw-free parent`() {
        Fixture().use { fixture ->
            enable(fixture)
            val calls = AtomicInteger()
            val diagnostics = Diagnostics(fixture.recorder, fixture.clock, redactor = {
                if (calls.incrementAndGet() == 2) error("secret-redactor")
                Redacted(it, false)
            })
            diagnostics.report(DiagnosticInput.error("shared", message = "raw-message", payloads = mapOf("request" to { "raw-body" }), context = mapOf("incident_id" to "token=raw-id")))
            fixture.flush()
            val fact = fixture.facts().single()
            assertEquals("diagnostic.redaction_failed", fact.name)
            assertFalse(fact.toString().contains("raw-"))
            assertFalse(fact.toString().contains("secret-"))
        }
    }

    @Test
    fun `one MiB is shared across payloads preserving head tail and original length`() {
        Fixture().use { fixture ->
            enable(fixture)
            Diagnostics(fixture.recorder, fixture.clock).report(DiagnosticInput.error("shared", payloads = mapOf(
                "request" to { "head" + "x".repeat(700_000) + "tail" }, "response" to { "start" + "y".repeat(700_000) + "end" },
            )))
            fixture.flush()
            val facts = fixture.facts()
            assertTrue(facts.single { it.name == "diagnostic.reported" }.data.getValue("truncated").jsonPrimitive.boolean)
            val kinds = facts.filter { it.name == "diagnostic.payload" }.map { it.data.getValue("payload_kind").jsonPrimitive.content }.distinct()
            assertTrue(kinds.sumOf { payload(facts, it).encodeToByteArray().size } <= 1024 * 1024)
            assertTrue(payload(facts, "request").startsWith("head") && payload(facts, "request").endsWith("tail"))
            assertTrue(payload(facts, "response").startsWith("start") && payload(facts, "response").endsWith("end"))
            assertEquals(700_008L, facts.first { it.data["payload_kind"] == JsonPrimitive("request") }.data["original_bytes"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun `full queue rejects every draft and accounts failure`() {
        Fixture(autoStart = false).use { fixture ->
            enable(fixture)
            val draft = Draft("protocol.error", "diagnostic", "critical", JsonObject(mapOf("transport" to JsonPrimitive("sse"), "stage" to JsonPrimitive("decode"), "error_code" to JsonPrimitive("decode_failed"))))
            while (fixture.recorder.record(draft) == Admission.QUEUED) Unit
            val before = fixture.recorder.health()
            val size = fixture.recorder.depth().items
            Diagnostics(fixture.recorder, fixture.clock).report(DiagnosticInput.error("shared", error = IllegalStateException("body")))
            assertEquals(size, fixture.recorder.depth().items)
            assertEquals(before.accepted, fixture.recorder.health().accepted)
            assertTrue(fixture.recorder.health().droppedFailure > before.droppedFailure)
        }
    }

    @Test
    fun `parallel identical errors retain three details and summarize extras`() {
        Fixture().use { fixture ->
            enable(fixture)
            val diagnostics = Diagnostics(fixture.recorder, fixture.clock)
            val executor = Executors.newFixedThreadPool(8)
            try {
                (0 until 100).map { index -> CompletableFuture.runAsync({
                    diagnostics.report(DiagnosticInput.error("shared", error = IllegalStateException("same"), context = mapOf("fault_id" to "fault-$index")))
                }, executor) }.forEach { it.get(15, TimeUnit.SECONDS) }
            } finally { executor.shutdownNow() }
            fixture.advanceClock(61_000)
            diagnostics.report(DiagnosticInput.error("shared", context = mapOf("fault_id" to "fault-0")))
            fixture.flush()
            assertEquals(100, fixture.facts().count { it.channel == "critical" })
            assertEquals(3, fixture.facts().count { it.name == "diagnostic.reported" })
            assertEquals(1, fixture.facts().count { it.data["count"]?.jsonPrimitive?.longOrNull == 97L })
        }
    }

    @Test
    fun `payload and redactor cancellation propagate without partial incident`() {
        listOf(true, false).forEach { supplier ->
            Fixture().use { fixture ->
                enable(fixture)
                val error = CancellationException("cancelled")
                val diagnostics = Diagnostics(fixture.recorder, fixture.clock, redactor = { if (!supplier) throw error; Redacted(it, false) })
                assertSame(error, assertFailsWith<CancellationException> { diagnostics.report(DiagnosticInput.error("shared", payloads = mapOf("request" to { throw error }))) })
                assertEquals(0, fixture.recorder.depth().items)
            }
        }
    }

    @Test
    fun `unsupported consumer and disabled logs leave payloads lazy`() {
        Fixture().use { fixture ->
            val input = DiagnosticInput.error("shared", payloads = mapOf("request" to { error("must stay lazy") }))
            Diagnostics(fixture.recorder, fixture.clock).report(input)
            enable(fixture, "logs_enabled" to JsonPrimitive(false))
            Diagnostics(fixture.recorder, fixture.clock).report(input)
            fixture.flush()
            assertTrue(fixture.facts().none { it.schema_version == "2.0" })
        }
    }

    private fun payload(facts: List<Fact>, kind: String): String {
        val rows = facts.filter { it.data["payload_kind"] == JsonPrimitive(kind) }.sortedBy { it.data.getValue("chunk_index").jsonPrimitive.long }
        val text = rows.joinToString("") { it.data.getValue("content").jsonPrimitive.content }
        return if (rows.firstOrNull()?.data?.get("encoding") == JsonPrimitive("base64")) Base64.getDecoder().decode(text).decodeToString() else text
    }
}

internal fun enable(fixture: Fixture, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) {
    val data = Json.parseToJsonElement(Fixture.defaultControl()).jsonObject +
        ("accepted_fact_schema_majors" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))) + fields
    fixture.base.resolve("control.json").writeText(JsonObject(data).toString())
    fixture.policies.refresh()
}
