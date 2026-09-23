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

    @Test
    fun `batch rejects mismatched payload incident ids without publishing any records`() {
        Fixture(autoStart = false).use { fixture ->
            fixture.base.resolve("control.json").writeText(Fixture.defaultControl().dropLast(1) + ",\"accepted_fact_schema_majors\":[1,2]}")
            fixture.policies.refresh()
            val drafts = incident("parent-id")
            val chunk = drafts.last()
            val mismatch = Draft(
                chunk.name, chunk.kind, chunk.channel,
                JsonObject(chunk.data + ("incident_id" to JsonPrimitive("different-data-id"))),
                chunk.context, chunk.epoch, chunk.purposes, chunk.schemaVersion,
            )
            val group = drafts.dropLast(1) + mismatch
            assertTrue(Dictionary.violations(group).isEmpty(), "the mismatch passes individual field validation")
            assertEquals(Admission.DROPPED, fixture.recorder.recordBatch(group))
            assertEquals(3, fixture.recorder.health().droppedInvalid)
            assertEquals(0, fixture.recorder.depth().items)
            assertEquals(0, fixture.recorder.depth().bytes)
            assertEquals(null, fixture.recorder.tryClaim(MAX_ITEMS, MAX_BYTES))
            assertEquals(Admission.QUEUED, fixture.recorder.recordBatch(drafts))
            val claim = assertNotNull(fixture.recorder.tryClaim(MAX_ITEMS, MAX_BYTES))
            assertEquals(3, claim.records.size)
            claim.release()
        }
    }

    @Test
    fun `one hundred concurrent incidents evict samples without losing failures`() {
        Fixture(autoStart = false).use { fixture ->
            fixture.base.resolve("control.json").writeText(Fixture.defaultControl().dropLast(1) + ",\"accepted_fact_schema_majors\":[1,2]}")
            fixture.policies.refresh()
            while (fixture.recorder.record(Draft("telemetry.health", "health", "critical", healthData())) == Admission.QUEUED) Unit
            val pool = Executors.newFixedThreadPool(8)
            try {
                val jobs = (0 until 100).map { index ->
                    pool.submit<Admission> { fixture.recorder.recordBatch(incident("inc-$index")) }
                }
                jobs.forEach { job -> assertEquals(Admission.QUEUED, job.get(30, TimeUnit.SECONDS)) }
            } finally {
                pool.shutdownNow()
            }
            val claim = assertNotNull(fixture.recorder.tryClaim(MAX_ITEMS, MAX_BYTES))
            assertEquals(300, claim.records.take(300).count { it.fact.context["incident_id"] != null })
            assertEquals(100, claim.records.count { it.fact.name == "diagnostic.reported" })
            assertEquals(200, claim.records.count { it.fact.name == "diagnostic.payload" })
            assertEquals(0, fixture.recorder.health().droppedFailure)
            assertTrue(fixture.recorder.health().droppedEvicted > 0)
            assertEquals(JsonPrimitive("good"), Health(fixture.recorder, fixture.writer, fixture.clock).snapshot()["quality"])
            claim.release()
            claim.release()
            assertEquals(0, fixture.recorder.depth().items)
            assertEquals(0, fixture.recorder.depth().bytes)
        }
    }

    @Test
    fun `batch validation and reservation never publish partial incidents`() {
        Fixture(autoStart = false).use { fixture ->
            fixture.base.resolve("control.json").writeText(Fixture.defaultControl().dropLast(1) + ",\"accepted_fact_schema_majors\":[1,2]}")
            fixture.policies.refresh()
            val drafts = incident("invalid")
            assertEquals(Admission.DROPPED, fixture.recorder.recordBatch(drafts + drafts.last()))
            assertEquals(Admission.DROPPED, fixture.recorder.recordBatch(drafts.drop(1)))
            assertEquals(Admission.DROPPED, fixture.recorder.recordBatch(drafts.dropLast(1)))
            assertEquals(0, fixture.recorder.depth().items)
            while (fixture.recorder.record(Draft("rpc", "operation", "critical", endData("rpc"))) == Admission.QUEUED) Unit
            val before = fixture.recorder.depth()
            assertEquals(Admission.DROPPED, fixture.recorder.recordBatch(incident("full")))
            assertEquals(before, fixture.recorder.depth())
        }
    }

    private fun incident(id: String): List<Draft> = listOf(Draft(
        "diagnostic.reported", "diagnostic", "diagnostic",
        buildJsonObject {
            put("severity", "error")
            put("component", "backend.rpc")
            put("code", "decode")
            put("message", "failed")
            put("thread_name", "worker")
            put("thread_id", 1)
            putJsonArray("payload_refs") { add("response") }
            put("truncated", false)
        },
        context = mapOf("incident_id" to id), purposes = setOf("logs"), schemaVersion = "2.0",
    )) + DiagnosticPayload.parts(id, "response", "x".repeat(5000).encodeToByteArray()).drafts

    private val tempDirs = mutableListOf<Path>()
    private val stores = mutableListOf<PolicyStore>()

    @Test
    fun `concurrent failure reservations and releases preserve the budget`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        repeat(1600) { queue.tryOffer { record("diagnostic", 16, seq = it + 1L) } }
        val gate = CountDownLatch(1)
        val done = CountDownLatch(8)
        val pool = Executors.newFixedThreadPool(9)
        try {
            val consumer = pool.submit<List<Fact>> {
                gate.await()
                val facts = ArrayList<Fact>()
                while (done.count > 0 || queue.depthItems > 0) {
                    val claim = queue.tryClaim(17, 1024)
                    if (claim == null) {
                        Thread.yield()
                        continue
                    }
                    try {
                        facts += claim.records.map { it.fact }
                        assertTrue(queue.depthItems in 0..MAX_ITEMS)
                        assertTrue(queue.depthBytes in 0..MAX_BYTES)
                    } finally {
                        claim.release()
                    }
                }
                facts
            }
            val jobs = (0 until 8).map { worker ->
                pool.submit {
                    gate.await()
                    try {
                        repeat(100) { index ->
                            val group = QueuedGroup((0..1).map { part ->
                                val seq = (worker * 200 + index * 2 + part + 1).toLong()
                                val fact = queueFact(seq, "critical").copy(name = "error.reported")
                                QueuedRecord(fact, "critical", seq, 16, 0)
                            })
                            assertEquals(QueueOffer.QUEUED, queue.tryOffer(group).offer)
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            gate.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            val facts = consumer.get(30, TimeUnit.SECONDS).filter { it.name == "error.reported" }
            assertEquals(1600, facts.size)
            assertEquals(1600, facts.map { it.event_id }.distinct().size)
            assertEquals(0, queue.depthItems)
            assertEquals(0, queue.depthBytes)
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `failure group evicts and claims while the producer lock is held`() {
        val queue = StabilityQueue(6, 600, 2, 100)
        repeat(4) { queue.tryOffer { record("diagnostic", 16, seq = it + 1L) } }
        repeat(2) { queue.tryOffer { record("critical", 16, seq = it + 1L) } }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val job = pool.submit {
                queue.tryWithProducerLock {
                    entered.countDown()
                    assertTrue(release.await(30, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            val group = QueuedGroup((1L..3L).map { seq ->
                QueuedRecord(queueFact(seq, "diagnostic").copy(name = "error.reported"), "diagnostic", seq, 16, 0)
            })
            val outcome = queue.tryOffer(group)
            assertEquals(QueueOffer.QUEUED, outcome.offer)
            assertEquals(3, outcome.evicted)
            val claim = assertNotNull(queue.tryClaim(1, 1))
            assertEquals(3, claim.records.size, "batch limits must not split a group")
            assertEquals(6, queue.depthItems, "claimed records remain reserved")
            claim.release()
            claim.release()
            assertEquals(3, queue.depthItems)
            assertEquals(48, queue.depthBytes)
            release.countDown()
            job.get(30, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

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
    fun `impossible group is refused without evicting diagnostics`() {
        val queue = StabilityQueue(MAX_ITEMS, MAX_BYTES, RESERVED_ITEMS, RESERVED_BYTES)
        repeat(100) { assertEquals(QueueOffer.QUEUED, queue.tryOffer({ record("diagnostic", 16, seq = it + 1L) }).offer) }

        val outcome = queue.tryOffer({ record("critical", bytes = MAX_BYTES + 1, seq = 1) })
        assertEquals(QueueOffer.FULL, outcome.offer)
        assertEquals(0, outcome.evictedDiagnostics)
        assertEquals(100, queue.depthItems)
        assertEquals(100, queue.diagnosticDepthItems)
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
        assertTrue(depth.diagnosticBytes <= MAX_BYTES, "failure diagnostics may use the full byte budget")
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
        val phases = facts.map { it.data["phase"] }
        assertTrue(phases.take(40).all { it == JsonPrimitive("end") }, "failure ends must be claimed first")
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
