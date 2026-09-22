package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val FILE_NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*\\.jsonl$")
private val NAME_STARTED = "plugin.started"
private val NAME_SHUTDOWN = "plugin.shutdown"
internal const val EVIDENCE_NO_SHUTDOWN = "no_shutdown_after_started"

/** 测试/fixture共用的Json实例约定（writer.kt同款）：默认值随wire记录一并编码。 */
private val factJson = Json { encodeDefaults = true }

/**
 * plugin.unclean判定（设计7.3/M22）：插件下一实例按scope-id前缀找前任文件——最后一条
 * plugin.started之后没有plugin.shutdown即产出一条unclean事实（previous_run_id、固定
 * evidence token）。残缺尾行按§7.2跳过（不算shutdown）；不做writer死亡推断、不救援。
 * 每个前任文件至多一条；本实例文件与他scope不参与。Task 11在plugin.started之前调用，
 * 每实例启动执行一次。
 */
class UncleanDetector(
    private val outboxDir: Path,
    private val scopeId: String,
    private val producerId: String,
) {
    fun detect(): List<Draft> {
        if (!Files.isDirectory(outboxDir)) return emptyList()
        Files.list(outboxDir).use { files ->
            // Stream.filter/toList是JDK成员；mapNotNull是Kotlin Iterable扩展，先收list再链。
            return files.filter { path -> Files.isRegularFile(path) }
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("$scopeId-") && FILE_NAME_PATTERN.matches(name) &&
                        name != "$scopeId-$producerId.jsonl"
                }
                .toList()
                .mapNotNull { path -> detectUncleanRun(path) }
        }
    }

    /** 整读解析完整行，取最后一条started的run_id，其后无同run的shutdown即unclean。 */
    private fun detectUncleanRun(path: Path): Draft? {
        val facts = parseFacts(path)
        val lastStarted = facts.indexOfLast { it.name == NAME_STARTED }
        if (lastStarted < 0) return null
        val runId = facts[lastStarted].run_id
        val shutdownAfter = facts.drop(lastStarted + 1).any { it.name == NAME_SHUTDOWN && it.run_id == runId }
        if (shutdownAfter) return null
        return Draft(
            "plugin.unclean",
            "lifecycle",
            "critical",
            buildJsonObject {
                put("previous_run_id", runId)
                put("evidence", EVIDENCE_NO_SHUTDOWN)
            },
            purposes = setOf("metrics", "logs"),
        )
    }

    /**
     * 受10MiB上限约束的整读（Writer预算，设计7.4）；每实例启动执行一次。按行解析合法
     * Fact；残缺行（无LF崩溃残页）与坏行按§7.2跳过，既不算shutdown也不阻断解析。
     */
    private fun parseFacts(path: Path): List<Fact> {
        val bytes = Files.readAllBytes(path)
        val text = bytes.toString(Charsets.UTF_8)
        return text.lineSequence().filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { factJson.decodeFromString(Fact.serializer(), line) }.getOrNull() }
            .toList()
    }
}
