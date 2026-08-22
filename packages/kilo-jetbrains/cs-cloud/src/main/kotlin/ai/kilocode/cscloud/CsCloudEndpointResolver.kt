package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Discovers the endpoint written by a running cs-cloud daemon. */
class CsCloudEndpointResolver(
    private val home: Path,
    private val env: Map<String, String>,
) {
    fun resolve(): Result<CsCloudEndpoint> = runCatching {
        val root = home.resolve(".costrict").resolve("cs-cloud")
        val raw = readUrl(root.resolve("server_url"))
        val base = normalize(raw)
        val key = env["CS_BRIDGE_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: env["CS_CLOUD_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: readKey(root.resolve("config.json"))
            ?: throw CsCloudDiscoveryError.MissingApiKey()
        CsCloudEndpoint(base, key)
    }.recoverCatching { error ->
        when (error) {
            is CsCloudDiscoveryError -> throw error
            else -> throw CsCloudDiscoveryError.UnreadableUrl(error)
        }
    }

    private fun readUrl(path: Path): String {
        if (!Files.exists(path)) throw CsCloudDiscoveryError.MissingUrl()
        val value = try {
            Files.readString(path, StandardCharsets.UTF_8).trim()
        } catch (error: Exception) {
            throw CsCloudDiscoveryError.UnreadableUrl(error)
        }
        if (value.isEmpty()) throw CsCloudDiscoveryError.MissingUrl()
        return value
    }

    private fun readKey(path: Path): String? {
        if (!Files.exists(path)) return null
        val value = try {
            val json = Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject
            json["api_key"]?.jsonPrimitive?.content?.trim()
        } catch (error: Exception) {
            throw CsCloudDiscoveryError.MalformedConfig(error)
        }
        return value?.takeIf { it.isNotEmpty() }
    }

    private fun normalize(raw: String): String {
        val uri = try {
            URI(raw)
        } catch (error: Exception) {
            throw CsCloudDiscoveryError.MalformedUrl()
        }
        val host = uri.host?.lowercase()?.trim('[', ']')
        if (uri.isOpaque || !uri.isAbsolute || uri.scheme.lowercase() !in setOf("http", "https") || host == null ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
        ) throw CsCloudDiscoveryError.MalformedUrl()
        if (host != "localhost" && host != "127.0.0.1" && host != "::1") {
            throw CsCloudDiscoveryError.NonLoopbackUrl()
        }
        return raw.trimEnd('/')
    }
}
