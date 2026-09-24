package ai.kilocode.stability

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScopeIdStoreTest {
    private val dir = Files.createTempDirectory("stability-scope")
    private val file = dir.resolve("kilo-stability-scope-id")

    @AfterTest
    fun cleanup() {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `first creation returns a complete scope readable after the forced channel closes`() {
        val id = FileScopeIdStore(dir).loadOrCreate()

        assertTrue(Regex("^sc-[0-9a-f]{12}$").matches(id))
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(16)
            while (channel.read(buffer) > 0) Unit
            assertEquals(id, String(buffer.array(), 0, buffer.position(), Charsets.UTF_8))
            assertEquals(15L, channel.size())
        }
        assertEquals(id, FileScopeIdStore(dir).loadOrCreate())
        Files.list(dir).use { paths -> assertEquals(listOf(file), paths.toList()) }
    }

    @Test
    fun `first creation atomically persists a valid legacy setting and never reads it again`() {
        val legacy = "sc-0123456789ab"
        assertEquals(legacy, FileScopeIdStore(dir) { legacy }.loadOrCreate())
        assertEquals(legacy, Files.readString(file))
        Files.list(dir).use { paths -> assertEquals(listOf(file), paths.toList()) }
        val store = FileScopeIdStore(dir) { error("existing scope must ignore legacy settings") }
        assertEquals(legacy, store.loadOrCreate())
    }

    @Test
    fun `invalid legacy settings cannot become a scope or path`() {
        listOf("", "sc-short", "../other-scope", "sc-0123456789ag").forEach { legacy ->
            val id = FileScopeIdStore(dir) { legacy }.loadOrCreate()
            assertTrue(Regex("^sc-[0-9a-f]{12}$").matches(id))
            assertNotEquals(legacy, id)
            assertEquals(id, Files.readString(file))
            Files.delete(file)
        }
    }

    @Test
    fun `legacy setting failure does not publish a replacement scope`() {
        assertFailsWith<IOException> { FileScopeIdStore(dir) { throw IOException("unavailable") }.loadOrCreate() }
        Files.list(dir).use { paths -> assertTrue(paths.toList().isEmpty()) }
    }

    @Test
    fun `existing scope is reused across store instances without rewriting`() {
        val id = "sc-0123456789ab"
        Files.writeString(file, id)
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000))
        val stamp = Files.getLastModifiedTime(file)
        val store = FileScopeIdStore(dir)

        assertEquals(id, store.loadOrCreate())
        assertEquals(id, store.loadOrCreate())
        assertEquals(id, FileScopeIdStore(dir).loadOrCreate())
        assertEquals(stamp, Files.getLastModifiedTime(file))
    }

    @Test
    fun `partial invalid and oversized contents are replaced by a complete random scope`() {
        listOf("", "sc-0123", "sc-0123456789ag", "sc-0123456789ab\n", "../other-scope", "x".repeat(4096))
            .forEach { content ->
                Files.writeString(file, content)
                val id = FileScopeIdStore(dir).loadOrCreate()
                assertTrue(Regex("^sc-[0-9a-f]{12}$").matches(id))
                assertNotEquals(content, id)
                assertEquals(id, Files.readString(file))
                assertEquals(id, FileScopeIdStore(dir).loadOrCreate())
            }
    }

    @Test
    fun `concurrent stores publish and return one scope`() {
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(8).use { pool ->
            val tasks = List(8) {
                pool.submit<String> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    FileScopeIdStore(dir).loadOrCreate()
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val ids = tasks.map { it.get(10, TimeUnit.SECONDS) }.toSet()
            assertEquals(setOf(Files.readString(file)), ids)
        }
    }

    @Test
    fun `nonregular scope path fails without replacing its contents or returning a random id`() {
        Files.createDirectory(file)
        val child = Files.writeString(file.resolve("keep"), "unchanged")

        assertFailsWith<IOException> { FileScopeIdStore(dir).loadOrCreate() }
        assertEquals("unchanged", Files.readString(child))
        Files.list(dir).use { paths -> assertEquals(listOf(file), paths.toList()) }
    }

    @Test
    fun `scope survives a child JVM halting immediately after creation`() {
        val cp = listOf(ScopeIdProcess::class.java, FileScopeIdStore::class.java, Unit::class.java)
            .map(::location)
            .distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElseThrow(),
            "-cp", cp, ScopeIdProcess::class.java.name, dir.toString(),
        ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "scope child did not halt")
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            assertEquals(0, process.exitValue(), output)
            assertEquals(output, Files.readString(file))
            assertEquals(output, FileScopeIdStore(dir).loadOrCreate())
        } finally {
            process.destroyForcibly()
        }
    }

    private fun location(type: Class<*>): String {
        val name = type.name.replace('.', '/') + ".class"
        val url = requireNotNull(type.getResource("/$name"))
        if (url.protocol == "jar") {
            return Path.of(URI.create(url.toString().removePrefix("jar:").substringBefore("!/"))).toString()
        }
        require(url.protocol == "file")
        return name.split('/').fold(Path.of(url.toURI())) { path, _ -> path.parent }.toString()
    }
}

internal object ScopeIdProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        println(FileScopeIdStore(Path.of(args.single())).loadOrCreate())
        Runtime.getRuntime().halt(0)
    }
}
