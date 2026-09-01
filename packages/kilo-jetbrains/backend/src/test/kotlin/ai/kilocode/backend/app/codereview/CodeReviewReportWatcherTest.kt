// kilocode_change - new file
package ai.kilocode.backend.app.codereview

import ai.kilocode.backend.app.SseEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeReviewReportWatcherTest {
    @Test
    fun `report path matches only the report json under code-review_result`() {
        val root = Files.createTempDirectory("kilo-review-root").toRealPath()
        val dir = root.resolve("code-review_result").also { Files.createDirectories(it) }
        val report = dir.resolve("review-report.json")
        Files.writeString(report, "{}")

        val hit = CodeReviewReportWatcher.reportPath(
            root,
            SseEvent("host.file.created", """{"type":"host.file.created","properties":{"path":"${json(report.toString())}"}}"""),
        )
        val miss = CodeReviewReportWatcher.reportPath(
            root,
            SseEvent("host.file.updated", """{"type":"host.file.updated","properties":{"path":"${json(root.resolve("src/Main.kt").toString())}"}}"""),
        )
        val outside = CodeReviewReportWatcher.reportPath(
            root,
            SseEvent("host.file.created", """{"type":"host.file.created","properties":{"path":"${json(root.resolveSibling("other").resolve("code-review_result").resolve("review-report.json").toString())}"}}"""),
        )

        assertEquals(report, hit)
        assertNull(miss)
        assertNull(outside)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `parse counts chinese severities and extracts quality line`() {
        val root = Files.createTempDirectory("kilo-review-root").toRealPath()
        val report = root.resolve("review-report.json")
        val body = """
            {
              "report": "I-AM-CODE-REVIEW-REPORT-V1",
              "issues": [
                {"severity": "高", "type": "逻辑缺陷", "location": "src/a.ts:1-2", "title": "a"},
                {"severity": "中", "type": "静态缺陷", "location": "src/b.ts:3", "title": "b"},
                {"severity": "低", "type": "内存问题", "location": "src/c.ts:5-9", "title": "c"},
                {"severity": "未知", "type": "静态缺陷", "location": "src/d.ts:1", "title": "d"}
              ],
              "conclusion": "### CoStrict评审摘要\n**质量评分**：良好\n**评分详情**：\n- 安全漏洞：高 0 / 中 0 / 低 0",
              "extra": {"future": "field"}
            }
        """.trimIndent()

        val dto = CodeReviewReportWatcher.parse(body, root.toString(), report)

        assertEquals("I-AM-CODE-REVIEW-REPORT-V1", dto.marker)
        assertEquals(1, dto.highCount)
        assertEquals(1, dto.middleCount)
        assertEquals(1, dto.lowCount)
        assertEquals("质量评分：良好", dto.qualitySummary)
        assertEquals(report.resolveSibling("review-report.md").toString(), dto.reportMdPath)
        assertTrue(!dto.degraded)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `parse degrades on unknown marker and malformed json`() {
        val root = Files.createTempDirectory("kilo-review-root").toRealPath()
        val report = root.resolve("review-report.json")

        val unknownMarker = CodeReviewReportWatcher.parse(
            """{"report": "V2-OTHER", "issues": []}""",
            root.toString(),
            report,
        )
        val malformed = CodeReviewReportWatcher.parse("not json", root.toString(), report)

        assertTrue(unknownMarker.degraded)
        assertTrue(malformed.degraded)
        assertEquals("", unknownMarker.marker)
        root.toFile().deleteRecursively()
    }

    private fun json(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
