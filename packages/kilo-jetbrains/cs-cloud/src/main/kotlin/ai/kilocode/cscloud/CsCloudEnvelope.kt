package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unwraps the `{ok, data}` / `{ok, error}` control-plane envelope (design §5.1):
 *  - `{"ok":true,"data":...}`  → the `data` payload
 *  - `{"ok":false,"error":{"code","message"}}` → throws [CsCloudRequestException]
 */
object CsCloudEnvelope {
    private val json = Json { ignoreUnknownKeys = true }

    fun unwrap(body: String, status: Int): JsonElement {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw CsCloudRequestException("invalid_response", "cs-cloud response is malformed", status)
        }
        if (root["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
            return root["data"] ?: JsonNull
        }
        val error = root["error"]?.jsonObject
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "request_failed"
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "cs-cloud request failed"
        throw CsCloudRequestException(code, message, status)
    }

    /** Returns the unwrapped `data` payload, or null when [body] is not an envelope. */
    fun unwrapOrNull(body: String, status: Int): JsonElement? =
        if (looksLikeEnvelope(body)) unwrap(body, status) else null

    private fun looksLikeEnvelope(body: String): Boolean {
        val trimmed = body.trim()
        return trimmed.startsWith("{") && runCatching {
            json.parseToJsonElement(trimmed).jsonObject.containsKey("ok")
        }.getOrDefault(false)
    }
}
