package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

data class Health(val healthy: Boolean, val version: String, val capabilities: Set<String> = emptySet())

object CsCloudHealth {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseHealth(body: String): Health {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw CsCloudRequestException("invalid_health", "cs-cloud health response is malformed", 200) }
        val ok = root["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        if (ok != true) {
            val error = root["error"]?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.content ?: "health_failed"
            val message = error?.get("message")?.jsonPrimitive?.content ?: "cs-cloud health check failed"
            throw CsCloudRequestException(code, message, 200)
        }
        val data = root["data"]?.jsonObject
            ?: throw CsCloudRequestException("invalid_health", "cs-cloud health data is missing", 200)
        val version = data["version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: throw CsCloudRequestException("invalid_health", "cs-cloud health version is missing", 200)
        val capabilities = data["capabilities"]?.jsonArray?.mapTo(mutableSetOf()) { it.jsonPrimitive.content }.orEmpty()
        return Health(healthy = data["status"]?.jsonPrimitive?.content == "ok", version = version, capabilities = capabilities)
    }
}

fun parseHealth(body: String): Health = CsCloudHealth.parseHealth(body)
