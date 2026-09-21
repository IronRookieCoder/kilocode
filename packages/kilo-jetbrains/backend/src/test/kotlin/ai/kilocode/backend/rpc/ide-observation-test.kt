package ai.kilocode.backend.rpc

import ai.kilocode.backend.app.IDE_OPERATION_DEADLINE_MS
import ai.kilocode.backend.app.VfsRefreshObservation
import ai.kilocode.stability.Fixture
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M23（C5）ide.operation：插件自有IDE能力边界观测。
 *
 * vfs_refresh由[KiloBackendWorkspaceRefresh]的真实处理器记录：begin在调度点，end只在
 * postRunnable完成信号——`file.refresh`在EDT回调里真正完成后结算，绝不在refresh(true,...)
 * 调度返回时提前success；异步链路早退（项目已关闭→cancelled）与真实失败（刷新异常→failure=ide、
 * 路径未找到→failure=environment）各自唯一终态。一个逻辑调用只在实际处理器记录一次，前端
 * 调用方绝不重复相加。
 *
 * **apply_edit覆盖记录（G0/G1，brief Step 4）**：apply_edit在v1"无对应业务入口／未覆盖"——
 * WorkspaceRpcApiImpl的写入只是缺省配置创建，不能映射为apply_edit；COSTRICT_IDE_TOOLS也没有
 * 编辑工具。本计划不伪造零成功率，不为补遥测新增编辑功能；未来该业务独立实现时，在其自有
 * 处理器补同一个ide.operation接口。字典词表保留apply_edit值（见dictionary.kt覆盖注释）。
 *
 * 测试驱动真实[VfsRefreshObservation]与真实Recorder落盘（Fixture）。
 */
class IdeObservationTest {

    private fun ideFacts(fixture: Fixture) = fixture.facts().filter { it.name == "ide.operation" }

    private fun ends(fixture: Fixture) = ideFacts(fixture).filter {
        it.data["phase"]?.jsonPrimitive?.contentOrNull == "end"
    }

    private fun starts(fixture: Fixture) = ideFacts(fixture).filter {
        it.data["phase"]?.jsonPrimitive?.contentOrNull == "start"
    }

    @Test
    fun `vfs refresh success settles once on the completion signal`() {
        Fixture().use { fixture ->
            val refresh = VfsRefreshObservation(fixture.operations)
            refresh.succeeded()
            refresh.succeeded()
            fixture.flush()

            assertEquals(1, starts(fixture).size)
            assertEquals(1, ends(fixture).size)
            val start = starts(fixture).single()
            val end = ends(fixture).single()
            assertEquals("vfs_refresh", start.data.getValue("operation").jsonPrimitive.content)
            assertEquals("vfs_refresh", end.data.getValue("operation").jsonPrimitive.content)
            assertEquals(IDE_OPERATION_DEADLINE_MS, start.data.getValue("deadline_ms").jsonPrimitive.long)
            assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
            assertEquals("refresh", end.data.getValue("stage").jsonPrimitive.content)
            // 同一逻辑调用的start/end以operation_id配对。
            assertEquals(start.context["operation_id"], end.context["operation_id"])
            // ide.operation是双用途出口（设计11.2）。
            assertEquals(setOf("metrics", "logs"), end.purposes)
        }
    }

    @Test
    fun `vfs refresh failure records failure only`() {
        Fixture().use { fixture ->
            val refresh = VfsRefreshObservation(fixture.operations)
            refresh.failed("ide", "refresh_failed")
            fixture.flush()

            assertEquals(1, starts(fixture).size)
            val end = ends(fixture).single()
            assertEquals("failure", end.data.getValue("result").jsonPrimitive.content)
            assertEquals("ide", end.data.getValue("cause").jsonPrimitive.content)
            assertEquals("refresh_failed", end.data.getValue("error_code").jsonPrimitive.content)
            assertTrue(ends(fixture).none { it.data.getValue("result").jsonPrimitive.content == "success" })
        }
    }

    @Test
    fun `path not found settles an environment failure`() {
        Fixture().use { fixture ->
            VfsRefreshObservation(fixture.operations).failed("environment", "path_not_found")
            fixture.flush()

            val end = ends(fixture).single()
            assertEquals("failure", end.data.getValue("result").jsonPrimitive.content)
            assertEquals("environment", end.data.getValue("cause").jsonPrimitive.content)
            assertEquals("path_not_found", end.data.getValue("error_code").jsonPrimitive.content)
        }
    }

    @Test
    fun `completion after cancellation does not double settle`() {
        Fixture().use { fixture ->
            val refresh = VfsRefreshObservation(fixture.operations)
            refresh.cancelled()
            refresh.succeeded()
            refresh.failed("ide", "refresh_failed")
            fixture.flush()

            // 唯一end由Operation的CAS保证：晚到的完成信号不二次结算。
            val end = ends(fixture).single()
            assertEquals("cancelled", end.data.getValue("result").jsonPrimitive.content)
        }
    }

    @Test
    fun `refresh without collection opens no denominator`() {
        Fixture().use { fixture ->
            VfsRefreshObservation(null).succeeded()
            fixture.flush()

            assertTrue(ideFacts(fixture).isEmpty())
        }
    }
}
