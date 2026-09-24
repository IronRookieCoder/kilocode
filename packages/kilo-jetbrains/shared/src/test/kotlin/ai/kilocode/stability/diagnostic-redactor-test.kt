package ai.kilocode.stability

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRedactorTest {

    @Test
    fun `redacts credentials while preserving Windows paths`() {
        val input = "path=C:\\work\\a.kt Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.a.b password=hunter2"

        val out = DiagnosticRedactor.clean(input)

        assertTrue("C:\\work\\a.kt" in out.text)
        assertFalse("eyJhbGciOiJIUzI1NiJ9.a.b" in out.text)
        assertFalse("hunter2" in out.text)
        assertTrue("<redacted:authorization>" in out.text)
        assertTrue("<redacted:password>" in out.text)
        assertTrue(out.changed)
    }

    @Test
    fun `redacts JSON headers URLs stacks PEM and sensitive environment values`() {
        val input = """
            {"access_token":"token-value","client_secret":"client-value","name":"ordinary business value"}
            Proxy-Authorization: Basic cHJveHk6c2VjcmV0
            Cookie: session=secret-cookie
            Set-Cookie: refresh=another-secret-cookie
            https://example.test/api?api_key=url-secret&keep=ordinary
            java.lang.IllegalStateException: refresh_token=refresh-secret
            -----BEGIN PRIVATE KEY-----
            very-secret-key-material
            -----END PRIVATE KEY-----
            OPENAI_API_KEY=env-secret
        """.trimIndent()

        val out = DiagnosticRedactor.clean(input)

        listOf(
            "token-value", "client-value", "cHJveHk6c2VjcmV0", "secret-cookie", "another-secret-cookie", "url-secret",
            "refresh-secret", "very-secret-key-material", "env-secret",
        ).forEach { secret -> assertFalse(secret in out.text, secret) }
        assertTrue("ordinary business value" in out.text)
        assertTrue("keep=ordinary" in out.text)
        assertTrue("<redacted:access-token>" in out.text)
        assertTrue("<redacted:client-secret>" in out.text)
        assertTrue("<redacted:proxy-authorization>" in out.text)
        assertTrue("<redacted:cookie>" in out.text)
        assertTrue("<redacted:set-cookie>" in out.text)
        assertTrue("<redacted:api-token>" in out.text)
        assertTrue("<redacted:refresh-token>" in out.text)
        assertTrue("<redacted:private-key>" in out.text)
        assertTrue(out.changed)
    }

    @Test
    fun `leaves ordinary values unchanged`() {
        val input = "customer=acme path=C:\\work\\a.kt amount=42 status=retry"

        val out = DiagnosticRedactor.clean(input)

        assertTrue(out.text == input)
        assertFalse(out.changed)
    }

    @Test
    fun `redacts complete authorization values for every scheme`() {
        val input = """
            Authorization: Basic basic-secret
            Authorization: Digest username="alice", response="digest-secret", realm="example"
            Proxy-Authorization: Negotiate proxy-secret
            context Authorization: Basic inline-basic-secret password=still-secret
        """.trimIndent()

        val out = DiagnosticRedactor.clean(input)

        listOf("basic-secret", "digest-secret", "proxy-secret", "inline-basic-secret", "still-secret").forEach { secret ->
            assertFalse(secret in out.text, secret)
        }
        assertTrue("<redacted:password>" in out.text)
    }

    @Test
    fun `redacts escaped JSON values and cloud credentials anywhere in text`() {
        val input = """
            {"password":"prefix\"SENSITIVE-SUFFIX","AWS_SECRET_ACCESS_KEY":"aws-json-secret"}
            diagnostic OPENAI_API_KEY="env secret with spaces"
            trace AWS_ACCESS_KEY_ID=access-id-secret
        """.trimIndent()

        val out = DiagnosticRedactor.clean(input)

        listOf("prefix", "SENSITIVE-SUFFIX", "aws-json-secret", "env secret with spaces", "access-id-secret").forEach { secret ->
            assertFalse(secret in out.text, secret)
        }
        assertTrue(out.changed)
    }

    @Test
    fun `redacts real JWTs without changing ordinary dotted identifiers`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature"
        val input = "class=ai.kilocode.backend.app.Service host=example.test/path jwt=$jwt"

        val out = DiagnosticRedactor.clean(input)

        assertFalse(jwt in out.text)
        assertTrue("ai.kilocode.backend.app.Service" in out.text)
        assertTrue("example.test/path" in out.text)
    }

    @Test
    fun `redacts URL userinfo and unterminated PEM blocks`() {
        val input = """
            https://user:url-password@example.test/fail
            -----BEGIN PRIVATE KEY-----
            unterminated-private-material
        """.trimIndent()

        val out = DiagnosticRedactor.clean(input)

        assertFalse("url-password" in out.text)
        assertFalse("unterminated-private-material" in out.text)
        assertTrue("example.test/fail" in out.text)
        assertTrue("<redacted:private-key>" in out.text)
    }
}
