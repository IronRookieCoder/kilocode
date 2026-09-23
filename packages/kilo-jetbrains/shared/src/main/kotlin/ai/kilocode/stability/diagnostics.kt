package ai.kilocode.stability

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.io.UncheckedIOException
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val WINDOW_MS = 60_000L
private const val MAX_INCIDENT_BYTES = 1024 * 1024
private const val MAX_KEYS = 1024
private const val MAX_FRAMES = 5
private const val SCALAR_BYTES = 64
private val IDENTIFIER = Regex("[A-Za-z0-9_.-]{1,128}")
private val SCALAR = Regex("[A-Za-z0-9_.#$<> -]+")

/** 完整诊断组的构建与准入；先脱敏所有原文，最后一次性发布parent、chunks和可用计数。 */
class Diagnostics(
    private val recorder: Recorder,
    private val clock: Clock,
    maxRateKeys: Int = MAX_KEYS,
    private val redactor: (String) -> Redacted = DiagnosticRedactor::clean,
) {
    private class Rate(val fingerprint: String, val frames: List<String>, val category: String, var name: String) {
        var window = -1L
        var details = 0
        var extra = 0L
    }

    private val capacity = maxRateKeys.coerceAtLeast(1)
    private val lock = Any()
    private val seen = LinkedHashMap<String, Unit>()
    private val windows = LinkedHashMap<String, Rate>()
    private val overflow = Rate("overflow", emptyList(), "other", "error.reported")

    fun report(input: DiagnosticInput): String {
        val error = input.error
        if (error is CancellationException) throw error
        if (error is VirtualMachineError || error is ThreadDeath) fatal(error)
        val candidate = input.context["incident_id"] ?: input.context["fault_id"]
        val id = candidate?.takeIf(IDENTIFIER::matches) ?: UUID.randomUUID().toString()
        try {
            synchronized(lock) { collect(input, id) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: VirtualMachineError) {
            fatal(failure)
        } catch (failure: ThreadDeath) {
            fatal(failure)
        }
        return id
    }

    private fun collect(input: DiagnosticInput, id: String) {
        val time = clock.wall() / WINDOW_MS
        flush(time)
        val fault = input.context["fault_id"] ?: id
        if (seen.put(fault, Unit) != null) return
        if (seen.size > MAX_KEYS) seen.remove(seen.keys.first())
        val error = input.error
        val frames = frames(error)
        val fingerprint = hash(
            (error?.javaClass?.name ?: input.component + ":" + input.attributes["code"]) +
                "\n" + frames.joinToString("\n"),
        )
        val category = category(error)
        val name = if (input.handled) "error.reported" else "error.uncaught"
        val context = input.context + mapOf("fault_id" to fault, "incident_id" to id)
        val minimal = context.mapValues { (_, value) -> if (IDENTIFIER.matches(value)) value else hash(value) }
        val count = if (error != null && "metrics" in recorder.beginSnapshot(clock.wall(), name).purposes) {
            count(name, fingerprint, category, scalar(input.component), minimal)
        } else null
        val limit = recorder.limit("diagnostic.reported", 2)
        val legacy = limit == 0 && recorder.limit(name) > 0
        val quota = if (legacy) recorder.limit(name) else limit
        val state = reserve(Rate(fingerprint, frames, category, name), time, quota)
        when {
            state == null -> count?.let(recorder::record)
            legacy -> {
                count?.let(recorder::record)
                recorder.record(detail(state, 1, minimal))
            }
            else -> recorder.recordBatch(drafts(input, id, context, count))
        }
    }

    private fun reserve(rate: Rate, time: Long, quota: Int): Rate? {
        if (quota == 0) return null
        val state = windows[rate.fingerprint] ?: if (windows.size < capacity) {
            rate.also { windows[rate.fingerprint] = it }
        } else overflow
        state.name = rate.name
        if (state.window != time) {
            state.window = time
            state.details = 0
        }
        return if (state === overflow || state.details >= quota) {
            state.extra++
            null
        } else {
            state.details++
            state
        }
    }

    // 任意过滤器/Throwable格式化故障必须fail closed；取消和致命错误在同一出口重新抛出。
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun drafts(input: DiagnosticInput, id: String, context: Map<String, String>, count: Draft?): List<Draft> =
        try {
            incident(input, id, context, count)
        } catch (failure: Throwable) {
            if (failure is CancellationException || failure is VirtualMachineError || failure is ThreadDeath) {
                throw failure
            }
            // 原始数据及失败异常都不能用于后备记录，亦不提交此前构建的计数或分片。
            listOf(parent("diagnostic.redaction_failed", mapOf("incident_id" to id), buildJsonObject {
                put("severity", "error")
                put("component", "diagnostics")
                put("code", "redaction_failed")
                put("message", "Diagnostic redaction failed")
                put("thread_name", "unknown")
                put("thread_id", 0)
                put("payload_refs", JsonArray(emptyList()))
                put("truncated", true)
            }))
        }

    private fun incident(input: DiagnosticInput, id: String, context: Map<String, String>, count: Draft?): List<Draft> {
        fun clean(text: String) = redactor(text).text
        val component = scalar(clean(input.component))
        val attributes = input.attributes.entries.associate { (key, value) ->
            clean(key) to clean("$key=$value").substringAfter('=')
        }
        val cleaned = context.mapValues { clean(it.value) }
        val content = content(input, redactor)
        if (attributes.isNotEmpty()) {
            content["attributes"] = JsonObject(attributes.mapValues { JsonPrimitive(it.value) }).toString()
        }
        val bytes = content.filterValues { it.isNotEmpty() }.mapValues { it.value.encodeToByteArray() }
        val total = bytes.values.sumOf { it.size.toLong() }
        var remaining = MAX_INCIDENT_BYTES
        var pending = total
        val parts = bytes.entries.mapIndexed { index, (kind, value) ->
            val budget = if (pending <= remaining) value.size else {
                // 每种非空载荷至少有一字节，以便分片保留其原长/hash；末种拿完余量。
                (remaining.toLong() * value.size / pending).toInt().coerceIn(1, remaining - (bytes.size - index - 1))
            }
            remaining -= budget
            pending -= value.size
            DiagnosticPayload.parts(id, kind, value, budget).let { result ->
                result.copy(drafts = result.drafts.map {
                    Draft(it.name, it.kind, it.channel, it.data, cleaned, it.epoch, it.purposes, it.schemaVersion)
                })
            }
        }
        val data = buildJsonObject {
            put("severity", input.severity.name.lowercase())
            put("component", component)
            put("code", scalar(attributes["code"] ?: "other"))
            put("message", "Diagnostic detail in payloads")
            put("thread_name", scalar(clean(input.thread)))
            put("thread_id", input.threadId)
            input.error?.let { error ->
                put("exception_type", scalar(clean(error.javaClass.name)))
                put("suppressed_count", suppressed(error))
            }
            metadata(attributes).forEach { (key, value) -> put(key, value) }
            put("payload_bytes", total)
            put("payload_refs", JsonArray(bytes.keys.map(::JsonPrimitive)))
            put("truncated", parts.any { it.truncated })
        }
        val counters = count?.let {
            listOf(Draft(it.name, it.kind, it.channel, JsonObject(it.data + mapOf(
                "component" to JsonPrimitive(component), "fault_id" to JsonPrimitive(cleaned.getValue("fault_id")),
            )), cleaned, it.epoch, it.purposes, it.schemaVersion))
        } ?: emptyList()
        return counters + parent("diagnostic.reported", cleaned, data) + parts.flatMap { it.drafts }
    }

    private fun suppressed(error: Throwable): Int {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val queue = ArrayDeque<Throwable>()
        queue.add(error)
        var count = 0
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            count += next.suppressed.size
            queue.addAll(next.suppressed)
            next.cause?.let(queue::add)
        }
        return count
    }

    private fun flush(time: Long) {
        (windows.values + overflow).forEach { state ->
            if (state.extra == 0L || state.window >= time) return@forEach
            if (recorder.limit(state.name) > 0) recorder.record(detail(state, state.extra, emptyMap()))
            state.extra = 0
        }
    }

    private fun detail(state: Rate, count: Long, context: Map<String, String>) = Draft(
        state.name, "diagnostic", "diagnostic", buildJsonObject {
            put("message", if (context.isNotEmpty()) "fault detail: error_class=${state.category}"
                else "fault summary: error_class=${state.category} suppressed=$count")
            put("frames", JsonArray(state.frames.map(::JsonPrimitive)))
            put("fingerprint", state.fingerprint)
            put("count", count)
        }, context, purposes = setOf("logs"),
    )

    private fun parent(name: String, context: Map<String, String>, data: JsonObject) =
        Draft(name, "diagnostic", "diagnostic", data, context, purposes = setOf("logs"), schemaVersion = "2.0")

    @Suppress("ThrowingExceptionFromFinally") // 致命错误身份必须优先于最小记录尝试期间的任何次生异常。
    private fun fatal(error: Throwable): Nothing {
        try {
            recorder.record(count("error.uncaught", "fatal", "other", "diagnostics", mapOf("fault_id" to "fatal")))
        } finally {
            throw error
        }
    }
}

private fun frames(error: Throwable?): List<String> = error?.stackTrace?.asSequence()
    ?.filter { it.className.startsWith("ai.kilocode.") }
    ?.map { "${it.className}#${it.methodName}" }
    ?.filter { SCALAR.matches(it) }
    ?.take(MAX_FRAMES)?.toList() ?: emptyList()

private fun content(input: DiagnosticInput, redactor: (String) -> Redacted): LinkedHashMap<String, String> {
    fun clean(text: String) = redactor(text).text
    val content = linkedMapOf("message" to clean(input.message))
    input.error?.let { error ->
        content["stack"] = clean(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString())
        if (error.message != input.message) error.message?.let { content["exception_message"] = clean(it) }
    }
    input.payloads.forEach { (kind, supplier) ->
        require(Regex("[a-z][a-z0-9_]{0,63}").matches(kind)) { "Invalid diagnostic payload kind" }
        require(kind !in content && kind != "attributes") { "Reserved diagnostic payload kind" }
        content[kind] = clean(supplier())
    }
    return content
}

private fun metadata(attributes: Map<String, String>): JsonObject = buildJsonObject {
    listOf("method", "route", "content_type", "json_path", "expected_type", "actual_type").forEach { key ->
        attributes[key]?.takeIf { it.isNotEmpty() && it.encodeToByteArray().size <= SCALAR_BYTES }?.let { value ->
            put(key, if (key in setOf("method", "expected_type", "actual_type")) scalar(value) else value)
        }
    }
    attributes["http_status"]?.toLongOrNull()?.takeIf { it >= 0 }?.let { put("http_status", it) }
}

private fun count(
    name: String,
    fingerprint: String,
    category: String,
    component: String,
    context: Map<String, String>,
) = Draft(name, "diagnostic", "critical", buildJsonObject {
    put("fault_id", context.getValue("fault_id"))
    put("error_class", category)
    put("handled", name != "error.uncaught")
    put("fingerprint", fingerprint)
    put("component", scalar(component))
}, context, purposes = setOf("metrics"))

private fun scalar(text: String): String = text.takeIf {
    it.encodeToByteArray().size <= SCALAR_BYTES && SCALAR.matches(it)
} ?: "unknown"

private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }

private fun category(error: Throwable?): String = when (error) {
    is NoClassDefFoundError -> "no_class_def_found"
    is LinkageError -> "linkage_error"
    is UncheckedIOException, is IOException -> "io_error"
    is TimeoutException -> "timeout_exception"
    is SerializationException -> "json_parse"
    is NullPointerException -> "npe"
    is IllegalStateException -> "illegal_state"
    else -> "other"
}
