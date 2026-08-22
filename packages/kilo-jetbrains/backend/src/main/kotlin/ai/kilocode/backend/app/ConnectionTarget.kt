// kilocode_change - new file
package ai.kilocode.backend.app

/** Base URL used by a backend connection, normalized for URL composition. */
data class ConnectionTarget private constructor(val base: String) {
    companion object {
        operator fun invoke(base: String) = ConnectionTarget(
            base.trim().trimEnd('/').also { require(it.isNotEmpty()) { "Connection target URL must not be empty" } },
        )
    }
}
