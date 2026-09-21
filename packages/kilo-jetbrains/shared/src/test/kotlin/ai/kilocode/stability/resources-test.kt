package ai.kilocode.stability

import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * M24（C5）资源数量gauge：subscription/controller/editor三类插件自有资源的持有计数。
 *
 * token仅首次close减计数（brief verbatim语义）：重复close/重复dispose不负数；
 * 三类独立计数；snapshot恒含三类当前值——只读当前值，绝不推导泄漏或JVM内存归属。
 * gauge草稿（resource.snapshot）是30秒循环实际投递的载荷，逐条对应字典闭集。
 */
class ResourcesTest {

    // ---------- Step 1 verbatim：双close只减一次 ----------

    @Test fun `closing a resource twice decrements once`() {
        val resources = Resources()
        val token = resources.acquire("controller")
        assertEquals(1L, resources.snapshot()["controller"])
        token.close()
        token.close()
        assertEquals(0L, resources.snapshot()["controller"])
    }

    // ---------- 生命周期计数：基线恢复、永不负数 ----------

    @Test fun `open and close 100 controllers returns counts to baseline`() {
        val resources = Resources()
        val tokens = List(100) { resources.acquire("controller") }
        assertEquals(100L, resources.snapshot()["controller"])
        tokens.forEach { it.close() }
        assertEquals(0L, resources.snapshot()["controller"])
    }

    @Test fun `unsubscribe and repeated dispose never go negative`() {
        val resources = Resources()
        val subscription = resources.acquire("subscription")
        val editor = resources.acquire("editor")
        subscription.close()
        subscription.close()
        editor.close()
        editor.close()
        editor.close()
        assertEquals(0L, resources.snapshot()["subscription"])
        assertEquals(0L, resources.snapshot()["editor"])
    }

    @Test fun `kinds are counted independently`() {
        val resources = Resources()
        val subscription = resources.acquire("subscription")
        val controller = resources.acquire("controller")
        val editor = resources.acquire("editor")
        val secondEditor = resources.acquire("editor")

        subscription.close()
        assertEquals(mapOf("subscription" to 0L, "controller" to 1L, "editor" to 2L), resources.snapshot())

        controller.close()
        secondEditor.close()
        assertEquals(mapOf("subscription" to 0L, "controller" to 0L, "editor" to 1L), resources.snapshot())
        editor.close()
    }

    @Test fun `snapshot always reports all three kinds`() {
        assertEquals(
            setOf("subscription", "controller", "editor"),
            Resources().snapshot().keys,
        )
        assertEquals(0L, Resources().snapshot().values.sum())
    }

    @Test fun `acquire and close are safe across threads`() {
        val resources = Resources()
        val latch = CountDownLatch(1)
        val tokens = List(64) { resources.acquire("controller") }
        repeat(64) { n ->
            Thread {
                tokens[n].close()
                tokens[n].close()
                latch.countDown()
            }.start()
        }
        // 每个token至多减一次：64个双close后计数必须精确归零（并发下不负数、不漏减）。
        while (latch.count > 0) latch.await()
        Thread.sleep(50)
        assertEquals(0L, resources.snapshot()["controller"])
    }

    @Test fun `unknown kind is rejected loudly`() {
        val resources = Resources()
        assertFailsWith<NoSuchElementException> { resources.acquire("jvm_memory") }
        assertTrue(resources.snapshot().values.sum() == 0L)
    }

    // ---------- 30秒gauge循环的载荷：resource.snapshot草稿 ----------

    @Test fun `gauge drafts cover every kind with the current count`() {
        val resources = Resources()
        resources.acquire("subscription")
        val controller = resources.acquire("controller")
        resources.acquire("editor")
        resources.acquire("editor")

        val drafts = resourceSnapshotDrafts(resources.snapshot())
        assertEquals(
            listOf("subscription", "controller", "editor"),
            drafts.map { it.data.getValue("resource").jsonPrimitive.content },
        )
        assertEquals(listOf(1L, 1L, 2L), drafts.map { it.data.getValue("count").jsonPrimitive.long })
        // 三类各恰一条：每30秒的gauge循环产出固定的三条resource.snapshot。
        assertEquals(RESOURCE_KINDS.size, drafts.size)

        controller.close()
        val after = resourceSnapshotDrafts(resources.snapshot())
        // subscription的token仍被持有：gauge只反映当前值。
        assertEquals(listOf(1L, 0L, 2L), after.map { it.data.getValue("count").jsonPrimitive.long })
    }

    @Test fun `gauge drafts carry the registered sample shape`() {
        val drafts = resourceSnapshotDrafts(Resources().snapshot())
        assertTrue(drafts.all { it.name == "resource.snapshot" })
        assertTrue(drafts.all { it.kind == "sample" })
        assertTrue(drafts.all { it.channel == "critical" })
        // resource.snapshot是metrics-only出口（设计11.2）：请求metrics，不形成高频日志流。
        assertTrue(drafts.all { it.purposes == setOf("metrics") })
        assertTrue(drafts.all { Dictionary.validate(it) })
        // 仅当前值：绝无泄漏推导/JVM内存归属等额外字段。
        assertTrue(drafts.all { it.data.keys == setOf("resource", "count") })
    }
}
