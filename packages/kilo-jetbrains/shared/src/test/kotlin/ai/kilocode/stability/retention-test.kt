package ai.kilocode.stability

import java.nio.file.attribute.FileTime
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import kotlin.io.path.writeText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** 一个小时的毫秒数（测试内做age/quota换算，避免魔法数字散落）。 */
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS
private const val MIB = 1024L * 1024

class RetentionTest {

    @Test
    fun `cleanup never removes claimed files`() {
        Fixture().use { fixture ->
            val dir = fixture.root.resolve("diagnostic")
            Files.createDirectories(dir)
            val claimed = Files.writeString(dir.resolve("old.claimed"), "claimed\n")
            Files.setLastModifiedTime(claimed, FileTime.fromMillis(0))
            Retention(fixture.root, fixture.clock).sweep()
            assertTrue(Files.exists(claimed))
        }
    }

    @Test
    fun `locked old producer keeps all data until its writer lock is released`() {
        // 锁保持测试用真实FileChannel锁（同JVM语义等价：tryLock失败即跳过）。
        // 跨JVM（真实第二进程/Go consumer、进程死亡释放、休眠保留）归G1平台矩阵验证。
        val base = Files.createTempDirectory("retention-lock")
        try {
            val clock = SweepClock()
            val layout = oldProducer(base, "pr-lock", pid = 4242, processStart = 111)
            val open = segment(layout.root, "critical", "run-a-0.open")
            val ready = segment(layout.root, "critical", "run-a-0.ready")
            age(open, clock, DAY_MS + HOUR_MS)
            age(ready, clock, DAY_MS + HOUR_MS)
            val lock = lockFile(layout.root.resolve("writer.lock"))
            sweep(base, clock).sweep()
            assertTrue(Files.exists(open), "held writer.lock must keep .open")
            assertTrue(Files.exists(ready), "held writer.lock must keep .ready")
            // 关闭通道即释放锁（FileLock随channel.close()释放）。
            lock.close()
            sweep(base, clock).sweep()
            assertFalse(Files.exists(ready), "released lock must allow expired .ready cleanup")
            assertFalse(Files.exists(open), "released lock must allow expired .open cleanup")
            assertTrue(Files.exists(layout.root.resolve("writer.lock")), "lock files are never removed")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `expired open and ready of a dead producer are swept while fresh files stay`() {
        val base = Files.createTempDirectory("retention-age")
        try {
            val clock = SweepClock()
            val layout = oldProducer(base, "pr-age", pid = 4242, processStart = 111)
            val expiredOpen = segment(layout.root, "critical", "run-a-0.open")
            val expiredReady = segment(layout.root, "critical", "run-a-0.ready")
            val freshOpen = segment(layout.root, "diagnostic", "run-b-0.open")
            val freshReady = segment(layout.root, "diagnostic", "run-b-1.ready")
            age(expiredOpen, clock, DAY_MS + HOUR_MS)
            age(expiredReady, clock, DAY_MS + HOUR_MS)
            sweep(base, clock).sweep()
            assertFalse(Files.exists(expiredOpen), "expired .open is deletable under both locks")
            assertFalse(Files.exists(expiredReady), "expired .ready is deletable under both locks")
            assertTrue(Files.exists(freshOpen), "unexpired .open stays for consumer rescue")
            assertTrue(Files.exists(freshReady), "unexpired .ready stays for consumer claim")
            assertTrue(Files.exists(layout.registration), "data remains so registration stays")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `metadata is removed only when data is empty and lock files stay`() {
        val base = Files.createTempDirectory("retention-metadata")
        try {
            val clock = SweepClock()
            val layout = oldProducer(base, "pr-meta", pid = 4242, processStart = 111)
            val ready = segment(layout.root, "diagnostic", "run-a-0.ready")
            age(ready, clock, DAY_MS + HOUR_MS)
            sweep(base, clock).sweep()
            assertFalse(Files.exists(layout.producerJson), "empty producer metadata is removed")
            assertFalse(Files.exists(layout.registration), "empty producer registration is removed")
            assertTrue(Files.exists(layout.root.resolve("writer.lock")), "writer.lock is kept")
            assertTrue(Files.exists(layout.root.resolve("exchange.lock")), "exchange.lock is kept")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `alive writer with matching start is skipped but pid reuse is swept`() {
        val base = Files.createTempDirectory("retention-pid")
        try {
            val clock = SweepClock()
            val layout = oldProducer(base, "pr-pid", pid = 4242, processStart = 111)
            val ready = segment(layout.root, "critical", "run-a-0.ready")
            age(ready, clock, DAY_MS + HOUR_MS)
            sweep(base, clock, processes = { PidEvidence.Alive(111) }).sweep()
            assertTrue(Files.exists(ready), "matching pid+process_start means a live writer: skip")
            sweep(base, clock, processes = { PidEvidence.Unknown }).sweep()
            assertTrue(Files.exists(ready), "unknown start identity is insufficient evidence: skip")
            sweep(base, clock, processes = { PidEvidence.Alive(999) }).sweep()
            assertFalse(Files.exists(ready), "same pid with a different start is pid reuse: sweep")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `same root multi producer sweep stays scoped per producer`() {
        val base = Files.createTempDirectory("retention-multi")
        try {
            val clock = SweepClock()
            val done = oldProducer(base, "pr-done", pid = 4242, processStart = 111)
            val doneReady = segment(done.root, "critical", "run-a-0.ready")
            age(doneReady, clock, DAY_MS + HOUR_MS)
            val fresh = oldProducer(base, "pr-fresh", pid = 4243, processStart = 222)
            val freshReady = segment(fresh.root, "critical", "run-b-0.ready")
            val unregistered = oldProducer(base, "pr-ghost", pid = 4244, processStart = 333, withRegistration = false)
            val ghostReady = segment(unregistered.root, "critical", "run-c-0.ready")
            age(ghostReady, clock, DAY_MS + HOUR_MS)
            sweep(base, clock).sweep()
            assertFalse(Files.exists(doneReady), "verified expired source is cleaned")
            assertFalse(Files.exists(done.registration), "finished source registration is removed")
            assertTrue(Files.exists(freshReady), "other producer's fresh data is untouched")
            assertTrue(Files.exists(fresh.registration), "other producer's registration is untouched")
            assertTrue(Files.exists(ghostReady), "unregistered source identity is unverified: keep")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `own source quota evicts oldest diagnostic ready before critical`() {
        val base = Files.createTempDirectory("retention-quota")
        try {
            val clock = SweepClock()
            val root = base.resolve("v1").resolve("pr-self")
            // 每个文件恰2000字节：总预算比较确定，淘汰次序与阈值一一对应。
            val oldDiagnostic = paddedSegment(root, "diagnostic", "run-a-0.ready", 2000)
            val newDiagnostic = paddedSegment(root, "diagnostic", "run-a-1.ready", 2000)
            val critical = paddedSegment(root, "critical", "run-a-2.ready", 2000)
            val open = paddedSegment(root, "diagnostic", "run-a-3.open", 2000)
            age(oldDiagnostic, clock, 2 * HOUR_MS)
            age(newDiagnostic, clock, HOUR_MS)
            age(critical, clock, HOUR_MS)
            // 总量8000>6000：淘汰最旧diagnostic后恰好6000，回到预算内即停。
            sweep(base, clock, maxBytes = 6000).sweepOwnSource()
            assertFalse(Files.exists(oldDiagnostic), "oldest diagnostic .ready evicted first")
            assertTrue(Files.exists(newDiagnostic), "newer diagnostic .ready stays within quota")
            assertTrue(Files.exists(critical), "critical .ready stays while diagnostic frees space")
            assertTrue(Files.exists(open), ".open of the active writer is never evicted")
            // 总量6000>3000：先下一个diagnostic再critical（diagnostic清空后才动critical）。
            sweep(base, clock, maxBytes = 3000).sweepOwnSource()
            assertFalse(Files.exists(newDiagnostic), "still over quota: next oldest diagnostic goes")
            assertFalse(Files.exists(critical), "diagnostic exhausted: oldest critical .ready goes")
            assertTrue(Files.exists(open), ".open is still never evicted")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `expired claimed and done files are never touched by retention`() {
        val base = Files.createTempDirectory("retention-consumer-owned")
        try {
            val clock = SweepClock()
            val layout = oldProducer(base, "pr-consumer", pid = 4242, processStart = 111)
            val claimed = segment(layout.root, "critical", "run-a-0.claimed")
            val done = segment(layout.root, "diagnostic", "run-a-1.done")
            age(claimed, clock, DAY_MS + HOUR_MS)
            age(done, clock, DAY_MS + HOUR_MS)
            sweep(base, clock).sweep()
            assertTrue(Files.exists(claimed), "claimed is daemon-owned; retention never touches it")
            assertTrue(Files.exists(done), "done is daemon-owned; retention never touches it")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `own source sweep takes exchange only while the writer lock is already held`() {
        val base = Files.createTempDirectory("retention-order")
        try {
            val clock = SweepClock()
            val root = base.resolve("v1").resolve("pr-order")
            val ready = segment(root, "critical", "run-a-0.ready")
            age(ready, clock, DAY_MS + HOUR_MS)
            // 交换锁被他人持有（consumer）：不得删除（本轮跳过）。
            val exchange = lockFile(root.resolve("exchange.lock"))
            sweep(base, clock, producerId = "pr-order").sweepOwnSource()
            assertTrue(Files.exists(ready), "exchange.lock held elsewhere: skip, no deletion")
            exchange.close()
            // writer锁已由活跃writer持有（此处用真实锁模拟）：顺序writer→exchange，删除得以进行。
            val writer = lockFile(root.resolve("writer.lock"))
            sweep(base, clock, producerId = "pr-order").sweepOwnSource()
            assertFalse(Files.exists(ready), "writer.lock held + exchange acquired: expired .ready goes")
            writer.close()
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stale registration without its outbox root is removed while foreign paths stay`() {
        val base = Files.createTempDirectory("retention-stale")
        val foreignRoot = Files.createTempDirectory("foreign-outbox")
        try {
            val clock = SweepClock()
            val registrations = base.resolve("registrations")
            val gone = oldProducer(base, "pr-gone", pid = 4242, processStart = 111)
            gone.root.toFile().deleteRecursively()
            val foreignReg = writeRegistration(registrations, "pr-foreign", foreignRoot)
            sweep(base, clock).sweep()
            assertFalse(Files.exists(gone.registration), "registration whose outbox vanished is stale")
            assertTrue(Files.exists(foreignReg), "registration outside this log root is untouched")
        } finally {
            base.toFile().deleteRecursively()
            foreignRoot.toFile().deleteRecursively()
        }
    }

    // --- 夹具：旧producer布局与真实锁文件 -----------------------------------------------

    private class OldLayout(
        val root: Path,
        val producerJson: Path,
        val registration: Path,
    )

    private fun oldProducer(
        base: Path,
        producerId: String,
        pid: Long,
        processStart: Long,
        withRegistration: Boolean = true,
    ): OldLayout {
        val root = base.resolve("v1").resolve(producerId)
        Files.createDirectories(root)
        val producerJson = root.resolve("producer.json")
        val json = buildJsonObject {
            put("producer_id", producerId)
            put("pid", pid)
            put("process_start", processStart)
        }
        producerJson.writeText(json.toString())
        root.resolve("writer.lock").writeText("")
        root.resolve("exchange.lock").writeText("")
        val registrations = base.resolve("registrations")
        val registration = if (withRegistration) {
            writeRegistration(registrations, producerId, root)
        } else {
            registrations.resolve("$producerId.json")
        }
        return OldLayout(root, producerJson, registration)
    }

    private fun writeRegistration(dir: Path, producerId: String, outbox: Path): Path {
        Files.createDirectories(dir)
        val json = buildJsonObject {
            put("schema_major", 1)
            put("outbox_path", outbox.toAbsolutePath().toString())
            put("producer_id", producerId)
            put("pid", 4242L)
            put("process_start", 111L)
            put("created_at", 0L)
        }
        return Files.writeString(dir.resolve("$producerId.json"), json.toString())
    }

    private fun segment(root: Path, channel: String, name: String): Path {
        val dir = root.resolve(channel)
        Files.createDirectories(dir)
        return Files.writeString(dir.resolve(name), "{\"line\":1}\n")
    }

    /** 定长伪段文件：预算测试需要确定的字节量。 */
    private fun paddedSegment(root: Path, channel: String, name: String, size: Int): Path {
        val dir = root.resolve(channel)
        Files.createDirectories(dir)
        return Files.write(dir.resolve(name), ByteArray(size) { 120 })
    }

    private fun age(path: Path, clock: SweepClock, ageMs: Long) {
        Files.setLastModifiedTime(path, FileTime.fromMillis(clock.wall() - ageMs))
    }

    private fun lockFile(path: Path): FileChannel {
        val channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
        channel.lock(0, 1, false)
        return channel
    }

    private fun sweep(
        base: Path,
        clock: Clock,
        producerId: String = "pr-self",
        maxBytes: Long = 10 * MIB,
        processes: ProcessIdentity = ProcessIdentity { PidEvidence.Gone },
    ): Retention = Retention(
        root = base.resolve("v1").resolve(producerId),
        clock = clock,
        v1Root = base.resolve("v1"),
        registrationsDir = base.resolve("registrations"),
        maxBytes = maxBytes,
        maxAgeMs = DAY_MS,
        processes = processes,
    )
}
