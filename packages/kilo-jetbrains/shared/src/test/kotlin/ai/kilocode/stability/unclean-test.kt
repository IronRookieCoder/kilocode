package ai.kilocode.stability

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * plugin.unclean前任文件判定（任务7，设计7.3）：插件下一实例按scope-id前缀找前任文件，
 * 最后一条plugin.started之后没有plugin.shutdown即产出恰好一条unclean草稿。
 * 残缺尾行按§7.2跳过；本实例自己的文件与他scope的文件不参与。
 */
class UncleanTest {

    @Test
    fun `previous file ending after started without shutdown yields one unclean draft`() {
        val dir = Files.createTempDirectory("unclean")
        try {
            val previous = dir.resolve("sc-live-pr-old1.jsonl")
            previous.writeText(
                factLine(name = "plugin.started", runId = "run-a") +
                    factLine(name = "rpc", runId = "run-a") +
                    "{\"schema_version\":\"1.0\",\"event_id\":", // 残缺尾行，必须跳过（§7.2/7.3）
            )
            val drafts = UncleanDetector(dir, scopeId = "sc-live", producerId = "pr-new1").detect()
            assertEquals(1, drafts.size)
            assertEquals("plugin.unclean", drafts[0].name)
            assertEquals("run-a", drafts[0].data["previous_run_id"]?.jsonPrimitive?.content)
            assertEquals("no_shutdown_after_started", drafts[0].data["evidence"]?.jsonPrimitive?.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clean previous file yields nothing`() {
        val dir = Files.createTempDirectory("unclean2")
        try {
            dir.resolve("sc-live-pr-old2.jsonl").writeText(
                factLine(name = "plugin.started", runId = "run-b") +
                    factLine(name = "plugin.shutdown", runId = "run-b"),
            )
            assertTrue(UncleanDetector(dir, "sc-live", "pr-new2").detect().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `other scopes and self file are ignored`() {
        val dir = Files.createTempDirectory("unclean3")
        try {
            dir.resolve("sc-other-pr-x.jsonl").writeText(factLine(name = "plugin.started", runId = "run-c"))
            dir.resolve("sc-live-pr-self.jsonl").writeText(factLine(name = "plugin.started", runId = "run-d"))
            assertTrue(UncleanDetector(dir, "sc-live", "pr-self").detect().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---------- 测试辅助 ----------

    private var seq = 0L

    private val factJson = Json { encodeDefaults = true }

    /** 最小合法Fact（wire字段闭集，与fact-test的EXAMPLE_FACT同形状）：序列化为单行+LF。 */
    private fun factLine(name: String, runId: String): String {
        seq += 1
        val lifecycle = name == "plugin.started" || name == "plugin.shutdown"
        val fact = Fact(
            event_id = "ev-%06d".format(seq),
            timestamp = 1_790_000_000_000L + seq,
            producer_id = "pr-previous",
            run_id = runId,
            channel = "critical",
            seq = seq,
            account_epoch = "acct-a",
            policy_revision = 12L,
            purposes = setOf("metrics", "logs"),
            device_id = "device-a4",
            plugin_version = "1.0.0",
            ide_product = "IU",
            ide_build = "build-a4",
            ide_build_major = "2026.1",
            os_family = "windows",
            arch = "x64",
            env = "test",
            mode = "monolith",
            side = "monolith",
            connection_provider = "cs-cloud",
            kind = if (lifecycle) "lifecycle" else "operation",
            name = name,
            data = if (lifecycle) {
                buildJsonObject { }
            } else {
                buildJsonObject { put("phase", "progress"); put("api_group", "session") }
            },
        )
        return factJson.encodeToString(Fact.serializer(), fact) + "\n"
    }
}
