package ai.kilocode.backend.rpc

import ai.kilocode.backend.app.KiloAppState
import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.backend.testing.FakeCliServer
import ai.kilocode.backend.testing.MockCliServer
import ai.kilocode.backend.testing.TestLog
import ai.kilocode.rpc.dto.CommitMessageRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Commit-message generation over the session RPC: asserts the exact CLI HTTP
 * body (including provider/model passthrough) and result mapping.
 */
class CommitMessageRpcTest {
    private val mock = MockCliServer()
    private val log = TestLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val apps = mutableListOf<KiloBackendAppService>()

    @AfterTest
    fun tearDown() = runBlocking {
        apps.forEach { it.dispose() }
        apps.clear()
        scope.cancel()
        mock.close()
    }

    @Test
    fun `sends path and directory query to the cli`() = runBlocking {
        mock.commitMessage = """{"message":"feat: add login"}"""
        val app = app()

        val result = KiloSessionRpcApiImpl(app).generateCommitMessage(CommitMessageRequestDto(directory = "/repo"))

        assertEquals("feat: add login", result.message)
        assertEquals(null, result.error)
        assertFalse(result.noChanges)
        val path = assertNotNull(mock.lastCommitMessagePath)
        assertTrue(path.startsWith("/commit-message?"), path)
        assertTrue(path.contains("directory="), path)
        assertEquals("""{"path":"/repo"}""", mock.lastCommitMessageBody)
    }

    @Test
    fun `passes model selection and previous message through`() = runBlocking {
        val app = app()

        KiloSessionRpcApiImpl(app).generateCommitMessage(
            CommitMessageRequestDto(
                directory = "/repo",
                previousMessage = "old text",
                providerID = "anthropic",
                modelID = "claude-sonnet",
            ),
        )

        val body = assertNotNull(mock.lastCommitMessageBody)
        assertTrue(body.contains("""{"path":"/repo""""))
        assertTrue(body.contains(""""previousMessage":"old text""""))
        assertTrue(body.contains(""""providerID":"anthropic""""))
        assertTrue(body.contains(""""modelID":"claude-sonnet""""))
    }

    @Test
    fun `maps no-changes and failure errors without throwing`() = runBlocking {
        val app = app()
        val rpc = KiloSessionRpcApiImpl(app)

        mock.commitMessageStatus = 422
        mock.commitMessage = """{"message":"No changes found to generate a commit message for"}"""
        val empty = rpc.generateCommitMessage(CommitMessageRequestDto(directory = "/repo"))
        assertEquals(null, empty.message)
        assertTrue(empty.noChanges)
        assertEquals("No changes found to generate a commit message for", empty.error)

        mock.commitMessage = """{"message":"generation timed out"}"""
        val failed = rpc.generateCommitMessage(CommitMessageRequestDto(directory = "/repo"))
        assertFalse(failed.noChanges)
        assertEquals("generation timed out", failed.error)
    }

    @Test
    fun `caps commit message output length`() {
        val long = ("feat: x\n\n" + List(30) { "body line ${it + 1} with some padding text" }.joinToString("\n"))
        val capped = KiloSessionRpcApiImpl.capCommitMessage(long)
        assertTrue(capped.length <= 500, "length=${capped.length}")
        assertTrue(capped.lines().size <= 12, "lines=${capped.lines().size}")
        assertEquals("feat: x", capped.lines().first())
    }

    @Test
    fun `extracts only the final commit message from reasoning-laden replies`() {
        val reply = """
            The user wants a Git commit message. Let me think about this.

            ```
            chore: add stuff
            ```

            That is 16 characters. Good, under 72.

            Let me count again and verify the format follows the rules.

            chore: scaffold new Rust project

            Initialize demo-project with Cargo.toml.
        """.trimIndent()

        assertEquals(
            "chore: scaffold new Rust project\n\nInitialize demo-project with Cargo.toml.",
            KiloSessionRpcApiImpl.commitMessageFrom(reply),
        )
    }

    @Test
    fun `falls back to the conversation api when the endpoint is missing`() = runBlocking {
        mock.commitMessageStatus = 404
        mock.commitMessage = """{"message":"Not Found"}"""
        val repo = Files.createTempDirectory("kilo-commit-repo")
        try {
            git(repo, "init", "-q")
            Files.writeString(repo.resolve("tracked.txt"), "hello\n")
            git(repo, "add", "-A")
            git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
            Files.writeString(repo.resolve("new.py"), "print('hello costrict')\n")

            val app = app()
            assertTrue(mock.awaitSseConnection())

            val deferred = async {
                KiloSessionRpcApiImpl(app).generateCommitMessage(
                    CommitMessageRequestDto(directory = repo.toString(), providerID = "anthropic", modelID = "claude"),
                )
            }
            withTimeoutOrNull(10_000) {
                while (mock.lastPromptBody == null) delay(50)
            }
            assertNotNull(mock.lastPromptBody)
            val prompt = assertNotNull(mock.lastPromptBody)
            assertTrue(prompt.contains("Write a short Git commit message"), prompt)
            assertTrue(prompt.contains("\"model\":{\"providerID\":\"anthropic\",\"modelID\":\"claude\"}"), prompt)
            // untracked files are described with a content preview — plain git diff shows nothing for them
            assertTrue(prompt.contains("?? new.py"), prompt)
            assertTrue(prompt.contains("Content preview"), prompt)
            assertTrue(prompt.contains("print('hello costrict')"), prompt)

            mock.pushEvent(
                "message.updated",
                """{"properties":{"sessionID":"ses_test","info":{"id":"msg1","sessionID":"ses_test","role":"assistant","time":{"created":1000,"updated":1000}}}}""",
            )
            mock.pushEvent(
                "message.part.updated",
                """{"properties":{"sessionID":"ses_test","part":{"id":"prt1","sessionID":"ses_test","messageID":"msg1","type":"text","text":"feat: generated"}}}""",
            )
            mock.pushEvent("session.result", """{"properties":{"sessionID":"ses_test","isError":false}}""")

            val result = assertNotNull(deferred.await())
            assertEquals("feat: generated", result.message)
            assertEquals(null, result.error)
            // throwaway session is cleaned up
            assertTrue(mock.requestCount("/session/ses_test") >= 1)
        } finally {
            Files.walk(repo).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun git(dir: java.nio.file.Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(dir.toFile())
            .start()
        process.waitFor()
    }

    private suspend fun app(): KiloBackendAppService {
        val app = KiloBackendAppService.create(scope, FakeCliServer(mock), log).also { apps.add(it) }
        app.connect()
        val state = assertNotNull(
            withTimeoutOrNull(35_000) {
                app.appState.first {
                    it is KiloAppState.Ready || it is KiloAppState.Error || it is KiloAppState.MigrationRequired
                }
            },
            "App startup timed out in ${app.appState.value}; logs=${log.messages}",
        )
        assertIs<KiloAppState.Ready>(state, "App startup failed; logs=${log.messages}")
        return app
    }
}
