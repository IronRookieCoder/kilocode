package ai.kilocode.stability

import ai.kilocode.log.KiloLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ResourceLock("diagnostic-bridge")
class DiagnosticBridgeTest {

    @Test
    fun `warn and error mirror with their severity and throwable`() {
        val seen = mutableListOf<DiagnosticInput>()

        DiagnosticBridge.install(seen::add).use {
            val log = KiloLog.create(BridgeFixture::class.java, sandbox = true)
            log.warn("warning")
            log.error("failed", IllegalStateException("raw"))
        }

        assertEquals(2, seen.size)
        assertEquals(DiagnosticSeverity.WARN, seen[0].severity)
        assertEquals(DiagnosticSeverity.ERROR, seen[1].severity)
        assertEquals("raw", seen[1].error?.message)
    }

    @Test
    fun `info and debug do not mirror`() {
        val seen = mutableListOf<DiagnosticInput>()

        DiagnosticBridge.install(seen::add).use {
            val log = KiloLog.create(BridgeFixture::class.java, sandbox = true)
            log.info("info")
            log.debug { "debug" }
        }

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `sink logging does not recurse`() {
        val seen = mutableListOf<DiagnosticInput>()

        DiagnosticBridge.install {
            seen += it
            KiloLog.create(BridgeFixture::class.java, sandbox = true).warn("nested")
        }.use {
            KiloLog.create(BridgeFixture::class.java, sandbox = true).warn("outer")
        }

        assertEquals(listOf("outer"), seen.map { it.message })
    }

    @Test
    fun `closing installation stops capture`() {
        val seen = mutableListOf<DiagnosticInput>()
        val bridge = DiagnosticBridge.install(seen::add)

        bridge.close()
        KiloLog.create(BridgeFixture::class.java, sandbox = true).warn("ignored")

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `nested installations restore only still-open sinks`() {
        val first = mutableListOf<DiagnosticInput>()
        val second = mutableListOf<DiagnosticInput>()
        val outer = DiagnosticBridge.install(first::add)
        val inner = DiagnosticBridge.install(second::add)

        inner.close()
        DiagnosticBridge.publish(DiagnosticInput(DiagnosticSeverity.WARN, "test", "first"))
        outer.close()
        DiagnosticBridge.publish(DiagnosticInput(DiagnosticSeverity.WARN, "test", "ignored"))

        assertEquals(listOf("first"), first.map { it.message })
        assertTrue(second.isEmpty())
    }

    @Test
    fun `publish returns while a sink blocks and close prevents later delivery`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val seen = mutableListOf<String>()
        val bridge = DiagnosticBridge.install {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            seen += it.message
        }

        try {
            val task = CompletableFuture.runAsync {
                DiagnosticBridge.publish(DiagnosticInput(DiagnosticSeverity.WARN, "test", "first"))
                returned.countDown()
            }
            assertTrue(returned.await(1, TimeUnit.SECONDS), "publish must not wait for the sink")
            assertTrue(entered.await(5, TimeUnit.SECONDS), "background sink did not start")
            CompletableFuture.runAsync {
                DiagnosticBridge.publish(DiagnosticInput(DiagnosticSeverity.WARN, "test", "queued"))
                queued.countDown()
            }
            assertTrue(queued.await(1, TimeUnit.SECONDS), "contended publish must not wait for the sink")
            val closing = CompletableFuture.runAsync {
                bridge.close()
                closed.countDown()
            }
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS), "close must retain ownership while the sink runs")
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS), "close did not wait for the sink")
            closing.get(5, TimeUnit.SECONDS)
            DiagnosticBridge.publish(DiagnosticInput(DiagnosticSeverity.WARN, "test", "second"))
            DiagnosticBridge.await(bridge)
            task.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("first", "queued"), seen)
        } finally {
            release.countDown()
            bridge.close()
            DiagnosticBridge.await(bridge)
        }
    }

    @Test
    fun `sink failure is isolated from the publishing thread`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val bridge = DiagnosticBridge.install {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            error("sink failure")
        }

        try {
            val task = CompletableFuture.runAsync {
                DiagnosticBridge.publish(DiagnosticInput(DiagnosticSeverity.ERROR, "test", "failed"))
                returned.countDown()
            }
            assertTrue(returned.await(1, TimeUnit.SECONDS), "publish must not wait for a failing sink")
            assertTrue(entered.await(5, TimeUnit.SECONDS), "background sink did not start")
            release.countDown()
            DiagnosticBridge.await(bridge)
            task.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            bridge.close()
            DiagnosticBridge.await(bridge)
        }
    }

    @Test
    fun `coroutine context is scoped to its coroutine`() = runBlocking {
        val seen = mutableListOf<DiagnosticInput>()

        DiagnosticBridge.install(seen::add).use {
            withContext(
                DiagnosticContextElement(
                    context = mapOf("operation_id" to "op-1"),
                    attributes = mapOf("code" to "timeout"),
                    payloads = mapOf("request" to { "body" }),
                ),
            ) {
                KiloLog.create(BridgeFixture::class.java, sandbox = true).error("contextual")
            }
            KiloLog.create(BridgeFixture::class.java, sandbox = true).error("plain")
        }

        assertEquals(mapOf("operation_id" to "op-1"), seen[0].context)
        assertEquals(mapOf("code" to "timeout"), seen[0].attributes)
        assertEquals("body", seen[0].payloads.getValue("request").invoke())
        assertTrue(seen[1].context.isEmpty())
        assertTrue(seen[1].attributes.isEmpty())
        assertTrue(seen[1].payloads.isEmpty())
    }

    private class BridgeFixture
}
