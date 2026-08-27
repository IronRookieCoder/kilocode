package ai.kilocode.backend.cscloud

import ai.kilocode.connection.Transport
import ai.kilocode.connection.TransportException
import ai.kilocode.connection.TransportFactory
import ai.kilocode.cscloud.CsCloudEndpoint
import ai.kilocode.cscloud.CsCloudHttpClients
import ai.kilocode.cscloud.CsCloudRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.file.Path

/**
 * TransportFactory bound to the resolved cs-cloud endpoint.
 *
 * Each [create] returns a lightweight [Transport] sharing the endpoint's
 * OkHttp client; the client's interceptors rewrite plugin routes to cs-cloud
 * routes, attach workspace/auth headers, retry idempotent calls and unwrap
 * `{ok, data}` envelopes.
 */
class CsCloudTransportFactory(
    private val endpoint: CsCloudEndpoint,
    private val workspace: () -> Path?,
) : TransportFactory {
    private val clients = CsCloudHttpClients(endpoint, workspace)

    override fun create(): Transport = CsCloudTransport(endpoint.base, clients.client)
}

/**
 * Request/response transport over the cs-cloud control plane (design §3.1).
 *
 * The route is the plugin-style route (e.g. `/session?directory=...`); the
 * client's route interceptor rewrites it to the control-plane route. Non-2xx
 * responses surface as [TransportException] via [CsCloudRequestException].
 */
class CsCloudTransport(
    private val base: String,
    private val http: OkHttpClient,
) : Transport {

    override suspend fun call(method: String, route: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(base + route)
                .method(method, body?.toRequestBody(JSON))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    response.body?.string().orEmpty()
                }
            } catch (e: CsCloudRequestException) {
                throw TransportException(e.status, e.message, null, e)
            } catch (e: IOException) {
                throw TransportException(null, e.message ?: "cs-cloud transport error", null, e)
            }
        }

    /** The OkHttp client is owned by the connection and shared across transports. */
    override fun close() = Unit

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
