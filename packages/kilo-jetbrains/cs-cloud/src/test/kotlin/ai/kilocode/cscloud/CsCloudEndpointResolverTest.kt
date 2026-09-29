package ai.kilocode.cscloud

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CsCloudEndpointResolverTest {
    @Test
    fun `cs-bridge root is preferred over legacy cs-cloud`() = withRoot {
        writeUrl("http://127.0.0.1:8080", dir = "cs-bridge")
        writeUrl("http://127.0.0.1:9999")
        writeConfig("config-key")

        assertEquals(CsCloudEndpoint("http://127.0.0.1:8080", null), resolve().getOrThrow())
    }

    @Test
    fun `config key follows the discovered root`() = withRoot {
        writeUrl("http://127.0.0.1:8080", dir = "cs-bridge")
        writeConfig("bridge-key", dir = "cs-bridge")
        writeConfig("legacy-key")

        assertEquals("bridge-key", resolve().getOrThrow().key)
    }

    @Test
    fun `legacy cs-cloud root is used when cs-bridge has no server_url`() = withRoot {
        root.resolve(".costrict/cs-bridge").createDirectories()
        writeConfig("bridge-key", dir = "cs-bridge")
        writeUrl("http://127.0.0.1:8080")

        val found = resolve().getOrThrow()
        assertEquals("http://127.0.0.1:8080", found.base)
        assertNull(found.key)
    }

    @Test
    fun `empty server_url does not fall through to the other root`() = withRoot {
        writeUrl("http://127.0.0.1:8080")
        root.resolve(".costrict/cs-bridge").createDirectories()
        root.resolve(".costrict/cs-bridge/server_url").writeText("  ")

        assertIs<CsCloudDiscoveryError.MissingUrl>(resolve().exceptionOrNull())
    }

    @Test
    fun `missing URL returns typed error when only cs-bridge exists`() = withRoot {
        root.resolve(".costrict/cs-bridge").createDirectories()

        assertIs<CsCloudDiscoveryError.MissingUrl>(resolve().exceptionOrNull())
    }

    @Test
    fun `bridge key takes precedence over cloud key and config`() = withRoot {
        writeUrl("http://127.0.0.1:8080/")
        writeConfig("config-key")

        val result = resolve(mapOf("CS_BRIDGE_API_KEY" to " bridge ", "CS_CLOUD_API_KEY" to "cloud"))

        assertEquals(CsCloudEndpoint("http://127.0.0.1:8080", "bridge"), result.getOrThrow())
    }

    @Test
    fun `cloud key takes precedence over config`() = withRoot {
        writeUrl("http://localhost:8080")
        writeConfig("config-key")

        assertEquals("cloud", resolve(mapOf("CS_CLOUD_API_KEY" to "cloud")).getOrThrow().key)
    }

    @Test
    fun `config key is used when environment is absent`() = withRoot {
        writeUrl("http://[::1]:8080/")
        writeConfig("config-key")

        assertEquals("config-key", resolve().getOrThrow().key)
    }

    @Test
    fun `blank values disable authentication`() = withRoot {
        writeUrl("http://127.0.0.1")
        writeConfig("  ")

        assertNull(resolve(mapOf("CS_BRIDGE_API_KEY" to " ", "CS_CLOUD_API_KEY" to "\t")).getOrThrow().key)
    }

    @Test
    fun `missing URL returns typed error`() = withRoot {
        val error = resolve().exceptionOrNull()
        assertIs<CsCloudDiscoveryError.MissingUrl>(error)
    }

    @Test
    fun `missing key disables authentication`() = withRoot {
        writeUrl("http://127.0.0.1")
        assertNull(resolve().getOrThrow().key)
    }

    @Test
    fun `malformed and non loopback URLs are rejected`() = withRoot {
        writeUrl("not a URL")
        assertIs<CsCloudDiscoveryError.MalformedUrl>(resolve(mapOf("CS_BRIDGE_API_KEY" to "key")).exceptionOrNull())

        writeUrl("https://example.com/")
        assertIs<CsCloudDiscoveryError.NonLoopbackUrl>(resolve(mapOf("CS_BRIDGE_API_KEY" to "key")).exceptionOrNull())
    }

    private fun resolve(env: Map<String, String> = emptyMap()): Result<CsCloudEndpoint> =
        CsCloudEndpointResolver(root, env).resolve()

    private fun writeUrl(value: String, dir: String = "cs-cloud") {
        root.resolve(".costrict/$dir").createDirectories()
        root.resolve(".costrict/$dir/server_url").writeText(value)
    }

    private fun writeConfig(key: String, dir: String = "cs-cloud") {
        root.resolve(".costrict/$dir").createDirectories()
        root.resolve(".costrict/$dir/config.json").writeText("{\"api_key\":\"$key\"}")
    }

    private fun withRoot(block: CsCloudEndpointResolverTest.() -> Unit) {
        root = Files.createTempDirectory("cs-cloud-test")
        try {
            block()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private lateinit var root: Path
}
