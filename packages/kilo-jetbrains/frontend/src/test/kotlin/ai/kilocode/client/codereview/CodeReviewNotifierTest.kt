// kilocode_change - new file
package ai.kilocode.client.codereview

import ai.kilocode.rpc.dto.CodeReviewReportDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeReviewNotifierTest {
    @Test
    fun `reports match only the owning workspace directory`() {
        val report = report("C:/work/demo")
        assertTrue(CodeReviewNotifier.matches(report, "C:\\work\\demo"))
        assertTrue(CodeReviewNotifier.matches(report, "C:/work/demo/"))
        assertFalse(CodeReviewNotifier.matches(report, "C:/work/other"))
        assertFalse(CodeReviewNotifier.matches(report, null))
    }

    private fun report(directory: String) = CodeReviewReportDto(
        directory = directory,
        reportJsonPath = "$directory/code-review_result/review-report.json",
        reportMdPath = "$directory/code-review_result/review-report.md",
        marker = "I-AM-CODE-REVIEW-REPORT-V1",
        highCount = 1,
        middleCount = 2,
        lowCount = 3,
    )
}
