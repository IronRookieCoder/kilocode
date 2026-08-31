package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/** Stable error codes carried by cloud-favorites DTOs. */
object CloudFavoritesErrors {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val NOT_FOUND = "NOT_FOUND"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val INTERNAL = "INTERNAL"
}

/** A Costrict cloud favorite entry (skill/agent/command/mcp) with its local lifecycle status. */
@Serializable
data class CloudFavoriteItem(
    val id: String,
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    val itemType: String = "",
    val status: String = "",
    val localPath: String? = null,
)

/** Result of listing cloud favorites via the cs-cloud daemon. */
@Serializable
data class CloudFavoritesResult(
    val ok: Boolean,
    val items: List<CloudFavoriteItem> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/** Result of a cloud favorite load/unload action. */
@Serializable
data class CloudFavoriteActionResult(
    val ok: Boolean,
    val item: CloudFavoriteItem? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)
