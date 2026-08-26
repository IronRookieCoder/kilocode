package ai.kilocode.cscloud

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CsCloudEndpointResolverTest {

    private val configDir: Path = Files.createTempDirectory("cscloud-config")

    @Test
    fun `resolves endpoint from server_url and CS_BRIDGE_API_KEY`() {
        configDir.resolve("server_url").writeText("http://127.0.0.1:3012\n")
        val resolver = CsCloudEndpointResolver(configDir) { name ->
            if (name == "CS_BRIDGE_API_KEY") "bridge-key" else null
        }
        val endpoint = resolver.resolve().getOrThrow()
        assertEquals("http://127.0.0.1:3012", endpoint.base)
        assertEquals("bridge-key", endpoint.key)
    }

    @Test
    fun `falls back to CS_CLOUD_API_KEY when bridge key is absent`() {
        configDir.resolve("server_url").writeText("http://localhost:3012")
        val resolver = CsCloudEndpointResolver(configDir) { name ->
            if (name == "CS_CLOUD_API_KEY") "cloud-key" else null
        }
        assertEquals("cloud-key", resolver.resolve().getOrThrow().key)
    }

    @Test
    fun `reads api_key from config json when no env key is set`() {
        configDir.resolve("server_url").writeText("http://127.0.0.1:3012")
        configDir.resolve("config.json").writeText("""{"api_key":"cfg-key","other":1}""")
        val resolver = CsCloudEndpointResolver(configDir) { null }
        assertEquals("cfg-key", resolver.resolve().getOrThrow().key)
    }

    @Test
    fun `env key wins over config json key`() {
        configDir.resolve("server_url").writeText("http://127.0.0.1:3012")
        configDir.resolve("config.json").writeText("""{"api_key":"cfg-key"}""")
        val resolver = CsCloudEndpointResolver(configDir) { name ->
            if (name == "CS_BRIDGE_API_KEY") "env-key" else null
        }
        assertEquals("env-key", resolver.resolve().getOrThrow().key)
    }

    @Test
    fun `missing server_url is a discovery failure`() {
        val resolver = CsCloudEndpointResolver(configDir) { null }
        val failure = resolver.resolve().exceptionOrNull()
        assertTrue(failure is CsCloudDiscoveryError.MissingUrl)
    }

    @Test
    fun `missing api key is a discovery failure`() {
        configDir.resolve("server_url").writeText("http://127.0.0.1:3012")
        val resolver = CsCloudEndpointResolver(configDir) { null }
        val failure = resolver.resolve().exceptionOrNull()
        assertTrue(failure is CsCloudDiscoveryError.MissingKey)
    }

    @Test
    fun `non-loopback host is rejected`() {
        configDir.resolve("server_url").writeText("http://example.com:3012")
        val resolver = CsCloudEndpointResolver(configDir) { name ->
            if (name == "CS_BRIDGE_API_KEY") "key" else null
        }
        val failure = resolver.resolve().exceptionOrNull()
        assertTrue(failure is CsCloudDiscoveryError.NonLoopbackUrl)
    }

    @Test
    fun `malformed url is rejected`() {
        configDir.resolve("server_url").writeText("not a url")
        val resolver = CsCloudEndpointResolver(configDir) { name ->
            if (name == "CS_BRIDGE_API_KEY") "key" else null
        }
        assertFailsWith<CsCloudDiscoveryError.MalformedUrl> { resolver.resolve().getOrThrow() }
    }

    @Test
    fun `malformed config json is rejected`() {
        configDir.resolve("server_url").writeText("http://127.0.0.1:3012")
        configDir.resolve("config.json").writeText("{ not json")
        val resolver = CsCloudEndpointResolver(configDir) { null }
        assertFailsWith<CsCloudDiscoveryError.MalformedConfig> { resolver.resolve().getOrThrow() }
    }

    @Test
    fun `ipv6 loopback and bracketed hosts are accepted`() {
        for (host in listOf("http://[::1]:3012", "http://[0:0:0:0:0:0:0:1]:3012")) {
            configDir.resolve("server_url").writeText(host)
            val resolver = CsCloudEndpointResolver(configDir) { name ->
                if (name == "CS_BRIDGE_API_KEY") "key" else null
            }
            assertTrue(resolver.resolve().isSuccess, "expected $host to be accepted")
        }
    }

    @Test
    fun `trailing slash is trimmed from base`() {
        configDir.resolve("server_url").writeText("http://127.0.0.1:3012/")
        val resolver = CsCloudEndpointResolver(configDir) { name ->
            if (name == "CS_BRIDGE_API_KEY") "key" else null
        }
        assertEquals("http://127.0.0.1:3012", resolver.resolve().getOrThrow().base)
    }
}
