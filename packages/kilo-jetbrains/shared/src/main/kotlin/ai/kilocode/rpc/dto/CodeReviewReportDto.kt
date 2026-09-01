package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/**
 * A completed CoStrict code-review report detected in a workspace.
 *
 * Emitted by the backend once `<workspace>/code-review_result/review-report.json`
 * appears and its size/mtime stays stable. [degraded] means the report could not
 * be parsed with the known schema (unknown `report` marker or malformed JSON) —
 * the Markdown twin is still worth opening.
 */
@Serializable
data class CodeReviewReportDto(
    val directory: String,
    val reportJsonPath: String,
    val reportMdPath: String,
    val marker: String,
    val highCount: Int,
    val middleCount: Int,
    val lowCount: Int,
    val qualitySummary: String? = null,
    val degraded: Boolean = false,
)
