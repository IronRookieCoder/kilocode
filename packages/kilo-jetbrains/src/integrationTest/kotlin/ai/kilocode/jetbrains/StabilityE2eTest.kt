package ai.kilocode.jetbrains

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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Stability collection end-to-end against the append protocol (docs/jetbrains-stability-design.md):
 * the built plugin runs in a real Starter-driven IDE whose `user.home` is an isolated temp dir, so
 * the test can publish the cs-cloud control file itself and observe the whole plugin-side chain:
 *
 *  - fail-open unbound placeholder policy without a control file (epoch=unbound, revision=0,
 *    both purposes open) and activation on the first valid permit,
 *  - the flat outbox layout: exactly one append-only `<scope-id>-<producer-id>.jsonl` per JVM,
 *    no registrations, no producer.json, no lock files and no .open/.ready/.claimed state machine,
 *  - NDJSON wire format with the frozen 25-required-field closed set, per-channel seq continuity,
 *    LF-terminated UTF-8 without BOM/CR and the 32KiB record budget,
 *  - graceful close appends plugin.shutdown (end_kind=app_close) as the final record; a revoked
 *    run ends WITHOUT faking a shutdown and its pending file is cleaned up (§8),
 *  - epoch rotation on the single append file: old-epoch facts are never rebound, seq gaps sit
 *    only at policy boundaries, a retired epoch written back never revives (falls back to the
 *    unbound placeholder),
 *  - scope-prefix residue cleanup: same-scope files stale beyond 24h are deleted, other-scope
 *    files and fresh files stay (§7.4), and a hard-killed predecessor run is reported by the
 *    next launch as plugin.unclean with its previous_run_id (§7.3).
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
    private val unboundEpoch = "unbound"

    private val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** 平铺追加文件名（§5.2）：`<scope-id>-<producer-id>.jsonl`，两段id各12个十六进制字符。 */
    private val outboxFileRegex = Regex("^sc-[0-9a-f]{12}-pr-[0-9a-f]{12}\\.jsonl$")

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
        "rpc", "render.apply", "edt.delay", "edt.stall", "resource.snapshot", "availability",
        "migration.required", "session.dispose_risk",
        "plugin.started", "plugin.shutdown", "plugin.unclean", "toolwindow.setup", "backend.load",
        "plugin.readiness", "connection", "connection.attempt", "connection.state_changed", "connection.recovery",
        "csc.install", "csc.start", "credentials.ready", "cli.download", "session.open", "session.restore",
        "action", "ide.operation", "telemetry.health", "protocol.error", "edt.violation",
        "error.uncaught", "error.reported",
    )

    private val json = Json

    // ------------------------------------------------------------------
    // Scenario 1: valid permit from cold start → one append-only wire-clean file
    // ------------------------------------------------------------------

    @Test
    fun `valid permit from cold start appends one wire-clean file and closes with a shutdown`() {
        writeControlFile(revision = 1, enabled = true)
        val launchMs = System.currentTimeMillis()

        lateinit var jsonl: Path
        var grew = false

        runPluginIde("stabilityE2eCollection") {
            awaitColdStartReady()

            // —— §5.2 append layout: exactly one flat jsonl appears after activation ——
            jsonl = awaitSingleOutboxFile(timeoutMs = 75_000)
            println("[e2e] outbox file after ${System.currentTimeMillis() - launchMs}ms: ${jsonl.fileName}")
            assertAppendLayoutClean(uniqueJsonl = true)

            // —— IDE 存活期间字节单调增长（追加协议：文件只增不减）——
            var last = Files.size(jsonl)
            repeat(6) {
                Thread.sleep(10_000)
                val now = Files.size(jsonl)
                assertTrue(now >= last, "the fact file must never shrink while the IDE is alive ($last → $now)")
                if (now > last) grew = true
                last = now
            }
            assertTrue(grew, "the fact file must keep growing while the IDE collects")
        }
        val closeMs = System.currentTimeMillis()

        // —— graceful close: layout still clean, still exactly one file ——
        assertAppendLayoutClean(uniqueJsonl = true)
        val facts = readFacts(listOf(jsonl))
        assertTrue(facts.size >= 10, "a whole IDE session must leave more than ${facts.size} facts")

        // —— full wire-format validation of the single append file ——
        val failures = validateFacts(
            facts = facts,
            windowStartMs = launchMs - 10_000,
            windowEndMs = closeMs + 10_000,
            expectedEpoch = { it == accountEpoch },
            expectedRevision = { _ -> 1L },
        )
        assertTrue(failures.isEmpty(), "wire-format violations:\n${failures.joinToString("\n")}")

        // —— M22 lifecycle: exactly one plugin.started, graceful shutdown recorded last ——
        assertEquals(1, facts.count { it.name == "plugin.started" }, "one plugin.started per run")
        assertEquals(0, facts.count { it.name == "plugin.unclean" }, "a clean cold start reports no unclean predecessor")
        val shutdowns = facts.filter { it.name == "plugin.shutdown" }
        assertEquals(1, shutdowns.size, "graceful app close must record exactly one plugin.shutdown")
        assertEquals(
            "app_close",
            shutdowns.single().obj["data"]!!.jsonObject["end_kind"]!!.jsonPrimitive.content,
            "app close must be reported as end_kind=app_close",
        )
        val lastLine = facts.maxBy { it.lineNo }
        assertEquals("plugin.shutdown", lastLine.name, "the graceful close must append plugin.shutdown as the final record")
        assertEquals(shutdowns.single().lineNo, lastLine.lineNo, "the shutdown must be the physically last line")

        printEvidence("collection", facts, outboxDir())
    }

    // ------------------------------------------------------------------
    // Scenario 2: policy gates the run (unbound fail-open → activate → revoke + cleanup → re-permit)
    // ------------------------------------------------------------------

    @Test
    fun `policy lifecycle gates collection and cleans the pending file on revocation without faking shutdown`() {
        val launchMs = System.currentTimeMillis()
        lateinit var jsonl: Path
        var runA: String? = null
        var runB: String? = null
        var runAFacts: List<FactLine> = emptyList()
        var revokeMs = 0L

        runPluginIde("stabilityE2ePolicy") {
            awaitColdStartReady()

            // —— §8 fail open: no control file → collection continues under the unbound placeholder ——
            jsonl = awaitSingleOutboxFile(timeoutMs = 60_000)
            awaitTolerantFact(timeoutMs = 60_000) { it.epoch == unboundEpoch && it.revision == 0L }
                ?: throw AssertionError("no unbound/rev0 fact landed without a control file (fail open expected)")
            println("[e2e] unbound collection confirmed at ${System.currentTimeMillis() - launchMs}ms")
            assertAppendLayoutClean(uniqueJsonl = true)

            // —— §8 first permit: within the 30s poll budget new facts adopt the real epoch/revision ——
            writeControlFile(revision = 1, enabled = true)
            awaitTolerantFact(timeoutMs = 65_000) { it.epoch == accountEpoch && it.revision == 1L }
                ?: throw AssertionError("new facts did not adopt epoch/rev1 within the poll budget")
            Thread.sleep(10_000) // a few more permitted facts

            // Snapshot run A's pending file before revocation (revocation deletes it from disk).
            runA = awaitAnyRunId(jsonl, timeoutMs = 30_000)
            runAFacts = parseTolerantLines(Files.readAllBytes(jsonl), jsonl)
            println("[e2e] run A snapshot: ${runAFacts.size} facts (unbound + rev1 mix), run=$runA")

            // —— §8 revocation (common enabled=false): stop within 65s AND delete the pending file ——
            revokeMs = System.currentTimeMillis()
            writeControlFile(revision = 2, enabled = false)
            awaitFileGone(jsonl, deadlineMs = revokeMs + 65_000)
            println("[e2e] pending file deleted ${System.currentTimeMillis() - revokeMs}ms after revocation")
            Thread.sleep(12_000)
            assertFalse(Files.exists(jsonl), "a revoked run must not re-create its fact file")
            assertAppendLayoutClean(uniqueJsonl = false)

            // —— §3 re-permit: the file is rebuilt under the SAME name with a NEW run_id ——
            writeControlFile(revision = 3, enabled = true)
            jsonl = awaitSingleOutboxFile(timeoutMs = 75_000)
            awaitTolerantFact(timeoutMs = 65_000) { it.revision == 3L }
                ?: throw AssertionError("run B did not record rev3 facts after re-permit")
            runB = awaitAnyRunId(jsonl, timeoutMs = 30_000)
            Thread.sleep(10_000)
        }

        // —— final file belongs to run B only (run A's file was cleaned on revocation) ——
        assertAppendLayoutClean(uniqueJsonl = true)
        val runBFacts = readFacts(listOf(jsonl))
        assertTrue(runBFacts.isNotEmpty() && runBFacts.all { it.runId == runB }, "the rebuilt file must hold only run B facts")

        val failuresB = validateFacts(
            facts = runBFacts,
            windowStartMs = launchMs - 10_000,
            windowEndMs = System.currentTimeMillis() + 10_000,
            expectedEpoch = { it == accountEpoch },
            expectedRevision = { _ -> 3L },
        )
        assertTrue(failuresB.isEmpty(), "run B wire violations:\n${failuresB.joinToString("\n")}")
        assertEquals(1, runBFacts.count { it.name == "plugin.started" }, "re-permit starts a new run with one plugin.started")
        assertEquals(runB, runBFacts.first { it.name == "plugin.started" }.runId, "run B identity is stable in its file")
        val shutdownB = runBFacts.last { it.name == "plugin.shutdown" }
        assertEquals("app_close", shutdownB.obj["data"]!!.jsonObject["end_kind"]!!.jsonPrimitive.content)
        assertEquals(runBFacts.size, shutdownB.lineNo, "run B closes with plugin.shutdown as the physically last line")

        // —— run A (in-memory snapshot): unbound facts then rev1 facts, no fabricated shutdown ——
        assertTrue(runAFacts.any { it.epoch == unboundEpoch && it.revision == 0L }, "run A must have collected unbound facts")
        assertTrue(runAFacts.any { it.epoch == accountEpoch && it.revision == 1L }, "run A must have collected rev1 facts")
        val startedA = runAFacts.single { it.name == "plugin.started" }
        assertEquals(setOf("metrics", "logs"), startedA.purposes, "the unbound placeholder opens both purposes (dual tag)")
        assertEquals(0, runAFacts.count { it.name == "plugin.shutdown" }, "revocation must end the run WITHOUT fabricating plugin.shutdown")
        // 旧 unbound 事实不改绑：unbound 事实保持 epoch=unbound/rev=0
        assertTrue(
            runAFacts.filter { it.epoch == unboundEpoch }.all { it.revision == 0L },
            "previously unbound facts must never be rebound to the account epoch",
        )
        val failuresA = validateFacts(
            facts = runAFacts,
            windowStartMs = launchMs - 10_000,
            windowEndMs = revokeMs + 65_000,
            expectedEpoch = { it == unboundEpoch || it == accountEpoch },
            expectedRevision = { fact -> if (fact.epoch == unboundEpoch) 0L else 1L },
        )
        assertTrue(failuresA.isEmpty(), "run A wire violations:\n${failuresA.joinToString("\n")}")
        assertTrue(runA != runB, "re-permit must start a new run_id")

        printEvidence("policy", runAFacts + runBFacts, outboxDir())
        preserveTelemetryHome("policy")
    }

    // ------------------------------------------------------------------
    // Scenario 3: account epoch rotation on the single append file (§8.1)
    // ------------------------------------------------------------------

    /**
     * The daemon-side epoch lifecycle is external, but every plugin-side duty of §8.1 is
     * observable here by rewriting the control file inside one live IDE session:
     *
     *  - direct epoch swap: the run keeps its identity and keeps collecting, but no further
     *    OLD-epoch fact may land — queued-but-unwritten old-epoch facts are dropped at the
     *    write gate, never rebound to the new epoch (seq gaps only at that policy boundary);
     *  - pending: purposes empty → the run ends without fabricating plugin.shutdown and the
     *    pending file is cleaned;
     *  - ready under a brand-new epoch → a new run_id with exactly one plugin.started;
     *  - writing a RETIRED epoch back: never revived (§8.1) — the run falls back to the
     *    unbound placeholder instead of collecting under the retired epoch.
     */
    @Test
    fun `epoch rotation retires the old account without rebinding or faking shutdown`() {
        val epoch1 = accountEpoch
        val epoch2 = "acct-e2e-02"
        val epoch3 = "acct-e2e-03"
        val launchMs = System.currentTimeMillis()
        lateinit var jsonl: Path
        var swapMs = 0L
        var pendingMs = 0L
        var regressMs = 0L
        var runA: String? = null
        var runB: String? = null
        var runAFacts: List<FactLine> = emptyList()

        // 控制文件先于启动落盘：冷启动即持 epoch1 有效许可（run A 的首条事实即 epoch1，
        // 不混入无许可前缀），轮换行为与 §8.1 的"有效许可内换代"对齐。
        writeControlFile(revision = 1, enabled = true, epoch = epoch1)
        runPluginIde("stabilityE2eEpoch") {
            awaitColdStartReady()
            jsonl = awaitSingleOutboxFile(timeoutMs = 75_000)
            runA = awaitAnyRunId(jsonl, timeoutMs = 30_000)
            Thread.sleep(45_000) // epoch-1 facts accumulate

            // —— direct swap to a new valid epoch: run A survives, old epoch must go quiet ——
            swapMs = System.currentTimeMillis()
            writeControlFile(revision = 2, enabled = true, epoch = epoch2)
            awaitTolerantFact(timeoutMs = 65_000) { it.epoch == epoch2 && it.runId == runA }
                ?: throw AssertionError("the direct swap must keep run A collecting under the new epoch")
            val epoch1Ids = tolerantFacts().filter { it.epoch == epoch1 }.map { it.eventId }.toSet()
            assertTrue(epoch1Ids.isNotEmpty(), "epoch-1 facts must exist before the swap")
            Thread.sleep(20_000)
            assertEquals(
                epoch1Ids,
                tolerantFacts().filter { it.epoch == epoch1 }.map { it.eventId }.toSet(),
                "no further $epoch1 fact may land once the new epoch was observed",
            )
            runAFacts = parseTolerantLines(Files.readAllBytes(jsonl), jsonl)
            println("[e2e] run A snapshot before pending: ${runAFacts.size} facts")

            // —— pending ends the run without faking a shutdown; the pending file is cleaned (§8) ——
            pendingMs = System.currentTimeMillis()
            writeControlFile(revision = 3, enabled = true, epoch = epoch2, accountState = "pending")
            awaitFileGone(jsonl, deadlineMs = pendingMs + 65_000)
            println("[e2e] pending cleaned the pending file ${System.currentTimeMillis() - pendingMs}ms after publish")

            // —— ready under a brand-new epoch starts a new run (§8.1 "新操作上下文") ——
            writeControlFile(revision = 4, enabled = true, epoch = epoch3)
            jsonl = awaitSingleOutboxFile(timeoutMs = 75_000)
            awaitTolerantFact(timeoutMs = 65_000) { it.epoch == epoch3 && it.revision == 4L }
                ?: throw AssertionError("run B did not collect under the brand-new epoch")
            runB = awaitAnyRunId(jsonl, timeoutMs = 30_000)
            Thread.sleep(15_000)

            // —— a retired epoch never revives: the run falls back to the unbound placeholder ——
            regressMs = System.currentTimeMillis()
            writeControlFile(revision = 5, enabled = true, epoch = epoch1)
            awaitTolerantFact(timeoutMs = 65_000) { it.epoch == unboundEpoch && it.revision == 0L && it.runId == runB }
                ?: throw AssertionError("after the retired-epoch regression the run must fall back to the unbound placeholder")
        }

        // —— final file: run B facts under epoch3/rev4, then unbound/rev0 after the regression ——
        assertAppendLayoutClean(uniqueJsonl = true)
        val runBFacts = readFacts(listOf(jsonl))
        assertTrue(runBFacts.all { it.runId == runB }, "the rebuilt file must hold only run B facts")
        val failures = validateFacts(
            facts = runBFacts,
            windowStartMs = launchMs - 10_000,
            windowEndMs = System.currentTimeMillis() + 10_000,
            expectedEpoch = { it == epoch3 || it == unboundEpoch },
            expectedRevision = { fact -> if (fact.epoch == epoch3) 4L else 0L },
        )
        assertTrue(failures.isEmpty(), "run B wire violations:\n${failures.joinToString("\n")}")

        // No fact may carry a pending/regression revision, and the retired epoch never reappears.
        assertTrue(runBFacts.none { it.revision == 3L || it.revision == 5L }, "pending/regression revisions must collect nothing")
        assertTrue(runBFacts.none { it.epoch == epoch1 || it.epoch == epoch2 }, "retired epochs must never be rebound")

        // Run B stops collecting under epoch3 at the regression and continues unbound.
        assertTrue(runBFacts.any { it.epoch == epoch3 }, "run B must have collected epoch-3 facts before the regression")
        assertTrue(
            runBFacts.filter { it.epoch == epoch3 }.maxOf { it.timestamp } <= regressMs + 65_000,
            "epoch-3 collection must stop within the poll budget after the retired-epoch regression",
        )
        assertTrue(runBFacts.any { it.epoch == unboundEpoch }, "after the regression the run continues under the placeholder")
        assertEquals(1, runBFacts.count { it.name == "plugin.started" })
        assertEquals(0, runBFacts.count { it.name == "plugin.unclean" })
        val shutdownB = runBFacts.filter { it.name == "plugin.shutdown" }
        assertEquals(1, shutdownB.size, "run B is active at close (placeholder permits) → one app_close shutdown")
        assertEquals("app_close", shutdownB.single().obj["data"]!!.jsonObject["end_kind"]!!.jsonPrimitive.content)
        assertEquals(runBFacts.size, shutdownB.single().lineNo, "plugin.shutdown must be the physically last line")
        // seq 缺口只允许出现在策略边界（run B 正常无缺口；此处作为边界守卫）。
        assertSeqGapsAtPolicyBoundaries(runBFacts, listOf(regressMs))

        // —— run A (in-memory snapshot): swap keeps the run, no rebinding, gaps only at the swap ——
        assertTrue(runAFacts.any { it.epoch == epoch1 } && runAFacts.any { it.epoch == epoch2 }, "run A must span the direct swap")
        val failuresA = validateFacts(
            facts = runAFacts,
            windowStartMs = launchMs - 10_000,
            windowEndMs = pendingMs + 65_000,
            expectedEpoch = { it == epoch1 || it == epoch2 },
            expectedRevision = { fact -> if (fact.epoch == epoch1) 1L else 2L },
            // epoch 守卫在写入侧丢弃排队旧epoch事实：缺口是策略性丢弃，边界在下方逐一核对。
            seqGapsAllowed = { _ -> true },
        )
        assertTrue(failuresA.isEmpty(), "run A wire violations:\n${failuresA.joinToString("\n")}")
        assertSeqGapsAtPolicyBoundaries(runAFacts, listOf(swapMs))

        val lastEpoch1 = runAFacts.filter { it.epoch == epoch1 }.maxOf { it.timestamp }
        val firstEpoch2 = runAFacts.filter { it.epoch == epoch2 }.minOf { it.timestamp }
        assertTrue(lastEpoch1 < firstEpoch2, "an $epoch1 fact ($lastEpoch1) was admitted after an $epoch2 fact ($firstEpoch2)")
        assertTrue(runAFacts.all { it.runId == runA }, "run A identity must not change across the epoch swap")
        assertEquals(1, runAFacts.count { it.name == "plugin.started" })
        assertEquals(0, runAFacts.count { it.name == "plugin.shutdown" }, "pending must end the run without faking shutdown")

        printEvidence("epoch", runAFacts + runBFacts, outboxDir())
        preserveTelemetryHome("epoch")
    }

    // ------------------------------------------------------------------
    // Scenario 4: scope-prefix residue cleanup + hard-kill unclean handover (§7.3/§7.4)
    // ------------------------------------------------------------------

    /**
     * Two sequential IDE launches sharing one isolated `user.home` (one telemetry root):
     *
     *  - launch A collects under a valid permit and is then HARD-KILLED (no plugin.stop): its
     *    append file keeps a started-without-shutdown trail — the unclean predecessor;
     *  - before launch B the test plants residue in the flat outbox: an EMPTY same-scope file
     *    backdated 25h beyond the 24h retention (must be swept by B) and an other-scope file
     *    equally stale (must be kept — cross-scope residue is the consumer's job, §7.4);
     *  - launch B (valid permit) must keep A's fresh file, report its own run cleanly, and —
     *    when B shares A's scope id — sweep only the stale same-scope file and report A's death
     *    as its FIRST business fact: plugin.unclean with previous_run_id = A's run_id (§7.3).
     *
     * R14 ruling (see task-13 report §1): the sandbox IDE's PropertiesComponent state does NOT
     * survive between two launches — every launch after the first runs ConfigImportHelper
     * (migrate.config marker is re-created by the IDE itself each startup), which resets the
     * config dir and drops the persisted scope id (probe evidence: scopeA=sc-17c323cb3d3e →
     * scopeB=sc-eecdebd904e9; seeded other.xml wiped by the import). The plugin-side scope load
     * happens at app-service construction, before any driver hook can re-seed, so the fallback
     * mandated by the ruling applies: the same-scope file is seeded from A's file-name prefix
     * and the unclean + same-scope-sweep assertions run ONLY when B's own file shares that
     * prefix. When the environment breaks scope persistence (current behavior), the test records
     * that loudly and keeps the other assertions; the unclean detection logic itself is covered
     * by the T7 unit suite (unclean-test.kt).
     */
    @Test
    fun `dead predecessor file is kept unclean-reported while stale same scope residue is swept`() {
        val evidenceRoot = Path.of("out", "stability-evidence", "residue").toAbsolutePath()
        evidenceRoot.toFile().deleteRecursively()

        writeControlFile(revision = 1, enabled = true)

        var fileA: Path? = null
        var runA: String? = null
        val launchAMs = System.currentTimeMillis()

        // —— launch A: collect, then die by process destroy (no graceful close, no shutdown) ——
        runPluginIde("stabilityE2eResidue", hardKill = true) {
            awaitColdStartReady()
            val observed = awaitSingleOutboxFile(timeoutMs = 75_000)
            fileA = observed
            runA = awaitAnyRunId(observed, timeoutMs = 30_000)
            println("[e2e] run A (${observed.fileName}) ready after ${System.currentTimeMillis() - launchAMs}ms")
            Thread.sleep(60_000) // facts + flushes; the harness force-kills the IDE afterwards
        }
        val outbox = outboxDir()
        val fileAAfter = outbox.resolve(requireNotNull(fileA) { "run A's outbox file was never observed" }.fileName)
        val scopeA = fileAAfter.fileName.toString().substringBefore("-pr-")
        assertTrue(Files.exists(fileAAfter), "a hard-killed run leaves its pending file in place")
        assertFalse(
            tolerantFacts(outbox).any { it.runId == runA && it.name == "plugin.shutdown" },
            "a hard kill must not fabricate a plugin.shutdown for run A",
        )

        // —— plant residue: same-scope expired (swept when scopes match) + other-scope expired ——
        val staleSameScope = outbox.resolve("${scopeA}-pr-00000000000a.jsonl")
        Files.writeString(staleSameScope, "")
        backdate(staleSameScope, hoursAgo = 25)
        val staleOtherScope = outbox.resolve("sc-0fffffffffff-pr-00000000000b.jsonl")
        Files.writeString(staleOtherScope, "")
        backdate(staleOtherScope, hoursAgo = 25)
        println("[e2e] planted residue: $staleSameScope (25h, same scope), $staleOtherScope (25h, other scope)")

        // —— launch B: valid permit → unclean detection + startup retention sweep (§7.4) ——
        lateinit var fileB: Path
        var runB: String? = null
        runPluginIde("stabilityE2eResidue") {
            awaitColdStartReady()
            val known = setOf(fileAAfter.fileName.toString(), staleOtherScope.fileName.toString(), staleSameScope.fileName.toString())
            fileB = awaitSingleOutboxFile(timeoutMs = 75_000, exclude = known)
            // the sweep and the unclean detection run at service init — settle, then verify
            awaitTolerantFact(timeoutMs = 60_000) { it.runId != runA && it.name == "plugin.started" }
                ?: throw AssertionError("run B never recorded plugin.started")
            runB = awaitAnyRunId(fileB, timeoutMs = 30_000)
            Thread.sleep(20_000)
        }

        val scopeB = fileB.fileName.toString().substringBefore("-pr-")
        val sameScopeAcrossLaunches = scopeB == scopeA
        println(
            "[e2e] scope persistence across launches: scopeA=$scopeA scopeB=$scopeB same=$sameScopeAcrossLaunches " +
                "(sandbox ConfigImportHelper resets PropertiesComponent between launches — see task-13 report)",
        )

        // —— residue assertions that hold regardless of scope persistence ——
        assertTrue(Files.exists(staleOtherScope), "other-scope residue is outside the plugin's cleanup scope")
        assertTrue(Files.exists(fileAAfter), "A's file is within the 24h retention and must be kept")
        assertTrue(runB != runA, "launch B must run under a fresh run_id")

        // —— run B recorded a clean, wire-valid run ——
        val factsB = readFacts(listOf(fileB))
        assertTrue(factsB.isNotEmpty(), "run B must have recorded facts")
        val failures = validateFacts(
            facts = factsB,
            windowStartMs = launchAMs - 10_000,
            windowEndMs = System.currentTimeMillis() + 10_000,
            expectedEpoch = { it == accountEpoch },
            expectedRevision = { _ -> 1L },
        )
        assertTrue(failures.isEmpty(), "run B wire violations:\n${failures.joinToString("\n")}")
        assertEquals(1, factsB.count { it.name == "plugin.started" }, "one plugin.started for run B")

        if (sameScopeAcrossLaunches) {
            // R14 (a) full branch: B shares A's scope → sweep + unclean handover must happen.
            assertFalse(Files.exists(staleSameScope), "stale same-scope residue must be swept by the next launch (§7.4)")
            assertEquals(1, factsB.count { it.name == "plugin.unclean" }, "exactly one unclean predecessor report")
            val first = factsB.minBy { it.lineNo }
            assertEquals("plugin.unclean", first.name, "run B's first business fact must report the unclean predecessor")
            val uncleanData = first.obj["data"]!!.jsonObject
            assertEquals(runA, uncleanData["previous_run_id"]!!.jsonPrimitive.content, "previous_run_id must be run A's id")
            assertEquals(
                "no_shutdown_after_started",
                uncleanData["evidence"]!!.jsonPrimitive.content,
                "the unclean evidence token must be the fixed constant",
            )
        } else {
            // R14 (a) recorded skip: the sandbox resets IDE settings between launches, so B's
            // scope differs and the cross-launch same-scope duties cannot be exercised here.
            assertTrue(
                Files.exists(staleSameScope),
                "with a different scope B must treat the planted file as other-scope residue and keep it",
            )
            assertEquals(
                0,
                factsB.count { it.name == "plugin.unclean" },
                "without a same-scope predecessor B must not report plugin.unclean",
            )
            println(
                "[e2e] RECORDED (R14 fallback a): cross-launch plugin.unclean + same-scope sweep NOT exercisable in this " +
                    "sandbox (scopeA=$scopeA != scopeB=$scopeB; ConfigImportHelper resets IDE settings every launch). " +
                    "Detection logic is covered by the T7 unit suite (shared unclean-test.kt).",
            )
        }

        // —— append layout survived the whole exercise ——
        assertAppendLayoutClean(uniqueJsonl = false)

        // —— keep the final tree for manual inspection ——
        Files.createDirectories(evidenceRoot.resolve("outbox"))
        copyTree(outbox, evidenceRoot.resolve("outbox"))
        preserveTelemetryHome("residue")
        println("[e2e] residue evidence kept at $evidenceRoot (outbox + telemetry-home)")
        printEvidence("residue", factsB, outbox)
    }

    // ------------------------------------------------------------------
    // Control file (§8 wire fields; closed set, additionalProperties=false)
    // ------------------------------------------------------------------

    private fun telemetryHome(): Path = cloud.parent.resolve("telemetry")

    private fun outboxDir(): Path = telemetryHome().resolve("outbox")

    private fun controlFile(): Path = telemetryHome().resolve("control").resolve("jetbrains.json")

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
    // Append outbox layout (§5.2) — flat single jsonl, no locks/registries
    // ------------------------------------------------------------------

    /** All regular files currently in the flat outbox. */
    private fun outboxEntries(): List<Path> =
        if (Files.isDirectory(outboxDir())) {
            Files.list(outboxDir()).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
        } else {
            emptyList()
        }

    /** The outbox jsonl files whose name is not in [exclude]. */
    private fun outboxJsonlFiles(exclude: Set<String> = emptySet()): List<Path> =
        outboxEntries().filter { path ->
            val name = path.fileName.toString()
            name !in exclude && outboxFileRegex.matches(name)
        }

    /**
     * Asserts the whole telemetry home follows the append layout: flat jsonl facts only —
     * no registrations directory, no producer.json, no lock files, no .open/.ready/.claimed
     * state-machine suffixes (§5.2/§3.1). [uniqueJsonl] additionally requires exactly one file.
     */
    private fun assertAppendLayoutClean(uniqueJsonl: Boolean) {
        val home = telemetryHome()
        val entries = outboxEntries()
        val names = entries.map { it.fileName.toString() }
        val jsonl = names.filter { outboxFileRegex.matches(it) }
        assertTrue(
            names.all { outboxFileRegex.matches(it) },
            "the outbox must hold only flat scope-producer jsonl files, got $names",
        )
        if (uniqueJsonl) {
            assertEquals(1, jsonl.size, "exactly one producer fact file must exist, got $jsonl")
        }
        assertFalse(Files.exists(home.resolve("registrations")), "the append protocol keeps no registrations directory")
        val forbiddenSuffixes = listOf(".open", ".ready", ".claimed", ".lock", ".tmp", ".json")
        names.forEach { name ->
            forbiddenSuffixes.forEach { suffix ->
                assertTrue(!name.endsWith(suffix), "state-machine/lock/metadata files must not exist, found $name")
            }
        }
    }

    /** Polls until the outbox holds exactly one producer jsonl (optionally excluding [exclude] names). */
    private fun awaitSingleOutboxFile(timeoutMs: Long, exclude: Set<String> = emptySet()): Path {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val files = outboxJsonlFiles(exclude)
            if (files.size == 1) return files.single()
            Thread.sleep(1_000)
        }
        throw AssertionError(
            "no single producer jsonl appeared within ${timeoutMs}ms; outbox: ${outboxEntries().map { it.fileName }}",
        )
    }

    // ------------------------------------------------------------------
    // Facts (§6.1) — strict final read & tolerant mid-run scan of the live file
    // ------------------------------------------------------------------

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
        val purposes: Set<String>,
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
            facts += parseStrictLines(text, file)
        }
        return facts
    }

    /** Strict per-line parse: every line must be a complete wire record inside the closed set. */
    private fun parseStrictLines(text: String, file: Path): List<FactLine> {
        val facts = mutableListOf<FactLine>()
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
                purposes = purposesOf(obj),
            )
        }
        return facts
    }

    /**
     * Tolerant per-line parse for snapshots of the live append file: a trailing partial line
     * (no LF yet, writer mid-append) is skipped; only complete lines are returned.
     */
    private fun parseTolerantLines(bytes: ByteArray, file: Path): List<FactLine> {
        val text = bytes.toString(Charsets.UTF_8)
        val complete = if (text.endsWith("\n")) text else text.substringBeforeLast('\n', "")
        if (complete.isBlank()) return emptyList()
        return parseStrictLines(complete, file)
    }

    private fun requiredString(obj: JsonObject, key: String, file: Path, line: Int): String =
        obj[key]?.jsonPrimitive?.content ?: error("${file.fileName}:${line + 1} missing $key")

    private fun requiredLong(obj: JsonObject, key: String, file: Path, line: Int): Long =
        obj[key]?.jsonPrimitive?.longOrNull ?: error("${file.fileName}:${line + 1} missing $key")

    private fun purposesOf(obj: JsonObject): Set<String> =
        (obj["purposes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()

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
        facts.groupBy { it.file }.forEach { (file, fileFacts) ->
            if (!outboxFileRegex.matches(file.fileName.toString())) {
                fail("unexpected outbox file name ${file.fileName}")
            }
        }

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
            if (fact.seq < 1) fail("$where: seq must start at 1")
            if (!expectedEpoch(fact.epoch)) {
                fail("$where: account_epoch ${fact.epoch} is not in the expected set")
            }
            if (fact.revision != expectedRevision(fact)) {
                fail("$where: policy_revision ${fact.revision} != expected ${expectedRevision(fact)}")
            }
            if (fact.purposes.isEmpty() || fact.purposes.any { it !in purposeValues }) {
                fail("$where: purposes must be a non-empty subset of $purposeValues, got ${fact.purposes}")
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
    // Mid-run tolerant scan of the live append file
    // ------------------------------------------------------------------

    private data class TolerantFact(
        val eventId: String,
        val runId: String,
        val epoch: String,
        val revision: Long,
        val name: String,
    )

    /**
     * Tolerant scan of every outbox jsonl (the live writer may be mid-append): a trailing
     * partial line is skipped and unparseable lines are ignored. Used for in-run quiet /
     * adoption probes; final validation stays strict.
     */
    private fun tolerantFacts(outbox: Path = outboxDir()): List<TolerantFact> =
        outboxJsonlFiles().flatMap { file ->
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
                            revision = obj["policy_revision"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                            name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        )
                    }.getOrNull()
                }
                .toList()
        }

    /** Polls until any fact matches [predicate]; null on timeout. */
    private fun awaitTolerantFact(timeoutMs: Long, predicate: (TolerantFact) -> Boolean): TolerantFact? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            tolerantFacts().firstOrNull(predicate)?.let { return it }
            Thread.sleep(1_000)
        }
        return null
    }

    /** Polls until [file] disappears; fails once [deadlineMs] has passed. */
    private fun awaitFileGone(file: Path, deadlineMs: Long) {
        while (System.currentTimeMillis() < deadlineMs) {
            if (!Files.exists(file)) return
            Thread.sleep(1_000)
        }
        throw AssertionError("${file.fileName} still present ${deadlineMs - System.currentTimeMillis()}ms after its deadline")
    }

    /** First run_id visible in [file] (complete lines only). */
    private fun awaitAnyRunId(file: Path, timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file)) {
                parseTolerantLines(Files.readAllBytes(file), file).firstOrNull()?.let { return it.runId }
            }
            Thread.sleep(1_000)
        }
        throw AssertionError("no fact line appeared in ${file.fileName} within ${timeoutMs}ms")
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
    // Residue planting helpers & evidence retention (§7.3/§7.4)
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
     * Keeps the home-side telemetry evidence (outbox + control file) under
     * `out/stability-evidence/<scenario>/` — the temp telemetry home is wiped by tearDown,
     * so it needs an explicit copy for manual inspection (Task 14 cites these trees).
     */
    private fun preserveTelemetryHome(scenario: String) {
        val source = telemetryHome()
        if (!Files.isDirectory(source)) return
        val dest = Path.of("out", "stability-evidence", scenario, "telemetry-home").toAbsolutePath()
        dest.toFile().deleteRecursively()
        Files.createDirectories(dest)
        copyTree(source, dest)
        println("[e2e] telemetry home evidence kept at $dest")
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
        println("[e2e] files: ${outboxEntries().joinToString { it.fileName.toString() }}")
    }
}
