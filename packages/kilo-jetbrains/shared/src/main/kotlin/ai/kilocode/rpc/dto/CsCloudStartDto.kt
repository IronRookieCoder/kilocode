package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/** Outcome of asking the backend to start the local cs-cloud daemon via `csc cloud start`. */
@Serializable
data class CsCloudStartDto(
    val ok: Boolean,
    val message: String? = null,
    val code: String? = null,
)
