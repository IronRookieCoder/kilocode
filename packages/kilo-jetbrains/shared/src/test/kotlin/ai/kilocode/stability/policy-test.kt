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
 * fail open（设计第8章）：控制文件缺失、为空、畸形或未知schema_major时默认不限制采集，
 * [PolicyStore.current]返回unbound占位策略（全用途、revision=0、epoch=`unbound`），
 * 读文件与判定同样在构造时同步发生，不等待下一次轮询。
 * 限制只能来自当前有效的显式策略：显式`enabled=false`、公共`expires_at`过期、
 * 某用途显式关闭或过期一律停止对应采集（不fall open）；各用途到期独立判断。
 * 占位epoch不绑定账户代际、永不退役；任何已观察到的真实epoch更替都永久退役旧epoch，
 * 时钟回跳不得复活已失效许可。
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
        assertEquals(setOf("metrics", "logs"), store.current().permit(now, "action"))
        assertEquals(12L, store.current().revision)

        replaceControl(file, controlJson(revision = 13, logsEnabled = false))
        store.refresh()
        assertEquals(13L, store.current().revision)
        assertEquals(setOf("metrics"), store.current().permit(now, "action"))
    }

    @Test
    fun `unreadable control file falls open at construction`() {
        val file = tempDir().resolve("jetbrains.json")
        val policy = assertNotNull(newStore(file) { 2_000L }.current())
        assertEquals(EPOCH_UNBOUND, policy.epoch)
        assertEquals(0L, policy.revision)
        assertEquals(setOf("metrics", "logs"), policy.permit(2_000L, "plugin.started"))
    }

    @Test
    fun `unknown schema major falls open immediately`() {
        val file = writeControl(controlJson(major = 2))
        val policy = assertNotNull(newStore(file) { 2_000L }.current())
        assertEquals(EPOCH_UNBOUND, policy.epoch)
        assertEquals(0L, policy.revision)
    }

    @Test
    fun `malformed control file falls open immediately`() {
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
            val policy = assertNotNull(newStore(file) { 2_000L }.current(), "broken case $index must fall open")
            assertEquals(EPOCH_UNBOUND, policy.epoch, "broken case $index must yield the unbound placeholder")
            assertEquals(0L, policy.revision, "broken case $index must yield revision 0")
        }
    }

    @Test
    fun `unknown top level key falls open`() {
        val dir = tempDir()
        val base = Json.parseToJsonElement(controlJson()).jsonObject
        val poisoned = JsonObject(LinkedHashMap(base).apply { put("extra_field", JsonPrimitive(1)) })
        val policy = assertNotNull(newStore(writeControl(dir, poisoned.toString())) { 2_000L }.current())
        assertEquals(EPOCH_UNBOUND, policy.epoch)
        assertEquals(0L, policy.revision)
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
        assertNull(partial.current().metrics)
        assertEquals(setOf("logs"), partial.current().permit(2_000, "action"))
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
        assertEquals(setOf("metrics", "logs"), store.current().permit(now, "action"))
        now = 3_000L
        assertEquals(setOf("metrics"), store.current().permit(now, "action"))
        now = 8_000L
        assertEquals(emptySet(), store.current().permit(now, "action"))
        assertEquals(12L, store.current().revision)
    }

    @Test
    fun `user revocation publishes a disabled snapshot and can be lifted`() {
        val file = writeControl(controlJson())
        val now = 2_000L
        val store = newStore(file) { now }
        assertEquals(setOf("metrics", "logs"), store.current().permit(now, "action"))

        replaceControl(file, controlJson(enabled = false))
        store.refresh()
        val revoked = assertNotNull(store.current())
        assertFalse(revoked.enabled)
        assertEquals(emptySet(), revoked.permit(now, "action"))

        replaceControl(file, controlJson())
        store.refresh()
        assertEquals(setOf("metrics", "logs"), store.current().permit(now, "action"))
    }

    @Test
    fun `epoch transition through pending clears the old epoch`() {
        val file = writeControl(controlJson(epoch = "acct-a"))
        val now = 2_000L
        val store = newStore(file) { now }
        assertEquals("acct-a", store.current().epoch)

        replaceControl(file, controlJson(epoch = "acct-b", state = "pending"))
        store.refresh()
        assertEquals("acct-b", store.current().epoch)
        assertEquals(emptySet(), store.current().permit(now, "action"))
        assertTrue("acct-a" in store.retiredEpochs)

        replaceControl(file, controlJson(epoch = "acct-b", state = "ready"))
        store.refresh()
        assertEquals(setOf("metrics", "logs"), store.current().permit(now, "action"))
    }

    @Test
    fun `same account relogin retires the old epoch forever`() {
        val file = writeControl(controlJson(epoch = "acct-a"))
        val now = 2_000L
        val store = newStore(file) { now }
        assertEquals("acct-a", store.current().epoch)

        replaceControl(file, controlJson(epoch = "acct-a2"))
        store.refresh()
        assertEquals("acct-a2", store.current().epoch)
        assertEquals(setOf("metrics", "logs"), store.current().permit(now, "action"))

        replaceControl(file, controlJson(epoch = "acct-a"))
        store.refresh()
        // 已退役epoch的回写不再被收养：落回unbound占位策略（默认不限制采集），旧epoch永不复活
        assertEquals(EPOCH_UNBOUND, store.current().epoch)
        assertTrue("acct-a" in store.retiredEpochs)
    }

    @Test
    fun `clock jump backwards never revives an expired permit`() {
        val file = writeControl(controlJson())
        var now = 7_000L
        val store = newStore(file) { now }
        assertEquals(setOf("metrics"), store.current().permit(now, "action"))

        now = 1_000L
        assertEquals(setOf("metrics"), store.current().permit(now, "action"))

        now = 9_000L
        assertEquals(emptySet(), store.current().permit(now, "action"))
        now = 5_000L
        assertEquals(emptySet(), store.current().permit(now, "action"))
    }

    @Test
    fun `background poll picks up replacement without explicit refresh`() {
        val file = writeControl(controlJson())
        val now = 2_000L
        val store = newStore(file, pollIntervalMs = 50) { now }
        assertEquals(12L, store.current().revision)

        replaceControl(file, controlJson(revision = 13))
        val deadline = System.nanoTime() + 2_000_000_000L
        while (store.current().revision != 13L && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(13L, store.current().revision)
    }

    // ---------- fail open：无有效策略返回unbound占位策略（设计第8章） ----------

    @Test
    fun `missing control file falls open to the unbound placeholder policy`() {
        val dir = Files.createTempDirectory("policy-failopen").also { path -> tempDirs.add(path) }
        val store = PolicyStore(dir.resolve("control.json"), { 1_000L }, pollIntervalMs = 60_000L)
        try {
            val policy = store.current()
            assertNotNull(policy)
            assertEquals(EPOCH_UNBOUND, policy.epoch)
            assertEquals(0L, policy.revision)
            assertEquals(setOf("metrics", "logs"), policy.permit(1_000L, "plugin.started"))
            assertEquals(setOf("metrics", "logs"), policy.permit(9_999_999_999L, "edt.delay"))
        } finally {
            store.close()
        }
    }

    @Test
    fun `malformed and unknown major files also fall open`() {
        val dir = Files.createTempDirectory("policy-failopen2").also { path -> tempDirs.add(path) }
        val control = dir.resolve("control.json")
        listOf("", "{", "{\"schema_major\":99}", "not json at all").forEach { text ->
            control.writeText(text)
            val store = PolicyStore(control, { 1_000L }, pollIntervalMs = 60_000L)
            try {
                assertEquals(EPOCH_UNBOUND, store.current().epoch)
                assertEquals(0L, store.current().revision)
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun `explicit disabled and expired policies still stop collection`() {
        val dir = Files.createTempDirectory("policy-explicit").also { path -> tempDirs.add(path) }
        val control = dir.resolve("control.json")
        // 有效schema、enabled=false：显式撤销，不得fall open
        control.writeText(controlJson(enabled = false, expires = 5_000L))
        val revoked = PolicyStore(control, { 1_000L }, pollIntervalMs = 60_000L)
        try {
            assertTrue(revoked.current().permit(1_000L, "plugin.started").isEmpty())
        } finally {
            revoked.close()
        }
        // 有效schema但公共expires已过：显式授权边界已过，停采
        control.writeText(controlJson(enabled = true, expires = 500L))
        val expired = PolicyStore(control, { 1_000L }, pollIntervalMs = 60_000L)
        try {
            assertTrue(expired.current().permit(1_000L, "plugin.started").isEmpty())
        } finally {
            expired.close()
        }
    }

    @Test
    fun `unbound placeholder epoch is never retired`() {
        val dir = Files.createTempDirectory("policy-unbound-retire").also { path -> tempDirs.add(path) }
        val control = dir.resolve("control.json")
        control.writeText(controlJson(epoch = EPOCH_UNBOUND, enabled = true, expires = 9_999_999L))
        val store = PolicyStore(control, { 1_000L }, pollIntervalMs = 60_000L)
        try {
            control.writeText(controlJson(epoch = "acct-real", enabled = true, expires = 9_999_999L))
            store.refresh()
            Files.delete(control)
            store.refresh()
            // 回到无策略：占位策略仍可用，unbound未被退役拖累
            assertEquals(EPOCH_UNBOUND, store.current().epoch)
            assertFalse(EPOCH_UNBOUND in store.retiredEpochs, "占位epoch永不退役")
        } finally {
            store.close()
        }
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
