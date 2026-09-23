package ai.kilocode.stability

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.HttpURLConnection
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException

private const val MAX_ERRORS = 1024
private const val RATE_LIMITED = 429
private const val LAST_SERVER_ERROR = 599

data class ErrorInfo(
    val cause: String,
    val code: String,
    val type: String,
    val status: Int? = null,
    val path: String? = null,
    val expected: String? = null,
    val actual: String? = null,
) {
    fun attributes(): Map<String, String> = buildMap {
        put("code", code)
        status?.let { put("http_status", it.toString()) }
        path?.let { put("json_path", it) }
        expected?.let { put("expected_type", it) }
        actual?.let { put("actual_type", it) }
    }
}

/** Raw HTTP boundaries have a status even when the client does not throw an exception. */
class HttpFailure(val status: Int, message: String) : IOException(message)

/** Identity matching never invokes user-defined Throwable hashCode/equals while holding a collector lock. */
internal class ErrorKey(error: Throwable) : WeakReference<Throwable>(error) {
    private val hash = System.identityHashCode(error)
    override fun hashCode() = hash
    override fun equals(other: Any?): Boolean = this === other ||
        other is ErrorKey && get()?.let { it === other.get() } == true
}

internal fun causes(error: Throwable?): List<Throwable> {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val chain = ArrayList<Throwable>()
    var next = error
    while (next != null && seen.add(next)) {
        chain.add(next)
        next = next.cause
    }
    return chain
}

/** Status comes from the HTTP response, never a guessed number in a human message. */
object ErrorClassifier {
    private val statuses = LinkedHashMap<ErrorKey, Int>()

    fun observe(error: Throwable, status: Int): ErrorInfo {
        synchronized(statuses) {
            statuses.entries.removeIf { it.key.get() == null }
            if (statuses.size >= MAX_ERRORS) statuses.remove(statuses.keys.first())
            statuses[ErrorKey(error)] = status
        }
        return classify(error)
    }

    fun classify(error: Throwable): ErrorInfo {
        val type = error.javaClass.name
        val chain = causes(error)
        val status = chain.firstNotNullOfOrNull { (it as? HttpFailure)?.status } ?:
            synchronized(statuses) { chain.firstNotNullOfOrNull { statuses[ErrorKey(it)] } }
        if (status != null) return http(type, status)
        return when (error) {
            is InterruptedIOException, is TimeoutException, is TimeoutCancellationException ->
                ErrorInfo("network", "timeout", type)
            is CancellationException -> ErrorInfo("user", "cancelled", type)
            is ConnectException -> ErrorInfo("network", "connect_failed", type)
            is SocketException -> ErrorInfo("network", "socket_failed", type)
            is SerializationException -> json(error)
            is LinkageError -> ErrorInfo("plugin", "linkage_error", type)
            is IOException -> ErrorInfo("network", "io_error", type)
            else -> ErrorInfo("unknown", "other", type)
        }
    }

    private fun http(type: String, status: Int) = ErrorInfo(
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN)
            "user" else "agent_core",
        when (status) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> "unauthorized"
            HttpURLConnection.HTTP_FORBIDDEN -> "forbidden"
            HttpURLConnection.HTTP_NOT_FOUND -> "not_found"
            RATE_LIMITED -> "rate_limited"
            in HttpURLConnection.HTTP_INTERNAL_ERROR..LAST_SERVER_ERROR -> "server_error"
            else -> "http_error"
        }, type, status,
    )

    private fun json(error: SerializationException): ErrorInfo {
        val message = error.message.orEmpty()
        return ErrorInfo(
            "plugin", "decode_failed", error.javaClass.name,
            path = Regex("at path: (\\$[^\\s]*)").find(message)?.groupValues?.get(1),
            expected = if (message.contains("object '{'")) "object" else null,
            actual = if (message.contains("had '\"'")) "string" else null,
        )
    }
}
