package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/** A user-safe failure returned by the cs-cloud control plane. */
class CsCloudRequestException(
    val code: String,
    override val message: String,
    val status: Int,
) : IllegalStateException(message) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromResponse(response: Response, body: String): CsCloudRequestException {
            val fallback = when (response.code) {
                401, 403 -> "unauthorized"
                404 -> "not_found"
                in 500..599 -> "unavailable"
                else -> "http_${response.code}"
            }
            val error = runCatching {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            }.getOrNull()
            val code = error?.get("code")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: fallback
            val message = error?.get("message")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: response.message.ifBlank { "cs-cloud request failed" }
            return CsCloudRequestException(code, message, response.code)
        }
    }
}

class CsCloudUnsupportedOperationException : UnsupportedOperationException(
    "cs-cloud daemon lifecycle is managed outside the JetBrains plugin",
)
