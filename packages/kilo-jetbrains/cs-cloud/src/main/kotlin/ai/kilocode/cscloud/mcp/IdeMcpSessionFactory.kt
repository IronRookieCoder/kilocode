package ai.kilocode.cscloud.mcp

import ai.kilocode.KiloPlugin
import com.intellij.openapi.extensions.ExtensionPointName

interface IdeMcpSessionFactory {
    fun enabled(allow: Set<String>): Set<String>
    suspend fun open(tools: Set<String>, ready: suspend (IdeMcpTransport) -> Nothing): Nothing

    companion object {
        val EP: ExtensionPointName<IdeMcpSessionFactory> =
            ExtensionPointName.create("${KiloPlugin.ID}.ideMcpSessionFactory")
    }
}
