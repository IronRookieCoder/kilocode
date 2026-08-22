package ai.kilocode.cscloud

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CsCloudEndpointResolverTest {
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
    fun `blank values are treated as missing`() = withRoot {
        writeUrl("http://127.0.0.1")
        writeConfig("  ")

        val error = resolve(mapOf("CS_BRIDGE_API_KEY" to " ", "CS_CLOUD_API_KEY" to "\t")).exceptionOrNull()

        assertIs<CsCloudDiscoveryError.MissingApiKey>(error)
    }

    @Test
    fun `missing URL returns typed error`() = withRoot {
        val error = resolve().exceptionOrNull()
        assertIs<CsCloudDiscoveryError.MissingUrl>(error)
    }

    @Test
    fun `missing key returns typed error`() = withRoot {
        writeUrl("http://127.0.0.1")
        val error = resolve().exceptionOrNull()
        assertIs<CsCloudDiscoveryError.MissingApiKey>(error)
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

    private fun writeUrl(value: String) {
        root.resolve(".costrict/cs-cloud").createDirectories()
        root.resolve(".costrict/cs-cloud/server_url").writeText(value)
    }

    private fun writeConfig(key: String) {
        root.resolve(".costrict/cs-cloud").createDirectories()
        root.resolve(".costrict/cs-cloud/config.json").writeText("{\"api_key\":\"$key\"}")
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
