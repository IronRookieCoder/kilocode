package ai.kilocode.jetbrains

import com.intellij.driver.sdk.invokeAction
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
 * 全字典真实IDE落盘验收（设计第9章/第14章"插件端所有类型"口径）：
 *
 * 真实 Starter IDE（隔离home）+ 双用途有效控制文件 → 隐藏自检动作
 * `Kilo.StabilitySelfTest`（经 `-Dcostrict.stability.selftest=true` 开启）对除
 * plugin.started/plugin.shutdown/telemetry.health（服务级单发与真实快照）外的全部登记
 * name各产出一条字典合法事实，走真实准入→队列→writer→封存→outbox管线；优雅关闭排空
 * 后从.ready/.claimed还原并断言：
 *
 *  - **30个登记name全部落盘**（27个经自检 + started/shutdown/health由服务自然产出）；
 *  - 每个name的kind/channel/purposes与事件字典投影一致（含error族计数critical/metrics与
 *    详情diagnostic/logs两形态分道）；7种kind齐备；两通道目录均有封存文件；
 *  - 自检事实带workspace_id=ws-selftest标记，与自然事实可区分（人工检查入口）；
 *  - 完整§6.1线格式校验（独立副本契约，同StabilityE2eTest原则：不依赖插件模块类）；
 *  - **落盘文件保留**：outbox全树+登记+控制文件复制到`out/stability-evidence/dictionary/`
 *    供人工检查（沙箱目录同名重跑会被清空，evidence目录才是稳定保留点）。
 */
class StabilityDictionaryE2eTest : IntegrationTestBase() {

    // ------------------------------------------------------------------
    // Independent copy of the frozen wire contract (design §6.1/§9)
    // ------------------------------------------------------------------

    private val accountEpoch = "acct-e2e-dict"

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

    /** 30个登记name（设计§9字典）。 */
    private val registeredNames = setOf(
        "rpc", "render.apply", "edt.delay", "resource.snapshot", "availability",
        "migration.required", "session.dispose_risk",
        "plugin.started", "plugin.shutdown", "plugin.unclean", "toolwindow.setup", "backend.load",
        "plugin.readiness", "connection", "connection.attempt", "connection.state_changed", "connection.recovery",
        "csc.install", "csc.start", "credentials.ready", "cli.download", "session.open", "session.restore",
        "action", "ide.operation", "telemetry.health", "protocol.error", "edt.violation",
        "error.uncaught", "error.reported",
    )

    /** name→(kind, channel, purposes) 字典投影独立副本；error族按通道分两形态。 */
    private val dual = setOf("metrics", "logs")
    private val metricsOnly = setOf("metrics")

    private fun expectedForm(name: String, channel: String): Triple<String, String, Set<String>>? = when {
        name == "error.reported" || name == "error.uncaught" ->
            if (channel == "critical") Triple("diagnostic", "critical", metricsOnly)
            else Triple("diagnostic", "diagnostic", setOf("logs"))
        else -> Triple(kindOf(name), "critical", if (name in metricsOnlyNames) metricsOnly else dual)
    }

    private val metricsOnlyNames = setOf(
        "rpc", "render.apply", "edt.delay", "resource.snapshot", "availability", "migration.required", "session.dispose_risk",
    )

    private fun kindOf(name: String): String = when (name) {
        "connection.state_changed", "migration.required", "session.dispose_risk" -> "transition"
        "plugin.started", "plugin.shutdown", "plugin.unclean" -> "lifecycle"
        "availability" -> "interval"
        "edt.delay", "render.apply", "resource.snapshot" -> "sample"
        "protocol.error", "edt.violation" -> "diagnostic"
        "telemetry.health" -> "health"
        else -> "operation"
    }

    /** 只有自检动作会产出的事实（轮询触发成功的确定性判据）。 */
    private val sweepExclusiveNames = setOf(
        "plugin.unclean", "migration.required", "csc.install", "csc.start", "cli.download",
        "session.dispose_risk", "protocol.error", "edt.violation", "render.apply",
    )

    private val json = Json

    // ------------------------------------------------------------------
    // Scenario: full dictionary in a real IDE, files preserved
    // ------------------------------------------------------------------

    @Test
    fun `every registered fact type lands on disk in a real ide and files are preserved`() {
        writeControlFile()
        val launchMs = System.currentTimeMillis()
        lateinit var registration: Registration

        runPluginIde(
            testName = "stabilityE2eDictionary",
            extraSystemProperties = mapOf("costrict.stability.selftest" to "true"),
        ) {
            awaitColdStartReady()
            registration = awaitRegistration()
            val outbox = registration.outboxPath
            println("[dict] registration: ${registration.producerId}")

            // run活跃且封存已在工作（自然事实流：started/readiness/connection/availability/
            // rpc/edt.delay/toolwindow.setup/backend.load/health/resource.snapshot）
            awaitFirstSealed(outbox, timeoutMs = 75_000)

            // —— 全字典自检：隐藏动作经真实管线入队27个登记name ——
            invokeAction("Kilo.StabilitySelfTest")
            awaitSweepFacts(outbox, timeoutMs = 90_000)
            println("[dict] sweep facts visible after ${System.currentTimeMillis() - launchMs}ms")

            // 健康摘要（服务自然产出，30秒节奏）至少一份后收尾
            awaitHealthFact(outbox, timeoutMs = 60_000)
            Thread.sleep(5_000) // 排空余量
        }
        val closeMs = System.currentTimeMillis()
        val outbox = registration.outboxPath

        // —— 优雅关闭排空：无.open残留，两通道都留有封存文件 ——
        assertTrue(segmentFiles(outbox, setOf("open")).isEmpty(), "no .open segment may remain after a graceful close")
        val sealedByChannel = segmentFiles(outbox, setOf("ready", "claimed"))
            .groupBy { it.parent.fileName.toString() }
        assertTrue(sealedByChannel["critical"].orEmpty().isNotEmpty(), "critical channel must keep sealed files")
        assertTrue(
            sealedByChannel["diagnostic"].orEmpty().isNotEmpty(),
            "diagnostic channel must keep sealed files (error details)",
        )

        // —— 全量线格式 + 字典形态校验 ——
        val facts = readFacts(segmentFiles(outbox, setOf("ready", "claimed")))
        assertTrue(facts.isNotEmpty(), "facts must be sealed on disk")
        val failures = validateFacts(facts, launchMs - 10_000, closeMs + 10_000)
        assertTrue(failures.isEmpty(), "violations:\n${failures.joinToString("\n")}")

        // —— 30个登记name全部落盘（27自检 + started/shutdown/health自然）——
        val missing = registeredNames - facts.map { it.name }.toSet()
        assertTrue(missing.isEmpty(), "every registered name must land on disk; missing: $missing")

        // —— 每个name的kind/channel/purposes与字典投影一致 ——
        val formViolations = facts.mapNotNull { fact ->
            val expected = expectedForm(fact.name, fact.channel) ?: return@mapNotNull "unregistered name ${fact.name}"
            when {
                fact.kind != expected.first -> "${fact.name}/${fact.channel}: kind ${fact.kind} != ${expected.first}"
                fact.channel != expected.second -> "${fact.name}: channel ${fact.channel} != ${expected.second}"
                fact.purposes != expected.third -> "${fact.name}/${fact.channel}: purposes ${fact.purposes} != ${expected.third}"
                else -> null
            }
        }
        assertTrue(formViolations.isEmpty(), "dictionary form violations:\n${formViolations.joinToString("\n")}")

        // —— 7种kind齐备；purposes三类投影齐备（metrics-only/logs-only/dual）——
        assertEquals(kinds, facts.map { it.kind }.toSet(), "all 7 kinds must appear on disk")
        assertTrue(
            facts.any { it.purposes == metricsOnly } && facts.any { it.purposes == setOf("logs") } &&
                facts.any { it.purposes == dual },
            "metrics-only, logs-only and dual purposes projections must all be present",
        )

        // —— error族两形态分道且同一fault_id关联 ——
        val errorCounts = facts.filter { it.name.startsWith("error.") && it.channel == "critical" }
        val errorDetails = facts.filter { it.channel == "diagnostic" }
        assertTrue(errorCounts.isNotEmpty(), "error count form (critical/metrics) must be on disk")
        assertTrue(errorDetails.isNotEmpty(), "error detail form (diagnostic/logs) must be on disk")
        assertTrue(errorDetails.all { it.name.startsWith("error.") && it.purposes == setOf("logs") })
        val detailFaultIds = errorDetails.mapNotNull { fact -> fact.obj["context"]?.jsonObject?.get("fault_id") }
        assertTrue(
            errorCounts.any { count ->
                detailFaultIds.any { id -> id.jsonPrimitive.content == count.obj["context"]!!.jsonObject["fault_id"]!!.jsonPrimitive.content }
            },
            "a detail fact must reference the same fault_id as its count fact",
        )

        // —— M22 lifecycle：恰1条started、1条shutdown(app_close) ——
        assertEquals(1, facts.count { it.name == "plugin.started" })
        val shutdowns = facts.filter { it.name == "plugin.shutdown" }
        assertEquals(1, shutdowns.size)
        assertEquals("app_close", shutdowns.single().obj["data"]!!.jsonObject["end_kind"]!!.jsonPrimitive.content)

        // —— 自检标记：sweep事实带ws-selftest（人工检查时可与自然事实区分）——
        assertTrue(
            facts.count {
                it.obj["context"]?.jsonObject?.get("workspace_id")?.jsonPrimitive?.content == "ws-selftest"
            } >= 25,
            "the self-test sweep must tag its facts with workspace_id=ws-selftest",
        )

        // —— 人工检查留档：outbox全树 + telemetry home（登记+控制文件）——
        preserveEvidence(registration, facts)
    }

    // ------------------------------------------------------------------
    // Control file (§8) & registration (§5.2) — same wire shape as StabilityE2eTest
    // ------------------------------------------------------------------

    private fun controlFile(): Path = cloud.parent.resolve("telemetry").resolve("control").resolve("jetbrains.json")

    private fun registrationsDir(): Path = cloud.parent.resolve("telemetry").resolve("registrations")

    private fun writeControlFile() {
        val expiresAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)
        Files.createDirectories(controlFile().parent)
        Files.writeString(
            controlFile(),
            """
            {
              "schema_major": 1,
              "revision": 1,
              "enabled": true,
              "metrics_enabled": true,
              "metrics_expires_at": $expiresAt,
              "logs_enabled": true,
              "logs_expires_at": $expiresAt,
              "account_epoch": "$accountEpoch",
              "account_state": "ready",
              "expires_at": $expiresAt,
              "metrics_allowed_categories": ["critical", "diagnostic"],
              "logs_allowed_categories": ["critical", "diagnostic"],
              "log_detail_rate_limit": {"per_fingerprint_max_per_minute": 3}
            }
            """.trimIndent(),
        )
    }

    private data class Registration(val producerId: String, val outboxPath: Path)

    private fun awaitRegistration(timeoutMs: Long = 75_000): Registration {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dir = registrationsDir()
            val file = if (Files.isDirectory(dir)) {
                Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".json") }.findFirst().orElse(null)
                }
            } else {
                null
            }
            if (file != null) {
                val root = json.parseToJsonElement(Files.readString(file)).jsonObject
                assertTrue(
                    root["outbox_path"] != null && root["producer_id"] != null,
                    "registration must carry outbox_path and producer_id",
                )
                return Registration(
                    producerId = root["producer_id"]!!.jsonPrimitive.content,
                    outboxPath = Path.of(root["outbox_path"]!!.jsonPrimitive.content),
                )
            }
            Thread.sleep(1_000)
        }
        throw AssertionError("no producer registration appeared within ${timeoutMs}ms")
    }

    // ------------------------------------------------------------------
    // Segment files & facts
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
        val kind: String,
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
                    eventId = obj["event_id"]!!.jsonPrimitive.content,
                    timestamp = obj["timestamp"]!!.jsonPrimitive.longOrNull ?: error("timestamp"),
                    runId = obj["run_id"]!!.jsonPrimitive.content,
                    channel = obj["channel"]!!.jsonPrimitive.content,
                    seq = obj["seq"]!!.jsonPrimitive.longOrNull ?: error("seq"),
                    name = obj["name"]!!.jsonPrimitive.content,
                    kind = obj["kind"]!!.jsonPrimitive.content,
                    purposes = (obj["purposes"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet(),
                )
            }
        }
        return facts
    }

    /** §6.1 wire invariants（独立副本，同StabilityE2eTest口径）。 */
    private fun validateFacts(facts: List<FactLine>, windowStartMs: Long, windowEndMs: Long): List<String> {
        val failures = mutableListOf<String>()
        fun fail(message: String) {
            failures += message
        }

        if (facts.map { it.obj["producer_id"]?.jsonPrimitive?.content }.toSet().size != 1) fail("producer_id must be single")
        if (facts.map { it.obj["device_id"]?.jsonPrimitive?.content }.toSet().size != 1) fail("device_id must be stable")

        val eventIds = mutableListOf<String>()
        val seqsByRunChannel = mutableMapOf<Pair<String, String>, MutableList<Long>>()
        for (fact in facts) {
            val where = "${fact.file.fileName}:${fact.lineNo} (${fact.name})"
            val obj = fact.obj
            if (obj.keys.any { it !in factFieldNames }) fail("$where: unknown field(s) ${obj.keys - factFieldNames}")
            val required = factFieldNames - "context"
            if (!obj.keys.containsAll(required)) fail("$where: missing field(s) ${required - obj.keys}")
            if (obj["schema_version"]?.jsonPrimitive?.content != "1.0") fail("$where: schema_version must be 1.0")
            if (!uuidRegex.matches(fact.eventId)) fail("$where: event_id is not a UUID")
            if (fact.timestamp < windowStartMs || fact.timestamp > windowEndMs) fail("$where: timestamp outside window")
            if (fact.channel !in channels) fail("$where: channel ${fact.channel}")
            if (fact.file.parent.fileName.toString() != fact.channel) fail("$where: channel field disagrees with directory")
            if (fact.seq < 1) fail("$where: seq must start at 1")
            if (obj["account_epoch"]?.jsonPrimitive?.content != accountEpoch) fail("$where: unexpected account_epoch")
            if (obj["policy_revision"]?.jsonPrimitive?.longOrNull != 1L) fail("$where: policy_revision must be 1")
            if (fact.purposes.isEmpty() || fact.purposes.any { it !in purposeValues }) fail("$where: purposes $fact.purposes")
            if (obj["source"]?.jsonPrimitive?.content != "jetbrains-plugin") fail("$where: source")
            listOf("plugin_version", "ide_product", "ide_build", "ide_build_major", "os_family", "arch").forEach { key ->
                val value = obj[key]?.jsonPrimitive?.content
                if (value.isNullOrEmpty() || value == "unknown") fail("$where: $key must be a real value")
            }
            if (obj["env"]?.jsonPrimitive?.content !in envs) fail("$where: env")
            if (obj["mode"]?.jsonPrimitive?.content !in modes) fail("$where: mode")
            if (obj["side"]?.jsonPrimitive?.content !in sides) fail("$where: side")
            if (obj["connection_provider"]?.jsonPrimitive?.content !in providers) fail("$where: connection_provider")
            if (fact.kind !in kinds) fail("$where: kind ${fact.kind}")
            if (fact.name !in registeredNames) fail("$where: name '${fact.name}' not registered")
            obj["context"]?.let { context ->
                val contextObj = context as? JsonObject
                if (contextObj == null || contextObj.keys.any { it !in contextKeys }) fail("$where: context keys")
            }
            val data = obj["data"] as? JsonObject
            if (data == null) {
                fail("$where: data must be an object")
            } else {
                if (fact.name == "plugin.shutdown") {
                    val endKind = data["end_kind"]?.jsonPrimitive?.content
                    if (endKind !in endKinds) fail("$where: end_kind $endKind")
                }
                if (fact.kind == "operation") {
                    data["phase"]?.jsonPrimitive?.content?.let { if (it !in phaseValues) fail("$where: phase $it") }
                }
            }
            eventIds += fact.eventId
            seqsByRunChannel.getOrPut(fact.runId to fact.channel) { mutableListOf() } += fact.seq
        }

        eventIds.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { fail("duplicate event_id $it") }
        seqsByRunChannel.forEach { (runChannel, seqs) ->
            val sorted = seqs.sorted()
            if (sorted.first() != 1L) fail("$runChannel: seq must start at 1")
            if (seqs.toSet().size != seqs.size) fail("$runChannel: duplicate seq")
            val gaps = sorted.zipWithNext().filter { (a, b) -> b != a + 1 }
            if (gaps.isNotEmpty()) fail("$runChannel: seq gaps at $gaps")
        }
        return failures
    }

    // ------------------------------------------------------------------
    // Polls (tolerant mid-run scan of .open + sealed files)
    // ------------------------------------------------------------------

    private fun tolerantNames(outbox: Path): Set<String> =
        segmentFiles(outbox).flatMap { file ->
            val bytes = Files.readAllBytes(file)
            if (bytes.isEmpty()) return@flatMap emptyList()
            val text = bytes.toString(Charsets.UTF_8)
            val complete = if (text.endsWith("\n")) text else text.substringBeforeLast('\n', "")
            complete.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.parseToJsonElement(line).jsonObject["name"]?.jsonPrimitive?.content }.getOrNull()
                }
                .toList()
        }.toSet()

    private fun awaitSweepFacts(outbox: Path, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val names = tolerantNames(outbox)
            val missing = sweepExclusiveNames - names
            if (missing.isEmpty()) return
            Thread.sleep(1_000)
        }
        throw AssertionError(
            "self-test sweep facts not visible within ${timeoutMs}ms; missing: ${sweepExclusiveNames - tolerantNames(outbox)}",
        )
    }

    private fun awaitHealthFact(outbox: Path, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if ("telemetry.health" in tolerantNames(outbox)) return
            Thread.sleep(1_000)
        }
        throw AssertionError("telemetry.health never appeared within ${timeoutMs}ms")
    }

    private fun awaitFirstSealed(outbox: Path, timeoutMs: Long): Path {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            segmentFiles(outbox, setOf("ready")).firstOrNull()?.let { return it }
            Thread.sleep(1_000)
        }
        throw AssertionError("no .ready segment appeared within ${timeoutMs}ms")
    }

    // ------------------------------------------------------------------
    // Evidence retention (manual inspection)
    // ------------------------------------------------------------------

    private fun preserveEvidence(registration: Registration, facts: List<FactLine>) {
        val evidenceRoot = Path.of("out", "stability-evidence", "dictionary").toAbsolutePath()
        evidenceRoot.toFile().deleteRecursively()
        val outboxCopy = Files.createDirectories(evidenceRoot.resolve("outbox"))
        copyTree(registration.outboxPath, outboxCopy)
        val home = cloud.parent.resolve("telemetry")
        if (Files.isDirectory(home)) copyTree(home, Files.createDirectories(evidenceRoot.resolve("telemetry-home")))

        println("[dict] evidence kept at $evidenceRoot (outbox + telemetry-home)")
        println(
            "[dict] ${facts.size} facts, runs=${facts.map { it.runId }.distinct().size}, " +
                "channels=${facts.groupBy { it.channel }.mapValues { it.value.size }}",
        )
        facts.groupBy { it.name }.toSortedMap().forEach { (name, group) ->
            println("[dict]   $name × ${group.size}")
        }
        // 每个name一条完整样本行（人工检查速览；自检优先取ws-selftest标记的）
        val samples = evidenceRoot.resolve("samples.md")
        Files.writeString(
            samples,
            buildString {
                appendLine("# 全字典真实IDE落盘样本（每name一条，自检事实优先）")
                appendLine()
                facts.groupBy { it.name }.toSortedMap().forEach { (name, group) ->
                    val sample = group.firstOrNull {
                        it.obj["context"]?.jsonObject?.get("workspace_id")?.jsonPrimitive?.content == "ws-selftest"
                    } ?: group.first()
                    appendLine("## $name × ${group.size}")
                    appendLine("```json")
                    appendLine(sample.obj.toString())
                    appendLine("```")
                    appendLine()
                }
            },
        )
        println("[dict] per-name samples: $samples")
    }

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
}
