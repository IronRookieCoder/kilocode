package ai.kilocode.stability

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Storage追加原语与追加式单writer（任务4/5，设计5.2/6.1/7.1/7.4）。
 *
 * 全部用例走真实文件与真实Recorder：临时目录、真实NDJSON追加落盘；
 * 唯一注入口是Storage故障钩子（注入真实故障——关闭通道、占位目录，绝不伪造成功）。
 * flush阈值为构造参数（设计7.1：阈值按真实事件率校准），30秒延迟经可控时钟驱动；
 * R3要求flush时序用例可证伪：经writer的internal pending观测面断言
 * "deadline未到/不足16条时pending>0，越过deadline或第16条后pending==0"，
 * 不保留只数行数的永真断言。
 */
class WriterTest {

    @Test
    fun `rewrite evicts an existing incident with mismatched payload ids as a whole`() {
        Fixture(autoStart = false, tickMs = 60_000L, maxFileBytes = 13_000).use { fixture ->
            fixture.base.resolve("control.json").writeText(Fixture.defaultControl().dropLast(1) + ",\"accepted_fact_schema_majors\":[1,2]}")
            fixture.policies.refresh()
            val chunks = DiagnosticPayload.parts("parent-id", "response", "x".repeat(5000).encodeToByteArray()).drafts
            assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(listOf(v2Draft("parent-id")) + chunks))
            val claim = requireNotNull(fixture.recorder.tryClaim(3, 1024 * 1024))
            val facts = claim.records.map { it.fact }
            claim.release()
            val chunk = facts.last()
            val mismatch = chunk.copy(data = JsonObject(chunk.data + ("incident_id" to JsonPrimitive("different-data-id"))))
            val lines = (facts.dropLast(1) + mismatch).joinToString("", transform = { fact ->
                factJson.encodeToString(Fact.serializer(), fact) + "\n"
            })
            fixture.storage.verifyLayout()
            fixture.storage.atomicWrite(fixture.outboxDir.resolve(fixture.fileName), lines.encodeToByteArray())
            fixture.writer.start()
            repeat(20) {
                fixture.recorder.record(Draft("resource.snapshot", "sample", "critical", buildJsonObject {
                    put("resource", "subscription")
                    put("count", 1)
                }))
            }
            fixture.flush()
            assertTrue(fixture.writer.stats().droppedEvicted > 0, "the existing file must undergo capacity rewrite")
            assertTrue(fixture.facts().none { it.context["incident_id"] == "parent-id" })
            assertTrue(fixture.facts().none { it.name == "diagnostic.payload" }, "even the matching chunk must be evicted")
            assertEquals(3, fixture.writer.stats().droppedFailure)
            assertTrue(Files.size(fixture.outboxDir.resolve(fixture.fileName)) <= fixture.maxFileBytes)
        }
    }

    @Test
    fun `interrupted partial group write still rolls back the file`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            fixture.recorder.record(criticalDraft())
            fixture.flush()
            val file = fixture.outboxDir.resolve(fixture.fileName)
            val before = Files.readAllBytes(file).toList()
            fixture.storage.beforeWrite = { channel ->
                fixture.storage.beforeWrite = null
                channel.write(ByteBuffer.wrap("{\"partial\"".encodeToByteArray()))
                Thread.currentThread().interrupt()
            }
            fixture.recorder.record(criticalDraft())
            fixture.flush()
            assertEquals(before, Files.readAllBytes(file).toList())
            assertEquals(0, fixture.recorder.depth().items)
        }
    }

    @Test
    fun `failed fsync marks pending failure quality degraded`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            fixture.failNextForce()
            fixture.recorder.record(criticalDraft())
            fixture.flush()
            assertEquals(1, fixture.writer.stats().droppedFailure)
            assertEquals(JsonPrimitive("degraded"), Health(fixture.recorder, fixture.writer, fixture.clock).snapshot()["quality"])
        }
    }

    @Test
    fun `new samples cannot evict a failure and incident retention is all or none`() {
        Fixture(tickMs = 60_000L, maxFileBytes = 13_000).use { fixture ->
            fixture.base.resolve("control.json").writeText(Fixture.defaultControl().dropLast(1) + ",\"accepted_fact_schema_majors\":[1,2]}")
            fixture.policies.refresh()
            repeat(2) { index ->
                val id = "small-$index"
                val chunks = DiagnosticPayload.parts(id, "response", "x".repeat(5000).encodeToByteArray()).drafts
                assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(listOf(v2Draft(id)) + chunks))
                fixture.flush()
            }
            repeat(20) {
                fixture.recorder.record(Draft("resource.snapshot", "sample", "critical", buildJsonObject {
                    put("resource", "subscription")
                    put("count", 1)
                }))
            }
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(0, facts.count { it.context["incident_id"] == "small-0" })
            assertEquals(3, facts.count { it.context["incident_id"] == "small-1" })
            assertTrue(Files.size(fixture.outboxDir.resolve(fixture.fileName)) <= 13_000)
            assertEquals(3, fixture.writer.stats().droppedFailure)
        }
    }

    @Test
    fun `partial group write rolls back without leaving parent or chunks`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            fixture.base.resolve("control.json").writeText(v2Control(1, 2))
            fixture.policies.refresh()
            fixture.recorder.record(v2Draft("kept"))
            fixture.flush()
            val chunks = DiagnosticPayload.parts("inc-1", "response", "x".repeat(5000).encodeToByteArray()).drafts
            fixture.storage.beforeWrite = { channel ->
                fixture.storage.beforeWrite = null
                channel.write(ByteBuffer.wrap("{\"partial\"".encodeToByteArray()))
                channel.close()
            }
            assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(listOf(v2Draft()) + chunks))
            fixture.flush()
            assertEquals(listOf("kept"), fixture.facts().filterNot { "checkpoint" in it.data }.map { it.context["incident_id"] })
            assertEquals(3, fixture.writer.stats().writeErrors)
            assertEquals(3, fixture.writer.stats().droppedFailure)
            assertEquals(0, fixture.recorder.depth().bytes)
        }
    }

    @Test
    fun `rewrite retains complete newest incidents ahead of operations and samples`() {
        Fixture(autoStart = false, tickMs = 60_000L, maxFileBytes = 50L * 1024 * 1024).use { fixture ->
            fixture.base.resolve("control.json").writeText(Fixture.defaultControl().dropLast(1) + ",\"accepted_fact_schema_majors\":[1,2]}")
            fixture.policies.refresh()
            fixture.storage.verifyLayout()
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            val claim = requireNotNull(fixture.recorder.tryClaim(1, 1024 * 1024))
            val seed = claim.records.single().fact
            claim.release()
            val line = factJson.encodeToString(Fact.serializer(), seed.copy(
                kind = "health", name = "telemetry.health", data = buildJsonObject { put("drop", 0) },
            )) + "\n"
            val file = fixture.outboxDir.resolve(fixture.fileName)
            Files.newBufferedWriter(file).use { out ->
                repeat((fixture.maxFileBytes / line.encodeToByteArray().size).toInt()) { out.write(line) }
            }
            fixture.writer.start()
            repeat(2) { index ->
                val id = "inc-$index"
                val parent = v2Draft(id)
                val chunks = DiagnosticPayload.parts(id, "response", "x".repeat(50_000).encodeToByteArray()).drafts
                assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(listOf(parent) + chunks))
                fixture.flush()
            }
            repeat(20) { fixture.recorder.record(criticalDraft()) }
            fixture.flush()
            val facts = fixture.facts()
            repeat(2) { index ->
                val group = facts.filter { it.context["incident_id"] == "inc-$index" }
                assertEquals(14, group.size)
                assertEquals(1, group.count { it.name == "diagnostic.reported" })
                assertEquals(13, group.count { it.name == "diagnostic.payload" })
            }
            assertTrue(Files.size(file) <= fixture.maxFileBytes)
            assertTrue(fixture.writer.stats().droppedEvicted > 0)
        }
    }

    @Test
    fun `writer rejects a whole batch when capability changes before write`() {
        Fixture(autoStart = false).use { fixture ->
            fixture.base.resolve("control.json").writeText(v2Control(1, 2))
            fixture.policies.refresh()
            val chunks = DiagnosticPayload.parts("inc-1", "response", "x".repeat(5000).encodeToByteArray()).drafts
            assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(listOf(v2Draft()) + chunks))
            fixture.base.resolve("control.json").writeText(v2Control(1))
            fixture.policies.refresh()
            fixture.writer.start()
            fixture.flush()
            assertTrue(fixture.facts().isEmpty())
            assertEquals(3, fixture.writer.stats().droppedPolicy)
            assertEquals(3, fixture.writer.stats().droppedFailure)
            assertEquals(0, fixture.recorder.depth().items)
        }
    }

    // ---------- Task 4：Storage追加原语直测（追加不截断、删除后按原名重建） ----------

    @Test
    fun `openAppend appends and recreates without truncation`() {
        val dir = Files.createTempDirectory("storage-append")
        try {
            val storage = Storage(dir)
            storage.verifyLayout()
            val file = dir.resolve("sc-ab12-pr-cd34.jsonl")
            storage.openAppend(file).use { channel ->
                storage.writeAll(channel, ByteBuffer.wrap("{\"a\":1}\n".encodeToByteArray()))
                storage.force(channel)
            }
            storage.openAppend(file).use { channel ->
                storage.writeAll(channel, ByteBuffer.wrap("{\"a\":2}\n".encodeToByteArray()))
                storage.force(channel)
            }
            assertEquals("{\"a\":1}\n{\"a\":2}\n", Files.readString(file))
            // 被外部删除后按原名重建，不视为错误（§7.4）
            Files.delete(file)
            storage.openAppend(file).use { channel ->
                storage.writeAll(channel, ByteBuffer.wrap("{\"a\":3}\n".encodeToByteArray()))
                storage.force(channel)
            }
            assertEquals("{\"a\":3}\n", Files.readString(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---------- 追加单文件：两通道同文件、通道seq各自连续、UTF-8无BOM LF结尾 ----------

    @Test
    fun `facts append to one jsonl file across both channels with per-channel seq`() {
        Fixture(tickMs = 600_000L).use { fixture ->
            repeat(8) { assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft())) }
            repeat(8) { assertEquals(Admission.QUEUED, fixture.recorder.record(diagnosticDraft())) }
            fixture.flush()
            val file = fixture.outboxDir.resolve(fixture.fileName)
            assertTrue(Files.isRegularFile(file))
            val bytes = Files.readAllBytes(file)
            assertEquals(10.toByte(), bytes.last(), "行以LF结尾（§6.1）")
            assertFalse(
                bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
                "UTF-8无BOM（§6.1）",
            )
            val lines = Files.readAllLines(file)
            assertEquals(17, lines.size) // 16条数据 + 一个独立force的checkpoint。
            val facts = lines.map { factJson.decodeFromString(Fact.serializer(), it) }
            assertEquals((1L..9L).toList(), facts.filter { it.channel == "critical" }.map { it.seq })
            assertEquals((1L..8L).toList(), facts.filter { it.channel == "diagnostic" }.map { it.seq })
        }
    }

    @Test
    fun `writer preserves queued v2 diagnostics when only v2 is accepted`() {
        Fixture(autoStart = false).use { fixture ->
            fixture.base.resolve("control.json").writeText(v2Control(2))
            fixture.policies.refresh()
            assertEquals(Admission.QUEUED, fixture.recorder.record(v2Draft()))

            fixture.writer.start()
            fixture.flush()

            assertEquals(listOf("diagnostic.reported"), fixture.facts().map { fact -> fact.name })
        }
    }

    @Test
    fun `writer drops queued v2 diagnostics after v2 capability is revoked`() {
        Fixture(autoStart = false).use { fixture ->
            fixture.base.resolve("control.json").writeText(v2Control(1, 2))
            fixture.policies.refresh()
            assertEquals(Admission.QUEUED, fixture.recorder.record(v2Draft()))

            fixture.base.resolve("control.json").writeText(v2Control(1))
            fixture.policies.refresh()
            fixture.writer.start()
            fixture.flush()

            assertTrue(fixture.facts().isEmpty())
            assertEquals(1L, fixture.writer.stats().droppedPolicy)
        }
    }

    // ---------- R3可证伪：第16条触发批flush，不等age deadline ----------

    @Test
    fun `batch threshold of sixteen records flushes without waiting for the age deadline`() {
        Fixture(tickMs = 50L).use { fixture ->
            repeat(15) { assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft())) }
            assertTrue(awaitDrained(fixture), "15条应被tick循环写入（未fsync）")
            fixture.advanceClock(5_000L)
            fixture.writer.wakeForTest() // 同步屏障：一次tick循环已完整执行
            assertTrue(
                fixture.writer.pendingForTest(),
                "不足16条且远未到30秒：批次必须保持pending（未flush）",
            )
            repeat(1) { assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft())) } // 第16条
            assertTrue(awaitDrained(fixture), "第16条应完成写入")
            assertFalse(fixture.writer.pendingForTest(), "第16条必须立即触发批flush（pending归零）")
            assertEquals(17, countLines(fixture.outboxDir.resolve(fixture.fileName)))
        }
    }

    // ---------- R3可证伪：首条未fsync写入起30秒内flush；deadline前一毫秒不得flush ----------

    @Test
    fun `pending bytes flush at most thirty seconds after the first unwritten batch entry`() {
        Fixture(tickMs = 50L, maxFlushAgeMs = 30_000L).use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            assertTrue(awaitDrained(fixture))
            assertTrue(fixture.writer.pendingForTest(), "已写入未fsync的记录必须处于pending")
            fixture.advanceClock(29_999L)
            fixture.writer.wakeForTest()
            assertTrue(fixture.writer.pendingForTest(), "恰好29_999毫秒尚未超过30秒，不得flush")
            fixture.advanceClock(2L)
            fixture.writer.wakeForTest()
            assertFalse(fixture.writer.pendingForTest(), "越过30秒deadline必须flush积压批次")
            assertEquals(2, countLines(fixture.outboxDir.resolve(fixture.fileName)))
        }
    }

    // ---------- 容量重写（§7.4）：保留尾部整行、淘汰计入droppedEvicted、重写后可继续追加 ----------

    @Test
    fun `oversize file rewrites keeping the tail and counting evicted lines`() {
        Fixture(tickMs = 50L, maxFileBytes = 2L * 1024).use { fixture ->
            repeat(40) { assertEquals(Admission.QUEUED, fixture.recorder.record(wideCriticalDraft())) }
            fixture.flush()
            val file = fixture.outboxDir.resolve(fixture.fileName)
            assertTrue(Files.size(file) <= 2L * 1024, "重写后文件必须回到预算内")
            val lines = Files.readAllLines(file)
            assertTrue(lines.size in 1 until 40, "expected eviction, got ${lines.size}")
            assertTrue(fixture.writer.stats().droppedEvicted > 0, "被淘汰行必须计入droppedEvicted")
            // 保留行不被改写：末行仍是完整合法事实
            factJson.decodeFromString(Fact.serializer(), lines.last())
            // 重写后继续追加正常：新事实落盘（必要时再次淘汰旧行腾位），文件仍在预算内。
            val previous = fixture.facts().maxOf { it.seq }
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            fixture.flush()
            assertTrue(Files.size(file) <= 2L * 1024, "继续追加后文件仍必须回到预算内")
            val after = Files.readAllLines(file)
            assertTrue(after.size <= lines.size + 1, "追加至多新增一行（淘汰只减不增）")
            assertTrue(fixture.facts().last { it.name == "rpc" }.seq > previous, "新数据seq递增；容量淘汰checkpoint允许留空号")
        }
    }

    // ---------- 文件被删：下次追加按原名重建，不视为错误（§7.4） ----------

    @Test
    fun `deleted outbox file is recreated on the next append`() {
        Fixture(tickMs = 50L).use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            fixture.flush()
            val file = fixture.outboxDir.resolve(fixture.fileName)
            assertTrue(Files.exists(file))
            // Windows句柄语义（无FILE_SHARE_DELETE，见retention-test同款坑）：删除必须发生在
            // 无打开通道的时刻——先结束本写会话再删，由下一个追加会话按原名重建（§7.4）。
            fixture.writer.close()
            Files.delete(file)
            val reopened = Writer(
                root = fixture.outboxDir,
                fileName = fixture.fileName,
                identity = REOPEN_IDENTITY,
                recorder = fixture.recorder,
                policies = fixture.policies,
                clock = fixture.clock,
                storage = fixture.storage,
            )
            reopened.start()
            try {
                assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
                reopened.flush()
                assertEquals(2, countLines(file), "重建后只包含新事实和checkpoint")
            } finally {
                reopened.close()
            }
        }
    }

    // ---------- 崩溃残页（§7.2）：无LF的半行既不拼接下一条事实，也不进入重写结果 ----------

    @Test
    fun `append after crash residue starts on a fresh line`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            fixture.flush()
            val file = fixture.outboxDir.resolve(fixture.fileName)
            // 模拟崩溃残页：会话结束后向文件尾追加半行（无LF）——重启会话不得拼接。
            fixture.writer.close()
            Files.write(file, "{\"partial\"".encodeToByteArray(), StandardOpenOption.APPEND)
            val resumed = Writer(
                root = fixture.outboxDir,
                fileName = fixture.fileName,
                identity = REOPEN_IDENTITY,
                recorder = fixture.recorder,
                policies = fixture.policies,
                clock = fixture.clock,
                storage = fixture.storage,
            )
            resumed.start()
            try {
                assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
                resumed.flush()
                val lines = Files.readAllLines(file)
                assertEquals(5, lines.size, "两条数据与checkpoint各占一行，残页也独立成行")
                factJson.decodeFromString(Fact.serializer(), lines[0]) // 崩溃前的完整事实
                assertEquals("{\"partial\"", lines[2], "残页字节终止为独立残行（consumer按§7.2跳过）")
                factJson.decodeFromString(Fact.serializer(), lines[3]) // 新事实不得与残页拼接成一行
            } finally {
                resumed.close()
            }
        }
    }

    @Test
    fun `rewrite drops the unterminated residue tail`() {
        Fixture(tickMs = 60_000L, maxFileBytes = 1700L).use { fixture ->
            repeat(2) { assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft())) }
            fixture.flush()
            val file = fixture.outboxDir.resolve(fixture.fileName)
            // 会话内追加半行（无LF）：下一次超预算写入触发rewrite，必须从最后一个行边界截断。
            Files.write(file, "{\"partial\"".encodeToByteArray(), StandardOpenOption.APPEND)
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            fixture.flush()
            val text = Files.readString(file)
            assertFalse(text.contains("{\"partial\""), "rewrite不得把无LF残页带入保留结果")
            val lines = Files.readAllLines(file)
            assertTrue(lines.size in 1..2, "保留完整行并追加新事实，got ${lines.size}")
            lines.forEach { line -> factJson.decodeFromString(Fact.serializer(), line) } // 每行都是完整合法事实
            assertTrue(fixture.writer.stats().droppedEvicted > 0)
        }
    }

    // ---------- 8.1：观察到epoch更替后，已排队旧epoch事实不得改绑落盘 ----------

    @Test
    fun `queued facts of a retired epoch are dropped at the write gate`() {
        // 60s tick保证记录在换代观察前仍在队列（构造真实的"已排队未写出"窗口）。
        Fixture(tickMs = 60_000L).use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            fixture.rotateEpochControl("acct-b", revision = 2L)
            fixture.flush()
            assertEquals(0, fixture.facts().size, "退役epoch的排队事实不得落盘")
            assertEquals(1, fixture.writer.stats().droppedPolicy)
            assertEquals(0, fixture.recorder.depth().items)
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            fixture.flush()
            val facts = fixture.facts()
            assertEquals(2, facts.size)
            assertTrue(facts.all { it.account_epoch == "acct-b" })
        }
    }

    // ---------- 预算释放：批次写入并计数后恰好释放一次 ----------

    @Test
    fun `claimed budget is released exactly once after the batch is written`() {
        Fixture().use { fixture ->
            repeat(5) { assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft())) }
            fixture.flush()

            assertEquals(0, fixture.recorder.depth().items)
            assertEquals(0, fixture.recorder.depth().bytes)
            assertEquals(5, fixture.facts().count { it.name == "rpc" })
        }
    }

    // ---------- 入盘前重判期：策略失效的排队事实不落盘 ----------

    @Test
    fun `policy gone before the write drops queued records without a file`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(criticalDraft()))
            fixture.expireControl()
            fixture.flush()

            assertTrue(fixture.facts().isEmpty(), "无有效许可不得有事实落盘")
            assertEquals(1L, fixture.writer.stats().droppedPolicy)
            assertEquals(0, fixture.recorder.depth().items)
        }
    }

    @Test
    fun `unverifiable storage root disables collection`() {
        Fixture(autoStart = false).use { fixture ->
            Files.write(fixture.outboxDir, byteArrayOf())
            fixture.writer.start()

            assertTrue(await { fixture.writer.state != WriterState.CREATED }, "启动应结束于终态")
            assertEquals(WriterState.DISABLED, fixture.writer.state)
            assertNotEquals(null, fixture.writer.disabledReason)
            assertTrue(fixture.facts().isEmpty())
        }
    }

    // ---------- Windows ACL配置后核验：根DACL为当前用户最小授权，无继承残留 ----------

    @Test
    fun `producer root acl is reconfigured for the current user on acl filesystems`() {
        Fixture().use { fixture ->
            if (!await { fixture.writer.state != WriterState.CREATED }) return  // 等启动结束（根目录已建）
            if (fixture.writer.state != WriterState.ACTIVE) return  // 存储不可用场景由disable用例覆盖
            val views = fixture.outboxDir.fileSystem.supportedFileAttributeViews()
            if ("posix" in views) return  // POSIX路径走0700置位回读，本断言仅适用ACL模型
            val view = Files.getFileAttributeView(fixture.outboxDir, AclFileAttributeView::class.java)
                ?: return
            val acl = view.acl
            assertTrue(acl.isNotEmpty(), "配置后的DACL不应为空")
            val user = System.getProperty("user.name")
            assertTrue(
                acl.all { entry -> entryForUser(entry, user) || isSystemPrincipal(entry) },
                "DACL只应包含当前用户与SYSTEM的条目，实际：${acl.map { it.principal().name }}",
            )
            assertTrue(acl.any { entry -> entryForUser(entry, user) }, "应有对当前用户的ALLOW条目")
            assertTrue(
                acl.all { entry ->
                    entry.flags().containsAll(listOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT))
                },
                "条目应对子目录/文件可继承（子项由根最小DACL覆盖）",
            )
        }
    }

    private fun isSystemPrincipal(entry: AclEntry): Boolean =
        entry.principal().name.substringAfterLast('\\').equals("SYSTEM", ignoreCase = true)

    private fun entryForUser(entry: AclEntry, user: String): Boolean {
        if (entry.type() != AclEntryType.ALLOW) return false
        val name = entry.principal().name
        return name.equals(user, ignoreCase = true) ||
            name.substringAfterLast('\\').substringAfterLast('/').equals(user, ignoreCase = true)
    }

    // ---------- Draft辅助（data键闭集按Dictionary.SPEC_TABLE；rpc为metrics-only的
    // operation名：end相公共终态五键+api_group；error.reported取详情分支四键） ----------

    private fun criticalDraft(): Draft = Draft("rpc", "operation", "critical", buildJsonObject {
        put("phase", "end")
        put("result", "success")
        put("duration_ms", 1)
        put("stage", "unknown")
        put("cause", "unknown")
        put("error_code", "none")
        put("api_group", "session")
    }, purposes = setOf("metrics", "logs"))

    private fun diagnosticDraft(): Draft = Draft("error.reported", "diagnostic", "diagnostic", buildJsonObject {
        put("message", "diagnostic channel payload")
        put("frames", JsonArray(listOf(JsonPrimitive("frame.one"), JsonPrimitive("frame.two"))))
        put("fingerprint", "fp-diagnostic-1")
        put("count", 1)
    }, purposes = setOf("logs"))

    private fun v2Draft(id: String = "inc-1"): Draft = Draft(
        "diagnostic.reported", "diagnostic", "diagnostic",
        buildJsonObject {
            put("severity", "error")
            put("component", "backend.rpc")
            put("code", "json_decode_failed")
            put("message", "Expected object at $.projectID")
            put("thread_name", "DefaultDispatcher-worker-1")
            put("thread_id", 42)
            put("payload_refs", JsonArray(listOf(JsonPrimitive("response"))))
            put("truncated", false)
        },
        context = mapOf("incident_id" to id),
        purposes = setOf("logs"),
        schemaVersion = "2.0",
    )

    private fun v2Control(vararg majors: Int): String = buildJsonObject {
        put("schema_major", 1)
        put("revision", 12L)
        put("enabled", true)
        put("metrics_enabled", false)
        put("metrics_expires_at", 9_000_000_000_000L)
        put("metrics_allowed_categories", JsonArray(emptyList()))
        put("logs_enabled", true)
        put("logs_expires_at", 9_000_000_000_000L)
        put("logs_allowed_categories", JsonArray(listOf(JsonPrimitive("diagnostic"))))
        put("account_epoch", "acct-a")
        put("account_state", "ready")
        put("expires_at", 9_000_000_000_000L)
        put("log_detail_rate_limit", buildJsonObject { put("per_fingerprint_max_per_minute", 3) })
        put("accepted_fact_schema_majors", JsonArray(majors.map { major -> JsonPrimitive(major) }))
    }.toString()

    /** 更宽的critical载荷：error_code取64字节内长值，在小预算下更快触发容量重写。 */
    private fun wideCriticalDraft(): Draft = Draft("rpc", "operation", "critical", buildJsonObject {
        put("phase", "end")
        put("result", "success")
        put("duration_ms", 1)
        put("stage", "unknown")
        put("cause", "unknown")
        put("error_code", "x".repeat(60))
        put("api_group", "session")
    }, purposes = setOf("metrics", "logs"))

    // ---------- 驱动 ----------

    private fun countLines(file: Path): Int =
        Files.readAllBytes(file).count { byte -> byte == 10.toByte() }

    private fun await(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /** 记录已完整写入并释放预算（claim后release）的确定性barrier；避免与tick排空竞态。 */
    private fun awaitDrained(fixture: Fixture): Boolean =
        await { fixture.recorder.depth().items == 0 }

    private val factJson = Json { encodeDefaults = true }

    /** 重建用例的第二写会话身份（文件名沿用夹具同名，writer身份不参与文件命名）。 */
    private val REOPEN_IDENTITY = ProducerIdentity(
        producerId = "pr-a4",
        runId = "run-reopen",
        deviceId = "device-a4",
        pluginVersion = "1.0.0",
        ideProduct = "IU",
        ideBuild = "build-a4",
        ideBuildMajor = "2026.1",
        osFamily = "windows",
        arch = "x64",
        env = "test",
        mode = "monolith",
        side = "monolith",
        connectionProvider = "cs-cloud",
    )
}
