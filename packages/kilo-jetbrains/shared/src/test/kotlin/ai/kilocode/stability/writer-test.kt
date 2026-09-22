package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 安全目录、单writer与原子封存（任务A4，设计5.2/7.1/7.2）。
 *
 * 全部用例走真实文件与真实Recorder：临时目录、真实writer锁、真实NDJSON落盘；
 * 唯一注入口是Storage故障钩子（注入真实故障——关闭通道、占位目录，绝不伪造成功）。
 * 封存的字节阈值为构造参数（设计7.1：阈值按真实事件率校准），默认1MiB/64KiB不变，
 * 测试以校准值驱动同一代码路径获得确定性；30秒/300秒延迟经可控时钟驱动，不等待真实时间。
 */
class WriterTest {

    // ---------- Step 1 verbatim：封存数据的UTF-8 NDJSON与不可变 ----------

    @Test
    fun `sealed data is utf8 ndjson and immutable`() {
        Fixture().use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(
                Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap()))))
            fixture.flush()
            val file = Files.list(fixture.root.resolve("critical")).use { files ->
                files.filter { it.toString().endsWith(".ready") }.findFirst().orElseThrow()
            }
            val bytes = Files.readAllBytes(file)
            assertEquals(10.toByte(), bytes.last())
            assertFalse(bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
            assertEquals("plugin.started", Json.parseToJsonElement(bytes.toString(Charsets.UTF_8).trim())
                .jsonObject.getValue("name").jsonPrimitive.content)
        }
    }

    // ---------- 16条封存：计数阈值，下一段从新文件开始 ----------

    @Test
    fun `sixteen records seal a segment and the next record starts a new file`() {
        Fixture().use { fixture ->
            repeat(16) { assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft())) }
            assertTrue(awaitReady(fixture, 1), "第16条写入后应立即封存出.ready")
            assertEquals(16, countLines(readyFiles(fixture).single()))

            assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
            assertTrue(awaitOpen(fixture, 1), "旧段已封存，第17条应打开新段")
            fixture.flush()
            assertEquals(2, readyFiles(fixture).size)
            assertEquals(17, fixture.facts().size)
        }
    }

    // ---------- 累计字节封存（默认64KiB，校准值16KiB驱动）：先于16条触发 ----------

    @Test
    fun `accumulated bytes seal a segment before sixteen records`() {
        Fixture(batchSealBytes = 16 * 1024L).use { fixture ->
            repeat(15) { assertEquals(Admission.QUEUED, fixture.recorder.record(detailDraft(it))) }
            assertTrue(awaitReady(fixture, 1), "累计字节到阈值应立即封存")
            fixture.flush()

            val sealed = readyFiles(fixture)
            assertTrue(sealed.size >= 2, "16KiB累计下15条大记录应跨多个段，实际${sealed.size}个")
            sealed.dropLast(1).forEach { path ->
                assertTrue(Files.size(path) >= 16 * 1024L, "字节封存的段应达到累计阈值")
            }
            sealed.forEach { path ->
                assertTrue(countLines(path) < 16, "字节阈值应先于16条触发")
            }
            assertEquals(15, fixture.facts().size)
        }
    }

    // ---------- 1MiB预封存（校准值16KiB驱动）：下一条将突破预算时先封存旧文件 ----------

    @Test
    fun `next record beyond the segment budget seals the old file first`() {
        Fixture(maxSegmentBytes = 16 * 1024L).use { fixture ->
            repeat(10) { assertEquals(Admission.QUEUED, fixture.recorder.record(detailDraft(it))) }
            fixture.flush()
            val sealed = readyFiles(fixture)
            assertTrue(sealed.size >= 3, "预算16KiB时10条大记录应跨多个段，实际${sealed.size}个")
            sealed.forEach { path ->
                assertTrue(Files.size(path) <= 16 * 1024L, "任何段都不得突破字节预算")
            }
            assertEquals(10, fixture.facts().size)
        }
    }

    // ---------- 可控时钟驱动延迟边界：critical 30秒 / diagnostic 300秒 ----------

    @Test
    fun `critical segment seals only after thirty seconds`() {
        Fixture().use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
            assertTrue(awaitDrained(fixture), "首条应已完整写入并释放预算")

            fixture.advanceClock(30_000L)
            Thread.sleep(400)
            assertTrue(readyFiles(fixture).isEmpty(), "恰好30秒尚未超过，不得封存")

            fixture.advanceClock(1L)
            assertTrue(awaitReady(fixture, 1), "超过30秒应封存")
            assertEquals(1, fixture.facts().size)
        }
    }

    @Test
    fun `diagnostic segment seals only after five minutes`() {
        Fixture().use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(detailDraft(0)))
            assertTrue(awaitDrained(fixture))

            fixture.advanceClock(300_000L)
            Thread.sleep(400)
            assertTrue(readyFiles(fixture).isEmpty(), "恰好300秒尚未超过，不得封存")

            fixture.advanceClock(1L)
            assertTrue(awaitReady(fixture, 1), "超过300秒应封存")
            assertEquals(1, fixture.facts().size)
            assertEquals("diagnostic", fixture.facts().single().channel)
        }
    }

    // ---------- 零记录：无定时封存、无空.ready、无通道目录 ----------

    @Test
    fun `zero records never create channel directories or empty ready files`() {
        Fixture().use { fixture ->
            fixture.advanceClock(600_000L)
            fixture.flush()
            assertFalse(Files.exists(fixture.root.resolve("critical")))
            assertFalse(Files.exists(fixture.root.resolve("diagnostic")))
            assertTrue(fixture.facts().isEmpty())
            assertEquals(WriterState.ACTIVE, fixture.writer.state)
        }
    }

    // ---------- 写入失败：保留.open、计数write_error、不伪造封存 ----------

    @Test
    fun `write failure keeps the open segment and counts the error`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            fixture.failNextWrite()
            assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
            fixture.flush()

            assertTrue(readyFiles(fixture).isEmpty(), "写入失败不得封存")
            assertTrue(openFiles(fixture).isNotEmpty(), ".open必须保留给consumer救援")
            assertNotEquals(0L, fixture.writer.stats().writeErrors)
            assertTrue(fixture.facts().isEmpty())
            assertEquals(0, fixture.recorder.depth().items, "失败批次也必须恰好释放一次预算")
        }
    }

    @Test
    fun `force failure keeps the segment open and counts the error`() {
        Fixture().use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
            assertTrue(awaitOpen(fixture, 1))

            fixture.failNextForce()
            fixture.flush()

            assertTrue(readyFiles(fixture).isEmpty(), "force失败即未同步，不得改名封存")
            assertNotEquals(0L, fixture.writer.stats().writeErrors)
            assertTrue(fixture.facts().isEmpty())
        }
    }

    @Test
    fun `rename failure keeps the open file and never fakes sealed`() {
        Fixture().use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
            assertTrue(awaitOpen(fixture, 1))
            val openBefore = openFiles(fixture).single()

            fixture.failNextRename()
            fixture.flush()

            assertTrue(readyFiles(fixture).isEmpty(), "原子改名失败不得声称已封存")
            assertTrue(Files.exists(openBefore), ".open必须原样保留")
            assertNotEquals(0L, fixture.writer.stats().writeErrors)
            assertEquals(0, fixture.facts().size)
        }
    }

    // ---------- 入盘前重判期：策略失效的排队事实不落盘 ----------

    @Test
    fun `policy gone before the write drops queued records without a file`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
            fixture.expireControl()
            fixture.flush()

            assertFalse(Files.exists(fixture.root.resolve("critical")), "无有效许可不得创建业务文件")
            assertEquals(1L, fixture.writer.stats().droppedPolicy)
            assertTrue(fixture.facts().isEmpty())
            assertEquals(0, fixture.recorder.depth().items)
        }
    }

    // ---------- 8.1：观察到epoch更替后，已排队旧epoch事实不得改绑落盘 ----------

    @Test
    fun `queued facts of a retired epoch are dropped at the write gate`() {
        Fixture(tickMs = 60_000L).use { fixture ->
            // epoch=acct-a下排队（60s tick内writer尚未取出，构造真实的"已排队未写出"窗口）
            assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
            // 直接换到新epoch（pending过渡未被观察到）：acct-a在策略层永久退役
            fixture.rotateEpochControl("acct-b", revision = 14L)
            fixture.flush()

            assertTrue(fixture.facts().none { it.account_epoch == "acct-a" }, "退役epoch的排队事实不得落盘")
            assertEquals(1L, fixture.writer.stats().droppedPolicy)
            assertEquals(0, fixture.recorder.depth().items)

            // 换代后的新事实照常落盘，携带新epoch与revision（不改绑）
            assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
            fixture.flush()
            val landed = fixture.facts()
            assertEquals(listOf("acct-b"), landed.map { it.account_epoch })
            assertEquals(listOf(14L), landed.map { it.policy_revision })
        }
    }

    // ---------- 预算释放：批次写入并计数后恰好释放一次 ----------

    @Test
    fun `claimed budget is released exactly once after the batch is written`() {
        Fixture().use { fixture ->
            repeat(5) { assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft())) }
            fixture.flush()

            assertEquals(0, fixture.recorder.depth().items)
            assertEquals(0, fixture.recorder.depth().bytes)
            assertEquals(5, fixture.facts().size)
        }
    }

    // ---------- writer锁：文件不unlink/recreate，第二个writer拿不到锁 ----------

    @Test
    fun `writer lock file survives close and blocks a second writer`() {
        val first = Fixture(cleanOnClose = false)
        try {
            first.use { fixture ->
                assertEquals(Admission.QUEUED, fixture.recorder.record(lifecycleDraft()))
                fixture.flush()
                assertTrue(Files.exists(fixture.root.resolve("writer.lock")), "锁文件不得unlink")
                assertTrue(Files.exists(fixture.root.resolve("exchange.lock")), "exchange锁文件只创建不删除")

                Fixture(base = fixture.base, cleanOnClose = false).use { second ->
                    assertTrue(await { second.writer.state != WriterState.CREATED }, "启动应结束于终态")
                    assertEquals(WriterState.DISABLED, second.writer.state)
                    assertNotEquals(null, second.writer.disabledReason)
                }
            }
            assertTrue(Files.exists(first.root.resolve("writer.lock")), "close后锁文件仍不删除")
        } finally {
            first.base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unverifiable storage root disables collection`() {
        Fixture(autoStart = false).use { fixture ->
            Files.write(fixture.root, byteArrayOf())
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
            val views = fixture.root.fileSystem.supportedFileAttributeViews()
            if ("posix" in views) return  // POSIX路径走0700置位回读，本断言仅适用ACL模型
            val view = Files.getFileAttributeView(fixture.root, AclFileAttributeView::class.java)
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

    // ---------- 夹具与驱动 ----------

    /** plugin.started仅允许空data（第9章），event_id已由recorder区分各条记录。 */
    private fun lifecycleDraft(): Draft = Draft(
        "plugin.started",
        "lifecycle",
        "critical",
        JsonObject(emptyMap()),
    )

    /** error.uncaught详情分支（message/frames/fingerprint/count恰好四键，设计第9章）；
     *  message/frames用双引号字符撑满边界（合法无路径分隔符），真实UTF-8转义后逼近单条上限。 */
    private fun detailDraft(index: Int): Draft = Draft(
        "error.uncaught",
        "diagnostic",
        "diagnostic",
        buildJsonObject {
            put("message", "\"".repeat(511) + (index % 10))
            put("frames", JsonArray(List(5) { JsonPrimitive("\"".repeat(256)) }))
            put("fingerprint", "p".repeat(64) + index)
            put("count", 1)
        },
        purposes = setOf("logs"),
    )

    private fun readyFiles(fixture: Fixture): List<Path> = listReady(fixture.root)

    private fun openFiles(fixture: Fixture): List<Path> = listOpen(fixture.root)

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

    private fun awaitReady(fixture: Fixture, count: Int): Boolean =
        await { readyFiles(fixture).size >= count }

    private fun awaitOpen(fixture: Fixture, count: Int): Boolean =
        await { openFiles(fixture).size >= count }

    /** 记录已完整写入并释放预算（claim后release）的确定性barrier；避免时钟推进与首条写入竞态。 */
    private fun awaitDrained(fixture: Fixture): Boolean =
        await { fixture.recorder.depth().items == 0 }
}

