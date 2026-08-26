package ai.kilocode.cscloud

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsCloudSseNormalizerTest {

    private val workspace: Path = Files.createTempDirectory("cscloud-ws")

    @Test
    fun `flat event type is read from root`() {
        val event = CsCloudSseNormalizer.normalize(null, """{"type":"session.created","sessionID":"s1"}""", workspace)
        assertEquals(SseEvent("session.created", """{"type":"session.created","sessionID":"s1"}"""), event)
    }

    @Test
    fun `sse event field wins over payload type`() {
        val data = """{"type":"ignored","payload":{"type":"message.delta"}}"""
        val event = CsCloudSseNormalizer.normalize("session.created", data, workspace)
        assertEquals("session.created", event?.type)
    }

    @Test
    fun `global event type is read from payload`() {
        val data = """{"type":"event","payload":{"type":"message.delta","text":"hi"}}"""
        val event = CsCloudSseNormalizer.normalize(null, data, workspace)
        assertEquals("message.delta", event?.type)
        assertEquals(data, event?.data)
    }

    @Test
    fun `tool call and result events are preserved`() {
        for (type in listOf("tool.call", "tool.result", "permission.request", "diff.updated")) {
            val data = """{"type":"$type","id":"x"}"""
            val event = CsCloudSseNormalizer.normalize(null, data, workspace)
            assertEquals(SseEvent(type, data), event, "expected $type to normalize")
        }
    }

    @Test
    fun `host file event for the workspace is emitted`() {
        val data = """{"type":"host.file.changed","directory":"${workspace.toAbsolutePath().normalize().toString().replace('\\', '/')}"}"""
        val event = CsCloudSseNormalizer.normalize(null, data, workspace)
        assertEquals("host.file.changed", event?.type)
    }

    @Test
    fun `host file event for another directory is dropped`() {
        val other = Files.createTempDirectory("other")
        val data = """{"type":"host.file.changed","directory":"${other.toAbsolutePath().normalize().toString().replace('\\', '/')}"}"""
        assertNull(CsCloudSseNormalizer.normalize(null, data, workspace))
    }

    @Test
    fun `unknown payload is dropped without throwing`() {
        assertNull(CsCloudSseNormalizer.normalize(null, """{"foo":"bar"}""", workspace))
        assertNull(CsCloudSseNormalizer.normalize(null, "not json", workspace))
        assertNull(CsCloudSseNormalizer.normalize(null, "", workspace))
    }

    @Test
    fun `belongsToWorkspace matches nested payload directory`() {
        val dir = workspace.toAbsolutePath().normalize().toString().replace('\\', '/')
        assertTrue(
            CsCloudSseNormalizer.belongsToWorkspace(
                """{"type":"event","payload":{"type":"host.file.changed","directory":"$dir"}}""",
                workspace,
            )
        )
        assertFalse(
            CsCloudSseNormalizer.belongsToWorkspace(
                """{"type":"event","payload":{"type":"host.file.changed","directory":"/elsewhere"}}""",
                workspace,
            )
        )
    }
}
