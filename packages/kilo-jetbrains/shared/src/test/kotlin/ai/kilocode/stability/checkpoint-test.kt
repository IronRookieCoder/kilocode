package ai.kilocode.stability

import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 真实 JSONL/force 故障测试；checkpoint 必须独立于周期 health 和额外文件恢复。 */
class CheckpointTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `checkpoint records data force time rather than its own force time`() {
        Fixture(tickMs = 600_000).use { fixture ->
            val time = fixture.clock.wall()
            fixture.storage.beforeForce = { fixture.advanceClock(100) }
            fixture.recorder.record(started())
            fixture.flush()
            assertEquals(time + 200, fixture.clock.wall())
            assertEquals(time + 100, fixture.writer.flushed)
            assertEquals(JsonPrimitive(time + 100), recovered(fixture))
        }
    }

    @Test
    fun `first data force writes one nonrecursive checkpoint and copied jsonl recovers it`() {
        Fixture(tickMs = 600_000).use { fixture ->
            val forces = AtomicInteger()
            fixture.storage.beforeForce = { forces.incrementAndGet() }
            fixture.recorder.record(started())
            fixture.flush()
            assertEquals(2, forces.get(), "One data force and one checkpoint force")
            val facts = fixture.facts()
            assertEquals(listOf(1L, 2L), facts.map { it.seq })
            val checkpoint = facts.single { it.data["checkpoint"] == JsonPrimitive(true) }
            assertEquals("telemetry.health", checkpoint.name)
            assertEquals(facts.first().run_id, checkpoint.run_id)
            assertEquals(facts.first().producer_id, checkpoint.producer_id)
            assertEquals(JsonPrimitive(0), checkpoint.data["drop"])
            assertEquals(JsonPrimitive(0), checkpoint.data["write_error"])
            assertEquals(JsonPrimitive(fixture.clock.wall()), checkpoint.data["last_flush_time"])
            fixture.flush()
            assertEquals(2, forces.get(), "An idle flush must not generate checkpoints")
            val copy = fixture.base.resolve("copied.jsonl")
            Files.copy(fixture.outboxDir.resolve(fixture.fileName), copy)
            assertEquals(checkpoint.data["last_flush_time"], UncleanDetector(copy).detect().single().data["last_flush_time"])
        }
    }

    @Test
    fun `checkpoint is emitted after recorder closes without reopening public admission`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.recorder.record(started())
            fixture.recorder.close()
            fixture.writer.close()
            assertEquals(Admission.DISABLED, fixture.recorder.record(started()))
            assertEquals(JsonPrimitive(fixture.clock.wall()), recovered(fixture))
        }
    }

    @Test
    fun `failed data force keeps the last recoverable checkpoint`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.recorder.record(started())
            fixture.flush()
            val previous = JsonPrimitive(fixture.clock.wall())
            fixture.advanceClock(100)
            fixture.recorder.record(started())
            fixture.failNextForce()
            fixture.flush()
            assertEquals(previous, recovered(fixture))
            assertEquals(1, fixture.facts().count { it.data["checkpoint"] == JsonPrimitive(true) })
        }
    }

    @Test
    fun `failed checkpoint force rolls back only checkpoint and preserves previous evidence`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.recorder.record(started())
            fixture.flush()
            val previous = JsonPrimitive(fixture.clock.wall())
            fixture.advanceClock(100)
            val forces = AtomicInteger()
            fixture.storage.beforeForce = { channel ->
                if (forces.incrementAndGet() == 2) {
                    fixture.storage.beforeForce = null
                    channel.close()
                }
            }
            fixture.recorder.record(started())
            fixture.flush()
            assertEquals(2, forces.get())
            assertEquals(previous, recovered(fixture))
            assertEquals(2, fixture.facts().count { it.name == "plugin.started" })
            assertEquals(1, fixture.writer.stats().writeErrors)
        }
    }

    @Test
    fun `failed checkpoint append removes partial line and keeps forced data`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.recorder.record(started())
            fixture.flush()
            val previous = JsonPrimitive(fixture.clock.wall())
            fixture.advanceClock(100)
            fixture.storage.beforeForce = {
                fixture.storage.beforeForce = null
                fixture.storage.beforeWrite = { channel ->
                    fixture.storage.beforeWrite = null
                    channel.write(ByteBuffer.wrap("{\"partial\"".encodeToByteArray()))
                    channel.close()
                }
            }
            fixture.recorder.record(started())
            fixture.flush()
            assertEquals(previous, recovered(fixture))
            assertEquals(2, fixture.facts().count { it.name == "plugin.started" })
            assertEquals(1, fixture.writer.stats().writeErrors)
        }
    }

    @Test
    fun `checkpoint honors latest policy and accepted schema gate`() {
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.storage.beforeForce = {
                fixture.storage.beforeForce = null
                fixture.expireControl()
            }
            fixture.recorder.record(started())
            fixture.flush()
            assertEquals(listOf("plugin.started"), fixture.facts().map { it.name })
        }
        Fixture(tickMs = 600_000).use { fixture ->
            fixture.storage.beforeForce = {
                fixture.storage.beforeForce = null
                val data = Json.parseToJsonElement(Fixture.defaultControl()).jsonObject +
                    ("accepted_fact_schema_majors" to JsonArray(listOf(JsonPrimitive(2))))
                fixture.base.resolve("control.json").writeText(JsonObject(data).toString())
                fixture.policies.refresh()
            }
            fixture.recorder.record(started())
            fixture.flush()
            assertEquals(listOf("plugin.started"), fixture.facts().map { it.name })
        }
    }

    @Test
    fun `capacity rewrite retains bounded latest checkpoint without evicting failure`() {
        Fixture(tickMs = 600_000, maxFileBytes = 6000).use { fixture ->
            assertEquals(Admission.QUEUED, fixture.recorder.record(Draft("protocol.error", "diagnostic", "critical", buildJsonObject {
                put("transport", "rpc")
                put("stage", "decode")
                put("error_code", "io_error")
            })))
            repeat(20) {
                fixture.advanceClock(100)
                fixture.recorder.record(started())
                fixture.flush()
                assertTrue(Files.size(fixture.outboxDir.resolve(fixture.fileName)) <= fixture.maxFileBytes)
                assertEquals(JsonPrimitive(fixture.clock.wall()), recovered(fixture))
            }
            assertEquals(1, fixture.facts().count { it.name == "protocol.error" })
            assertTrue(fixture.writer.stats().droppedEvicted > 0)
        }
    }

    @Test
    fun `full failure file evicts checkpoint rather than the complete failure`() {
        Fixture(autoStart = false).use { seed ->
            val draft = Draft("error.reported", "diagnostic", "diagnostic", buildJsonObject {
                put("message", "x".repeat(500))
                put("frames", JsonArray(listOf(JsonPrimitive("frame.one"))))
                put("fingerprint", "full-failure")
                put("count", 1)
            }, purposes = setOf("logs"))
            assertEquals(Admission.QUEUED, seed.recorder.record(draft))
            val claim = requireNotNull(seed.recorder.tryClaim(1, 4096))
            val fact = claim.records.single().fact
            claim.release()
            val bytes = json.encodeToString(Fact.serializer(), fact).encodeToByteArray().size + 1
            Fixture(tickMs = 600_000, maxFileBytes = bytes.toLong()).use { fixture ->
                assertEquals(Admission.QUEUED, fixture.recorder.record(draft))
                fixture.flush()
                assertEquals(listOf("error.reported"), fixture.facts().map { it.name })
                assertEquals(0, fixture.writer.stats().droppedFailure)
                assertEquals(1, fixture.writer.stats().droppedEvicted)
                assertTrue(Files.size(fixture.outboxDir.resolve(fixture.fileName)) <= bytes)
            }
        }
    }

    @Test
    fun `failed checkpoint rewrite preserves previous checkpoint and file budget`() {
        Fixture(tickMs = 600_000, maxFileBytes = 2400).use { fixture ->
            fixture.recorder.record(started())
            fixture.flush()
            val previous = JsonPrimitive(fixture.clock.wall())
            fixture.advanceClock(100)
            val forces = AtomicInteger()
            val data = AtomicReference<FileChannel>()
            fixture.storage.beforeForce = { channel ->
                val count = forces.incrementAndGet()
                if (count == 1) data.set(channel)
                if (count == 2) {
                    assertTrue(data.get() !== channel, "Checkpoint must force the atomic rewrite temp channel")
                    fixture.storage.beforeForce = null
                    channel.close()
                }
            }
            fixture.recorder.record(started())
            fixture.flush()
            assertEquals(2, forces.get())
            assertEquals(previous, recovered(fixture))
            assertEquals(2, fixture.facts().count { it.name == "plugin.started" })
            assertTrue(Files.size(fixture.outboxDir.resolve(fixture.fileName)) <= fixture.maxFileBytes)
            assertEquals(1, fixture.writer.stats().writeErrors)
        }
    }

    private fun started() = Draft("plugin.started", "lifecycle", "critical", buildJsonObject {})

    private fun recovered(fixture: Fixture) =
        UncleanDetector(fixture.outboxDir.resolve(fixture.fileName)).detect().single().data["last_flush_time"]
}
