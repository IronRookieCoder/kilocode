package ai.kilocode.connection

/**
 * Creates [Transport] instances bound to the current connection.
 *
 * A module providing the agent backend connection implements this contract;
 * request/response payloads are JSON documents shaped by the cross-module DTOs
 * from `ai.kilocode.rpc.dto`.
 */
interface TransportFactory {

    /** Create a transport for the current connection. */
    fun create(): Transport
}

/**
 * Request/response transport to the agent backend.
 *
 * Implementations throw [ai.kilocode.connection.TransportException] (or a
 * subclass) when the backend answers with an error or the call fails.
 */
interface Transport : AutoCloseable {

    /**
     * Perform a request against the backend.
     *
     * @param method HTTP-style request method ("GET", "POST", ...).
     * @param route backend route, e.g. "/global/health".
     * @param body optional JSON request payload.
     * @return the JSON response body.
     */
    suspend fun call(method: String, route: String, body: String? = null): String
}

/** Raised by [Transport] implementations when a call fails. */
class TransportException(
    val status: Int? = null,
    message: String,
    val body: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
