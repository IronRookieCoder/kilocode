package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the local cs-cloud control-plane endpoint from the user's Costrict config:
 *  - `~/.costrict/cs-cloud/server_url` carries the daemon base URL
 *  - the API key comes from `CS_BRIDGE_API_KEY`, then `CS_CLOUD_API_KEY`, then `config.json` `api_key`
 *
 * Safety: only loopback hosts (`127.0.0.1`, `localhost`, `::1`) are accepted; any other
 * host is rejected so a tampered config cannot point the plugin at a remote server.
 */
class CsCloudEndpointResolver(
    private val configDir: Path = defaultConfigDir(),
    private val environment: (String) -> String? = { System.getenv(it) },
) {

    /** @return the resolved endpoint, or a failure with a [CsCloudDiscoveryError] reason. */
    fun resolve(): Result<CsCloudEndpoint> = runCatching {
        val url = readServerUrl()
        if (url.isEmpty()) throw CsCloudDiscoveryError.MissingUrl()
        val base = normalizeBase(url)
        val key = readApiKey() ?: throw CsCloudDiscoveryError.MissingKey()
        CsCloudEndpoint(base, key)
    }

    private fun readServerUrl(): String {
        val file = configDir.resolve("server_url")
        if (!Files.exists(file)) throw CsCloudDiscoveryError.MissingUrl()
        return try {
            Files.readString(file).trim()
        } catch (e: Exception) {
            throw CsCloudDiscoveryError.UnreadableUrl(e)
        }
    }

    private fun normalizeBase(url: String): String {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw CsCloudDiscoveryError.MalformedUrl()
        }
        if (uri.scheme !in setOf("http", "https")) throw CsCloudDiscoveryError.MalformedUrl()
        val host = uri.host?.lowercase() ?: throw CsCloudDiscoveryError.MalformedUrl()
        if (!isLoopback(host)) throw CsCloudDiscoveryError.NonLoopbackUrl()
        return url.trimEnd('/')
    }

    private fun readApiKey(): String? {
        environment("CS_BRIDGE_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        environment("CS_CLOUD_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val config = configDir.resolve("config.json")
        if (!Files.exists(config)) return null
        return try {
            val root = json.parseToJsonElement(Files.readString(config)).jsonObject
            root["api_key"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            throw CsCloudDiscoveryError.MalformedConfig(e)
        }
    }

    private fun isLoopback(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        return h == "localhost" ||
            h == "127.0.0.1" ||
            h.startsWith("127.") ||
            h == "::1" ||
            h == "0:0:0:0:0:0:0:1"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultConfigDir(): Path =
            Path.of(System.getProperty("user.home", "."), ".costrict", "cs-cloud")
    }
}
