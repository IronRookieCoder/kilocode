package ai.kilocode.cscloud

import ai.kilocode.rpc.dto.HealthDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object CsCloudHealth {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseHealth(body: String): HealthDto {
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
        return HealthDto(healthy = data["status"]?.jsonPrimitive?.content == "ok", version = version)
    }
}

fun parseHealth(body: String): HealthDto = CsCloudHealth.parseHealth(body)
