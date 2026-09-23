package ai.kilocode.stability

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class DiagnosticPayloadTest {

    @Test
    fun `chunks UTF-8 text at character boundaries and preserves it`() {
        val raw = "界".repeat(30_000).encodeToByteArray()

        val out = DiagnosticPayload.parts("inc-1", "response", raw)
        val text = out.drafts.joinToString("") { it.data.getValue("content").jsonPrimitive.content }

        assertEquals(raw.decodeToString(), text)
        assertEquals(raw.size.toLong(), out.bytes)
        assertFalse(out.truncated)
        assertTrue(out.drafts.all { it.data.getValue("encoding").jsonPrimitive.content == "utf8" })
        assertTrue(out.drafts.all(::fitsWire))
        assertEquals((0 until out.drafts.size).toList(), out.drafts.map { it.data.getValue("chunk_index").jsonPrimitive.int })
        assertTrue(out.drafts.all { it.data.getValue("chunk_count").jsonPrimitive.int == out.drafts.size })
        assertTrue(Dictionary.validate(out.drafts))
    }

    @Test
    fun `encodes binary data as Base64 with an original SHA-256 summary`() {
        val raw = byteArrayOf(0, -1, 2, -128, 127)

        val out = DiagnosticPayload.parts("inc-1", "response", raw)
        val text = out.drafts.joinToString("") { it.data.getValue("content").jsonPrimitive.content }

        assertEquals(Base64.getEncoder().encodeToString(raw), text)
        assertEquals(sha(raw), out.hash)
        assertEquals(raw.size.toLong(), out.bytes)
        assertTrue(out.drafts.all { it.data.getValue("encoding").jsonPrimitive.content == "base64" })
        assertTrue(out.drafts.all(::fitsWire))
    }

    @Test
    fun `keeps the exact head and tail of an oversized payload`() {
        val raw = ByteArray(1_200 * 1024) { index -> (index % 251).toByte() }

        val out = DiagnosticPayload.parts("inc-1", "response", raw)
        val text = out.drafts.joinToString("") { it.data.getValue("content").jsonPrimitive.content }
        val kept = Base64.getDecoder().decode(text)

        assertTrue(out.truncated)
        assertEquals(raw.size.toLong(), out.bytes)
        assertEquals(sha(raw), out.hash)
        assertContentEquals(raw.copyOfRange(0, 512 * 1024), kept.copyOfRange(0, 512 * 1024))
        assertContentEquals(raw.copyOfRange(raw.size - 512 * 1024, raw.size), kept.copyOfRange(512 * 1024, kept.size))
        assertTrue(out.drafts.all(::fitsWire))
    }

    @Test
    fun `keeps every serialized wire row at or below 32 KiB`() {
        val raw = "x".repeat(256 * 1024).encodeToByteArray()

        val out = DiagnosticPayload.parts("incident-with-a-longer-id", "response", raw)

        assertTrue(out.drafts.all(::fitsWire))
    }

    @Test
    fun `accounts for JSON escaping in actual wire rows`() {
        val raw = ByteArray(16 * 1024)

        val out = DiagnosticPayload.parts("inc-1", "response", raw)

        assertTrue(out.drafts.all(::fitsWire))
    }

    private fun fitsWire(draft: Draft): Boolean = wire(draft).encodeToByteArray().size <= 32 * 1024

    private fun wire(draft: Draft): String = json.encodeToString(
        Fact.serializer(),
        Fact(
            schema_version = draft.schemaVersion,
            event_id = "event-1",
            timestamp = 1,
            producer_id = "producer-1",
            run_id = "run-1",
            channel = draft.channel,
            seq = 1,
            account_epoch = "account-1",
            policy_revision = 1,
            purposes = draft.purposes,
            device_id = "device-1",
            plugin_version = "1.0.0",
            ide_product = "IU",
            ide_build = "261.1",
            ide_build_major = "2026.1",
            os_family = "windows",
            arch = "x64",
            env = "test",
            mode = "monolith",
            side = "monolith",
            connection_provider = "kilo-cli",
            kind = draft.kind,
            name = draft.name,
            context = draft.context,
            data = draft.data,
        ),
    )

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}
