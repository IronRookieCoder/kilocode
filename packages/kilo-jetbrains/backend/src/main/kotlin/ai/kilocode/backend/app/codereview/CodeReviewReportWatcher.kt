// kilocode_change - new file
package ai.kilocode.backend.app.codereview

import ai.kilocode.backend.app.KiloBackendWorkspaceRefresh
import ai.kilocode.backend.app.SseEvent
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.CodeReviewReportDto
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Detects completed CoStrict code-review reports from `host.file.*` SSE events.
 *
 * The review skill writes `<workspace>/code-review_result/review-report.json`
 * (plus a human-readable `review-report.md` twin). Completion is judged by the
 * file, not by session state: once the JSON's size and mtime stay unchanged for
 * [STABLE_WINDOW_MS] it is parsed once and emitted. Repeated events for the
 * same (size, mtime) pair are deduplicated.
 */
class CodeReviewReportWatcher(
    private val cs: CoroutineScope,
    private val roots: () -> List<String>,
    private val emit: (CodeReviewReportDto) -> Unit,
    private val log: KiloLog,
) {
    companion object {
        const val REPORT_MARKER = "I-AM-CODE-REVIEW-REPORT-V1"
        const val REPORT_SUFFIX = "code-review_result/review-report.json"
        const val REPORT_JSON = "review-report.json"
        const val REPORT_MD = "review-report.md"
        const val STABLE_WINDOW_MS = 1_000L
        const val MAX_STABLE_TRIES = 30

        internal val json = Json { ignoreUnknownKeys = true }

        /** The report JSON path inside [root] this event refers to, or null. */
        internal fun reportPath(root: Path, event: SseEvent): Path? =
            KiloBackendWorkspaceRefresh.paths(root, event).firstOrNull { path ->
                path.fileName?.toString() == REPORT_JSON &&
                    path.toString().replace('\\', '/').endsWith(REPORT_SUFFIX)
            }

        /** Parse the report body into a DTO; degraded when marker/schema unknown. */
        internal fun parse(body: String, directory: String, jsonPath: Path): CodeReviewReportDto {
            val md = jsonPath.resolveSibling(REPORT_MD)
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return degraded(directory, jsonPath, md)
            val marker = obj["report"]?.jsonPrimitive?.contentOrNull ?: ""
            val issues = runCatching { obj["issues"]?.jsonArray }.getOrNull()
            if (marker != REPORT_MARKER || issues == null || !issues.all { it is JsonObject }) {
                return degraded(directory, jsonPath, md)
            }
            var high = 0
            var middle = 0
            var low = 0
            for (issue in issues) {
                when (issue.jsonObject["severity"]?.jsonPrimitive?.contentOrNull) {
                    "高" -> high += 1
                    "中" -> middle += 1
                    "低" -> low += 1
                }
            }
            return CodeReviewReportDto(
                directory = directory,
                reportJsonPath = jsonPath.toString(),
                reportMdPath = md.toString(),
                marker = marker,
                highCount = high,
                middleCount = middle,
                lowCount = low,
                qualitySummary = qualityLine(obj["conclusion"]?.jsonPrimitive?.contentOrNull),
                degraded = false,
            )
        }

        /** Extract the `**质量评分**：X` line from the conclusion Markdown, if present. */
        internal fun qualityLine(conclusion: String?): String? =
            conclusion
                ?.lineSequence()
                ?.firstOrNull { it.contains("质量评分") }
                ?.replace(Regex("[*#]"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun degraded(directory: String, jsonPath: Path, md: Path) = CodeReviewReportDto(
            directory = directory,
            reportJsonPath = jsonPath.toString(),
            reportMdPath = md.toString(),
            marker = "",
            highCount = 0,
            middleCount = 0,
            lowCount = 0,
            qualitySummary = null,
            degraded = true,
        )
    }

    private data class Stat(val size: Long, val mtime: Long)

    private val lastEmitted = ConcurrentHashMap<String, Stat>()

    /** Handle one `host.file.*` event; schedules stability confirmation on the app scope. */
    fun handle(event: SseEvent) {
        if (event.type != "host.file.created" && event.type != "host.file.updated") return
        for (rootPath in roots()) {
            val root = runCatching { Path.of(rootPath).toAbsolutePath().normalize() }.getOrNull() ?: continue
            val report = reportPath(root, event) ?: continue
            cs.launch {
                try {
                    val stat = awaitStable(report) ?: return@launch
                    val key = report.toString()
                    if (lastEmitted[key] == stat) return@launch
                    val body = runCatching { Files.readString(report) }.getOrNull()
                    if (body == null) {
                        log.warn("kind=codereview-report read=false path=$key")
                        return@launch
                    }
                    val dto = parse(body, root.toString(), report)
                    lastEmitted[key] = stat
                    log.info(
                        "kind=codereview-report ok=true degraded=${dto.degraded} " +
                            "high=${dto.highCount} middle=${dto.middleCount} low=${dto.lowCount} path=$key",
                    )
                    emit(dto)
                } catch (e: Exception) {
                    log.warn("kind=codereview-report failed path=$report", e)
                }
            }
        }
    }

    /** Poll size+mtime until stable across one window; null when absent or never settles. */
    private suspend fun awaitStable(path: Path): Stat? {
        repeat(MAX_STABLE_TRIES) {
            val first = stat(path) ?: return null
            delay(STABLE_WINDOW_MS)
            val second = stat(path) ?: return null
            if (first == second) return first
        }
        return null
    }

    private fun stat(path: Path): Stat? = runCatching {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        Stat(attrs.size(), attrs.lastModifiedTime().toMillis())
    }.getOrNull()
}
