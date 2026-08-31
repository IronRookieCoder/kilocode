package ai.kilocode.cscloud

import ai.kilocode.rpc.dto.CloudFavoriteActionResult
import ai.kilocode.rpc.dto.CloudFavoriteItem
import ai.kilocode.rpc.dto.CloudFavoritesErrors
import ai.kilocode.rpc.dto.CloudFavoritesResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.SocketTimeoutException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin client for the daemon favorites facade. Rides a client built with
 * [CsCloudRoute.responseInterceptor], so non-2xx responses surface as
 * [CsCloudRequestException]. Never throws: failures return ok=false DTOs.
 */
class CsCloudFavoritesApi(
    private val client: OkHttpClient,
    private val base: String,
) {
    suspend fun list(): CloudFavoritesResult = withContext(Dispatchers.IO) {
        try {
            val body = execute("$base$LIST_PATH")
            CloudFavoritesResult(ok = true, items = json.decodeFromString(itemList, body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CloudFavoritesResult(ok = false, errorCode = code(e), errorMessage = e.message)
        }
    }

    suspend fun load(id: String): CloudFavoriteActionResult = action(id, LOAD_PATH)

    suspend fun unload(id: String): CloudFavoriteActionResult = action(id, UNLOAD_PATH)

    private suspend fun action(id: String, path: String): CloudFavoriteActionResult = withContext(Dispatchers.IO) {
        try {
            val body = execute("$base${path.format(validate(id))}", method = "POST")
            val parsed = json.decodeFromString(ActionBody.serializer(), body)
            CloudFavoriteActionResult(ok = parsed.success, item = parsed.item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CloudFavoriteActionResult(ok = false, errorCode = code(e), errorMessage = e.message)
        }
    }

    private fun execute(url: String, method: String = "GET"): String =
        client.newCall(
            Request.Builder()
                .url(url)
                .method(method, if (method == "GET") null else ByteArray(0).toRequestBody())
                .build(),
        ).execute().use { response ->
            response.body?.string().orEmpty()
        }

    /** Path traversal guard mirroring the daemon's own route validation. */
    private fun validate(id: String): String {
        require('/' !in id && '\\' !in id && id !in setOf(".", "..") && id.isNotBlank() && NUL !in id) {
            "invalid favorite id"
        }
        return id
    }

    private fun code(e: Exception): String = when (e) {
        is CsCloudRequestException -> when (e.status) {
            401, 403 -> CloudFavoritesErrors.UNAUTHORIZED
            404 -> CloudFavoritesErrors.NOT_FOUND
            else -> CloudFavoritesErrors.INTERNAL
        }
        is IllegalArgumentException -> CloudFavoritesErrors.INTERNAL
        is SocketTimeoutException -> CloudFavoritesErrors.INTERNAL
        else -> CloudFavoritesErrors.UNAVAILABLE
    }

    @Serializable
    private data class ActionBody(
        val success: Boolean = false,
        val item: CloudFavoriteItem? = null,
        val slug: String? = null,
    )

    private companion object {
        const val NUL = '\u0000'
        const val LIST_PATH = "/api/v1/agents/favorites"
        const val LOAD_PATH = "/api/v1/agents/favorites/%s/load"
        const val UNLOAD_PATH = "/api/v1/agents/favorites/%s/unload"
        val json = Json { ignoreUnknownKeys = true }
        val itemList = ListSerializer(CloudFavoriteItem.serializer())
    }
}
