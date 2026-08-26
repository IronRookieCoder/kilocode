package ai.kilocode.cscloud

/**
 * Connection state machine states (design §4.2):
 * `disconnected → discovering → connecting → ready`, failing to `unavailable`.
 */
enum class CsCloudConnectionState {
    Disconnected,
    Discovering,
    Connecting,
    Ready,
    Unavailable,
}

/** Health-check diagnosis attached to [CsCloudConnectionState.Unavailable]. */
enum class CsCloudHealthDiagnosis {
    /** The daemon is not running or unreachable (network error / connection refused). */
    DAEMON_NOT_RUNNING,

    /** The API key is missing or rejected (HTTP 401/403). */
    CREDENTIALS_INVALID,

    /** The daemon is up but the csc agent is not ready (HTTP 503). */
    AGENT_NOT_READY,
}

/** Immutable status snapshot pushed to frontend listeners. */
data class CsCloudConnectionStatus(
    val state: CsCloudConnectionState,
    val diagnosis: CsCloudHealthDiagnosis? = null,
    val detail: String? = null,
)
