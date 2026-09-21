package ai.kilocode.backend.app

import ai.kilocode.backend.cli.KiloCliDownloader
import ai.kilocode.backend.cli.KiloCliPlatform
import ai.kilocode.backend.testing.TestLog
import ai.kilocode.stability.Fixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M10 migration.required（brief Step 6）：每次激活首次转换记录一次、重复提示不增计；
 * 另覆盖M09的cache不入download耗时与同操作迟到终态（唯一end由Operation CAS保证）。
 * 测试驱动真实[MigrationObservation]/[KiloCliDownloader]与真实Recorder落盘。
 */
class MigrationObservationTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `migration required records the first transition per activation`() {
        Fixture().use { fixture ->
            val observation = MigrationObservation { fixture.operations }

            assertTrue(observation.onMigrationRequired(), "first MigrationRequired entry must record")
            fixture.flush()

            val migrations = fixture.facts().filter { it.name == "migration.required" }
            assertEquals(1, migrations.size)
            val fact = migrations.single()
            assertEquals("transition", fact.kind)
            assertEquals("legacy_v5", fact.data["migration_kind"]?.jsonPrimitive?.content)
            // 字典把migration.required收窄为metrics-only出口（设计11.2），请求metrics+logs后落盘metrics。
            assertEquals(setOf("metrics"), fact.purposes)
        }
    }

    @Test
    fun `repeated migration prompts do not re-count`() {
        Fixture().use { fixture ->
            val observation = MigrationObservation { fixture.operations }

            assertTrue(observation.onMigrationRequired())
            // 重复load状态、重复提示渲染、强制重跑后的再次进入：都不再计。
            assertFalse(observation.onMigrationRequired())
            assertFalse(observation.onMigrationRequired())
            fixture.flush()

            assertEquals(1, fixture.facts().count { it.name == "migration.required" })
        }
    }

    @Test
    fun `migration observation without collection never consumes the once flag`() {
        Fixture().use { fixture ->
            var operations: ai.kilocode.stability.Operations? = null
            val observation = MigrationObservation { operations }

            // 采集不可用：本次不记录，也不消耗once标志（采集恢复后的首次进入仍会记录）。
            assertFalse(observation.onMigrationRequired())
            fixture.flush()
            assertEquals(0, fixture.facts().count { it.name == "migration.required" })

            operations = fixture.operations
            assertTrue(observation.onMigrationRequired())
            fixture.flush()
            assertEquals(1, fixture.facts().count { it.name == "migration.required" })
        }
    }

    @Test
    fun `cache hit is its own operation and not counted into download time`() = runBlocking {
        MockWebServer().use { server ->
            Fixture().use { fixture ->
                val bytes = archive()
                server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
                val digests = mapOf(KiloCliPlatform.current() to "sha256:${sha256(bytes)}")
                val downloader = KiloCliDownloader(
                    log = TestLog(),
                    root = dir,
                    baseUrl = server.url("/release").toString(),
                    api = server.url("/api").toString(),
                    digests = digests,
                    operations = fixture.operations,
                )

                val cli = downloader.resolve("1.2.3")
                assertTrue(cli.isFile)
                val cached = downloader.resolve("1.2.3")
                assertEquals(cli.absolutePath, cached.absolutePath)
                // 缓存命中不再发起下载：download耗时只属于下载operation本身。
                assertEquals(1, server.requestCount)

                fixture.flush()
                val facts = fixture.facts().filter { it.name == "cli.download" }
                val starts = facts.filter { it.data["phase"]?.jsonPrimitive?.content == "start" }
                val ends = facts.filter { it.data["phase"]?.jsonPrimitive?.content == "end" }
                assertEquals(2, starts.size)
                assertEquals(2, ends.size)
                val downloadEnd = ends.single { it.data["stage"]?.jsonPrimitive?.content == "verify" }
                assertEquals("success", downloadEnd.data["result"]?.jsonPrimitive?.content)
                assertEquals(null, downloadEnd.data["cache_hit"])
                val cacheEnd = ends.single { it.data["stage"]?.jsonPrimitive?.content == "cache" }
                assertEquals("success", cacheEnd.data["result"]?.jsonPrimitive?.content)
                assertEquals("true", cacheEnd.data["cache_hit"]?.jsonPrimitive?.content)
                // 两个独立operation：缓存命中的耗时不可能并入下载operation（各一end）。
                assertTrue(starts[0].context["operation_id"] != starts[1].context["operation_id"])
            }
        }
    }

    @Test
    fun `late terminal on the same operation produces a single end`() {
        Fixture().use { fixture ->
            val operation = fixture.operations.begin("backend.load", 60_000L)

            assertTrue(operation.end("success", "load"))
            // 同操作迟到终态：CAS丢弃，不再产生第二条end。
            assertFalse(operation.end("failure", "load", "unknown", "other"))
            fixture.flush()

            val ends = fixture.facts().filter {
                it.name == "backend.load" && it.data["phase"]?.jsonPrimitive?.content == "end"
            }
            assertEquals(1, ends.size)
            assertEquals("success", ends.single().data["result"]?.jsonPrimitive?.content)
        }
    }

    private fun archive(): ByteArray {
        val files = mapOf(
            "bin/${KiloCliPlatform.exe()}" to "#!/bin/sh\n".toByteArray(),
            "bin/kilo-sandbox-mutation-worker.js" to "worker\n".toByteArray(),
        )
        if (KiloCliPlatform.archive() == "zip") return zip(files)
        return tar(files)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.key))
                zip.write(entry.value)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tar(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        GzipCompressorOutputStream(out).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                files.forEach { entry ->
                    val item = TarArchiveEntry(entry.key)
                    item.size = entry.value.size.toLong()
                    tar.putArchiveEntry(item)
                    tar.write(entry.value)
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }
}
