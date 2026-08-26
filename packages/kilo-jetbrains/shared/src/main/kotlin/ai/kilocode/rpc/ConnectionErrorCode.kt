package ai.kilocode.rpc

/**
 * Stable machine-readable codes for connection failures, carried from the backend
 * through [ai.kilocode.rpc.dto.LoadErrorDto] and [ai.kilocode.rpc.dto.CsCloudStartDto]
 * so the frontend can offer targeted recovery actions instead of matching on
 * human-readable messages.
 */
object ConnectionErrorCode {
    /** The `csc` CLI is missing (or cs-cloud never wrote its server URL). */
    const val CSC_NOT_INSTALLED = "csc_not_installed"

    /** The cs-cloud daemon is not running or unreachable. */
    const val DAEMON_DOWN = "daemon_down"

    /** cs-cloud rejected the API key (HTTP 401/403). */
    const val UNAUTHORIZED = "unauthorized"

    /** No package manager (npm/pnpm/bun/yarn) was found to install the `csc` CLI. */
    const val NPM_NOT_FOUND = "npm_not_found"
}
