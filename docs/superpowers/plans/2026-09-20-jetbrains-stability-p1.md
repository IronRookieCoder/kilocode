# JetBrains P1 诊断观测 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐首次使用供给动作、协议兼容、RPC、EDT、渲染和资源观测，为稳定性灰度提供13组P1诊断依据。

**Architecture:** 在已有业务边界复用A的Operation/Recorder，避免全局拦截。前置供给动作只观测插件能证明的结果；EDT和资源由单JVM所有者管理，渲染以合并批次计时。

**Tech Stack:** Kotlin、平台coroutines、真实Application/EDT、现有HTTP/SSE测试基座、JDK单调时钟。

**Spec:** [设计9、10.3、12～14](../../jetbrains-stability-design.md)，[指标P1目录](../../jetbrains-plugin-stability-metrics.md)，[采集基础](./2026-09-20-jetbrains-stability-collector.md)，[P0计划](./2026-09-20-jetbrains-stability-p0.md)。

## Global Constraints

- “安装/启动/凭据/下载按实际业务deadline随开始事件记录”。
- “其返回ok=true只表示流程还在继续，不能映射为凭据就绪”。
- “同一JVM仅一个探针所有者”；“每秒至多投递一次，最多一个未完成探针”。
- “不能可靠区分休眠的情况记unknown，不算卡顿”。
- “成功的高频操作、每Token渲染和RPC不逐条生成日志”。
- “资源=subscription/controller/editor”；JVM总内存/CPU不视为插件独占消耗。
- M22基础run事实已随A5交付，本计划不建立第二个run生命周期。

---

## 文件职责

`shared/.../stability/rpc-observation.kt`封装单次RPC尝试；`frontend/.../stability/probe.kt`只管理探针状态；`shared/.../stability/resources.kt`返回可释放资源token。安装／启动／下载直接在现有所有者埋点，不新建镜像业务实现。M23仅接自有处理器，平台内置MCP工具的内部实现不由插件全局拦截。

共同接口沿用A、B：`Draft`、`Clock`、`Recorder.record`、`Operations.begin`、`Operation.end(..., fields=...)`。下面所有代码片段均置于列出的生产路径及真实测试类，测试必须调用这些生产实现。

## Task C1：安装、启动、凭据、下载和迁移（M06～M10）

**Files:**
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CscInstaller.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudStarter.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CscLogin.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionService.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/cli/KiloCliDownloader.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CscInstallerTest.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CscCloudStarterTest.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CscLoginTest.kt`
- Modify: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/cli/KiloCliDownloaderTest.kt`
- Create: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/migration-observation-test.kt`

**Interfaces:** 每个动作一个Operation；deadline从其已有参数换算毫秒，不把遥测30秒通用值强加给安装/登录。M08消费者使用G0可信凭据状态；插件仍不读JWT。

- [ ] **Step 1：在CscLoginTest现有“超时仍在运行”用例加事实断言。**

```kotlin
// 运行现有真实CscLogin测试命令并得到result后：
assertTrue(result.ok)
fixture.flush()
val success = fixture.facts().filter {
    it.name == "credentials.ready" && it.data["result"]?.jsonPrimitive?.content == "success"
}
assertTrue(success.isEmpty())
```

再在已有安装/启动测试中加入“退出0但不可发现／健康失败不成功”断言。先跑三个类确认新增断言失败或尚无目标事实，不能只断言没有success而在未埋点时误绿；同时要求有start和正确timeout/blocked终态。
- [ ] **Step 2：记录安装动作。** installer入口begin(csc.install)，携带实际package_manager；命令成功且工具可发现才success，不把installCsc随后调用start的失败改成安装失败。取消、无包管理器、network/disk/permission/other映射固定值，不采stdout/stderr。

```kotlin
val operation = operations.begin("csc.install", timeoutSeconds * 1000,
    buildJsonObject { put("package_manager", "npm") })
// 仅当前已实现的npm分支使用npm；其他实际分支按登记值。
operation.end("success", "discover")
```

上面的end放入组件确认可发现的分支；安装命令完成不是无条件执行该行。
- [ ] **Step 3：记录启动动作。** `CsCloudStarter.start`已有180秒默认业务等待，csc.start从spawn起、exit后进health，daemon可发现且健康才结束。不得等全部streams才算启动成功（属于M04），也不能只依据`CsCloudStartDto.ok`。由CsCloudConnectionService协调同一个启动operation与健康确认，失败code=spawn_failed/health_failed/timeout/csc_not_installed/other。
- [ ] **Step 4：记录凭据就绪。** 在真实需要凭据的readiness／认证请求前begin(credentials.ready)，stage=probe/wait；从G0可信凭据状态返回ready才success；登录进程仍运行到截止记timeout且不终止进程；明确缺失阻断记blocked。遥测许可尚未成立时不追补首次登录期间事实，这是设计观测盲区。

```kotlin
val operation = operations.begin("credentials.ready", timeoutSeconds * 1000)
// 仅可信当前业务代际确认ready的分支执行：
operation.end("success", "probe")
// 现有进程等待超时且仍运行的分支执行：
operation.end("timeout", "wait", "unknown", "timeout")
```

以上为两个互斥分支，不能顺序调用当作正常实现；最终由Operation保证唯一终态。迟到ready只可发安全诊断，不再end。
- [ ] **Step 5：记录下载与迁移。** KiloCliDownloader.resolve包围真实获取和验证，cached分支end(stage=cache,cache_hit=true)，download/extract/verify分别保留进度，失败映射指标3.2；固定provider=kilo-cli。MigrationRequired仅每激活首次转换记录transition，同时B1的M03 blocked；重复load状态和提示渲染不增计。

```kotlin
recorder.record(Draft("migration.required", "transition", "critical",
    buildJsonObject { put("migration_kind", "legacy_v5") }, purposes = setOf("metrics", "logs")))
```

`legacy_v5`是按现有LegacyV5迁移路径提出的受控值，必须在G0字典/Spec同时登记；不按错误文本拼migration_kind。
- [ ] **Step 6：运行上述五类测试和新增MigrationObservationTest，覆盖cache不入download耗时、重复迁移提示、同操作迟到终态，提交 `feat(jetbrains): observe setup prerequisites and migration blocks`。**

## Task C2：协议错误、释放风险和RPC（M15/M18/M19）

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/rpc-observation.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudSseClient.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/app/KiloSessionService.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/app/KiloAppService.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/app/KiloWorkspaceService.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/sse-observation-test.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/rpc-observation-test.kt`
- Create: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/dispose-observation-test.kt`

**Interfaces:** `suspend fun <T> Operations.rpc(group: String, block: suspend ()->T): T`；group仅profile/config/session/workspace/mcp/other。协议/释放事实通过Recorder写固定枚举，不反射所有RPC方法。

- [ ] **Step 1：写RPC取消仍传播且不计故障的测试。**

```kotlin
@Test fun `rpc cancellation is preserved`() = runTest {
    Fixture().use { fixture ->
        assertFailsWith<CancellationException> {
            fixture.operations.rpc("session") { throw CancellationException("closed") }
        }
        fixture.flush()
        val end = fixture.facts().single {
            it.name == "rpc" && it.data["phase"]?.jsonPrimitive?.content == "end"
        }
        assertEquals("cancelled", end.data.getValue("result").jsonPrimitive.content)
    }
}
```

- [ ] **Step 2：运行RpcObservationTest确认失败，实现生产wrapper。**

```kotlin
suspend fun <T> Operations.rpc(group: String, block: suspend () -> T): T {
    val operation = begin("rpc", 30_000, buildJsonObject { put("api_group", group) })
    try {
        val result = block()
        operation.end("success", "rpc")
        return result
    } catch (error: CancellationException) {
        operation.end("cancelled", "rpc", "user")
        throw error
    } catch (error: Exception) {
        operation.end("failure", "rpc", "unknown", "other")
        throw error
    }
}
```

RPC实际deadline存在时新增可选deadline参数并使用原值；不把长寿命Flow整个订阅时长当30秒RPC。wrapper放 `durable {}` 内每次实际调用处，重试单独attempt，用户operation仍由B负责。成功RPC仅metrics用途，失败按日志选择规则，不能逐次成功写logs；通过A的事件用途选择函数实现，不修改旧fact用途。
- [ ] **Step 3：在现有SSE解析失败／状态约束违规处记录protocol.error。** 用transport=sse/rpc、stage=decode/apply、error_code固定值；未知可选字段正常忽略不计错误。最小计数critical/metrics；安全详情另由Faults在logs许可下产生。同fault不得又作为两个独立error反复增加M14。测试输入非法JSON和正常新增可选字段，不上传原响应。
- [ ] **Step 4：记录session.dispose_risk。** controller处理global_disposed/server_instance_disposed时，只有conversation_active且不是正常退出才记录；按来源事件稳定ID在有限缓存中去重，关闭会话清理缓存；无服务端ID时按当前连接代际、来源和状态转换去重，不根据原始payload内容hash。该代际只做本地去重，不证明daemon重启。

```kotlin
recorder.record(Draft("session.dispose_risk", "transition", "critical",
    buildJsonObject {
        put("dispose_source", "global_disposed")
        put("conversation_active", true)
    }))
```

- [ ] **Step 5：用真实SSE和SessionController测试验证同事件重复、inactive、正常shutdown、RPC重试次数；运行下列命令，提交 `feat(jetbrains): observe protocol and rpc failures`。**

```powershell
./gradlew.bat :shared:test --tests 'ai.kilocode.stability.RpcObservationTest'
./gradlew.bat :cs-cloud:test --tests 'ai.kilocode.cscloud.SseObservationTest'
./gradlew.bat :frontend:test --tests 'ai.kilocode.client.session.controller.DisposeObservationTest'
```

## Task C3：单JVM EDT探针（M20）

**Files:**
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/probe.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/visibility-service.kt`
- Create: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/stability/probe-test.kt`

**Interfaces:** `Probe(clock: Clock, emit: (Draft)->Unit)`；`offer(): Long?`返回探针序号或null；`complete(seq: Long): Unit`；`interrupt(validity: String): Unit`；`enabled(value: Boolean): Unit`。probe保持单一pending，observation_id按连续观测区间生成UUID。

- [ ] **Step 1：写3秒单样本和至多一个pending测试。**

```kotlin
@Test fun `one delayed callback represents the entire wait`() {
    var now = 0L
    val clock = object : Clock { override fun wall() = now; override fun mono() = now }
    val events = mutableListOf<Draft>()
    val probe = Probe(clock, events::add)
    probe.enabled(true)
    val seq = checkNotNull(probe.offer())
    now = 1000
    assertNull(probe.offer())
    now = 3000
    probe.complete(seq)
    assertEquals(1, events.size)
    assertEquals(3000L, events.single().data.getValue("duration_ms").jsonPrimitive.long)
    assertEquals("valid", events.single().data.getValue("validity").jsonPrimitive.content)
}
```

- [ ] **Step 2：运行ProbeTest确认失败，实现单pending状态机。** 序号仅实际投递递增，时间是当前run相对单调毫秒。后台每秒最多一次offer，已pending不投递。EDT callback只记录完成时刻、将结果交后台，不做I/O或等待。

```kotlin
val seq = probe.offer()
if (seq != null) ApplicationManager.getApplication().invokeLater { probe.complete(seq) }
```

线程安全用短原子状态切换，不在EDT等待mutex。每条edt.delay包含observation_id/probe_seq/scheduled_mono_ms/completed_mono_ms/duration_ms/validity，purposes仅metrics。先由后台完成安全Draft构造，再record。
- [ ] **Step 3：处理失焦、暂停和调度断层。** VisibilityService聚合任一项目可见且IDE前台才启用；失焦/暂停使当前pending无效并更换observation_id。后台每tick记录调度间隔，超出预设容差（建议正常1000ms、间隔>2000ms判scheduler_gap，需G1校准）使pending失效。平台休眠通知确认前仅unknown；已invalid的旧callback不能在新观测区间当valid。不能只用delay时长断言插件卡顿。
- [ ] **Step 4：真实EDT验证及consumer向量。** 平台测试用明确latch阻塞EDT、后台确认已投递后再释放，watchdog仅防挂死；sleep类时效测试独立标注。写两相接有效区间→一stall、gap→两区间、3秒单样本→一stall、休眠→零stall的向量给G1，插件不累计云端stall。
- [ ] **Step 5：已确认线程违规只在插件自有明确证据边界记录edt.violation。** evidence=platform_thread_assertion且operation使用已登记操作；不能用3秒延迟推断违规或全局替换平台线程检查。按Faults规则传固定错误类别，不传断言原文。
- [ ] **Step 6：验证多面板只有一个probe、关闭释放、无pending泄漏，跑ProbeTest/AvailabilityTest与frontend detekt，提交 `feat(jetbrains): observe bounded edt delay samples`。**

## Task C4：合并批次渲染耗时（M21）

**Files:**
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionUpdateQueue.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt:2141`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/SessionUpdateQueueTest.kt`
- Create: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/render-observation-test.kt`

**Interfaces:** `Render(clock: Clock, recorder: Recorder, rate: Double = 1.0)`；`apply(size: Int, component: String, block: ()->Unit): Unit`，实现放新文件 `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/render.kt`。rate来自受控有效策略；默认1.0不采样，调整必须保留sample_rate。

- [ ] **Step 1：在现有批处理测试中断言一次fire只有一个render样本。**

```kotlin
@Test fun `failed render is never sampled out`() {
    Fixture().use { fixture ->
        val render = Render(fixture.clock, fixture.recorder, 0.01)
        assertFailsWith<IllegalStateException> {
            render.apply(5, "frontend") { throw IllegalStateException("private text") }
        }
        fixture.flush()
        val event = fixture.facts().single { it.name == "render.apply" }
        assertEquals("failure", event.data.getValue("result").jsonPrimitive.content)
        assertEquals(1.0, event.data.getValue("sample_rate").jsonPrimitive.double)
    }
}
```

- [ ] **Step 2：运行RenderObservationTest确认失败，实现围绕真实fire(out)的计时。** 开始在合并后批次进入模型处理时，结束在同步模型/组件监听更新完成后，不覆盖150ms等批等待，不以repaint排队代表像素绘制完成。

```kotlin
fun bucket(size: Int): String = when (size) {
    1 -> "1"
    in 2..5 -> "2-5"
    in 6..20 -> "6-20"
    in 21..100 -> "21-100"
    else -> "100+"
}
```

Render.apply用try/catch/finally保留业务异常传播；result初值success，异常设failure；failure始终critical、sample_rate=1；success按可控随机源或确定性均匀采样挑选，记录实际rate和duration_ms。Dictionary补已设计允许的sample_rate，不允许body/text。一个批次只在一层调用Render，不能queue和controller重复计时。
- [ ] **Step 3：验证批次大小五桶、成功采样与失败不采样、无每Token落盘；测试复用实际SessionUpdateQueue及真实EDT。** 成功和失败均由consumer按result区分分布，日志不逐次记录成功。
- [ ] **Step 4：跑SessionUpdateQueueTest/RenderObservationTest与frontend detekt，提交 `feat(jetbrains): sample batched render application latency`。**

## Task C5：自有IDE能力与资源数量（M23/M24）

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/resources.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/resources-test.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendWorkspaceRefresh.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/vfs/KiloVfsManager.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/diff/KiloDiffEditorKind.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/vfs/KiloFileEditor.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridge.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridgeTest.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/ListenerLifecycleTest.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/vfs/KiloVfsManagerTest.kt`
- Create: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/rpc/ide-observation-test.kt`

**Interfaces:** `Resources.acquire(kind: String): AutoCloseable`；`snapshot(): Map<String,Long>`；StabilityService owns one Resources。三个kind为subscription/controller/editor，token仅首次close减计数。

- [ ] **Step 1：写生命周期计数测试。**

```kotlin
@Test fun `closing a resource twice decrements once`() {
    val resources = Resources()
    val token = resources.acquire("controller")
    assertEquals(1L, resources.snapshot()["controller"])
    token.close()
    token.close()
    assertEquals(0L, resources.snapshot()["controller"])
}
```

- [ ] **Step 2：运行ResourcesTest确认失败，实现生产token。**

```kotlin
class Resources {
    private val counts = listOf("subscription", "controller", "editor")
        .associateWith { java.util.concurrent.atomic.AtomicLong() }
    fun acquire(kind: String): AutoCloseable {
        val count = counts.getValue(kind)
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        count.incrementAndGet()
        return AutoCloseable { if (closed.compareAndSet(false, true)) count.decrementAndGet() }
    }
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }
}
```

- [ ] **Step 3：在真实资源所有者绑定token。** controller构造／dispose；每个真正启动的会话/子会话订阅在onStart acquire、finally close；实际插件editor创建／dispose。取消订阅后新建用新token，注册listener不重复算controller。每30秒生成resource.snapshot三条gauge事实，仅当前值，不推导泄漏或JVM内存归属。
- [ ] **Step 4：接M23现有自有边界。** KiloDiffEditorKind实际打开diff→open_diff；KiloBackendWorkspaceRefresh完成回调→vfs_refresh；CsCloudMcpBridge.ensure新绑定成功→mcp_register。复用缓存lease不是注册操作。每个逻辑调用只在实际处理器记录一次，不同时在前端调用和backend实现上相加。

```kotlin
val operation = operations.begin("ide.operation", 30_000,
    buildJsonObject { put("operation", "mcp_register") })
// 放在ensure的新binding成功分支，CapabilityResult.Ready返回之前。
operation.end("success", "bind")
```

源码差异必须写入G0/G1覆盖记录：当前WorkspaceRpcApiImpl的写入只是缺省配置创建，不能映射为apply_edit；`COSTRICT_IDE_TOOLS`也没有编辑工具。v1对apply_edit登记“无对应业务入口／未覆盖”，不伪造零成功率，不为补遥测新增编辑功能。未来该业务独立实现时，在其自有处理器补同一个ide.operation接口；本计划只实现当前三种可观测能力。异步VFS调用要在postRunnable/完成信号结算，不能在refresh(true,...)返回时提前success。文件内容、路径、diff正文均不写事实。
- [ ] **Step 5：使用真实临时项目文件和既有VFS/生命周期测试验证成功、失败、关闭恢复基线。** 打开/关闭100次controller与editor后数量回落；取消订阅、重复dispose不负数；diff打开或VFS刷新失败只有failure；MCP复用不新增注册分母。
- [ ] **Step 6：运行ResourcesTest、IdeObservationTest、ListenerLifecycleTest、KiloVfsManagerTest和cs-cloud桥接现有定向测试，typecheck通过后提交 `feat(jetbrains): observe owned ide operations and resources`。**

## 灰度验收

```powershell
./gradlew.bat typecheck
./gradlew.bat :shared:detekt :frontend:detekt :backend:detekt :cs-cloud:detekt
./gradlew.bat :shared:test --tests 'ai.kilocode.stability.ResourcesTest' --tests 'ai.kilocode.stability.RpcObservationTest'
./gradlew.bat :frontend:test --tests 'ai.kilocode.client.stability.ProbeTest' --tests 'ai.kilocode.client.session.controller.RenderObservationTest' --tests 'ai.kilocode.client.session.controller.DisposeObservationTest'
```

同时跑C1/C2/C5指定的现有回归类。M22沿用A5运行事件；M17由外部消费者提供，不在P1补插件上传计数。两周基线、休眠真机、跨语言锁和双出口故障矩阵按总计划G1执行。实际不支持的IDE能力或远程端必须在验收报告明示，不能用缺少样本宣称零故障。
