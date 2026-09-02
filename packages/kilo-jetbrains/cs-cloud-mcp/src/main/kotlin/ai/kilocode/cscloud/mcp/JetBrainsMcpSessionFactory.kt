package ai.kilocode.cscloud.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolFilter
import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.InvocationTargetException

class JetBrainsMcpSessionFactory : IdeMcpSessionFactory {
    override fun enabled(allow: Set<String>): Set<String> {
        val available = McpToolsProvider.EP.extensionList.flatMap { provider ->
            runCatching { provider.getTools() }.getOrElse {
                LOG.warn("MCP tools provider failed provider=${provider.javaClass.name} error=${it.javaClass.name}")
                emptyList()
            }
        }.filterTo(mutableSetOf()) { it.descriptor.name in allow }
        return runCatching { filter(available) }.getOrElse {
            val err = cause(it)
            LOG.warn("MCP filter protocol failed error=${err.javaClass.name}", err)
            emptySet()
        }.mapTo(mutableSetOf()) { it.descriptor.name }
    }

    private fun filter(available: Set<McpTool>): Set<McpTool> {
        val type = McpToolFilterProvider::class.java.declaredClasses.single { it.simpleName == "McpToolFilterContext" }
        val ctor = type.constructors.singleOrNull { it.parameterCount == 1 }
        if (ctor != null) {
            val context = ctor.newInstance(available)
            val opts = McpServerService.McpSessionOptions(
                McpServerService.AskCommandExecutionMode.ASK,
                McpToolFilter.AllowList(available.mapTo(mutableSetOf()) { it.descriptor.name }),
            )
            McpToolFilterProvider.EP.extensionList.forEach { provider ->
                runCatching {
                    val method = McpToolFilterProvider::class.java.methods.single { it.name == "applyFilters" && it.parameterCount == 4 }
                    val mode = method.parameterTypes.last().enumConstants.single { (it as Enum<*>).name == "DIRECT" }
                    method.invoke(provider, context, null, opts, mode)
                }.onFailure {
                    val err = cause(it)
                    LOG.warn("MCP filter provider failed provider=${provider.javaClass.name} error=${err.javaClass.name}")
                }
            }
            return tools(context, "getOnTools") + tools(context, "getRouterOnlyTools")
        }

        val initial = type.constructors.single { it.parameterCount == 2 }.newInstance(emptySet<McpTool>(), available)
        val context = McpToolFilterProvider.EP.extensionList.fold(initial) { current, provider ->
            runCatching {
                val method = McpToolFilterProvider::class.java.methods.single { it.name == "getFilters" && it.parameterCount == 1 }
                val flow = method.invoke(provider, null) as StateFlow<*>
                val filters = flow.value as Collection<*>
                filters.fold(current) { value, filter ->
                    val modify = filter!!.javaClass.methods.single { it.name == "modify" && it.parameterCount == 1 }
                    val change = modify.invoke(filter, value)
                    val apply = change.javaClass.methods.single { it.name == "apply" && it.parameterCount == 1 }
                    apply.invoke(change, value)
                }
            }.getOrElse {
                val err = cause(it)
                LOG.warn("MCP filter provider failed provider=${provider.javaClass.name} error=${err.javaClass.name}")
                current
            }
        }
        return tools(context, "getAllowedTools") - tools(context, "getDisallowedTools")
    }

    private fun tools(context: Any, getter: String) =
        (context.javaClass.getMethod(getter).invoke(context) as Collection<*>).filterIsInstance<McpTool>().toSet()

    private fun cause(err: Throwable) = (err as? InvocationTargetException)?.targetException ?: err

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
