package ai.kilocode.cscloud.mcp

import kotlinx.serialization.Serializable

val COSTRICT_IDE_TOOLS: Set<String> = setOf(
    "analyze_calls", "get_file_problems", "lint_files", "get_project_dependencies",
    "get_project_modules", "get_symbol_info", "search_file", "search_regex", "search_symbol",
    "search_text", "read_file", "list_directory_tree", "get_all_open_file_paths",
    "open_file_in_editor", "build_project", "get_run_configurations", "execute_run_configuration",
)

@Serializable
data class IdeMcpTransportSpec(val type: String = "streamable_http", val url: String, val headers: Map<String, String>)

@Serializable
data class IdeMcpApprovalSpec(val build_project: String = "control_plane", val execute_run_configuration: String = "jetbrains_ask")

@Serializable
data class IdeMcpCapabilitySpec(
    val version: Int = 1,
    val generation: String,
    val workspace: String,
    val transport: IdeMcpTransportSpec,
    val tools: List<String>,
    val approval: IdeMcpApprovalSpec = IdeMcpApprovalSpec(),
)

data class IdeMcpTransport(val port: Int, val authHeader: String, val token: String) {
    override fun toString() = "IdeMcpTransport(port=$port, authHeader=<redacted>, token=<redacted>)"
}
