package ai.kilocode.cscloud.mcp

import com.intellij.openapi.extensions.ExtensionPointName

interface IdeMcpSessionFactory {
    fun enabled(allow: Set<String>): Set<String>
    suspend fun open(tools: Set<String>, ready: suspend (IdeMcpTransport) -> Nothing): Nothing

    companion object {
        val EP: ExtensionPointName<IdeMcpSessionFactory> =
            ExtensionPointName.create("ai.kilocode.jetbrains.ideMcpSessionFactory")
    }
}
