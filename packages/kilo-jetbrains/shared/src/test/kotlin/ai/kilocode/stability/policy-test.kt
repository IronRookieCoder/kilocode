package ai.kilocode.stability

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 许可、用途和账户代际（任务A2，设计第8章/8.1）。
 *
 * 公共策略缺失／过期／未知major关闭两用途；各用途到期独立判断；
 * 不可读或畸形文件立即fail closed，不等待下一次轮询；
 * 任何已观察到的epoch更替都永久退役旧epoch，时钟回跳不得复活已失效许可。
 */
class PolicyTest {

    private val tempDirs = mutableListOf<Path>()
    private val stores = mutableListOf<PolicyStore>()

    @AfterTest
    fun tearDown() {
        stores.forEach { store -> store.close() }
        stores.clear()
        tempDirs.forEach { dir -> dir.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    // ---------- 纯许可模型（Step 1 对称独立过期） ----------

    @Test
    fun `expired logs do not stop metrics`() {
        val policy = Policy(1, 12, true, "acct-a", "ready", 9000,
            Permit(true, 8000, setOf("action")), Permit(true, 1000, setOf("action")))
        assertEquals(setOf("metrics"), policy.permit(2000, "action"))
        assertEquals(emptySet(), policy.copy(state = "pending").permit(2000, "action"))
        assertEquals(emptySet(), policy.copy(enabled = false).permit(2000, "action"))
    }

    @Test
    fun `each purpose expires at its own exact boundary`() {
        val policy = readyPolicy(metricsExpires = 5_000, logsExpires = 3_000)
        assertEquals(setOf("metrics", "logs"), policy.permit(2_999, "action"))
        assertEquals(setOf("metrics"), policy.permit(3_000, "action"))
        assertEquals(emptySet(), policy.permit(5_000, "action"))
    }

    @Test
    fun `common expiry caps both purposes at the earlier bound`() {
        val policy = readyPolicy(commonExpires = 4_000, metricsExpires = 8_000, logsExpires = 9_000)
        assertEquals(setOf("metrics", "logs"), policy.permit(3_999, "action"))
        assertEquals(emptySet(), policy.permit(4_000, "action"))
    }

    @Test
    fun `unknown major closes both purposes`() {
        assertEquals(emptySet(), readyPolicy(major = 2).permit(2_000, "action"))
    }

    @Test
    fun `absent purpose permit closes only that purpose`() {
        assertEquals(setOf("logs"), readyPolicy(metrics = null).permit(2_000, "action"))
        assertEquals(setOf("metrics"), readyPolicy(logs = null).permit(2_000, "action"))
    }

    @Test
    fun `name outside the permit allows no purpose`() {
        assertEquals(emptySet(), readyPolicy().permit(2_000, "rpc"))
    }

    @Test
    fun `disabled account state stops both purposes`() {
        assertEquals(emptySet(), readyPolicy(state = "disabled").permit(2_000, "action"))
    }

    // ---------- 真实控制文件读取（Step 4 真实文件替换等） ----------

    @Test
    fun `atomic file replacement swaps the policy snapshot`() {
        val file = writeControl(controlJson())
        var now = 2_000L
        val store = newStore(file) { now }
        assertEquals(setOf("metrics", "logs"), store.current()?.permit(now, "action"))
        assertEquals(12L, store.current()?.revision)

        replaceControl(file, controlJson(revision = 13, logsEnabled = false))
        store.refresh()
        assertEquals(13L, store.current()?.revision)
        assertEquals(setOf("metrics"), store.current()?.permit(now, "action"))
    }

    @Test
    fun `unreadable control file fails closed at construction`() {
        val file = tempDir().resolve("jetbrains.json")
        assertNull(newStore(file) { 2_000L }.current())
    }

    @Test
    fun `unknown schema major fails closed immediately`() {
        val file = writeControl(controlJson(major = 2))
        assertNull(newStore(file) { 2_000L }.current())
    }

    @Test
    fun `malformed control file fails closed immediately`() {
        val broken = listOf(
            "",
            "{\"schema_major\": 1,",
            "[]",
            controlJson().replace("\"enabled\":true", "\"enabled\":\"yes\""),
            controlJson().replace("\"account_state\":\"ready\"", "\"account_state\":\"paused\""),
            controlJson().replace("\"expires_at\":9000", "\"expires_at\":-1"),
            controlJson().replace("\"account_epoch\":\"acct-a\"", "\"account_epoch\":\"\""),
            controlJson().replace("\"revision\":12", "\"revision\":-1"),
            controlJson(rateLimit = null),
            controlJson(rateLimit = 61),
        )
        val dir = tempDir()
        broken.forEachIndexed { index, text ->
            val file = writeControl(dir, text)
            assertNull(newStore(file) { 2_000L }.current(), "broken case $index must fail closed")
        }
    }

    @Test
    fun `unknown top level key fails closed`() {
        val dir = tempDir()
        val base = Json.parseToJsonElement(controlJson()).jsonObject
        val poisoned = JsonObject(LinkedHashMap(base).apply { put("extra_field", JsonPrimitive(1)) })
        assertNull(newStore(writeControl(dir, poisoned.toString())) { 2_000L }.current())
    }

    @Test
    fun `missing single purpose closes only that purpose`() {
        val absent = newStore(writeControl(tempDir(), controlJson(metricsEnabled = null, metricsExpires = null, metricsCategories = null))) {
            2_000L
        }
        val policy = assertNotNull(absent.current())
        assertNull(policy.metrics)
        assertNotNull(policy.logs)
        assertEquals(setOf("logs"), policy.permit(2_000, "action"))

        val partial = newStore(writeControl(tempDir(), controlJson(metricsExpires = null))) { 2_000L }
        assertNull(partial.current()?.metrics)
        assertEquals(setOf("logs"), partial.current()?.permit(2_000, "action"))
    }

    @Test
    fun `malformed single purpose closes only that purpose`() {
        val file = writeControl(controlJson(metricsCategories = listOf("verbose")))
        val store = newStore(file) { 2_000L }
        val policy = assertNotNull(store.current())
        assertNull(policy.metrics)
        assertNotNull(policy.logs)
        assertEquals(setOf("logs"), policy.permit(2_000, "action"))
    }

    @Test
    fun `expiry is judged live without file changes or mtime`() {
        val file = writeControl(controlJson())
        var now = 2_000L
        val store = newStore(file) { now }
        assertEquals(setOf("metrics", "logs"), store.current()?.permit(now, "action"))
        now = 3_000L
        assertEquals(setOf("metrics"), store.current()?.permit(now, "action"))
        now = 8_000L
        assertEquals(emptySet(), store.current()?.permit(now, "action"))
        assertEquals(12L, store.current()?.revision)
    }

    @Test
    fun `user revocation publishes a disabled snapshot and can be lifted`() {
        val file = writeControl(controlJson())
        val now = 2_000L
        val store = newStore(file) { now }
        assertEquals(setOf("metrics", "logs"), store.current()?.permit(now, "action"))

        replaceControl(file, controlJson(enabled = false))
        store.refresh()
        val revoked = assertNotNull(store.current())
        assertFalse(revoked.enabled)
        assertEquals(emptySet(), revoked.permit(now, "action"))

        replaceControl(file, controlJson())
        store.refresh()
        assertEquals(setOf("metrics", "logs"), store.current()?.permit(now, "action"))
    }

    @Test
    fun `epoch transition through pending clears the old epoch`() {
        val file = writeControl(controlJson(epoch = "acct-a"))
        val now = 2_000L
        val store = newStore(file) { now }
        assertEquals("acct-a", store.current()?.epoch)

        replaceControl(file, controlJson(epoch = "acct-b", state = "pending"))
        store.refresh()
        assertEquals("acct-b", store.current()?.epoch)
        assertEquals(emptySet(), store.current()?.permit(now, "action"))
        assertTrue("acct-a" in store.retiredEpochs)

        replaceControl(file, controlJson(epoch = "acct-b", state = "ready"))
        store.refresh()
        assertEquals(setOf("metrics", "logs"), store.current()?.permit(now, "action"))
    }

    @Test
    fun `same account relogin retires the old epoch forever`() {
        val file = writeControl(controlJson(epoch = "acct-a"))
        val now = 2_000L
        val store = newStore(file) { now }
        assertEquals("acct-a", store.current()?.epoch)

        replaceControl(file, controlJson(epoch = "acct-a2"))
        store.refresh()
        assertEquals("acct-a2", store.current()?.epoch)
        assertEquals(setOf("metrics", "logs"), store.current()?.permit(now, "action"))

        replaceControl(file, controlJson(epoch = "acct-a"))
        store.refresh()
        assertNull(store.current())
        assertTrue("acct-a" in store.retiredEpochs)
    }

    @Test
    fun `clock jump backwards never revives an expired permit`() {
        val file = writeControl(controlJson())
        var now = 7_000L
        val store = newStore(file) { now }
        assertEquals(setOf("metrics"), store.current()?.permit(now, "action"))

        now = 1_000L
        assertEquals(setOf("metrics"), store.current()?.permit(now, "action"))

        now = 9_000L
        assertEquals(emptySet(), store.current()?.permit(now, "action"))
        now = 5_000L
        assertEquals(emptySet(), store.current()?.permit(now, "action"))
    }

    @Test
    fun `background poll picks up replacement without explicit refresh`() {
        val file = writeControl(controlJson())
        val now = 2_000L
        val store = newStore(file, pollIntervalMs = 50) { now }
        assertEquals(12L, store.current()?.revision)

        replaceControl(file, controlJson(revision = 13))
        val deadline = System.nanoTime() + 2_000_000_000L
        while (store.current()?.revision != 13L && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(13L, store.current()?.revision)
    }

    // ---------- 夹具 ----------

    private fun tempDir(): Path = Files.createTempDirectory("stability-policy").also { path -> tempDirs.add(path) }

    private fun newStore(file: Path, pollIntervalMs: Long = 30_000, now: () -> Long): PolicyStore =
        PolicyStore(file, now, pollIntervalMs).also { store -> stores.add(store) }

    private fun writeControl(text: String): Path = writeControl(tempDir(), text)

    private fun writeControl(dir: Path, text: String): Path = dir.resolve("jetbrains.json").apply { writeText(text) }

    private fun replaceControl(file: Path, text: String) {
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        temp.writeText(text)
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 与verbatim测试同一组时刻：metrics 8000 / logs 1000→3000 / 公共9000。 */
    private fun readyPolicy(
        major: Int = 1,
        state: String = "ready",
        commonExpires: Long = 9_000,
        metricsExpires: Long = 8_000,
        logsExpires: Long = 3_000,
        metrics: Permit? = Permit(true, metricsExpires, setOf("action")),
        logs: Permit? = Permit(true, logsExpires, setOf("action")),
    ): Policy = Policy(major, 12, true, "acct-a", state, commonExpires, metrics, logs)

    /** 真实wire形状（G0 control-schema.json字段闭集）；null参数表示该字段整体缺失。 */
    private fun controlJson(
        major: Int = 1,
        revision: Long = 12,
        enabled: Boolean = true,
        metricsEnabled: Boolean? = true,
        metricsExpires: Long? = 8_000,
        metricsCategories: List<String>? = listOf("critical", "diagnostic"),
        logsEnabled: Boolean? = true,
        logsExpires: Long? = 3_000,
        logsCategories: List<String>? = listOf("critical", "diagnostic"),
        epoch: String = "acct-a",
        state: String = "ready",
        expires: Long = 9_000,
        rateLimit: Int? = 3,
    ): String = buildJsonObject {
        put("schema_major", major)
        put("revision", revision)
        put("enabled", enabled)
        if (metricsEnabled != null) put("metrics_enabled", metricsEnabled)
        if (metricsExpires != null) put("metrics_expires_at", metricsExpires)
        if (metricsCategories != null) putJsonArray("metrics_allowed_categories") { metricsCategories.forEach { add(it) } }
        if (logsEnabled != null) put("logs_enabled", logsEnabled)
        if (logsExpires != null) put("logs_expires_at", logsExpires)
        if (logsCategories != null) putJsonArray("logs_allowed_categories") { logsCategories.forEach { add(it) } }
        put("account_epoch", epoch)
        put("account_state", state)
        put("expires_at", expires)
        if (rateLimit != null) putJsonObject("log_detail_rate_limit") { put("per_fingerprint_max_per_minute", rateLimit) }
    }.toString()
}
