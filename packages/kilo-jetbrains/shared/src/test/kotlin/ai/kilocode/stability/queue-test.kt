package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 有界准入队列（任务A3，设计7.1）。
 *
 * 2000条且4MiB先到者为准；critical预留400条与20%字节，diagnostic至多1600条；
 * critical需空间时先驱逐最老diagnostic，仍不足拒绝新记录；生产路径只tryLock不等待；
 * seq按run/channel在准入前递增；writer取出的记录仍计入内存预算直至release；
 * shutdown后不再准入；多字节文本按UTF-8字节预算先于条数触顶。
 */
class QueueTest {

    private val tempDirs = mutableListOf<Path>()
    private val stores = mutableListOf<PolicyStore>()

    @AfterTest
    fun tearDown() {
        stores.forEach { store -> store.close() }
        stores.clear()
        tempDirs.forEach { dir -> dir.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    // ---------- 双维容量（纯队列结构） ----------

    @Test
    fun `1600 diagnostics leave room for 400 critical records`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        repeat(1600) { index ->
            val outcome = queue.tryOffer({ record("diagnostic", bytes = 16, seq = index + 1L) })
            assertEquals(QueueOffer.QUEUED, outcome.offer, "diagnostic $index must fit")
        }
        assertEquals(QueueOffer.FULL, queue.tryOffer({ record("diagnostic", 16, seq = 1601) }).offer)

        repeat(400) { index ->
            val outcome = queue.tryOffer({ record("critical", bytes = 16, seq = index + 1L) })
            assertEquals(QueueOffer.QUEUED, outcome.offer, "critical $index must fit the reservation")
        }
        assertEquals(2000, queue.depthItems)
        assertEquals(2000 * 16, queue.depthBytes)
    }

    @Test
    fun `diagnostic overflow drops without touching critical records`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        repeat(400) { assertEquals(QueueOffer.QUEUED, queue.tryOffer({ record("critical", 16, seq = it + 1L) }).offer) }
        repeat(1600) { assertEquals(QueueOffer.QUEUED, queue.tryOffer({ record("diagnostic", 16, seq = it + 1L) }).offer) }

        val outcome = queue.tryOffer({ record("diagnostic", 16, seq = 1601) })
        assertEquals(QueueOffer.FULL, outcome.offer)
        assertEquals(0, outcome.evictedDiagnostics)
        assertEquals(2000, queue.depthItems)
    }

    @Test
    fun `byte budget binds before the diagnostic item cap`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        val big = DIAGNOSTIC_BYTES / 1000 + 1
        repeat(999) { index ->
            assertEquals(QueueOffer.QUEUED, queue.tryOffer({ record("diagnostic", big, seq = index + 1L) }).offer)
        }

        assertEquals(999, queue.depthItems)
        assertTrue(queue.depthItems < 1600, "byte budget must bind before the item cap")
        assertEquals(QueueOffer.FULL, queue.tryOffer({ record("diagnostic", big, seq = 1000) }).offer)
        assertEquals(999, queue.depthItems)
    }

    @Test
    fun `sustained critical evicts only the oldest diagnostics`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        repeat(1600) { queue.tryOffer({ record("diagnostic", 16, mono = it.toLong(), seq = it + 1L) }) }
        repeat(400) { queue.tryOffer({ record("critical", 16, mono = (1_600 + it).toLong(), seq = it + 1L) }) }

        repeat(100) { round ->
            val outcome = queue.tryOffer({ record("critical", 16, mono = (2_000 + round).toLong(), seq = (401 + round).toLong()) })
            assertEquals(QueueOffer.QUEUED, outcome.offer, "critical round $round must evict a diagnostic")
            assertEquals(1, outcome.evictedDiagnostics)
        }

        assertEquals(2000, queue.depthItems)
        assertEquals(1500, queue.diagnosticDepthItems)

        val claimed = assertNotNull(queue.tryClaim(MAX_ITEMS, MAX_BYTES))
        val criticalRecords = claimed.records.filter { record -> record.channel == "critical" }
        assertEquals(500, criticalRecords.size)
        assertEquals((1L..500L).toList(), criticalRecords.map { it.seq })
        claimed.release()
    }

    @Test
    fun `critical larger than total capacity is refused after evicting every diagnostic`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        repeat(100) { assertEquals(QueueOffer.QUEUED, queue.tryOffer({ record("diagnostic", 16, seq = it + 1L) }).offer) }

        val outcome = queue.tryOffer({ record("critical", bytes = MAX_BYTES + 1, seq = 1) })
        assertEquals(QueueOffer.FULL, outcome.offer)
        assertEquals(100, outcome.evictedDiagnostics)
        assertEquals(0, queue.depthItems)
        assertEquals(0, queue.diagnosticDepthItems)
    }

    // ---------- writer交接（claim仍计入预算） ----------

    @Test
    fun `claim drains oldest first and keeps accounting until release`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        repeat(3) { assertEquals(QueueOffer.QUEUED, queue.tryOffer({ record("critical", 16, mono = it.toLong(), seq = it + 1L) }).offer) }

        val partial = assertNotNull(queue.tryClaim(2, MAX_BYTES))
        assertEquals(listOf(1L, 2L), partial.records.map { record -> record.seq })
        assertEquals(3, queue.depthItems)
        assertEquals(48, queue.depthBytes)

        partial.release()
        assertEquals(1, queue.depthItems)
        assertEquals(16, queue.depthBytes)

        val single = assertNotNull(queue.tryClaim(10, 24))
        assertEquals(1, single.records.size)
        assertEquals(1, queue.depthItems)
        single.release()

        assertEquals(null, queue.tryClaim(10, MAX_BYTES))
    }

    @Test
    fun `producers never wait for the queue lock`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        try {
            holder.submit {
                queue.tryWithProducerLock {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "held"
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(null, queue.tryClaim(10, MAX_BYTES))
            assertEquals(QueueOffer.CONTENTION, queue.tryOffer({ record("critical", 16, seq = 1) }).offer)
        } finally {
            release.countDown()
            holder.shutdown()
            assertTrue(holder.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    // ---------- Recorder准入：许可交集、字节预算、seq、shutdown ----------

    @Test
    fun `dictionary purposes select the available sinks per design 11_2`() {
        assertEquals(setOf("metrics"), Dictionary.purposes("rpc", JsonObject(emptyMap())))
        assertEquals(
            setOf("metrics"),
            Dictionary.purposes("render.apply", buildJsonObject { put("duration_ms", 5) }),
        )
        assertEquals(setOf("metrics"), Dictionary.purposes("edt.delay", sampleData()))
        assertEquals(
            setOf("metrics"),
            Dictionary.purposes("migration.required", buildJsonObject { put("migration_kind", "v2") }),
        )
        assertEquals(setOf("metrics", "logs"), Dictionary.purposes("action", endData("action")))
        assertEquals(setOf("metrics", "logs"), Dictionary.purposes("plugin.started", JsonObject(emptyMap())))
        assertEquals(setOf("metrics", "logs"), Dictionary.purposes("connection.state_changed", stateData()))
        assertEquals(setOf("metrics", "logs"), Dictionary.purposes("telemetry.health", healthData()))
        assertEquals(setOf("metrics", "logs"), Dictionary.purposes("protocol.error", protocolData()))
        assertEquals(setOf("metrics"), Dictionary.purposes("error.reported", minimalErrorData()))
        assertEquals(setOf("logs"), Dictionary.purposes("error.reported", detailErrorData()))
        assertEquals(setOf("logs"), Dictionary.purposes("error.uncaught", detailErrorData()))
        assertEquals(emptySet(), Dictionary.purposes("unknown.name", JsonObject(emptyMap())))
        assertEquals(emptySet(), Dictionary.purposes("error.reported", JsonObject(emptyMap())))
    }

    @Test
    fun `metrics only permit refuses the logs-only detail branch`() {
        val fixture = newFixture(OperationTest.controlJson(logsEnabled = false))
        val recorder = fixture.recorder

        assertEquals(Admission.DISABLED, recorder.record(Draft("error.reported", "diagnostic", "diagnostic", detailErrorData())))
        assertEquals(Admission.QUEUED, recorder.record(Draft("error.reported", "diagnostic", "critical", minimalErrorData())))
        assertEquals(Admission.QUEUED, recorder.record(Draft("rpc", "operation", "critical", endData("rpc"))))
        assertEquals(2L, recorder.health().accepted)
        assertEquals(1L, recorder.health().disabledPolicy)
    }

    @Test
    fun `logs only permit refuses metrics only rpc`() {
        val fixture = newFixture(OperationTest.controlJson(metricsEnabled = false))
        assertEquals(Admission.DISABLED, fixture.recorder.record(Draft("rpc", "operation", "critical", endData("rpc"))))
        assertEquals(
            Admission.QUEUED,
            fixture.recorder.record(Draft("error.reported", "diagnostic", "diagnostic", detailErrorData())),
        )
        assertEquals(1L, fixture.recorder.health().disabledPolicy)
    }

    @Test
    fun `multi-byte messages hit the byte budget before the item cap`() {
        val fixture = newFixture(OperationTest.controlJson())
        val recorder = fixture.recorder
        val draft = Draft("error.reported", "diagnostic", "diagnostic", detailErrorData())

        var queued = 0
        while (queued < 2000 && recorder.record(draft) == Admission.QUEUED) queued += 1

        assertTrue(queued in 1 until 1600, "byte budget must bind first, queued=$queued")
        assertEquals(Admission.QUEUED, recorder.record(Draft("rpc", "operation", "critical", endData("rpc"))))
        val depth = fixture.depth()
        assertTrue(depth.diagnosticBytes <= DIAGNOSTIC_BYTES, "diagnostic depth must respect the byte budget")
        assertEquals(queued + 1, depth.items)
        assertEquals(1L, recorder.health().droppedCapacity)
    }

    @Test
    fun `dropped records consume seq before admission`() {
        val fixture = newFixture(OperationTest.controlJson())
        val recorder = fixture.recorder
        val draft = Draft("error.reported", "diagnostic", "diagnostic", detailErrorData())

        var queued = 0
        while (queued < 2000 && recorder.record(draft) == Admission.QUEUED) queued += 1
        assertTrue(queued < 2000)

        val firstBatch = fixture.claimAll()
        assertEquals((1L..queued).toList(), firstBatch.map { record -> record.seq })

        assertEquals(Admission.QUEUED, recorder.record(draft))
        val facts = fixture.claimAll()
        assertEquals(1, facts.size)
        assertEquals(queued + 2L, facts.single().seq)
    }

    @Test
    fun `invalid drafts are dropped and counted`() {
        val fixture = newFixture(OperationTest.controlJson())
        val recorder = fixture.recorder

        assertEquals(
            Admission.DROPPED,
            recorder.record(Draft("not.in.dictionary", "health", "critical", healthData())),
        )
        assertEquals(
            Admission.DROPPED,
            recorder.record(Draft("telemetry.health", "health", "critical", healthData(extra = "unknown_key"))),
        )
        assertEquals(0, fixture.depth().items)
        assertEquals(2L, recorder.health().droppedInvalid)
    }

    @Test
    fun `shutdown stops admission deterministically`() {
        val fixture = newFixture(OperationTest.controlJson())
        val recorder = fixture.recorder
        assertEquals(Admission.QUEUED, recorder.record(Draft("rpc", "operation", "critical", endData("rpc"))))

        recorder.close()
        assertEquals(Admission.DISABLED, recorder.record(Draft("rpc", "operation", "critical", endData("rpc"))))
        assertEquals(1, fixture.depth().items)
        assertEquals(1L, recorder.health().disabledShutdown)
        assertEquals(1L, recorder.health().accepted)
    }

    @Test
    fun `unknown major falls open to the unbound placeholder`() {
        val dir = Files.createTempDirectory("stability-queue").also { tempDirs.add(it) }
        val file = dir.resolve("jetbrains.json").apply { writeText(OperationTest.controlJson(major = 2)) }
        val store = PolicyStore(file, { 2_000L }).also { stores.add(it) }
        val recorder = Recorder(PRODUCER_IDENTITY, store, SYSTEM_CLOCK)

        // fail open（设计§8）：未知schema_major视为无有效策略，unbound占位策略放行全部登记name
        // （默认不限制采集），不再默认关闭；准入的用途交集仍由Dictionary形态出口收窄。
        val rpc = Draft("rpc", "operation", "critical", endData("rpc"))
        val started = Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap()))
        assertEquals(Admission.QUEUED, recorder.record(rpc))
        assertEquals(Admission.QUEUED, recorder.record(started))
        assertEquals(0L, recorder.health().disabledPolicy)

        val claim = assertNotNull(recorder.tryClaim(MAX_ITEMS, MAX_BYTES))
        val facts = try {
            claim.records.map { record -> record.fact }
        } finally {
            claim.release()
        }
        assertEquals(listOf(EPOCH_UNBOUND, EPOCH_UNBOUND), facts.map { fact -> fact.account_epoch })
        assertEquals(listOf(0L, 0L), facts.map { fact -> fact.policy_revision })
        assertEquals(listOf(setOf("metrics"), setOf("metrics", "logs")), facts.map { fact -> fact.purposes })
    }

    @Test
    fun `concurrent record and end keep event ids and per channel seq unique`() {
        val fixture = newFixture(OperationTest.controlJson())
        val recorder = fixture.recorder
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val operations = Operations(recorder, SYSTEM_CLOCK, scope)
        val pool = Executors.newFixedThreadPool(8)
        val admissions = Collections.synchronizedList(mutableListOf<Admission>())
        val ends = AtomicInteger()
        val done = CountDownLatch(8)
        try {
            repeat(8) { worker ->
                pool.submit {
                    try {
                        repeat(40) { round ->
                            admissions += recorder.record(healthDraft(worker, round))
                            if (round % 8 == 0) {
                                val operation = operations.begin("backend.load", 60_000L)
                                if (operation.end("success")) ends.incrementAndGet()
                            }
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            scope.cancel()
        }

        val facts = fixture.claimAll()
        // 争用会按设计丢弃部分记录（tryLock立即放弃），因此只断言不变量：
        // 每条显式QUEUED必须可取回；操作记录不超出begin/end次数；无重复ID/seq。
        assertEquals(40, ends.get())
        assertTrue(facts.size >= admissions.count { it == Admission.QUEUED }, "every QUEUED record must be claimable")
        assertTrue(admissions.all { it == Admission.QUEUED || it == Admission.DROPPED })
        assertTrue(facts.count { it.data["phase"] == JsonPrimitive("start") } <= ends.get())
        assertTrue(facts.count { it.data["phase"] == JsonPrimitive("end") } <= ends.get())

        val ids = facts.map { fact -> fact.event_id }
        assertEquals(ids.size, ids.distinct().size, "event_id must be unique")

        val criticalSeqs = facts.filter { fact -> fact.channel == "critical" }.map { it.seq }
        assertEquals(criticalSeqs.size, criticalSeqs.distinct().size, "seq must be unique per channel")
        assertEquals(criticalSeqs.sorted(), criticalSeqs, "queued order must match seq order")
    }

    // ---------- 夹具 ----------

    private fun newFixture(control: String): QueueFixture {
        val dir = Files.createTempDirectory("stability-queue").also { tempDirs.add(it) }
        val file = dir.resolve("jetbrains.json").apply { writeText(control) }
        val store = PolicyStore(file, { SYSTEM_CLOCK.wall() }).also { stores.add(it) }
        return QueueFixture(Recorder(PRODUCER_IDENTITY, store, SYSTEM_CLOCK), file)
    }

    private inner class QueueFixture(
        val recorder: Recorder,
        private val file: Path,
    ) {
        fun depth(): QueueDepth = recorder.depth()

        fun claimAll(): List<Fact> {
            val facts = mutableListOf<Fact>()
            while (true) {
                val claim = recorder.tryClaim(MAX_ITEMS, MAX_BYTES) ?: break
                facts += claim.records.map { record -> record.fact }
                val claimed = claim.records.size
                claim.release()
                if (claimed < MAX_ITEMS) break
            }
            return facts
        }
    }

    private fun record(channel: String, bytes: Int, mono: Long = 0L, seq: Long): QueuedRecord =
        QueuedRecord(queueFact(seq, channel), channel, seq, bytes, mono)

    private fun queueFact(seq: Long, channel: String): Fact = Fact(
        event_id = UUID.randomUUID().toString(),
        timestamp = 0L,
        producer_id = "pr-test",
        run_id = "run-test",
        channel = channel,
        seq = seq,
        account_epoch = "acct-test",
        policy_revision = 1L,
        purposes = setOf("metrics"),
        device_id = "device-test",
        plugin_version = "1.0.0",
        ide_product = "IU",
        ide_build = "build-test",
        ide_build_major = "2026.1",
        os_family = "windows",
        arch = "x64",
        env = "test",
        mode = "split",
        side = "frontend",
        connection_provider = "cs-cloud",
        kind = "operation",
        name = "rpc",
        data = JsonObject(emptyMap()),
    )

    private fun endData(name: String): JsonObject = buildJsonObject {
        put("phase", "end")
        if (name == "rpc") put("api_group", "profile")
        if (name == "action") put("action", "prompt_submit")
        put("result", "success")
        put("duration_ms", 5)
        put("stage", "probe")
        put("cause", "unknown")
        put("error_code", "none")
    }

    private fun minimalErrorData(): JsonObject = buildJsonObject {
        put("fault_id", "fault-1")
        put("error_class", "IllegalState")
        put("handled", true)
        put("fingerprint", "fp-1")
        put("component", "frontend")
    }

    private fun detailErrorData(): JsonObject = buildJsonObject {
        put("message", "界".repeat(170) + "ab")
        putJsonArray("frames") {
            repeat(5) { index -> add("帧".repeat(85) + index) }
        }
        put("fingerprint", "fp-detail")
        put("count", 1)
    }

    private fun healthData(extra: String? = null): JsonObject = buildJsonObject {
        put("drop", 0)
        put("write_error", 0)
        put("depth_bytes", 128)
        put("oldest_age_ms", 5)
        if (extra != null) put(extra, 1)
    }

    private fun stateData(): JsonObject = buildJsonObject {
        put("from", "ready")
        put("to", "connecting")
        put("streams_open", 0)
        put("streams_total", 2)
    }

    private fun protocolData(): JsonObject = buildJsonObject {
        put("transport", "stdio")
        put("stage", "decode")
        put("error_code", "bad_frame")
    }

    private fun sampleData(): JsonObject = buildJsonObject {
        put("observation_id", "obs-1")
        put("probe_seq", 1)
        put("scheduled_mono_ms", 10)
        put("completed_mono_ms", 15)
        put("duration_ms", 5)
        put("validity", "valid")
    }

    private fun healthDraft(worker: Int, round: Int): Draft = Draft(
        "telemetry.health",
        "health",
        "critical",
        buildJsonObject {
            put("drop", worker)
            put("write_error", 0)
            put("depth_bytes", round)
            put("oldest_age_ms", 1)
        },
    )

    companion object {
        private val PRODUCER_IDENTITY = ProducerIdentity(
            producerId = "pr-a3",
            runId = "run-a3",
            deviceId = "device-a3",
            pluginVersion = "1.0.0",
            ideProduct = "IU",
            ideBuild = "build-a3",
            ideBuildMajor = "2026.1",
            osFamily = "windows",
            arch = "x64",
            env = "test",
            mode = "split",
            side = "frontend",
            connectionProvider = "cs-cloud",
        )

        private const val MAX_ITEMS = 2000
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val RESERVED_ITEMS = 400
        private const val RESERVED_BYTES = (MAX_BYTES + 4) / 5
        private const val DIAGNOSTIC_BYTES = MAX_BYTES - RESERVED_BYTES

        private val SYSTEM_CLOCK = object : Clock {
            override fun wall(): Long = System.currentTimeMillis()
            override fun mono(): Long = System.nanoTime() / 1_000_000
        }
    }
}
