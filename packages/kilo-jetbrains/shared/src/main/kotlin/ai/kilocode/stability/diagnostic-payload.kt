package ai.kilocode.stability

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 诊断载荷分片与原文摘要；hash和bytes始终描述输入原文。 */
data class PayloadResult(val drafts: List<Draft>, val bytes: Long, val hash: String, val truncated: Boolean)

private const val MAX_PAYLOAD_BYTES = 1024 * 1024

/* 为Fact外壳、JSON转义与可变的序号留出余量，确保单条真实UTF-8 wire记录低于32KiB。 */
private const val MAX_CONTENT_BYTES = 4 * 1024

/** 将已脱敏的原始字节拆为可重组的v2 diagnostic.payload草稿。 */
object DiagnosticPayload {
    fun parts(incident: String, kind: String, bytes: ByteArray, budget: Int = MAX_PAYLOAD_BYTES): PayloadResult {
        require(budget in 1..MAX_PAYLOAD_BYTES)
        val hash = sha(bytes)
        val truncated = bytes.size > budget
        val data = if (truncated) clip(bytes, budget) else bytes
        val text = utf8(data)
        val encoding = if (text == null) "base64" else "utf8"
        val content = text ?: Base64.getEncoder().encodeToString(data)
        val chunks = chunks(content, encoding)
        val drafts = chunks.mapIndexed { index, content -> draft(incident, kind, content, encoding, index, chunks.size, bytes.size.toLong(), hash, truncated) }
        return PayloadResult(drafts, bytes.size.toLong(), hash, truncated)
    }

    private fun clip(bytes: ByteArray, budget: Int): ByteArray = ByteArray(budget).also { out ->
        val head = (budget + 1) / 2
        val tail = budget - head
        bytes.copyInto(out, 0, 0, head)
        bytes.copyInto(out, head, bytes.size - tail, bytes.size)
    }

    private fun utf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun chunks(text: String, encoding: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (encoding == "base64") return text.chunked(MAX_CONTENT_BYTES)
        val out = ArrayList<String>()
        val part = StringBuilder()
        var size = 0
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            val char = String(Character.toChars(point))
            val next = char.encodeToByteArray().size
            if (size + next > MAX_CONTENT_BYTES && part.isNotEmpty()) {
                out += part.toString()
                part.clear()
                size = 0
            }
            part.append(char)
            size += next
            index += Character.charCount(point)
        }
        if (part.isNotEmpty()) out += part.toString()
        return out
    }

    @Suppress("LongParameterList")
    private fun draft(
        incident: String,
        kind: String,
        content: String,
        encoding: String,
        index: Int,
        count: Int,
        bytes: Long,
        hash: String,
        truncated: Boolean,
    ): Draft = Draft(
        name = "diagnostic.payload",
        kind = "diagnostic",
        channel = "diagnostic",
        context = mapOf("incident_id" to incident),
        purposes = setOf("logs"),
        schemaVersion = "2.0",
        data = buildJsonObject {
            put("incident_id", incident)
            put("payload_kind", kind)
            put("chunk_index", index)
            put("chunk_count", count)
            put("encoding", encoding)
            put("content", content)
            put("original_bytes", bytes)
            put("sha256", hash)
            put("truncated", truncated)
        },
    )

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
