package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val NAME_STARTED = "plugin.started"
private val NAME_SHUTDOWN = "plugin.shutdown"
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
    internal fun detect(bytes: ByteArray?): List<Draft> =
        bytes?.let(::detectUncleanRun)?.let(::listOf) ?: emptyList()

    /** 整读解析完整行，取最后一条started的run_id，其后无同run的shutdown即unclean。 */
    private fun detectUncleanRun(bytes: ByteArray): Draft? {
        val facts = parseFacts(bytes)
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
    private fun parseFacts(bytes: ByteArray): List<Fact> {
        val text = bytes.toString(Charsets.UTF_8)
        val last = text.lastIndexOf('\n')
        if (last < 0) return emptyList()
        return text.substring(0, last).lineSequence().filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { factJson.decodeFromString(Fact.serializer(), line) }.getOrNull() }
            .toList()
    }
}
