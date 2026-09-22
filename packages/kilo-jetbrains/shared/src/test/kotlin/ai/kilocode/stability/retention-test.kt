package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 可控双时钟（与Fixture的FixtureClock同构）：retention与service测试共用同一时间线。 */
internal class SweepClock(private val startWallMs: Long = 1_790_000_000_000L) : Clock {
    private var monoMs = 0L
    override fun wall(): Long = startWallMs + monoMs
    override fun mono(): Long = monoMs
    fun advance(ms: Long) {
        monoMs += ms
    }
}

/** 一个小时/一天的毫秒数（测试内做age换算，避免魔法数字散落）。 */
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/** 陈旧清理（任务6，设计7.4）：同scope-id前缀的过期平铺jsonl整文件删除，其余一律不碰。 */
class RetentionTest {

    @Test
    fun `sweep deletes only stale same-scope files`() {
        val dir = Files.createTempDirectory("retention")
        try {
            val clock = SweepClock()
            val active = dir.resolve("sc-live-pr-1111.jsonl").apply { writeText("{}\n") }
            val staleSameScope = dir.resolve("sc-live-pr-2222.jsonl").apply { writeText("{}\n") }
            val freshSameScope = dir.resolve("sc-live-pr-3333.jsonl").apply { writeText("{}\n") }
            val staleOtherScope = dir.resolve("sc-other-pr-4444.jsonl").apply { writeText("{}\n") }
            val staleJunk = dir.resolve("sc-live-notes.txt").apply { writeText("{}\n") }
            val staleDirectory = dir.resolve("sc-live-dir.jsonl").toFile().apply { mkdirs() }
            // 回拨：active与stale* 的mtime设为25小时前；fresh保持当前。活跃文件即使过期也无条件保留。
            val staleTime = FileTime.fromMillis(clock.wall() - DAY_MS - HOUR_MS)
            (listOf(active, staleSameScope, staleOtherScope, staleJunk)).forEach {
                Files.setLastModifiedTime(it, staleTime)
            }
            staleDirectory.setLastModified(staleTime.toMillis())

            Retention(dir, scopeId = "sc-live", activeFile = active, clock = clock).sweep()

            assertTrue(Files.exists(active), "活跃文件不动（即使在保留期内也无条件保留）")
            assertFalse(Files.exists(staleSameScope), "同scope过期删除（整文件，§7.4）")
            assertTrue(Files.exists(freshSameScope), "未过期不动")
            assertTrue(Files.exists(staleOtherScope), "他scope归cs-cloud清理")
            assertTrue(Files.exists(staleJunk), "非jsonl不动")
            assertTrue(staleDirectory.exists(), "目录不动（只删平铺常规文件）")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `exactly at the retention boundary the file stays and one tick later it goes`() {
        val dir = Files.createTempDirectory("retention-boundary")
        try {
            val clock = SweepClock()
            val boundary = dir.resolve("sc-live-pr-5555.jsonl").apply { writeText("{}\n") }
            Files.setLastModifiedTime(boundary, FileTime.fromMillis(clock.wall() - DAY_MS))
            val active = dir.resolve("sc-live-pr-0000.jsonl")
            Retention(dir, scopeId = "sc-live", activeFile = active, clock = clock).sweep()
            assertTrue(Files.exists(boundary), "恰好24小时未超过，不得删除")
            clock.advance(1L)
            Retention(dir, scopeId = "sc-live", activeFile = active, clock = clock).sweep()
            assertFalse(Files.exists(boundary), "超过24小时整文件删除")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing outbox directory is a no-op sweep`() {
        val base = Files.createTempDirectory("retention-missing")
        try {
            val missing = base.resolve("outbox")
            Retention(missing, scopeId = "sc-live", activeFile = missing.resolve("sc-live-pr-1.jsonl"), clock = SweepClock())
                .sweep()
            assertFalse(Files.exists(missing), "目录不存在不得创建任何文件（后台常态no-op）")
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
