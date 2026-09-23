package ai.kilocode.stability

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * plugin.unclean判定（设计7.3/M22）：同一scope文件保存多个run；只检查最后一个有效
 * plugin.started，且仅同一run的后续plugin.shutdown可以将其判为clean。残缺尾行和坏行跳过。
 */
class UncleanTest {
    private val timeout = 10L

    @Test
    fun `concurrent handles sharing an id remain open until every terminal`() {
        Fixture().use { fixture ->
            val executor = Executors.newFixedThreadPool(8)
            val started = CountDownLatch(8)
            val release = CountDownLatch(1)
            try {
                val jobs = List(8) {
                    executor.submit {
                        val operation = fixture.operations.begin("rpc", 60_000,
                            buildJsonObject { put("api_group", "session") }, mapOf("operation_id" to "shared"))
                        started.countDown()
                        check(release.await(timeout, TimeUnit.SECONDS)) { "Terminal release missing" }
                        operation.end("success")
                        operation.onDeadline()
                    }
                }
                assertTrue(started.await(timeout, TimeUnit.SECONDS), "Operations did not all start")
                val snapshot = fixture.operations.snapshot()
                assertEquals(mapOf("shared" to 8), snapshot)
                release.countDown()
                jobs.forEach { it.get(timeout, TimeUnit.SECONDS) }
                assertTrue(fixture.operations.snapshot().isEmpty())
                assertEquals(mapOf("shared" to 8), snapshot)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `failed force does not advance last known flush time`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.operations.record(Draft("plugin.started", "lifecycle", "critical", buildJsonObject {}))
            fixture.flush()
            val flushed = fixture.writer.flushed
            fixture.advanceClock(500)
            fixture.operations.begin("rpc", 60_000, buildJsonObject { put("api_group", "session") })
            fixture.failNextForce()
            fixture.flush()
            assertEquals(flushed, fixture.writer.flushed)
            assertTrue(fixture.writer.stats().writeErrors > 0)
        }
    }

    @Test
    fun `unclean evidence does not mistake the earlier run flush for the current run`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.operations.record(Draft("plugin.unclean", "lifecycle", "critical", buildJsonObject {
                put("previous_run_id", "older")
                put("evidence", EVIDENCE_NO_SHUTDOWN)
                put("last_flush_time", 1_790_000_000_000L)
            }))
            fixture.operations.record(Draft("plugin.started", "lifecycle", "critical", buildJsonObject {}))
            fixture.flush()
            val data = UncleanDetector(fixture.outboxDir.resolve(fixture.fileName)).detect().single().data
            assertTrue("last_flush_time" !in data, "An earlier run flush is not evidence for this run")
        }
    }

    @Test
    fun `legacy consumer retains lifecycle summary when the open set exceeds 32`() {
        Fixture().use { fixture ->
            val drafts = operationEvidence("plugin.shutdown", buildJsonObject { put("end_kind", "app_close") },
                (1..40).map { "operation-$it" }.toSet(), details = false)
            assertEquals(1, drafts.size)
            assertEquals(JsonPrimitive(true), drafts.single().data["truncated"])
            assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(drafts))
            fixture.flush()
            assertEquals(32, fixture.facts().single().data.getValue("open_operations").jsonArray.size)
        }
    }

    @Test
    fun `lifecycle payload remains complete when the scope file is compacted`() {
        Fixture(maxFileBytes = 24_000, tickMs = 600_000).use { fixture ->
            fixture.enableDiagnostics()
            val ids = (1..180).map { "operation-${it.toString().padStart(30, '0')}" }.toSet()
            val drafts = operationEvidence("plugin.shutdown", buildJsonObject { put("end_kind", "app_close") }, ids, true)
            assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(drafts), Dictionary.violations(drafts).toString())
            fixture.flush()
            repeat(50) {
                fixture.operations.record(Draft("resource.snapshot", "sample", "critical", buildJsonObject {
                    put("resource", "controller")
                    put("count", 1)
                }, purposes = setOf("metrics")))
                fixture.flush()
            }
            assertTrue(fixture.writer.stats().droppedEvicted > 0)
            assertEquals(1, fixture.facts().count { it.name == "plugin.shutdown" })
            assertEquals(ids, Json.parseToJsonElement(fixture.payload("open_operations"))
                .jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
    }

    @Test
    fun `recovery retains last run times and only unfinished operations`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.operations.record(Draft("plugin.started", "lifecycle", "critical", buildJsonObject {}))
            val open = fixture.operations.begin("rpc", 60_000, buildJsonObject { put("api_group", "session") })
            val completed = fixture.operations.begin("rpc", 60_000, buildJsonObject { put("api_group", "session") })
            completed.end("success")
            fixture.flush()
            val time = fixture.clock.wall()
            fixture.advanceClock(400)
            Health(fixture.recorder, fixture.writer, fixture.clock, intervalMs = 0).poll()
            fixture.flush()
            val previous = fixture.facts().last()
            val drafts = UncleanDetector(fixture.outboxDir.resolve(fixture.fileName)).detect()
            val data = drafts.single().data
            assertEquals(previous.run_id, data.getValue("previous_run_id").jsonPrimitive.content)
            assertEquals(previous.seq, data.getValue("last_seq").jsonPrimitive.long)
            assertEquals(previous.channel, data.getValue("last_channel").jsonPrimitive.content)
            assertEquals(previous.timestamp, data.getValue("last_fact_time").jsonPrimitive.long)
            assertEquals(time, data.getValue("last_flush_time").jsonPrimitive.long)
            assertEquals(listOf(open.id), data.getValue("open_operations").jsonArray.map { it.jsonPrimitive.content })
            fixture.operations.record(drafts.single())
            fixture.flush()
            assertEquals(1, fixture.facts().count { it.name == "plugin.unclean" })
        }
    }

    @Test
    fun `large unfinished set reconstructs from linked payload chunks`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.enableDiagnostics()
            fixture.operations.record(Draft("plugin.started", "lifecycle", "critical", buildJsonObject {}))
            val ids = List(150) {
                fixture.operations.begin("rpc", 60_000, buildJsonObject { put("api_group", "session") }).id
            }
            fixture.flush()
            val drafts = UncleanDetector(fixture.outboxDir.resolve(fixture.fileName)).detect()
            val parent = drafts.first()
            assertEquals(32, parent.data.getValue("open_operations").jsonArray.size)
            assertEquals(150, parent.data.getValue("open_operation_count").jsonPrimitive.long.toInt())
            assertTrue(drafts.size > 2)
            assertTrue(drafts.drop(1).all { it.context["incident_id"] == parent.context["incident_id"] })
            assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(drafts), Dictionary.violations(drafts).toString())
            fixture.flush()
            assertEquals(ids.toSet(), Json.parseToJsonElement(fixture.payload("open_operations"))
                .jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
    }

    @Test
    fun `operation snapshot is immutable and terminal paths remove once`() {
        Fixture().use { fixture ->
            val open = fixture.operations.begin("rpc", 60_000, buildJsonObject { put("api_group", "session") })
            val snapshot = fixture.operations.snapshot()
            assertEquals(setOf(open.id), snapshot.keys)
            open.progress("unknown")
            assertEquals(snapshot, fixture.operations.snapshot())
            assertTrue(open.end("success"))
            assertTrue(!open.end("error"))
            assertTrue(fixture.operations.snapshot().isEmpty())
            assertEquals(setOf(open.id), snapshot.keys)
            assertFailsWith<UnsupportedOperationException> { (snapshot as MutableMap<*, *>).clear() }
            val timed = fixture.operations.begin("rpc", 60_000, buildJsonObject { put("api_group", "session") })
            timed.onDeadline()
            val rejected = fixture.operations.begin("rpc", 60_000, buildJsonObject { put("api_group", "session") })
            rejected.end("success", fields = buildJsonObject { put("phase", "end") })
            assertTrue(fixture.operations.snapshot().isEmpty())
        }
    }

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
