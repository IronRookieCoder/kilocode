package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/**
 * A user-safe failure returned by the cs-cloud control plane, carrying the
 * `{ok:false, error:{code, message}}` payload and the HTTP status.
 */
class CsCloudRequestException(
    val code: String,
    override val message: String,
    val status: Int,
) : IllegalStateException(message) {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Builds the exception from a non-2xx response, falling back to status-derived codes. */
        fun fromResponse(response: Response, body: String): CsCloudRequestException {
            val fallback = when (response.code) {
                401, 403 -> "unauthorized"
                404 -> "not_found"
                503 -> "agent_not_ready"
                in 500..599 -> "unavailable"
                else -> "http_${response.code}"
            }
            val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
            val code = error?.get("code")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallback
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: response.message.ifBlank { "cs-cloud request failed" }
            return CsCloudRequestException(code, message, response.code)
        }
    }
}
