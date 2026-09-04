package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/** Outcome of asking the backend to start the local cs-cloud daemon via `csc cloud start`. */
@Serializable
data class CsCloudStartDto(
    val ok: Boolean,
    val message: String? = null,
    val code: String? = null,
    /**
     * Phase that produced this result ([STAGE_INSTALL] or [STAGE_START]); null when unknown.
     * Optional so payloads written before the field existed still decode.
     */
    val stage: String? = null,
) {
    companion object {
        /** Result came from the `csc install` phase of the install-then-start pipeline. */
        const val STAGE_INSTALL = "install"

        /** Result came from the `csc cloud start` phase. */
        const val STAGE_START = "start"
    }
}
