package ai.kilocode.stability

import ai.kilocode.log.KiloLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.Test
import kotlin.test.assertEquals
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
