package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/** Backend path state — response payload of the `/path` route. */
@Serializable
data class PathStateDto(
    val path: String? = null,
)
