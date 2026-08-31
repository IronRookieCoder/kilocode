package ai.kilocode.cscloud

import ai.kilocode.jetbrains.api.client.DefaultApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class CsCloudClients(
    val api: DefaultApi,
    val apiClient: OkHttpClient,
    val sseClient: OkHttpClient,
    val healthClient: OkHttpClient,
    val favoritesClient: OkHttpClient,
)

object CsCloudHttpClients {
    private const val HEALTH_TIMEOUT_SECONDS = 3L
    private const val FAVORITES_TIMEOUT_SECONDS = 120L

    fun create(endpoint: CsCloudEndpoint, roots: () -> List<Path> = { emptyList() }): CsCloudClients {
        val prefix = endpoint.base.toHttpUrl().encodedPath.trimEnd('/')
        val apiClient = OkHttpClient.Builder()
            .addInterceptor(CsCloudRoute.interceptor(prefix, roots))
            .addInterceptor(CsCloudRoute.responseInterceptor())
            .apply { endpoint.key?.let { addInterceptor(auth(it)) } }
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        // SSE must not use responseInterceptor: reading body.string() on an event stream
        // would wait for EOF and prevent EventSource from receiving frames.
        val sseClient = OkHttpClient.Builder()
            .addInterceptor(CsCloudRoute.interceptor(prefix, roots))
            .apply { endpoint.key?.let { addInterceptor(auth(it)) } }
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val healthClient = OkHttpClient.Builder()
            .addInterceptor(CsCloudRoute.interceptor(prefix, roots))
            .addInterceptor(CsCloudRoute.responseInterceptor())
            .apply { endpoint.key?.let { addInterceptor(auth(it)) } }
            .callTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val favoritesClient = OkHttpClient.Builder()
            .addInterceptor(CsCloudRoute.interceptor(prefix, roots))
            .addInterceptor(CsCloudRoute.responseInterceptor())
            .apply { endpoint.key?.let { addInterceptor(auth(it)) } }
            .callTimeout(FAVORITES_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(FAVORITES_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        return CsCloudClients(
            api = DefaultApi(basePath = endpoint.base, client = apiClient),
            apiClient = apiClient,
            sseClient = sseClient,
            healthClient = healthClient,
            favoritesClient = favoritesClient,
        )
    }

    private fun auth(key: String): Interceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer $key")
                .build(),
        )
    }
}
