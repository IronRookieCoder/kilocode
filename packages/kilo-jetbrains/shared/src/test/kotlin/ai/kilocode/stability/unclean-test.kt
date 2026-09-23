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
 * plugin.unclean判定（设计7.3/M22）：同一scope文件保存多个run；只检查最后一个有效
 * plugin.started，且仅同一run的后续plugin.shutdown可以将其判为clean。残缺尾行和坏行跳过。
 */
class UncleanTest {

    @Test
    fun `latest started after a clean run yields one unclean draft`() {
        val dir = Files.createTempDirectory("unclean")
        try {
            val file = dir.resolve("sc-live.jsonl")
            file.writeText(
                factLine(name = "plugin.started", runId = "run-a") +
                    factLine(name = "plugin.shutdown", runId = "run-a") +
                    factLine(name = "plugin.started", runId = "run-b"),
            )

            val drafts = UncleanDetector(file).detect()

            assertEquals(1, drafts.size)
            assertEquals("plugin.unclean", drafts.single().name)
            assertEquals("run-b", drafts.single().data["previous_run_id"]?.jsonPrimitive?.content)
            assertEquals("no_shutdown_after_started", drafts.single().data["evidence"]?.jsonPrimitive?.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `latest run with matching shutdown yields nothing`() {
        val dir = Files.createTempDirectory("unclean-clean")
        try {
            val file = dir.resolve("sc-live.jsonl")
            file.writeText(
                factLine(name = "plugin.started", runId = "run-a") +
                    factLine(name = "plugin.started", runId = "run-b") +
                    factLine(name = "plugin.shutdown", runId = "run-b"),
            )

            assertTrue(UncleanDetector(file).detect().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing scope file yields nothing`() {
        val dir = Files.createTempDirectory("unclean-missing")
        try {
            assertTrue(UncleanDetector(dir.resolve("sc-live.jsonl")).detect().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `nonregular scope path yields nothing`() {
        val dir = Files.createTempDirectory("unclean-nonregular")
        try {
            assertTrue(UncleanDetector(dir).detect().isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed complete rows are skipped when locating the latest started`() {
        val dir = Files.createTempDirectory("unclean-malformed")
        try {
            val file = dir.resolve("sc-live.jsonl")
            file.writeText(
                factLine(name = "plugin.started", runId = "run-a") +
                    "{\"name\":\"plugin.started\"}\n" +
                    factLine(name = "plugin.started", runId = "run-b"),
            )

            val drafts = UncleanDetector(file).detect()

            assertEquals("run-b", drafts.single().data["previous_run_id"]?.jsonPrimitive?.content)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unterminated tail is skipped`() {
        val dir = Files.createTempDirectory("unclean-tail")
        try {
            val file = dir.resolve("sc-live.jsonl")
            file.writeText(
                factLine(name = "plugin.started", runId = "run-a") +
                    factLine(name = "plugin.shutdown", runId = "run-a").removeSuffix("\n"),
            )

            val drafts = UncleanDetector(file).detect()

            assertEquals("run-a", drafts.single().data["previous_run_id"]?.jsonPrimitive?.content)
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
