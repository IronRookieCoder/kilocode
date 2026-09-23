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
}
