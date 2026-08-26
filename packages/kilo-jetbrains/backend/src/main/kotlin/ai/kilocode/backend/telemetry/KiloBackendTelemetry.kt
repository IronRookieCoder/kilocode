package ai.kilocode.backend.telemetry

import ai.kilocode.backend.dev.KiloDevMode
import ai.kilocode.connection.Transport
import ai.kilocode.log.KiloLog
import com.intellij.openapi.components.Service
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Service(Service.Level.APP)
class KiloBackendTelemetry(
    private val log: KiloLog = KiloLog.create(KiloBackendTelemetry::class.java),
) {
    suspend fun capture(transport: Transport?, event: String, properties: Map<String, String>) {
        val body = payload(event, properties)
        if (KiloDevMode.enabled()) {
            log.info(body)
            return
        }
        if (transport == null) return
        post(transport, "telemetry/capture", body)
    }

    suspend fun setEnabled(transport: Transport?, enabled: Boolean) {
        val body = JsonObject(mapOf("enabled" to JsonPrimitive(enabled))).toString()
        if (KiloDevMode.enabled()) {
            log.info(body)
            return
        }
        if (transport == null) return
        post(transport, "telemetry/setEnabled", body)
    }

    private suspend fun post(transport: Transport, path: String, body: String) {
        try {
            transport.use {
                it.call("POST", "/$path", body)
            }
        } catch (e: Exception) {
            log.warn("telemetry $path failed: ${e.message}", e)
        }
    }

    private fun payload(event: String, properties: Map<String, String>): String = JsonObject(
        mapOf(
            "event" to JsonPrimitive(event),
            "properties" to JsonObject(base() + properties.mapValues { JsonPrimitive(it.value) }),
        ),
    ).toString()

    private fun base(): Map<String, JsonPrimitive> =
        KiloLog.payload(log).mapValues { JsonPrimitive(it.value) }
}
