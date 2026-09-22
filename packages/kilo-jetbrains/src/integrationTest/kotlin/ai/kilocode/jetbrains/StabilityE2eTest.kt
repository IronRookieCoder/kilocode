package ai.kilocode.jetbrains

import com.intellij.driver.sdk.getOpenProjects
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.getLastModifiedTime

/**
 * Stability collection end-to-end (docs/jetbrains-stability-design.md §14): the built plugin
 * runs in a real Starter-driven IDE whose `user.home` is an isolated temp dir, so the test can
 * publish the cs-cloud control file (§8) itself and observe the whole plugin-side chain:
 *
 *  - permit gating (fail-closed without a control file, activation on first permit,
 *    revocation ends the run without faking `plugin.shutdown`, re-permit starts a new run_id),
 *  - registration + outbox layout + producer.json (§5.2),
 *  - NDJSON wire format with all §6.1 common fields and closed value sets,
 *  - sealing `.open`→`.ready` and the graceful-close final drain (`plugin.shutdown`
 *    with end_kind=app_close, no `.open` left),
 *  - handover states: a `.ready` claimed under `exchange.lock` becomes `.claimed` and is
 *    never touched by the plugin again (§7.2), while new facts keep landing,
 *  - cross-process `writer.lock`: held by the live JVM writer (Java AND Go probes — §14.1
 *    requires real cross-language interop evidence, not two same-language unit suites),
 *    released after process death (§7.3).
 *
 * The wire-contract sets below are a deliberate independent copy of the design doc — this
 * source set does not compile against plugin modules, and a contract test must not import
 * the implementation it validates.
 */
class StabilityE2eTest : IntegrationTestBase() {

    // ------------------------------------------------------------------
    // Independent copy of the frozen wire contract (design §6.1/§9)
    // ------------------------------------------------------------------

    private val accountEpoch = "acct-e2e-01"

    private val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private val factFieldNames = setOf(
        "schema_version", "event_id", "timestamp", "producer_id", "run_id", "channel", "seq",
        "account_epoch", "policy_revision", "purposes", "source", "device_id", "plugin_version",
        "ide_product", "ide_build", "ide_build_major", "os_family", "arch", "env", "mode", "side",
        "connection_provider", "kind", "name", "context", "data",
    )

    private val channels = setOf("critical", "diagnostic")
    private val kinds = setOf("operation", "transition", "lifecycle", "interval", "diagnostic", "health", "sample")
    private val envs = setOf("prod", "dev", "test")
    private val modes = setOf("monolith", "split")
    private val sides = setOf("monolith", "frontend", "backend")
    private val providers = setOf("cs-cloud", "kilo-cli", "unknown")
    private val contextKeys = setOf("operation_id", "attempt_id", "fault_id", "trace_id", "workspace_id")
    private val purposeValues = setOf("metrics", "logs")
    private val phaseValues = setOf("start", "progress", "end")
    private val endKinds = setOf("app_close", "unload")

    /** All registered fact names (design §9 dictionary; METRICS_ONLY ∪ DUAL ∪ ERROR). */
    private val registeredNames = setOf(
        "rpc", "render.apply", "edt.delay", "resource.snapshot", "availability",
        "migration.required", "session.dispose_risk",
        "plugin.started", "plugin.shutdown", "plugin.unclean", "toolwindow.setup", "backend.load",
        "plugin.readiness", "connection", "connection.attempt", "connection.state_changed", "connection.recovery",
        "csc.install", "csc.start", "credentials.ready", "cli.download", "session.open", "session.restore",
        "action", "ide.operation", "telemetry.health", "protocol.error", "edt.violation",
        "error.uncaught", "error.reported",
    )

    private val producerJsonFields = setOf(
        "producer_id", "device_id", "plugin_version", "ide_product", "ide_build", "ide_build_major",
        "os_family", "arch", "mode", "side", "env", "connection_provider",
    )

    private val registrationFields = setOf(
        "schema_major", "outbox_path", "producer_id", "pid", "process_start", "created_at",
    )

    private val sealedSuffixes = setOf("ready", "claimed")

    private val json = Json

    // ------------------------------------------------------------------
    // Scenario 1: valid permit from cold start → collect, seal, hand over
    // ------------------------------------------------------------------

    @Test
    fun `valid permit from cold start collects seals and survives a consumer claim`() {
        writeControlFile(revision = 1, enabled = true)
        val launchMs = System.currentTimeMillis()

        lateinit var registration: Registration
        var claimedFile: Path? = null
        var claimedBytes: ByteArray? = null
        var factsBeforeClaim = 0
        var claimMs = 0L

        val result = runPluginIde("stabilityE2eCollection") {
            awaitColdStartReady()

            // —— §5.2 registration + outbox layout ——
            registration = awaitRegistration()
            val outbox = registration.outboxPath
            println("[e2e] registration after ${System.currentTimeMillis() - launchMs}ms: ${registration.producerId}")
            assertTrue(Files.isRegularFile(outbox.resolve("producer.json")), "producer.json must exist")
            assertTrue(Files.isRegularFile(outbox.resolve("writer.lock")), "writer.lock must exist")
            assertTrue(Files.isRegularFile(outbox.resolve("exchange.lock")), "exchange.lock must exist")

            // —— §7.2/§14.1 cross-process lock: the live JVM writer must hold writer.lock ——
            val writerLock = outbox.resolve("writer.lock")
            assertFalse(
                javaCanLockByteRange(writerLock),
                "another JVM process must NOT acquire writer.lock [0,1) while the IDE runs",
            )
            when (val verdict = goLockProbe(writerLock)) {
                null -> println("[e2e] go probe unavailable (toolchain missing); JVM↔JVM probe above still applies")
                "HELD_BY_PEER" -> println("[e2e] go probe: writer.lock HELD_BY_PEER (JVM↔Go interop confirmed)")
                else -> throw AssertionError("go probe expected HELD_BY_PEER against the live writer, got: $verdict")
            }

            // —— §7.1 sealing: first critical .ready within the 30s critical deadline budget ——
            val firstReady = awaitFirstSealed(outbox, timeoutMs = 75_000)
            val sealAgeMs = Files.getLastModifiedTime(firstReady).toMillis() - firstReadyTimestamp(firstReady)
            println("[e2e] first sealed segment: ${firstReady.fileName} (seal latency ≈ ${sealAgeMs}ms after its last record)")

            // —— §7.2 handover: claim the oldest .ready under exchange.lock ——
            val claimed = claimOldestReady(outbox)
                ?: throw AssertionError("a .ready segment must be claimable under exchange.lock")
            claimedFile = claimed
            claimedBytes = Files.readAllBytes(claimed)
            claimMs = System.currentTimeMillis()
            factsBeforeClaim = readFacts(segmentFiles(outbox, sealedSuffixes)).size
            println("[e2e] claimed ${claimed.fileName}; sealed facts so far: $factsBeforeClaim")

            // The plugin must keep collecting after the claim (§7.2: consumer handover never blocks the writer).
            Thread.sleep(40_000)
        }
        val closeMs = System.currentTimeMillis()

        val outbox = registration.outboxPath

        // —— §7.3 process death releases the lock (both language probes) ——
        assertTrue(javaCanLockByteRange(outbox.resolve("writer.lock")), "writer.lock must be free after IDE exit")
        when (val verdict = goLockProbe(outbox.resolve("writer.lock"))) {
            null -> println("[e2e] go probe skipped after close (toolchain missing)")
            "ACQUIRED" -> println("[e2e] go probe: writer.lock ACQUIRED after IDE exit (death releases the lock)")
            else -> throw AssertionError("go probe expected ACQUIRED after IDE exit, got: $verdict")
        }

        // —— §7.2 the plugin never touches .claimed: presence and content survive the whole run ——
        val claimed = claimedFile!!
        val claimedNow = Files.readAllBytes(claimed)
        assertTrue(Files.exists(claimed), ".claimed file must still exist after IDE close")
        assertTrue(claimedBytes!!.contentEquals(claimedNow), ".claimed content must be immutable for the plugin")

        // —— registration cross-check against the real IDE log dir (§5.2 outbox_path) ——
        val expectedRoot = result.runContext.logsDir
            .resolve("costrict-telemetry").resolve("v1").resolve(registration.producerId)
        assertEquals(
            expectedRoot.absolutePathString().lowercase(),
            outbox.absolutePathString().lowercase(),
            "registration outbox_path must be <ide-log-dir>/costrict-telemetry/v1/<producer-id>",
        )

        // —— graceful close drained and sealed everything (§7.1 final flush) ——
        assertTrue(
            segmentFiles(outbox, setOf("open")).isEmpty(),
            "no .open segment may remain after a graceful app close",
        )

        // —— full wire-format validation of every sealed/claimed fact ——
        val facts = readFacts(segmentFiles(outbox, sealedSuffixes))
        assertTrue(facts.size > factsBeforeClaim, "new facts must be recorded after the consumer claim")
        val failures = validateFacts(
            facts = facts,
            windowStartMs = launchMs - 10_000,
            windowEndMs = closeMs + 10_000,
            expectedEpoch = { it == accountEpoch },
            expectedRevision = { _ -> 1L },
        )
        assertTrue(failures.isEmpty(), "wire-format violations:\n${failures.joinToString("\n")}")

        // —— M22 lifecycle: exactly one plugin.started, graceful shutdown recorded ——
        assertEquals(1, facts.count { it.name == "plugin.started" }, "one plugin.started per run")
        val shutdowns = facts.filter { it.name == "plugin.shutdown" }
        assertEquals(1, shutdowns.size, "graceful app close must record exactly one plugin.shutdown")
        assertEquals(
            "app_close",
            shutdowns.single().obj["data"]!!.jsonObject["end_kind"]!!.jsonPrimitive.content,
            "app close must be reported as end_kind=app_close",
        )
        val maxTs = facts.maxOf { it.timestamp }
        assertTrue(
            shutdowns.single().timestamp >= maxTs - 2_000,
            "plugin.shutdown must be the (near-)last fact; was ${shutdowns.single().timestamp} vs max $maxTs",
        )
        assertTrue(
            facts.none { it.timestamp > claimMs && it.file == claimed },
            "no post-claim fact may appear inside the claimed segment (immutability)",
        )

        printEvidence("collection", facts, outbox)
    }

    // ------------------------------------------------------------------
    // Scenario 2: policy gates the run (fail-closed → activate → revoke → re-permit)
    // ------------------------------------------------------------------

    @Test
    fun `policy lifecycle gates collection fail-closed without faking shutdown`() {
        val launchMs = System.currentTimeMillis()
        lateinit var registration: Registration
        var revokeMs = 0L
        var runA: String? = null
        var runB: String? = null

        runPluginIde("stabilityE2ePolicy") {
            awaitColdStartReady()

            // —— §8 fail-closed: no control file → no run, no registration, no outbox files ——
            Thread.sleep(5_000)
            assertTrue(
                listJsonFiles(registrationsDir()).isEmpty(),
                "without a control file the plugin must not register a producer (fail closed)",
            )

            // —— §8 first permit activates within the 30s poll budget (measured) ——
            val publishedMs = System.currentTimeMillis()
            writeControlFile(revision = 1, enabled = true)
            registration = awaitRegistration()
            val outbox = registration.outboxPath
            println("[e2e] activation latency (publish→registration): ${System.currentTimeMillis() - publishedMs}ms")
            runA = awaitAnyRunPrefix(outbox, timeoutMs = 30_000)

            // Accumulate facts; the first critical seal fires at the 30s age deadline.
            Thread.sleep(45_000)

            // —— §8 revocation (common enabled=false) ends the run, seals everything, no fake shutdown ——
            revokeMs = System.currentTimeMillis()
            writeControlFile(revision = 2, enabled = false)
            awaitAllSealed(outbox, timeoutMs = 75_000)
            val sealedSnapshot = segmentFiles(outbox, sealedSuffixes)
                .map { it.fileName.toString() to it.getLastModifiedTime().toMillis() }
            Thread.sleep(12_000)
            val afterQuiet = segmentFiles(outbox, sealedSuffixes)
                .map { it.fileName.toString() to it.getLastModifiedTime().toMillis() }
            assertEquals(
                sealedSnapshot,
                afterQuiet,
                "a revoked run must not produce or modify any further segment files",
            )

            // —— §3 re-permit starts a NEW run_id (device_id/producer unchanged) ——
            writeControlFile(revision = 3, enabled = true)
            runB = awaitNewRunPrefix(outbox, knownRun = runA!!, timeoutMs = 75_000)
            println("[e2e] run rotation: $runA → $runB")
            Thread.sleep(15_000)
        }

        val outbox = registration.outboxPath
        assertTrue(
            segmentFiles(outbox, setOf("open")).isEmpty(),
            "no .open segment may remain after a graceful app close",
        )

        val facts = readFacts(segmentFiles(outbox, sealedSuffixes))
        val runAFacts = facts.filter { it.runId == runA }
        val runBFacts = facts.filter { it.runId == runB }
        assertTrue(runAFacts.isNotEmpty() && runBFacts.isNotEmpty(), "both runs must have produced facts")

        val failures = validateFacts(
            facts = facts,
            windowStartMs = launchMs - 10_000,
            windowEndMs = System.currentTimeMillis() + 10_000,
            expectedEpoch = { it == accountEpoch },
            expectedRevision = { fact -> if (fact.runId == runA) 1L else 3L },
        )
        assertTrue(failures.isEmpty(), "wire-format violations:\n${failures.joinToString("\n")}")

        assertEquals(1, runAFacts.count { it.name == "plugin.started" })
        assertEquals(
            0,
            runAFacts.count { it.name == "plugin.shutdown" },
            "revocation must end the run WITHOUT fabricating plugin.shutdown (end_kind is closed to app_close/unload)",
        )
        assertEquals(1, runBFacts.count { it.name == "plugin.started" })
        assertEquals(1, runBFacts.count { it.name == "plugin.shutdown" }, "run B is active at close → one app_close shutdown")

        // Facts of the revoked run stop within the poll budget (30s policy + 30s activation + grace).
        val lastRunAFact = runAFacts.maxOf { it.timestamp }
        assertTrue(
            lastRunAFact <= revokeMs + 65_000,
            "run A must stop collecting within the poll budget after revocation; last fact was " +
                "${lastRunAFact - revokeMs}ms after revoke",
        )

        // Both runs share one producer (same JVM instance) and one registration.
        assertEquals(1, listJsonFiles(registrationsDir()).size, "one producer registration for the whole instance")

        printEvidence("policy", facts, outbox)
    }

    // ------------------------------------------------------------------
    // Scenario 3: account epoch rotation (§8.1 / §14.1 row 9 — plugin half)
    // ------------------------------------------------------------------

    /**
     * The daemon-side epoch lifecycle is external, but every plugin-side duty of §8.1 is
     * observable here by rewriting the control file inside one live IDE session:
     *
     *  - direct epoch swap (the daemon's pending transition too brief for the 30s poll to
     *    observe): the run keeps its identity and keeps collecting, but no further OLD-epoch
     *    fact may land — queued-but-unwritten old-epoch facts are dropped at the write gate,
     *    never rebound to the new epoch;
     *  - pending: purposes empty → run ends WITHOUT fabricating plugin.shutdown;
     *  - ready under a brand-new epoch → a new run_id with exactly one plugin.started;
     *  - writing a RETIRED epoch back: fails closed forever (no revival, §8.1) — the run ends
     *    without a shutdown and nothing is collected under it.
     */
    @Test
    fun `epoch rotation retires the old account without rebinding or faking shutdown`() {
        val epoch1 = accountEpoch
        val epoch2 = "acct-e2e-02"
        val epoch3 = "acct-e2e-03"
        val launchMs = System.currentTimeMillis()
        lateinit var registration: Registration
        var swapMs = 0L
        var pendingMs = 0L
        var regressMs = 0L
        var runA: String? = null
        var runB: String? = null

        runPluginIde("stabilityE2eEpoch") {
            awaitColdStartReady()
            writeControlFile(revision = 1, enabled = true, epoch = epoch1)
            registration = awaitRegistration()
            val outbox = registration.outboxPath
            runA = awaitAnyRunPrefix(outbox, timeoutMs = 30_000)
            Thread.sleep(45_000) // epoch-1 facts accumulate; first critical seal fires

            // —— direct swap to a new valid epoch: run A survives, old epoch must go quiet ——
            swapMs = System.currentTimeMillis()
            writeControlFile(revision = 2, enabled = true, epoch = epoch2)
            Thread.sleep(65_000) // 30s poll + queue drain + margin
            val epoch1Ids = epochFactIds(outbox, epoch1)
            assertTrue(epoch1Ids.isNotEmpty(), "epoch-1 facts must exist before the swap")
            Thread.sleep(20_000)
            assertEquals(
                epoch1Ids,
                epochFactIds(outbox, epoch1),
                "no further $epoch1 fact may land once the new epoch was observed",
            )
            assertTrue(
                tolerantFacts(outbox).any { it.epoch == epoch2 && it.runId == runA },
                "the direct swap must keep run A collecting under the new epoch",
            )

            // —— pending ends the run without faking a shutdown (§8/§8.1) ——
            pendingMs = System.currentTimeMillis()
            writeControlFile(revision = 3, enabled = true, epoch = epoch2, accountState = "pending")
            Thread.sleep(40_000)

            // —— ready under a brand-new epoch starts a new run (§8.1 "新操作上下文") ——
            writeControlFile(revision = 4, enabled = true, epoch = epoch3)
            runB = awaitNewRunPrefix(outbox, knownRun = runA!!, timeoutMs = 75_000)
            Thread.sleep(15_000)

            // —— a retired epoch never revives: fail closed (§8.1) ——
            regressMs = System.currentTimeMillis()
            writeControlFile(revision = 5, enabled = true, epoch = epoch1)
            Thread.sleep(40_000)
        }

        val outbox = registration.outboxPath
        val facts = readFacts(segmentFiles(outbox, sealedSuffixes))
        val failures = validateFacts(
            facts = facts,
            windowStartMs = launchMs - 10_000,
            windowEndMs = System.currentTimeMillis() + 10_000,
            expectedEpoch = { it == epoch1 || it == epoch2 || it == epoch3 },
            expectedRevision = { fact ->
                when (fact.epoch) {
                    epoch1 -> 1L
                    epoch2 -> 2L
                    else -> 4L
                }
            },
            // §6.1 gaps signal loss: the write-gate drops (epoch guard at the swap, purposes
            // gate at pending/regression) intentionally consume seq — allowed, but every gap
            // must sit at a policy boundary (asserted right below), never mid-steady-state.
            seqGapsAllowed = { _ -> true },
        )
        assertTrue(failures.isEmpty(), "wire-format violations:\n${failures.joinToString("\n")}")
        assertSeqGapsAtPolicyBoundaries(facts, listOf(swapMs, pendingMs, regressMs))

        // No fact may carry the pending (rev 3) or regression (rev 5) revisions.
        assertTrue(facts.none { it.revision == 3L || it.revision == 5L }, "pending/regression revisions must collect nothing")

        // No rebinding inside run A: once a new-epoch fact was admitted, no old-epoch fact follows.
        val runAFacts = facts.filter { it.runId == runA }
        val lastEpoch1 = runAFacts.filter { it.epoch == epoch1 }.maxOfOrNull { it.timestamp }
        val firstEpoch2 = runAFacts.filter { it.epoch == epoch2 }.minOfOrNull { it.timestamp }
        if (lastEpoch1 != null && firstEpoch2 != null) {
            assertTrue(
                lastEpoch1 < firstEpoch2,
                "an $epoch1 fact ($lastEpoch1) was admitted after an $epoch2 fact ($firstEpoch2)",
            )
        }
        assertTrue(runAFacts.any { it.epoch == epoch2 }, "run A must span the direct swap (same run_id, new epoch)")
        assertEquals(1, runAFacts.count { it.name == "plugin.started" })
        assertEquals(0, runAFacts.count { it.name == "plugin.shutdown" }, "pending must end the run without faking shutdown")
        assertTrue(
            runAFacts.maxOf { it.timestamp } <= pendingMs + 65_000,
            "run A must stop within the poll budget after pending",
        )

        val runBFacts = facts.filter { it.runId == runB }
        assertTrue(runBFacts.isNotEmpty() && runBFacts.all { it.epoch == epoch3 && it.revision == 4L })
        assertEquals(1, runBFacts.count { it.name == "plugin.started" })
        assertEquals(
            0,
            runBFacts.count { it.name == "plugin.shutdown" },
            "retired-epoch regression fails closed → no shutdown for run B",
        )
        assertTrue(
            runBFacts.maxOf { it.timestamp } <= regressMs + 65_000,
            "run B must stop within the poll budget after the retired-epoch regression",
        )

        printEvidence("epoch", facts, outbox)
        preserveTelemetryHome("epoch")
    }

    // ------------------------------------------------------------------
    // Scenario 4: dead-producer residue sweep (§7.4 / §14.1 rows 4 & 10)
    // ------------------------------------------------------------------

    /**
     * Two sequential IDE launches: launch 1 collects under its Starter sandbox; launch 2 (no
     * control file — fail-closed collection) must still sweep launch 1's residue via its
     * startup retention sweep (§7.4 "插件后续实例启动时…即使采集禁用也执行残留清理",
     * §14.1 rows 4 & 10).
     *
     * Planting window: Starter wipes the per-test sandbox at every launch (verified — a
     * same-name relaunch keeps no earlier files), so pre-launch planting is impossible.
     * Instead a watcher thread plants the residue as soon as launch 2's `log/idea.log`
     * appears: the IDE process has started (wipe already happened) while the plugin's
     * startup sweep runs seconds later at plugin/service init — the planted tree is in
     * place before the sweep.
     *
     *  - expired .open/.ready of a verified-dead producer are swept; fresh ones stay (the
     *    un-expired .open is left for the consumer's rescue path, §7.3/§7.4);
     *  - .claimed is never touched by the plugin — not even for a dead producer;
     *  - a producer whose data is fully swept loses producer.json AND its registration, while
     *    its lock files stay (never unlink/recreate, §7.2);
     *  - the fail-closed launch registers no new producer and creates no telemetry of its own.
     *
     * The planted "dead producer" reuses launch 1's real (now exited) pid as ownership
     * evidence; every data line on disk is the plugin's real wire format.
     */
    @Test
    fun `dead producer residue is swept by a later instance even while collection is off`() {
        val evidenceRoot = Path.of("out", "stability-evidence", "residue").toAbsolutePath()
        evidenceRoot.toFile().deleteRecursively()

        writeControlFile(revision = 1, enabled = true)
        lateinit var registrationA: Registration
        runPluginIde("stabilityE2eResidueA") {
            awaitColdStartReady()
            registrationA = awaitRegistration()
            val outboxA = registrationA.outboxPath
            awaitFirstSealed(outboxA, timeoutMs = 75_000)
            Thread.sleep(60_000) // ≥2 sealed segments + a healthy backlog
        }

        // —— residue source: A's real outbox (A's IDE process is dead; its pid is evidence) ——
        val sourceA = registrationA.outboxPath
        val sealedA = segmentFiles(sourceA, setOf("ready")).sortedBy { it.fileName.toString() }
        assertTrue(sealedA.size >= 2, "launch 1 must leave ≥2 sealed segments, got ${sealedA.size}")
        // Sandbox root is NOT the test JVM's cwd: Starter puts it under the git repo root's
        // out/ide-tests. Derive launch 2's sandbox from launch 1's REAL location instead of
        // guessing the root: outbox = <sandbox>/log/costrict-telemetry/v1/<producer> → 4 up.
        val sandboxA = sourceA.parent?.parent?.parent?.parent
            ?: error("unexpected outbox layout: $sourceA")
        assertEquals("stabilityE2eResidueA", sandboxA.fileName.toString(), "derived sandbox A from the outbox path")
        val sandboxB = sandboxA.resolveSibling("stabilityE2eResidueB")

        var planted = java.util.concurrent.atomic.AtomicBoolean(false)
        var plantError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val v1B = sandboxB.resolve("log").resolve("costrict-telemetry").resolve("v1")
        // CRITICAL: remove the stale sandbox from any previous run — its leftover kilo.log
        // would trigger the watcher long before launch 2 even starts, and Starter's
        // launch-prep wipe of the sandbox would then destroy the planted tree (this exact
        // race produced three failed rounds; Starter wipes the whole per-test dir at launch).
        sandboxB.toFile().deleteRecursively()
        val launchBFrom = System.currentTimeMillis()
        val watcher = Thread {
            try {
                // Plant as soon as launch 2's OWN plugin log appears (fresh kilo.log, written
                // after launch start) — plugin init, past Starter's launch-prep wipe. The
                // sweep itself only starts when the tool window opens (first StabilityService
                // injection), which the main thread gates on the planted flag below:
                // deterministic order — launch wipe → plant → open tool window → startup sweep.
                val marker = sandboxB.resolve("log").resolve("kilo.log")
                // Starter prep (IDE copy) alone can take >2min under machine load; the IDE
                // process then needs ~15s more to write kilo.log — 12min covers both.
                val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(12)
                while (true) {
                    if (System.currentTimeMillis() > deadline) {
                        throw AssertionError("launch 2's kilo.log never appeared; cannot plant residue")
                    }
                    val fresh = Files.exists(marker) &&
                        Files.getLastModifiedTime(marker).toMillis() > launchBFrom
                    if (fresh) break
                    Thread.sleep(200)
                }
                Thread.sleep(300)
                plantResidue(sourceA, registrationA, v1B, sealedA)
                planted.set(true)
                println("[e2e] planted at ${java.time.LocalTime.now()}")
                // Probes + timestamps: pinpoint the vanish moment against idea.log's timeline.
                val probeEarly = sandboxB.resolve("log").resolve("probe-early.txt")
                Files.writeString(probeEarly, "early\n")
                var snapshot = treeNames(v1B)
                val plantMs = System.currentTimeMillis()
                val until = plantMs + TimeUnit.SECONDS.toMillis(120)
                var probeLateDone = false
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(500)
                    val now = treeNames(v1B)
                    if (now != snapshot) {
                        println(
                            "[e2e] ${java.time.LocalTime.now()} planted tree changed: ${snapshot.size} -> ${now.size} entries;" +
                                " removed=${snapshot - now.toSet()}",
                        )
                        snapshot = now
                        if (now.isEmpty()) break
                    }
                    if (!probeLateDone && System.currentTimeMillis() - plantMs > 20_000) {
                        Files.writeString(sandboxB.resolve("log").resolve("probe-late.txt"), "late\n")
                        probeLateDone = true
                    }
                }
            } catch (t: Throwable) {
                plantError.set(t)
            }
        }
        watcher.isDaemon = true
        watcher.start()

        // —— launch 2 with NO control file: fail-closed collection, sweep still runs (§7.4) ——
        Files.delete(controlFile())
        runPluginIde("stabilityE2eResidueB") {
            // Wait for the project WITHOUT opening the tool window yet (openCostrictToolWindow
            // is the sweep trigger — see the watcher comment).
            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            while (runCatching { getOpenProjects() }.getOrNull().isNullOrEmpty()) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("Fixture project never opened within ${READY_TIMEOUT_MS}ms")
                Thread.sleep(500)
            }
            // Matches the watcher's 12min plant deadline; the driver block opens the tool
            // window only after the planted flag, keeping the sweep ordering deterministic.
            val plantDeadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(12)
            while (!planted.get() && System.currentTimeMillis() < plantDeadline) Thread.sleep(250)
            plantError.get()?.let { throw it }
            assertTrue(planted.get(), "residue must be planted before the tool window (sweep trigger) opens")
            // Now trigger the plugin's startup sweep by opening the tool window.
            openCostrictToolWindow()
            val healthBefore = daemon.requests.count { it.path == "/api/v1/runtime/health" }
            val sseBefore = daemon.requests.count { it.path == "/api/v1/events" }
            daemon.awaitNewRequest("GET", "/api/v1/runtime/health", healthBefore, READY_TIMEOUT_MS)
            daemon.awaitNewRequest("GET", "/api/v1/events", sseBefore, READY_TIMEOUT_MS)
            Thread.sleep(20_000) // margin well beyond the startup sweep
        }
        watcher.join(TimeUnit.SECONDS.toMillis(2))
        plantError.get()?.let { throw it }

        val outboxA = v1B.resolve(registrationA.producerId)
        val outboxD = v1B.resolve("pr-deadresidue01")
        // Diagnostic before assertions: if the tree vanished wholesale, the sweep never saw it.
        if (Files.isDirectory(v1B)) {
            val tree = Files.walk(v1B).use { stream -> stream.map { it.fileName.toString() }.toList() }
            println("[e2e] v1 tree after launch 2 (${tree.size} entries): $tree")
        } else {
            println("[e2e] v1 tree after launch 2: ENTIRE $v1B MISSING (planted tree was wiped, not swept)")
        }
        val expiredReadyA = outboxA.resolve("critical").resolve(sealedA.first().fileName.toString())
        val freshReadyA = outboxA.resolve("critical").resolve(sealedA.last().fileName.toString())

        // —— expired data swept; fresh data and .claimed untouched ——
        assertFalse(Files.exists(expiredReadyA), "expired .ready of the dead producer must be swept")
        assertTrue(Files.exists(freshReadyA), "fresh .ready must survive within the retention window")
        assertTrue(
            Files.exists(outboxA.resolve("critical").resolve("residue-fresh-0.open")),
            "un-expired .open of a dead source must await consumer rescue",
        )
        val claimedBytes = Files.readAllBytes(sealedA.last())
        assertTrue(
            Files.exists(outboxA.resolve("critical").resolve("residue-claimed-1.claimed")) &&
                Files.readAllBytes(outboxA.resolve("critical").resolve("residue-claimed-1.claimed"))
                    .contentEquals(claimedBytes),
            ".claimed must never be touched by the plugin (dead producer included)",
        )

        // —— fully swept producer loses metadata + registration; lock files stay ——
        assertFalse(Files.exists(outboxD.resolve("critical").resolve("rundead-0000-0.open")), "expired .open of producer D must be swept")
        assertFalse(Files.exists(outboxD.resolve("diagnostic").resolve("rundead-0000-1.ready")), "expired .ready of producer D must be swept")
        assertFalse(Files.exists(outboxD.resolve("producer.json")), "D's producer.json must go once its data is empty")
        assertFalse(Files.exists(registrationsDir().resolve("pr-deadresidue01.json")), "D's registration must go with it")
        assertTrue(Files.exists(outboxD.resolve("writer.lock")), "lock files are never removed")
        assertTrue(Files.exists(outboxD.resolve("exchange.lock")), "lock files are never removed")

        // —— producer A keeps metadata while data remains; launch 2 registered nothing ——
        assertTrue(Files.exists(outboxA.resolve("producer.json")), "A keeps metadata while data files remain")
        assertEquals(
            listOf("${registrationA.producerId}.json"),
            listJsonFiles(registrationsDir()).map { it.fileName.toString() }.sorted(),
            "exactly A's registration remains: D swept, launch 2 (fail closed) registered none",
        )
        val producerDirs = Files.list(v1B).use { files ->
            files.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(
            listOf("pr-deadresidue01", registrationA.producerId).sorted(),
            producerDirs,
            "no new producer dir from the fail-closed launch; D's root keeps only its lock files",
        )

        // —— keep the final tree for manual inspection (home side via preserveTelemetryHome) ——
        Files.createDirectories(evidenceRoot.resolve("log-root"))
        copyTree(v1B.parent, evidenceRoot.resolve("log-root"))
        preserveTelemetryHome("residue")
        println("[e2e] residue evidence kept at $evidenceRoot (log-root + telemetry-home)")
    }

    /**
     * Plants the residue matrix into [v1B] (launch 2's future sweep scope): producer A's real
     * outbox (one segment backdated past the 24h retention, plus fresh .open and a .claimed
     * copy) and the fully-expired synthetic producer `pr-deadresidue01` with matching
     * registration entries. Registration pointers are rewritten to the new v1 root.
     */
    private fun plantResidue(sourceA: Path, registrationA: Registration, v1B: Path, sealedA: List<Path>) {
        val outboxA = Files.createDirectories(v1B.resolve(registrationA.producerId))
        copyTree(sourceA, outboxA)

        // registration pointer follows the move (sweep requires registration.outbox_path == dir)
        Files.writeString(
            registrationsDir().resolve("${registrationA.producerId}.json"),
            """
            {
              "schema_major": 1,
              "outbox_path": "${outboxA.toString().replace("\\", "\\\\")}",
              "producer_id": "${registrationA.producerId}",
              "pid": ${registrationA.pid},
              "process_start": ${registrationA.processStart},
              "created_at": ${registrationA.createdAt}
            }
            """.trimIndent() + "\n",
        )

        backdate(outboxA.resolve("critical").resolve(sealedA.first().fileName.toString())) // 25h old → beyond the 24h retention

        // fresh .open of a dead source: kept, left to the consumer rescue path (§7.4)
        Files.writeString(outboxA.resolve("critical").resolve("residue-fresh-0.open"), firstLineOf(sealedA.last()) + "\n")

        // .claimed is daemon property: planted from a sealed copy, must never be touched
        Files.write(outboxA.resolve("critical").resolve("residue-claimed-1.claimed"), Files.readAllBytes(sealedA.last()))

        // fully-expired synthetic producer D (same dead pid as ownership evidence)
        val deadId = "pr-deadresidue01"
        val outboxD = Files.createDirectories(v1B.resolve(deadId).resolve("critical"))
            .parent.resolve("diagnostic")
        Files.createDirectories(outboxD)
        val deadRoot = outboxD.parent
        Files.writeString(
            deadRoot.resolve("producer.json"),
            """
            {
              "producer_id": "$deadId",
              "pid": ${registrationA.pid},
              "process_start": ${registrationA.processStart},
              "plugin_version": "1.0.0-rc.1",
              "ide_product": "IU"
            }
            """.trimIndent() + "\n",
        )
        val dOpen = deadRoot.resolve("critical").resolve("rundead-0000-0.open")
        val dReady = deadRoot.resolve("diagnostic").resolve("rundead-0000-1.ready")
        Files.writeString(dOpen, firstLineOf(sealedA.last()) + "\n")
        Files.writeString(dReady, firstLineOf(sealedA.last()) + "\n")
        backdate(dOpen)
        backdate(dReady)
        Files.createFile(deadRoot.resolve("writer.lock"))
        Files.createFile(deadRoot.resolve("exchange.lock"))
        Files.writeString(
            registrationsDir().resolve("$deadId.json"),
            """
            {
              "schema_major": 1,
              "outbox_path": "${deadRoot.toString().replace("\\", "\\\\")}",
              "producer_id": "$deadId",
              "pid": ${registrationA.pid},
              "process_start": ${registrationA.processStart},
              "created_at": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n",
        )
    }

    // ------------------------------------------------------------------
    // Control file (§8 wire fields; closed set, additionalProperties=false)
    // ------------------------------------------------------------------

    private fun controlFile(): Path = cloud.parent.resolve("telemetry").resolve("control").resolve("jetbrains.json")

    private fun registrationsDir(): Path = cloud.parent.resolve("telemetry").resolve("registrations")

    private fun writeControlFile(
        revision: Long,
        enabled: Boolean,
        epoch: String = accountEpoch,
        accountState: String = "ready",
    ) {
        val expiresAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)
        Files.createDirectories(controlFile().parent)
        Files.writeString(
            controlFile(),
            """
            {
              "schema_major": 1,
              "revision": $revision,
              "enabled": $enabled,
              "metrics_enabled": true,
              "metrics_expires_at": $expiresAt,
              "logs_enabled": true,
              "logs_expires_at": $expiresAt,
              "account_epoch": "$epoch",
              "account_state": "$accountState",
              "expires_at": $expiresAt,
              "metrics_allowed_categories": ["critical", "diagnostic"],
              "logs_allowed_categories": ["critical", "diagnostic"],
              "log_detail_rate_limit": {"per_fingerprint_max_per_minute": 3}
            }
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------------
    // Registration & producer metadata (§5.2)
    // ------------------------------------------------------------------

    private data class Registration(
        val producerId: String,
        val outboxPath: Path,
        val pid: Long,
        val processStart: Long,
        val createdAt: Long,
    )

    private fun awaitRegistration(timeoutMs: Long = 75_000): Registration {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val file = listJsonFiles(registrationsDir()).firstOrNull()
            if (file != null) {
                val root = json.parseToJsonElement(Files.readString(file)).jsonObject
                assertTrue(
                    root.keys.containsAll(registrationFields) && root.keys.all { it in registrationFields },
                    "registration field set must be exactly ${registrationFields.sorted()}; got ${root.keys.sorted()}",
                )
                val outboxPath = Path.of(root["outbox_path"]!!.jsonPrimitive.content)
                // producer.json closed-set check while we are at it
                val producerJson = json.parseToJsonElement(Files.readString(outboxPath.resolve("producer.json"))).jsonObject
                assertTrue(
                    producerJson.keys.containsAll(producerJsonFields) && producerJson.keys.all { it in producerJsonFields },
                    "producer.json field set must be exactly ${producerJsonFields.sorted()}; got ${producerJson.keys.sorted()}",
                )
                assertEquals("IU", producerJson["ide_product"]?.jsonPrimitive?.content, "Starter launches IntelliJ IDEA Ultimate")
                assertEquals("windows", producerJson["os_family"]?.jsonPrimitive?.content)
                assertEquals("monolith", producerJson["mode"]?.jsonPrimitive?.content, "Starter full IDE is monolith")
                assertEquals("monolith", producerJson["side"]?.jsonPrimitive?.content)
                assertTrue(
                    producerJson["env"]?.jsonPrimitive?.content?.let { it in envs } == true,
                    "producer.json env must be one of $envs",
                )
                assertTrue(
                    producerJson["plugin_version"]?.jsonPrimitive?.content?.let { it.isNotEmpty() && it != "unknown" } == true,
                    "plugin_version must be a real version, got ${producerJson["plugin_version"]}",
                )
                return Registration(
                    producerId = root["producer_id"]!!.jsonPrimitive.content,
                    outboxPath = outboxPath,
                    pid = root["pid"]!!.jsonPrimitive.longOrNull ?: 0L,
                    processStart = root["process_start"]!!.jsonPrimitive.longOrNull ?: 0L,
                    createdAt = root["created_at"]!!.jsonPrimitive.longOrNull ?: 0L,
                )
            }
            Thread.sleep(1_000)
        }
        throw AssertionError("no producer registration appeared within ${timeoutMs}ms of a valid control file")
    }

    private fun listJsonFiles(dir: Path): List<Path> =
        if (Files.isDirectory(dir)) {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.toList()
            }
        } else {
            emptyList()
        }

    // ------------------------------------------------------------------
    // Segment files & facts (§6.1/§7.1)
    // ------------------------------------------------------------------

    private fun segmentFiles(outbox: Path, suffixes: Set<String> = setOf("ready", "claimed", "open")): List<Path> =
        channels.flatMap { channel ->
            val dir = outbox.resolve(channel)
            if (Files.isDirectory(dir)) {
                Files.list(dir).use { stream ->
                    stream.filter { file ->
                        Files.isRegularFile(file) && suffixes.any { file.fileName.toString().endsWith(it) }
                    }.toList()
                }
            } else {
                emptyList()
            }
        }

    private data class FactLine(
        val file: Path,
        val lineNo: Int,
        val obj: JsonObject,
        val eventId: String,
        val timestamp: Long,
        val runId: String,
        val channel: String,
        val seq: Long,
        val name: String,
        val deviceId: String,
        val epoch: String,
        val revision: Long,
    )

    private fun readFacts(files: List<Path>): List<FactLine> {
        val facts = mutableListOf<FactLine>()
        for (file in files) {
            val bytes = Files.readAllBytes(file)
            assertFalse(
                bytes.size > 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte(),
                "${file.fileName} must not start with a UTF-8 BOM",
            )
            assertTrue(bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte(), "${file.fileName} must end with LF")
            val text = bytes.toString(Charsets.UTF_8)
            assertFalse('\r' in text, "${file.fileName} must not contain CR bytes")
            text.trimEnd('\n').split('\n').forEachIndexed { index, line ->
                if (line.toByteArray(Charsets.UTF_8).size > 32 * 1024) {
                    throw AssertionError("${file.fileName}:${index + 1} exceeds the 32KiB record limit")
                }
                val obj = json.parseToJsonElement(line) as? JsonObject
                    ?: error("${file.fileName}:${index + 1} is not a JSON object")
                facts += FactLine(
                    file = file,
                    lineNo = index + 1,
                    obj = obj,
                    eventId = requiredString(obj, "event_id", file, index),
                    timestamp = requiredLong(obj, "timestamp", file, index),
                    runId = requiredString(obj, "run_id", file, index),
                    channel = requiredString(obj, "channel", file, index),
                    seq = requiredLong(obj, "seq", file, index),
                    name = requiredString(obj, "name", file, index),
                    deviceId = requiredString(obj, "device_id", file, index),
                    epoch = requiredString(obj, "account_epoch", file, index),
                    revision = requiredLong(obj, "policy_revision", file, index),
                )
            }
        }
        return facts
    }

    private fun requiredString(obj: JsonObject, key: String, file: Path, line: Int): String =
        obj[key]?.jsonPrimitive?.content ?: error("${file.fileName}:${line + 1} missing $key")

    private fun requiredLong(obj: JsonObject, key: String, file: Path, line: Int): Long =
        obj[key]?.jsonPrimitive?.longOrNull ?: error("${file.fileName}:${line + 1} missing $key")

    /** Validates every §6.1 invariant; returns violation messages (empty = clean). */
    private fun validateFacts(
        facts: List<FactLine>,
        windowStartMs: Long,
        windowEndMs: Long,
        expectedEpoch: (String) -> Boolean,
        expectedRevision: (FactLine) -> Long,
        /** Per-runId: seq gaps allowed (§6.1 gaps signal loss — policy-drop scenarios opt in). */
        seqGapsAllowed: (String) -> Boolean = { _ -> false },
    ): List<String> {
        val failures = mutableListOf<String>()
        fun fail(message: String) {
            failures += message
        }

        val producerIds = facts.map { it.obj["producer_id"]?.jsonPrimitive?.content }.toSet()
        if (producerIds.size != 1) fail("expected a single producer_id, got $producerIds")
        val deviceIds = facts.map { it.deviceId }.toSet()
        if (deviceIds.size != 1) fail("device_id must be stable across a run, got $deviceIds")

        val eventIds = mutableListOf<String>()
        val seqsByRunChannel = mutableMapOf<Pair<String, String>, MutableList<Long>>()
        for (fact in facts) {
            val where = "${fact.file.fileName}:${fact.lineNo} (${fact.name})"
            val obj = fact.obj

            if (obj.keys.any { it !in factFieldNames }) fail("$where: unknown field(s) ${obj.keys - factFieldNames}")
            val required = factFieldNames - "context"
            if (!obj.keys.containsAll(required)) fail("$where: missing field(s) ${required - obj.keys}")

            if (obj["schema_version"]?.jsonPrimitive?.content != "1.0") fail("$where: schema_version must be 1.0")
            if (!uuidRegex.matches(fact.eventId)) fail("$where: event_id is not a UUID: ${fact.eventId}")
            if (fact.timestamp < windowStartMs || fact.timestamp > windowEndMs) {
                fail("$where: timestamp ${fact.timestamp} outside the test window")
            }
            if (fact.channel !in channels) fail("$where: channel '${fact.channel}' not in $channels")
            if (fact.file.parent.fileName.toString() != fact.channel) {
                fail("$where: channel field '${fact.channel}' disagrees with directory ${fact.file.parent.fileName}")
            }
            if (fact.seq < 1) fail("$where: seq must start at 1")
            if (!expectedEpoch(fact.epoch)) {
                fail("$where: account_epoch ${fact.epoch} is not in the expected set")
            }
            if (fact.revision != expectedRevision(fact)) {
                fail("$where: policy_revision ${fact.revision} != expected ${expectedRevision(fact)}")
            }
            val purposes = (obj["purposes"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            if (purposes.isEmpty() || purposes.any { it !in purposeValues }) {
                fail("$where: purposes must be a non-empty subset of $purposeValues, got $purposes")
            }
            if (obj["source"]?.jsonPrimitive?.content != "jetbrains-plugin") fail("$where: source must be jetbrains-plugin")
            listOf("plugin_version", "ide_product", "ide_build", "ide_build_major", "os_family", "arch").forEach { key ->
                val value = obj[key]?.jsonPrimitive?.content
                if (value.isNullOrEmpty() || value == "unknown") fail("$where: $key must be a real value, got '$value'")
            }
            val env = obj["env"]?.jsonPrimitive?.content
            if (env == null || env !in envs) fail("$where: env '$env' not in $envs")
            val mode = obj["mode"]?.jsonPrimitive?.content
            if (mode == null || mode !in modes) fail("$where: mode '$mode' not in $modes")
            val side = obj["side"]?.jsonPrimitive?.content
            if (side == null || side !in sides) fail("$where: side '$side' not in $sides")
            val provider = obj["connection_provider"]?.jsonPrimitive?.content
            if (provider == null || provider !in providers) fail("$where: connection_provider '$provider' not in $providers")
            val kind = obj["kind"]?.jsonPrimitive?.content
            if (kind == null || kind !in kinds) fail("$where: kind '$kind' not in $kinds")
            if (fact.name !in registeredNames) fail("$where: name '${fact.name}' is not registered in the dictionary")

            obj["context"]?.let { context ->
                val contextObj = context as? JsonObject
                if (contextObj == null) {
                    fail("$where: context must be an object")
                } else if (contextObj.keys.any { it !in contextKeys }) {
                    fail("$where: context key(s) outside ${contextKeys.sorted()}: ${contextObj.keys}")
                }
            }
            val data = obj["data"] as? JsonObject
            if (data == null) {
                fail("$where: data must be an object")
            } else {
                if (fact.name == "plugin.shutdown") {
                    val endKind = data["end_kind"]?.jsonPrimitive?.content
                    if (endKind !in endKinds) fail("$where: end_kind '$endKind' not in $endKinds")
                }
                if (kind == "operation") {
                    data["phase"]?.jsonPrimitive?.content?.let { phase ->
                        if (phase !in phaseValues) fail("$where: phase '$phase' not in $phaseValues")
                    }
                }
            }

            eventIds += fact.eventId
            seqsByRunChannel.getOrPut(fact.runId to fact.channel) { mutableListOf() } += fact.seq
        }

        val duplicates = eventIds.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) fail("event_id values must be unique; duplicates: $duplicates")

        seqsByRunChannel.forEach { (runChannel, seqs) ->
            val (runId, _) = runChannel
            val sorted = seqs.sorted()
            if (sorted.first() != 1L) fail("$runChannel: seq must start at 1, got ${sorted.first()}")
            if (seqs.toSet().size != seqs.size) fail("$runChannel: duplicate seq values")
            val gaps = sorted.zipWithNext().filter { (a, b) -> b != a + 1 }
            if (gaps.isNotEmpty() && !seqGapsAllowed(runId)) {
                fail("$runChannel: seq gaps (drops) at $gaps — full list $sorted")
            }
        }
        return failures
    }

    // ------------------------------------------------------------------
    // Mid-run tolerant scan (epoch-quiet checks read .open while the writer may append)
    // ------------------------------------------------------------------

    private data class TolerantFact(val eventId: String, val runId: String, val epoch: String)

    /**
     * Tolerant mid-run scan: unlike [readFacts] (strict, sealed files only) this also reads
     * `.open` segments while the live writer may be mid-append — a trailing partial line (no
     * LF yet) is dropped and per-line parse failures are skipped instead of failing the scan.
     * Only used for in-run quiet/monotonicity probes; final validation stays strict.
     */
    private fun tolerantFacts(outbox: Path): List<TolerantFact> =
        segmentFiles(outbox).flatMap { file ->
            val bytes = Files.readAllBytes(file)
            if (bytes.isEmpty()) return@flatMap emptyList()
            val text = bytes.toString(Charsets.UTF_8)
            val complete = if (text.endsWith("\n")) text else text.substringBeforeLast('\n', "")
            complete.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching {
                        val obj = json.parseToJsonElement(line) as? JsonObject ?: return@mapNotNull null
                        TolerantFact(
                            eventId = obj["event_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            runId = obj["run_id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            epoch = obj["account_epoch"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        )
                    }.getOrNull()
                }
                .toList()
        }

    private fun epochFactIds(outbox: Path, epoch: String): Set<String> =
        tolerantFacts(outbox).filter { it.epoch == epoch }.map { it.eventId }.toSet()

    /** Relative-path snapshot of a tree for change detection in the residue watcher. */
    private fun treeNames(root: Path): Set<String> =
        if (!Files.isDirectory(root)) {
            emptySet()
        } else {
            Files.walk(root).use { stream -> stream.map { root.relativize(it).toString() }.toList().toSet() }
        }

    /**
     * Every seq gap must be a policy-drop: the dropped record's seq was allocated between its
     * surviving neighbours, so at least one policy write time must lie within
     * [neighbourBefore, neighbourAfter] (± the 65s observation/drain budget). A gap with no
     * policy transition between its neighbours is an unexplained loss and fails the scenario.
     */
    private fun assertSeqGapsAtPolicyBoundaries(facts: List<FactLine>, policyWriteMs: List<Long>) {
        val budgetMs = 65_000L
        facts.groupBy { it.runId to it.channel }.forEach { (runChannel, runFacts) ->
            val bySeq = runFacts.associateBy { it.seq }
            val maxSeq = runFacts.maxOf { it.seq }
            (1L..maxSeq).filter { it !in bySeq }.forEach { missing ->
                val before = bySeq[missing - 1]
                val after = runFacts.filter { it.seq > missing }.minByOrNull { it.seq }
                val windowStart = (before?.timestamp ?: 0L) - 10_000
                val windowEnd = (after?.timestamp ?: Long.MAX_VALUE) + budgetMs
                val explained = policyWriteMs.any { write -> write in windowStart..windowEnd }
                assertTrue(
                    explained,
                    "$runChannel: unexplained seq gap at $missing (no policy write between " +
                        "seq ${missing - 1}@${before?.timestamp} and the next survivor) — loss outside a policy drop",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Residue planting & evidence retention (§7.4 / §14.1 row 10)
    // ------------------------------------------------------------------

    /** Backdates a file's mtime past the 24h retention boundary (25h by default). */
    private fun backdate(path: Path, hoursAgo: Long = 25) {
        Files.setLastModifiedTime(
            path,
            java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - TimeUnit.HOURS.toMillis(hoursAgo),
            ),
        )
    }

    /** First non-blank line of a sealed file — a real wire-format record for planting residue. */
    private fun firstLineOf(file: Path): String =
        Files.readString(file).lineSequence().first { it.isNotBlank() }

    /** Recursive tree copy (evidence retention); overwrites nothing, creates parents as needed. */
    private fun copyTree(source: Path, dest: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { entry ->
                val target = dest.resolve(source.relativize(entry).toString())
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(entry, target)
                }
            }
        }
    }

    /**
     * Keeps the home-side telemetry evidence (registrations + control file) under
     * `out/stability-evidence/<scenario>/` — the outboxes themselves already survive under
     * the Starter sandbox / the pinned shared log root; the home is wiped by tearDown, so it
     * needs an explicit copy for manual inspection.
     */
    private fun preserveTelemetryHome(scenario: String) {
        val source = cloud.parent.resolve("telemetry")
        if (!Files.isDirectory(source)) return
        val dest = Path.of("out", "stability-evidence", scenario, "telemetry-home").toAbsolutePath()
        dest.toFile().deleteRecursively()
        Files.createDirectories(dest)
        copyTree(source, dest)
        println("[e2e] telemetry home evidence kept at $dest")
    }

    // ------------------------------------------------------------------
    // Sealing / run-prefix polls (§7.1)
    // ------------------------------------------------------------------

    private fun awaitFirstSealed(outbox: Path, timeoutMs: Long): Path {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            segmentFiles(outbox, setOf("ready")).firstOrNull()?.let { return it }
            Thread.sleep(1_000)
        }
        throw AssertionError("no .ready segment appeared within ${timeoutMs}ms (critical seal deadline is 30s)")
    }

    private fun awaitAnyRunPrefix(outbox: Path, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            segmentFiles(outbox).firstOrNull()?.let { return runPrefix(it) }
            Thread.sleep(1_000)
        }
        throw AssertionError("no segment file appeared within ${timeoutMs}ms of activation")
    }

    private fun awaitNewRunPrefix(outbox: Path, knownRun: String, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            segmentFiles(outbox).map { runPrefix(it) }.firstOrNull { it != knownRun }?.let { return it }
            Thread.sleep(1_000)
        }
        throw AssertionError("no new run appeared within ${timeoutMs}ms of re-permit")
    }

    private fun awaitAllSealed(outbox: Path, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (segmentFiles(outbox, setOf("open")).isEmpty()) return
            Thread.sleep(2_000)
        }
        throw AssertionError(".open segments still present ${timeoutMs}ms after revocation (writer close must seal)")
    }

    /** `run-xxxxxxxxxxxx-N.open` → `run-xxxxxxxxxxxx` (§7.1 segment file naming). */
    private fun runPrefix(file: Path): String = file.fileName.toString().substringBeforeLast('-')

    private fun firstReadyTimestamp(file: Path): Long = readFacts(listOf(file)).maxOf { it.timestamp }

    // ------------------------------------------------------------------
    // Consumer-side claim (§7.2) & cross-process lock probes (§14.1)
    // ------------------------------------------------------------------

    /** Claims the oldest .ready (rename → .claimed) while holding exchange.lock, consumer-style. */
    private fun claimOldestReady(outbox: Path, timeoutMs: Long = 10_000): Path? {
        val exchange = outbox.resolve("exchange.lock")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            FileChannel.open(exchange, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock()
                if (lock != null) {
                    try {
                        val ready = segmentFiles(outbox, setOf("ready")).minByOrNull { it.getLastModifiedTime().toMillis() }
                            ?: return null
                        val claimed = ready.resolveSibling(ready.fileName.toString().removeSuffix(".ready") + ".claimed")
                        Files.move(ready, claimed, StandardCopyOption.ATOMIC_MOVE)
                        return claimed
                    } finally {
                        lock.release()
                    }
                }
            }
            Thread.sleep(500)
        }
        throw AssertionError("exchange.lock never became acquirable within ${timeoutMs}ms")
    }

    /** True when this process can take the exclusive [0,1) lock (i.e. NO live peer holds it). */
    private fun javaCanLockByteRange(lockFile: Path): Boolean =
        FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.tryLock(0, 1, false)?.let { lock ->
                lock.release()
                true
            } ?: false
        }

    /**
     * Runs the Go lock probe (`src/integrationTest/go/lockprobe/main.go`) against [lockFile].
     * Returns ACQUIRED / HELD_BY_PEER, or null when Go or the probe source is unavailable
     * (the Java probe still covers JVM↔JVM interop in that case).
     *
     * The probe is built once to `build/lockprobe/` and the binary executed directly —
     * `go run` rewrites the child's exit status (2 → 1 + "exit status 2" on stderr), which
     * would make the probe's verdict codes indistinguishable from toolchain failures.
     */
    private var probeBinary: Path? = null

    private fun goLockProbe(lockFile: Path): String? {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return null
        val source = findProbeSource() ?: run {
            println("[e2e] go probe source not found; skipping Go interop check")
            return null
        }
        val binary = probeBinary ?: buildProbe(source).also { probeBinary = it }
        val process = ProcessBuilder(binary.absolutePathString(), lockFile.absolutePathString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("go lock probe timed out")
        }
        // Probe contract: 0 = ACQUIRED, 2 = HELD_BY_PEER, anything else is a probe/toolchain fault.
        if (process.exitValue() != 0 && process.exitValue() != 2) {
            throw AssertionError("go lock probe failed (exit ${process.exitValue()}): $output")
        }
        return output.lineSequence().firstOrNull { it == "ACQUIRED" || it == "HELD_BY_PEER" }
            ?: throw AssertionError("go lock probe printed no verdict: $output")
    }

    private fun buildProbe(source: Path): Path {
        val target = Files.createDirectories(Path.of("build").resolve("lockprobe")).resolve("lockprobe.exe")
        val build = ProcessBuilder("go", "build", "-o", target.absolutePathString(), source.absolutePathString())
            .redirectErrorStream(true)
            .start()
        val output = build.inputStream.bufferedReader().readText().trim()
        if (!build.waitFor(180, TimeUnit.SECONDS)) {
            build.destroyForcibly()
            throw AssertionError("go build of the lock probe timed out")
        }
        if (build.exitValue() != 0) {
            throw AssertionError("go build of the lock probe failed (exit ${build.exitValue()}): $output")
        }
        return target
    }

    private fun findProbeSource(): Path? {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            val current = dir ?: return@repeat
            val candidate = current.resolve("src").resolve("integrationTest").resolve("go")
                .resolve("lockprobe").resolve("main.go")
            if (Files.isRegularFile(candidate)) return candidate
            dir = current.parent
        }
        return null
    }

    // ------------------------------------------------------------------
    // Evidence
    // ------------------------------------------------------------------

    private fun printEvidence(scenario: String, facts: List<FactLine>, outbox: Path) {
        val histogram = facts.groupBy { it.name }.mapValues { it.value.size }.toSortedMap()
        println(
            "[e2e] $scenario evidence: ${facts.size} facts, ${facts.map { it.runId }.distinct().size} run(s), " +
                "channels=${facts.groupBy { it.channel }.mapValues { it.value.size }}",
        )
        histogram.forEach { (name, count) -> println("[e2e]   $name × $count") }
        println("[e2e] outbox: $outbox")
        println(
            "[e2e] files: " +
                segmentFiles(outbox).sortedBy { it.fileName.toString() }.joinToString { it.fileName.toString() },
        )
    }
}
