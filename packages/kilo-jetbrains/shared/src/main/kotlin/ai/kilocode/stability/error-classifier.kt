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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
@OptIn(ExperimentalSerializationApi::class)
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
            is SerializationException -> ErrorInfo("plugin", "decode_failed", type)
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

    /** Only a boundary owning the exact payload and decoder descriptor may add structural metadata. */
    fun decode(error: Throwable, payload: String, descriptor: SerialDescriptor): ErrorInfo {
        val info = classify(error)
        if (error !is SerializationException) return info
        val shape = parse(payload)?.let { shape(it, descriptor, "$", 0) }
        return shape?.let { info.copy(path = it.path, expected = it.expected, actual = it.actual) } ?: info
    }

    private data class Shape(val path: String, val expected: String, val actual: String)

    private fun parse(payload: String): JsonElement? = try {
        Json.parseToJsonElement(payload)
    } catch (_: SerializationException) {
        null // A truncated or malformed capture cannot establish a structural path.
    }

    private fun expected(descriptor: SerialDescriptor): String? = when (descriptor.kind) {
        StructureKind.CLASS, StructureKind.OBJECT, StructureKind.MAP -> "object"
        StructureKind.LIST -> "array"
        else -> null
    }

    private fun actual(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) "string" else "primitive"
    }

    private fun shape(value: JsonElement, descriptor: SerialDescriptor, path: String, depth: Int): Shape? {
        val expected = expected(descriptor) ?: return null
        val actual = actual(value)
        return when {
            depth >= MAX_DEPTH || value == JsonNull && descriptor.isNullable -> null
            expected != actual -> Shape(path, expected, actual)
            else -> children(value, descriptor, path, depth)
        }
    }

    private fun children(value: JsonElement, descriptor: SerialDescriptor, path: String, depth: Int): Shape? =
        when (value) {
            is JsonArray -> value.indices.firstNotNullOfOrNull { index ->
                shape(value[index], descriptor.getElementDescriptor(0), "$path[$index]", depth + 1)
            }
            is JsonObject -> value.entries.firstNotNullOfOrNull { (key, item) ->
                val index = if (descriptor.kind == StructureKind.MAP) 1 else descriptor.getElementIndex(key)
                if (index < 0) return@firstNotNullOfOrNull null
                val next = if (PROPERTY.matches(key)) "$path.$key" else "$path[${JsonPrimitive(key)}]"
                shape(item, descriptor.getElementDescriptor(index), next, depth + 1)
            }
            else -> null
        }

    private const val MAX_DEPTH = 64
    private val PROPERTY = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
