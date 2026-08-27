package ai.kilocode.cscloud

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * HTTP clients for the cs-cloud control plane:
 *  - a request client that rewrites plugin routes, normalizes headers
 *    (`X-Workspace-Directory`, `Authorization: Bearer <key>` / `X-API-Key`),
 *    unwraps the `{ok, data}` envelope, and retries idempotent requests
 *  - a dedicated SSE client that never times out reads
 */
class CsCloudHttpClients(
    private val endpoint: CsCloudEndpoint,
    private val workspace: () -> Path?,
) {

    /** Request client with envelope unwrapping, route rewriting, retries and pooling. */
    val client: OkHttpClient = buildClient()

    /** SSE client: identical headers, no envelope unwrapping, unbounded read timeout. */
    val sseClient: OkHttpClient = buildSseClient()

    /** Plain client: header/route normalization only; raw bodies pass through untouched. */
    val plainClient: OkHttpClient = baseBuilder().build()

    private fun buildClient(): OkHttpClient = baseBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .addInterceptor(CsCloudRetryInterceptor())
        .addInterceptor(CsCloudRouteInterceptor(endpoint, workspace))
        .addInterceptor(CsCloudEnvelopeInterceptor())
        .build()

    private fun buildSseClient(): OkHttpClient = baseBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(CsCloudRouteInterceptor(endpoint, workspace))
}

/**
 * Rewrites plugin routes to control-plane routes and normalizes request headers:
 * `X-Workspace-Directory`, plus `Authorization: Bearer <key>` (or `X-API-Key`) when a key is present.
 */
class CsCloudRouteInterceptor(
    private val endpoint: CsCloudEndpoint,
    private val workspace: () -> Path?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val rewritten = request.newBuilder()
            .url(
                request.url.newBuilder()
                    .encodedPath(CsCloudRoute.mapPath(request.url.encodedPath))
                    .build()
            )
            .header("X-Workspace-Directory", workspace()?.toAbsolutePath()?.normalize()?.toString().orEmpty())
            .apply {
                endpoint.key?.let { key ->
                    header("Authorization", "Bearer $key")
                    header("X-API-Key", key)
                }
            }
            .build()
        return chain.proceed(rewritten)
    }
}

/**
 * Retries idempotent requests on connection-level [IOException]s (up to [maxAttempts]).
 * Non-idempotent requests pass through untouched.
 */
class CsCloudRetryInterceptor(
    private val maxAttempts: Int = 3,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method !in IDEMPOTENT) return chain.proceed(request)
        var attempt = 0
        while (true) {
            attempt++
            try {
                return chain.proceed(request)
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
            }
        }
    }

    private companion object {
        val IDEMPOTENT = setOf("GET", "HEAD", "PUT", "DELETE")
    }
}

/**
 * Unwraps the `{ok, data}` envelope on success and converts `{ok:false, error}` /
 * non-2xx responses into [CsCloudRequestException]. Non-envelope bodies pass through.
 */
class CsCloudEnvelopeInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val text = body.string()
        if (!response.isSuccessful) {
            throw CsCloudRequestException.fromResponse(response, text)
        }
        val payload = CsCloudEnvelope.unwrapOrNull(text, response.code) ?: text
        return response.newBuilder()
            .body(payload.toString().toResponseBody(body.contentType() ?: JSON_MEDIA_TYPE))
            .build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
