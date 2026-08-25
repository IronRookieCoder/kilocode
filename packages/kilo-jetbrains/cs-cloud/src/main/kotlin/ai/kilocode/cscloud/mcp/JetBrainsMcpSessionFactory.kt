package ai.kilocode.cscloud.mcp

import com.intellij.mcpserver.McpToolFilter
import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.diagnostic.Logger

class JetBrainsMcpSessionFactory : IdeMcpSessionFactory {
    override fun enabled(allow: Set<String>): Set<String> {
        val available = McpToolsProvider.EP.extensionList.flatMap { provider ->
            runCatching { provider.getTools() }.getOrElse {
                LOG.warn("MCP tools provider failed provider=${provider.javaClass.name} error=${it.javaClass.name}")
                emptyList()
            }
        }.filterTo(mutableSetOf()) { it.descriptor.name in allow }
        val filters = McpToolFilterProvider.EP.extensionList.flatMap { provider ->
            runCatching { provider.getFilters(null).value }.getOrElse {
                LOG.warn("MCP filter provider failed provider=${provider.javaClass.name} error=${it.javaClass.name}")
                emptyList()
            }
        }
        val initial = McpToolFilterProvider.McpToolFilterContext(available, emptySet())
        val context = filters.fold(initial) { current, filter -> filter.modify(current).apply(current) }
        return (context.allowedTools - context.disallowedTools).mapTo(mutableSetOf()) { it.descriptor.name }
    }

    override suspend fun open(tools: Set<String>, ready: suspend (IdeMcpTransport) -> Nothing): Nothing {
        McpServerService.getInstanceAsync().authorizedSession(
            McpServerService.McpSessionOptions(
                McpServerService.AskCommandExecutionMode.ASK,
                McpToolFilter.AllowList(tools),
            ),
        ) { port, header, token -> ready(IdeMcpTransport(port, header, token)) }
        error("authorized session ended")
    }

    companion object {
        private val LOG = Logger.getInstance(JetBrainsMcpSessionFactory::class.java)
    }
}
