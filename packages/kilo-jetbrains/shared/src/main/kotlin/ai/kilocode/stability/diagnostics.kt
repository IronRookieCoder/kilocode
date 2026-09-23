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

private data class Fields(val component: String, val context: Map<String, String>, val frames: List<String>)
private data class Batch(val count: Draft?, val logs: List<Draft>)

/** 日志parent/chunks原子发布；指标独立准入。状态锁不覆盖过滤器、supplier、格式化或准入。 */
class Diagnostics(
    private val recorder: Recorder,
    private val clock: Clock,
    maxRateKeys: Int = MAX_KEYS,
    private val redactor: (String) -> Redacted = DiagnosticRedactor::clean,
) {
    private class Rate(val fingerprint: String, val category: String, var name: String) {
        var window = -1L
        var details = 0
        var extra = 0L
    }

    private data class Slot(
        val id: String,
        val duplicate: Boolean,
        val detail: Boolean,
        val summaries: List<Pair<Rate, Long>>,
    )

    private val capacity = maxRateKeys.coerceAtLeast(1)
    private val lock = Any()
    private val seen = LinkedHashMap<String, String>()
    private val errors = LinkedHashMap<ErrorKey, String>()
    private val windows = LinkedHashMap<String, Rate>()
    private val overflow = Rate("overflow", "other", "error.reported")

    fun report(input: DiagnosticInput): String {
        val error = input.error
        if (error is CancellationException) throw error
        if (error is VirtualMachineError || error is ThreadDeath) fatal(error)
        val id = UUID.randomUUID().toString()
        return try {
            collect(input, id)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: VirtualMachineError) {
            fatal(failure)
        } catch (failure: ThreadDeath) {
            fatal(failure)
        }
    }

    private fun key(input: DiagnosticInput, id: String): String {
        val keys = causes(input.error).map(::ErrorKey)
        val supplied = input.context["fault_id"] ?: input.context["incident_id"]
        return hash(if (keys.isEmpty()) supplied ?: id else {
            synchronized(lock) {
                errors.entries.removeIf { entry -> entry.key.get() == null }
                val token = supplied ?: keys.firstNotNullOfOrNull { errors[it] } ?: id
                keys.forEach { key ->
                    if (!errors.containsKey(key) && errors.size >= MAX_KEYS) errors.remove(errors.keys.first())
                    errors[key] = token
                }
                token
            }
        })
    }

    private fun collect(input: DiagnosticInput, id: String): String {
        val time = clock.wall() / WINDOW_MS
        val error = input.error
        val key = key(input, id)
        val frames = frames(error)
        val fingerprint = hash(
            (error?.javaClass?.name ?: input.component + ":" + input.attributes["code"]) +
                "\n" + frames.joinToString("\n"),
        )
        val category = category(error)
        val name = if (input.handled) "error.reported" else "error.uncaught"
        val rate = Rate(fingerprint, category, name)
        val limit = recorder.limit("diagnostic.reported", 2)
        val legacy = limit == 0 && recorder.limit(name) > 0
        val quota = if (legacy) recorder.limit(name) else limit
        val slot = synchronized(lock) {
            val summaries = flush(time)
            val previous = seen[key]
            if (previous != null) Slot(previous, true, false, summaries)
            else {
                seen[key] = id
                if (seen.size > MAX_KEYS) seen.remove(seen.keys.first())
                Slot(id, false, reserve(rate, time, quota), summaries)
            }
        }
        slot.summaries.forEach { summary ->
            if (recorder.limit(summary.first.name) > 0) {
                recorder.record(detail(summary.first, summary.second, emptyMap()))
            }
        }
        if (slot.duplicate) return slot.id
        val batch = drafts(input, slot, rate, legacy)
        batch.count?.let(recorder::record)
        if (batch.logs.isNotEmpty()) recorder.recordBatch(batch.logs)
        return slot.id
    }

    private fun reserve(rate: Rate, time: Long, quota: Int): Boolean {
        if (quota == 0) return false
        val state = windows[rate.fingerprint] ?: if (windows.size < capacity) {
            Rate(rate.fingerprint, rate.category, rate.name).also { windows[rate.fingerprint] = it }
        } else overflow
        state.name = rate.name
        // 锁外采样可能乱序到达；旧调用沿用当前窗口，不能回退并重新发放配额。
        if (state.window < time) {
            state.window = time
            state.details = 0
        }
        return if (state === overflow || state.details >= quota) {
            state.extra++
            false
        } else {
            state.details++
            true
        }
    }

    // 任意过滤器/Throwable格式化故障必须fail closed；取消和致命错误在同一出口重新抛出。
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun drafts(input: DiagnosticInput, slot: Slot, rate: Rate, legacy: Boolean): Batch =
        try {
            val clean: (String) -> Redacted = { text ->
                val masked = input.secrets.fold(text) { value, secret ->
                    value.replace(secret, "<redacted:known-secret>")
                }
                val result = redactor(masked)
                Redacted(result.text, result.changed || masked != text)
            }
            val fields = fields(input, slot.id, clean)
            val count = input.error?.let {
                count(rate.name, rate.fingerprint, rate.category, fields.component, fields.context)
            }
            val logs = when {
                !slot.detail -> emptyList()
                legacy -> listOf(detail(rate, 1, fields.context, fields.frames))
                else -> incident(input, slot.id, fields, clean)
            }
            Batch(count, logs)
        } catch (failure: Throwable) {
            if (failure is CancellationException || failure is VirtualMachineError || failure is ThreadDeath) {
                throw failure
            }
            // 取消/致命错误之外的预处理故障只保留安全后备；限频/旧schema仍不能绕过许可。
            val logs = if (!slot.detail || legacy) emptyList() else listOf(
                parent("diagnostic.redaction_failed", mapOf("incident_id" to slot.id), buildJsonObject {
                    put("severity", "error")
                    put("component", "diagnostics")
                    put("code", "redaction_failed")
                    put("message", "Diagnostic redaction failed")
                    put("thread_name", "unknown")
                    put("thread_id", 0)
                    put("payload_refs", JsonArray(emptyList()))
                    put("truncated", true)
                }),
            )
            Batch(null, logs)
        }

    private fun incident(
        input: DiagnosticInput,
        id: String,
        fields: Fields,
        redactor: (String) -> Redacted,
    ): List<Draft> {
        fun clean(text: String) = redactor(text).text
        val attributes = input.attributes.entries.associate { (key, value) ->
            clean(key) to clean(DiagnosticRedactor.field(key, value).text)
        }
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
                    Draft(
                        it.name, it.kind, it.channel, it.data, fields.context, it.epoch, it.purposes, it.schemaVersion,
                    )
                })
            }
        }
        val data = buildJsonObject {
            put("severity", input.severity.name.lowercase())
            put("component", fields.component)
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
        return listOf(parent("diagnostic.reported", fields.context, data)) + parts.flatMap { it.drafts }
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

    private fun flush(time: Long): List<Pair<Rate, Long>> =
        (windows.values + overflow).mapNotNull { state ->
            if (state.extra == 0L || state.window >= time) return@mapNotNull null
            val summary = Rate(state.fingerprint, state.category, state.name) to state.extra
            state.extra = 0
            summary
        }

    private fun detail(
        state: Rate,
        count: Long,
        context: Map<String, String>,
        frames: List<String> = emptyList(),
    ) = Draft(
        state.name, "diagnostic", "diagnostic", buildJsonObject {
            put("message", if (context.isNotEmpty()) "fault detail: error_class=${state.category}"
                else "fault summary: error_class=${state.category} suppressed=$count")
            put("frames", JsonArray(frames.map(::JsonPrimitive)))
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

/** 所有出口复用同一个安全字段快照；调用方incident ID仅参与内存去重，永不写入事实。 */
private fun fields(input: DiagnosticInput, id: String, redactor: (String) -> Redacted): Fields {
    fun clean(text: String) = redactor(text).text
    val fault = input.context["fault_id"]?.let(redactor)
        ?.takeIf { !it.changed && IDENTIFIER.matches(it.text) }?.text ?: id
    val context = input.context.filterKeys { it != "incident_id" && it != "fault_id" }
        .entries.associate { (key, value) ->
            val text = clean(value)
            clean(key) to if (IDENTIFIER.matches(text)) text else hash(text)
        } + mapOf("incident_id" to id, "fault_id" to fault)
    return Fields(
        scalar(clean(input.component)), context,
        frames(input.error).map(::clean).filter { SCALAR.matches(it) },
    )
}

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
