package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val NAME_STARTED = "plugin.started"
private const val NAME_SHUTDOWN = "plugin.shutdown"
internal const val MAX_OPEN_OPERATIONS = 32
internal const val EVIDENCE_NO_SHUTDOWN = "no_shutdown_after_started"

/** 测试/fixture共用的Json实例约定（writer.kt同款）：默认值随wire记录一并编码。 */
private val factJson = Json { encodeDefaults = true }

/**
 * plugin.unclean判定（设计7.3/M22）：插件下一实例从共享scope文件读取前任run——最后一条
 * plugin.started之后没有plugin.shutdown即产出一条unclean事实（previous_run_id、固定
 * evidence token）。残缺尾行按§7.2跳过（不算shutdown）；不做writer死亡推断、不救援。
 * 每个scope文件至多一条；他scope不参与。Task 11在plugin.started之前调用，
 * 每实例启动执行一次。
 */
class UncleanDetector(private val file: Path) {
    fun detect(): List<Draft> {
        if (!Files.isRegularFile(file)) return emptyList()
        return detect(Files.readAllBytes(file))
    }

    /** 服务经Storage安全读取后传入事实文件字节；null表示目标不存在或不是普通文件。 */
    internal fun detect(bytes: ByteArray?, details: Boolean = true): List<Draft> =
        bytes?.let { detectUncleanRun(it, details) } ?: emptyList()

    /** 整读解析完整行，取最后一条started的run_id，其后无同run的shutdown即unclean。 */
    private fun detectUncleanRun(bytes: ByteArray, details: Boolean): List<Draft> {
        val facts = parseFacts(bytes)
        val lastStarted = facts.indexOfLast { it.name == NAME_STARTED }
        val runId = facts.getOrNull(lastStarted)?.run_id ?: return emptyList()
        val shutdownAfter = facts.drop(lastStarted + 1).any { it.name == NAME_SHUTDOWN && it.run_id == runId }
        return if (shutdownAfter) emptyList() else evidence(facts.filter { it.run_id == runId }, details)
    }

    private fun evidence(run: List<Fact>, details: Boolean): List<Draft> {
        val open = LinkedHashMap<String, Int>()
        // operation均在critical通道；failure优先写出会使物理行序不同于该通道seq。
        run.filter { it.kind == "operation" }.sortedBy { it.seq }.forEach { fact ->
            val id = fact.context["operation_id"] ?: return@forEach
            when ((fact.data["phase"] as? JsonPrimitive)?.content) {
                "start" -> open[id] = open.getOrDefault(id, 0) + 1
                "end" -> {
                    val count = open[id] ?: return@forEach
                    if (count == 1) open.remove(id) else open[id] = count - 1
                }
            }
        }
        val last = run.last()
        val flush = run.filter { it.name == "telemetry.health" }
            .mapNotNull { (it.data["last_flush_time"] as? JsonPrimitive)?.longOrNull }.maxOrNull()
        return operationEvidence(
            "plugin.unclean",
            buildJsonObject {
                put("previous_run_id", last.run_id)
                put("evidence", EVIDENCE_NO_SHUTDOWN)
                put("last_seq", last.seq)
                put("last_channel", last.channel)
                put("last_fact_time", last.timestamp)
                flush?.let { put("last_flush_time", it) }
            },
            open.keys,
            details,
        )
    }

    /**
     * 受50MiB上限约束的整读（Writer预算，设计7.4）；每实例启动执行一次。按行解析合法
     * Fact；残缺行（无LF崩溃残页）与坏行按§7.2跳过，既不算shutdown也不阻断解析。
     */
    private fun parseFacts(bytes: ByteArray): List<Fact> {
        val text = bytes.toString(Charsets.UTF_8)
        val last = text.lastIndexOf('\n')
        if (last < 0) return emptyList()
        return text.substring(0, last).lineSequence().filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { factJson.decodeFromString(Fact.serializer(), line) }.getOrNull() }
            .toList()
    }
}

/** lifecycle父记录内最多32个ID；大集合与父记录作为同一个incident原子提交。 */
internal fun operationEvidence(name: String, data: JsonObject, ids: Set<String>, details: Boolean): List<Draft> {
    val snapshot = ids.map { DiagnosticRedactor.clean(it).text }.sorted()
    val incident = UUID.randomUUID().toString()
    val payload = if (snapshot.size > MAX_OPEN_OPERATIONS && details) DiagnosticPayload.parts(
        incident, "open_operations", JsonArray(snapshot.map(::JsonPrimitive)).toString().encodeToByteArray(),
    ) else null
    val fields = buildJsonObject {
        data.forEach { (key, value) -> put(key, value) }
        put("open_operations", JsonArray(snapshot.take(MAX_OPEN_OPERATIONS).map(::JsonPrimitive)))
        put("open_operation_count", snapshot.size)
        if (snapshot.size > MAX_OPEN_OPERATIONS) {
            val refs = if (payload == null) emptyList() else listOf(JsonPrimitive("open_operations"))
            put("payload_refs", JsonArray(refs))
            put("truncated", payload?.truncated ?: true)
        }
    }
    return listOf(Draft(
        name, "lifecycle", "critical", fields,
        context = if (payload == null) emptyMap() else mapOf("incident_id" to incident),
    )) + payload?.drafts.orEmpty()
}
