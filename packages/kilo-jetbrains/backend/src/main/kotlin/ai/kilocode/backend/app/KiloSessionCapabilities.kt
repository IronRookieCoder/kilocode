// kilocode_change - new file
package ai.kilocode.backend.app

interface KiloSessionCapabilities {
    suspend fun ensure(id: String, directory: String): CapabilityResult

    suspend fun release(id: String, reason: CapabilityReleaseReason)

    suspend fun releaseAll(reason: CapabilityReleaseReason)
}

sealed interface CapabilityResult {
    data class Ready(val generation: String, val tools: Set<String>) : CapabilityResult

    data class Unavailable(val reason: String) : CapabilityResult
}

enum class CapabilityReleaseReason {
    IDLE,
    ABORT,
    DELETE,
    PROJECT_CLOSED,
    DISCONNECT,
    SHUTDOWN,
}
