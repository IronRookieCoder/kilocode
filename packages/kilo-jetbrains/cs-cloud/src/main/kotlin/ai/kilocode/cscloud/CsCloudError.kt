package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            // Two error shapes arrive here: the daemon envelope nests code and message under
            // "error", while proxied agent errors keep the code as a plain string next to a
            // top-level message ({"error":"CONFLICT","message":"session is already
            // processing a prompt"}). Handle both so the HTTP reason phrase never masks the
            // real cause.
            val error = root?.get("error")
            val errorObj = error as? JsonObject
            val errorCode = (error as? JsonPrimitive)?.takeIf { it.isString }?.content
            val code = errorObj.stringField("code") ?: errorCode?.takeIf { it.isNotBlank() } ?: fallback
            val message = errorObj.stringField("message")
                ?: root.stringField("message")
                ?: response.message.ifBlank { "cs-cloud request failed" }
            return CsCloudRequestException(code, message, response.code)
        }

        private fun JsonObject?.stringField(name: String): String? =
            this?.get(name)?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}

class CsCloudUnsupportedOperationException : UnsupportedOperationException(
    "cs-cloud daemon lifecycle is managed outside the JetBrains plugin",
)
