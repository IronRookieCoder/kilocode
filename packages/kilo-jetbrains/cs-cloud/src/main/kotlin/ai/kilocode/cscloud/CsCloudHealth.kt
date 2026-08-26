package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Health snapshot parsed from the `{ok, data}` `/api/v1/runtime/health` envelope
 * (design §5.1), e.g. `{"ok":true,"data":{"status":"ok","version":"1.0.0","uptime":42}}`.
 */
data class CsCloudHealth(
    val healthy: Boolean,
    val version: String,
    val uptime: Long? = null,
)

/** Parses control-plane health responses into [CsCloudHealth]. */
object CsCloudHealthParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** @throws CsCloudRequestException when the envelope reports `ok:false` or is malformed. */
    fun parse(body: String): CsCloudHealth {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw CsCloudRequestException("invalid_health", "cs-cloud health response is malformed", 200)
        }
        if (root["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() != true) {
            val error = root["error"]?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "health_failed"
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "cs-cloud health check failed"
            throw CsCloudRequestException(code, message, 200)
        }
        val data = root["data"]?.jsonObject
            ?: throw CsCloudRequestException("invalid_health", "cs-cloud health data is missing", 200)
        val version = data["version"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw CsCloudRequestException("invalid_health", "cs-cloud health version is missing", 200)
        val healthy = data["status"]?.jsonPrimitive?.content == "ok"
        val uptime = data["uptime"]?.jsonPrimitive?.content?.toLongOrNull()
        return CsCloudHealth(healthy, version, uptime)
    }
}
