// kilocode_change - new file
package ai.kilocode.backend.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KiloBackendWorkspaceRefreshTest {
    @Test
    fun `host file events keep only canonical paths inside workspace`() {
        val root = Files.createTempDirectory("kilo-vfs-root").toRealPath()
        val inside = root.resolve("src").resolve("Main.kt")
        val outside = root.resolveSibling("outside").resolve("Secret.kt")

        val created = KiloBackendWorkspaceRefresh.paths(
            root,
            SseEvent("host.file.created", """{"type":"host.file.created","properties":{"path":"${json(inside.toString())}"}}"""),
        )
        val rejected = KiloBackendWorkspaceRefresh.paths(
            root,
            SseEvent("host.file.updated", """{"type":"host.file.updated","properties":{"path":"${json(outside.toString())}"}}"""),
        )

        assertEquals(listOf(inside), created)
        assertTrue(rejected.isEmpty())
        root.toFile().deleteRecursively()
    }

    @Test
    fun `renames refresh both paths and idle refreshes workspace`() {
        val root = Files.createTempDirectory("kilo-vfs-root").toRealPath()
        val old = root.resolve("old.txt")
        val next = root.resolve("new.txt")

        val rename = KiloBackendWorkspaceRefresh.paths(
            root,
            SseEvent(
                "host.file.renamed",
                """{"type":"host.file.renamed","properties":{"old_path":"${json(old.toString())}","new_path":"${json(next.toString())}"}}""",
            ),
        )
        val idle = KiloBackendWorkspaceRefresh.paths(
            root,
            SseEvent("session.idle", """{"type":"session.idle","properties":{"sessionID":"ses_1"}}"""),
        )

        assertEquals(listOf(next, old), rename)
        assertEquals(listOf(root), idle)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `unrelated and malformed events do not refresh`() {
        val root = Files.createTempDirectory("kilo-vfs-root").toRealPath()

        assertTrue(KiloBackendWorkspaceRefresh.paths(root, SseEvent("message.updated", "{}")).isEmpty())
        assertTrue(KiloBackendWorkspaceRefresh.paths(root, SseEvent("host.file.updated", "not-json")).isEmpty())
        root.toFile().deleteRecursively()
    }

    private fun json(value: String) = value.replace("\\", "\\\\")
}
