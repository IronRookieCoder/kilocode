package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/** Input for AI commit-message generation scoped to a repository directory. */
@Serializable
data class CommitMessageRequestDto(
    val directory: String,
    /** Current commit-message text — when set, the model is asked for a different result. */
    val previousMessage: String? = null,
    /** Optional provider override — together with [modelID], uses this provider's model. */
    val providerID: String? = null,
    /** Optional model override — requires [providerID]. */
    val modelID: String? = null,
)

/** Result of commit-message generation: exactly one of message / error is set. */
@Serializable
data class CommitMessageResultDto(
    val message: String? = null,
    val error: String? = null,
    /** True when the repository has no changes to describe. */
    val noChanges: Boolean = false,
)
