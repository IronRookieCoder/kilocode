package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StorageTest {
    @Test
    fun `existing outbox link is rejected before target permissions change`() {
        val base = Files.createTempDirectory("stability-link")
        val target = Files.createDirectory(base.resolve("target"))
        val link = base.resolve("outbox")
        val permissions = permissions(target)
        try {
            link(link, target)
            assertFailsWith<StorageUnverifiedException> { Storage(link).verifyLayout() }
            assertEquals(permissions, permissions(target))
        } finally {
            Files.deleteIfExists(link)
            Files.delete(target)
            Files.delete(base)
        }
    }

    @Test
    fun `existing linked ancestor is rejected when outbox already exists`() {
        val base = Files.createTempDirectory("stability-ancestor")
        val target = Files.createDirectory(base.resolve("target"))
        val outbox = Files.createDirectory(target.resolve("outbox"))
        val link = base.resolve("telemetry")
        try {
            link(link, target)
            assertFailsWith<StorageUnverifiedException> { Storage(link.resolve("outbox")).verifyLayout() }
        } finally {
            Files.deleteIfExists(link)
            Files.delete(outbox)
            Files.delete(target)
            Files.delete(base)
        }
    }

    private fun link(path: Path, target: Path) {
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            Files.createSymbolicLink(path, target)
            return
        }
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", path.toString(), target.toString())
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }

    private fun permissions(path: Path): String {
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) return Files.getPosixFilePermissions(path).toString()
        return Files.getFileAttributeView(path, AclFileAttributeView::class.java).acl.toString()
    }
}
