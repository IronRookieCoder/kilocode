package ai.kilocode.backend.telemetry

import ai.kilocode.backend.app.ConnectionTarget
import ai.kilocode.backend.app.HttpCapture
import ai.kilocode.stability.Operations
import ai.kilocode.stability.StabilityService
import ai.kilocode.stability.HttpFailure
import ai.kilocode.stability.rpc
import com.intellij.openapi.components.service
import kotlinx.coroutines.CancellationException
import ai.kilocode.backend.dev.KiloDevMode
import ai.kilocode.log.KiloLog
import com.intellij.openapi.components.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class KiloBackendTelemetry(
    private val log: KiloLog = KiloLog.create(KiloBackendTelemetry::class.java),
    private val operations: Operations? = runCatching { service<StabilityService>().operations }.getOrNull(),
) {
    companion object {
        private const val TIMEOUT_MS = 5_000L
    }

    suspend fun capture(http: OkHttpClient?, base: String?, event: String, properties: Map<String, String>) {
        val body = payload(event, properties)
        if (KiloDevMode.enabled()) {
            log.info(body)
            return
        }
        if (http == null || base.isNullOrBlank()) return
        post(http, base, "telemetry/capture", body)
    }

    @Deprecated("Pass the connection base URL")
    suspend fun capture(http: OkHttpClient?, port: Int, event: String, properties: Map<String, String>) =
        capture(http, "http://127.0.0.1:$port", event, properties)

    suspend fun setEnabled(http: OkHttpClient?, base: String?, enabled: Boolean) {
        val body = JsonObject(mapOf("enabled" to JsonPrimitive(enabled))).toString()
        if (KiloDevMode.enabled()) {
            log.info(body)
            return
        }
        if (http == null || base.isNullOrBlank()) return
        post(http, base, "telemetry/setEnabled", body)
    }

    @Deprecated("Pass the connection base URL")
    suspend fun setEnabled(http: OkHttpClient?, port: Int, enabled: Boolean) =
        setEnabled(http, "http://127.0.0.1:$port", enabled)

    // Preserve cancellation while reporting and swallowing best-effort telemetry failures at the outer boundary.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private suspend fun post(http: OkHttpClient, base: String, path: String, body: String) {
        withContext(Dispatchers.IO) {
            try {
                val bounded = http.newBuilder()
                    .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                val req = Request.Builder()
                    .url("${ConnectionTarget(base).base}/$path")
                    .header("Accept", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                val capture = HttpCapture(req)
                val client = capture.client(bounded)
                val send: suspend () -> Unit = {
                    try {
                        client.newCall(req).execute().use { res ->
                            if (!res.isSuccessful) {
                                throw HttpFailure(res.code, "telemetry $path failed: HTTP ${res.code}")
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        operations?.report(capture.input("telemetry.capture", error))
                        throw error
                    }
                }
                if (operations == null) send() else operations.rpc("other", TIMEOUT_MS, send)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("telemetry $path failed: ${e.message}", e)
            }
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
