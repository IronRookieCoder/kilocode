# JetBrains P0 用户旅程 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在真实完成点记录打开、加载、连接、恢复、会话及关键操作结果，形成有分母的11组P0指标输入。

**Architecture:** 使用A计划的Operations和Recorder，按业务边界维护状态，不从日志或旧capture反推成功。frontend管理用户旅程和UI终点，backend/provider管理实际加载与连接；两端观测分层保留，不累加成总次数。

**Tech Stack:** Kotlin、IntelliJ Application/EDT、现有SessionControllerTestBase、MockWebServer真实HTTP/SSE、平台coroutines。

**Spec:** [设计9、10、12、14](../../jetbrains-stability-design.md)，[指标M01～M05、M11～M14、M16、M17](../../jetbrains-plugin-stability-metrics.md)，[采集基础](./2026-09-20-jetbrains-stability-collector.md)。

## Global Constraints

- “同一operation仅一个end”；“后台重试会增加attempt，但逻辑操作分母不随之增加”。
- “超时判定不取消业务本身”；“正常取消保留cancelled，不进入error计数”。
- “失败事实和P0结果不采样”。
- 默认观测截止：“界面初始化30秒、后端加载30秒、打开到可用60秒、逻辑连接30秒、恢复和普通交互30秒”。
- “不能把‘没有结束记录’当插件崩溃”。
- “插件‘发送成功’只表示请求被接收且界面正确更新，不表示模型回答正确或任务完成。”
- 新增Swing访问必须在真实EDT，标注RequiresEdt；不新增阻塞RPC。

---

## 结构与共同接口

新增 `frontend/.../stability/readiness.kt` 管理每激活上下文的M03；`availability.kt` 管理每项目非重叠区间；`shared/.../stability/connection-observation.kt`管理逻辑操作和attempt。SessionController只新增少量Operation引用和准确终点调用，不重构整个大文件。

A提供：`StabilityService.start(side)`、`.recorder`、`.operations`；`Operations.begin(name, deadline, fields, context): Operation`；`Operation.end(result, stage, cause, code): Boolean`。后续提到的 `operations` 均在实际调用处通过 `service<StabilityService>().operations` 获取，遵守不缓存其他service实例的规则。必要的Operation句柄可保存在对应业务对象中。

平台测试扩展现有真实基座。新增测试文件使用小写连字符；测试类沿用PascalCase。有关终态的断言既检查UI状态，也检查临时ready文件中的实际Fact。为跨模块复用A的Fixture，在shared Gradle开启 `java-test-fixtures`、将 `stability-fixture.kt`移至 `shared/src/testFixtures/kotlin/ai/kilocode/stability/`，在使用它的frontend/backend/cs-cloud测试声明 `testImplementation(testFixtures(project(":shared")))`；fixture依赖使用testFixturesImplementation，不让测试库进入插件包。该接线归B1交付，不另拆空任务。

现有SessionControllerTestBase直接构造services/controller，未替换Application服务。采用同样的生产依赖方式：给SessionController增加`private val operations: Operations = service<StabilityService>().operations`构造参数，所有真实构造点沿用默认值；测试基座在setUp创建Fixture，在controller构造传`operations=fixture.operations`，在tearDown先dispose controller再关闭fixture。这是实际操作观测依赖，不增加生产测试访问器。需要Recorder的controller事件通过`Operations.record(draft: Draft): Admission`转发到其同一recorder，避免测试读到另一全局实例；该方法也用于真实的transition/sample事件。

## Task B1：面板、资料加载与整体可用（M01/M02/M03）

**Files:**
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/readiness.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/KiloToolWindowFactory.kt:79`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/app/KiloAppService.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt:476`
- Modify: `packages/kilo-jetbrains/shared/build.gradle.kts`、`packages/kilo-jetbrains/frontend/build.gradle.kts`、`packages/kilo-jetbrains/backend/build.gradle.kts`、`packages/kilo-jetbrains/cs-cloud/build.gradle.kts`
- Move: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/stability-fixture.kt` → `packages/kilo-jetbrains/shared/src/testFixtures/kotlin/ai/kilocode/stability/stability-fixture.kt`
- Create: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/stability/readiness-test.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/KiloToolWindowFactoryTest.kt`
- Modify: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/KiloAppStateTest.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/SessionControllerTestBase.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/operation.kt`

**Interfaces:** `Readiness(operations: Operations, context: Map<String,String>)`；`update(view: Boolean, app: Boolean, workspace: Boolean, subscription: Boolean, input: Boolean, blocked: String? = null): Unit`。一次激活一个Readiness，不按每次状态流事件重新begin。代际不可确认期间不begin。

- [ ] **Step 1：写未全部就绪不得成功的测试。**

```kotlin
@Test fun `input alone is not readiness`() {
    Fixture().use { fixture ->
        val ready = Readiness(fixture.operations, emptyMap())
        ready.update(true, true, true, false, true)
        fixture.flush()
        assertEquals(0, fixture.facts().count {
            it.name == "plugin.readiness" && it.data["phase"]?.jsonPrimitive?.content == "end"
        })
        ready.update(true, true, true, true, true)
        ready.update(true, true, true, true, true)
        fixture.flush()
        assertEquals(1, fixture.facts().count {
            it.name == "plugin.readiness" && it.data["result"]?.jsonPrimitive?.content == "success"
        })
    }
}
```

Fixture增加 `operations: Operations`，使用自身真实Recorder和Clock。测试先运行 `:frontend:test --tests 'ai.kilocode.client.stability.ReadinessTest'`，确认未实现失败。
- [ ] **Step 2：实现Readiness。**

```kotlin
class Readiness(operations: Operations, context: Map<String, String>) {
    private val operation = operations.begin("plugin.readiness", 60_000, context = context)
    fun update(view: Boolean, app: Boolean, workspace: Boolean, subscription: Boolean,
               input: Boolean, blocked: String? = null) {
        if (blocked != null) {
            operation.end("blocked", "readiness", "environment", fields = buildJsonObject { put("reason", blocked) })
            return
        }
        if (view && app && workspace && subscription && input) {
            operation.end("success", "readiness", fields = buildJsonObject { put("reason", "none") })
        }
    }
}
```

blocked/reason的枚举需按G0登记，不能透传服务端message；使用A3已有的end.fields写reason。上面的blocked分支实际传`fields=buildJsonObject { put("reason", blocked) }`，code保持登记的安全错误码（不能拿reason代替error_code）；success也写reason=none。增加`Operations.record`的实现：`fun record(draft: Draft): Admission = recorder.record(draft)`，供同一依赖下的transition/sample使用。
- [ ] **Step 3：修正工具窗异常和M01终点。** `create()`在启动协程前begin30秒operation；外层同步异常stage=create；协程内resolve和setup异常必须在协程内捕获；setup安装根视图和基础控制器成功才end(success)。将setup内部catch改为返回Result<Unit>或向外传播至唯一记录边界，不能既失败又发Opened。

```kotlin
// 放在现有cs.launch内部；operation在create入口创建。
try {
    val dir = workspaces.resolveProjectDirectory(pid, hint)
    val workspace = workspaces.workspace(dir)
    withContext(Dispatchers.Main) { setup(project, toolWindow, workspace) }
    operation.end("success", "setup")
} catch (error: CancellationException) {
    operation.end("cancelled", "setup", "user")
    throw error
} catch (error: Exception) {
    operation.end("failure", "setup", "plugin", "other")
    service<StabilityService>().faults.report(error, "frontend", true)
    LOG.error("Failed to create Kilo tool window content", error)
}
```

`faults: Faults`是A6对象在StabilityService上的生产接口，本任务接线时公开只读属性。LinkageError在同一自有边界记录后重抛；不把Throwable全部吞掉。工具窗旧Opened/Setup Failed稳定性capture停止重复发送；与稳定性无关的产品使用事件继续保留。
- [ ] **Step 4：在backend `load(recover)`接M02。** begin30秒带trigger=initial/recovery；profile/config/notifications加载和恢复完成后结算；错误用timeout/profile_error/config_error/notifications_error/other；M02不能代替M03。加载协程替换或取消记cancelled；已超时的迟到完成不二次end。用Operation.end的fields填写reason。
- [ ] **Step 5：接M03激活与真实就绪。** 工具窗首次激活创建Readiness，AppService/Workspace/SessionController的现有状态变更在EDT更新五项条件；订阅建立必须有实际onStart/注册完成信号，不能仅Job已launch；input取实际可交互状态。凭据缺失或MigrationRequired记blocked，后续用户重试创建新激活操作；自动状态重复不新建分母。G0业务代际校验通过后才记录跨端成功。
- [ ] **Step 6：运行平台回归与提交。** 新增setup抛异常、异步resolve失败、关闭项目、部分资料失败、MigrationRequired重复通知测试；断言唯一end以及真实组件状态。

```powershell
./gradlew.bat :frontend:test --tests 'ai.kilocode.client.stability.ReadinessTest' --tests 'ai.kilocode.client.KiloToolWindowFactoryTest'
./gradlew.bat :backend:test --tests 'ai.kilocode.backend.app.KiloAppStateTest'
git add packages/kilo-jetbrains
git commit -m "feat(jetbrains): observe setup and readiness outcomes"
```

stage前检查 `git diff --name-only`，仅stage本任务清单，不包含用户或其他任务改动；此规则适用于后续所有提交。

## Task B2：逻辑连接、传输尝试与恢复（M04/M05）

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/connection-observation.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionService.kt:84`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendConnectionService.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt:214`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudConnectionServiceTest.kt`
- Modify: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/KiloConnectionServiceTest.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/connection-observation-test.kt`

**Interfaces:** `ConnectionObservation(operations: Operations)`；`request(trigger: String): Unit`、`attempt(stage: String): Operation`、`connected(): Unit`、`lost(reason: String): Unit`、`close(): Unit`。无provider依赖类从一开始就放shared stability，cs-cloud与kilo-cli均复用；backend不得反向依赖cs-cloud。

- [ ] **Step 1：写三次attempt只有一个用户成功测试。**

```kotlin
@Test fun `retries share logical denominator`() {
    Fixture().use { fixture ->
        val connection = ConnectionObservation(fixture.operations)
        connection.request("initial")
        repeat(2) { connection.attempt("health").end("failure", "health", "network", "health_failed") }
        connection.attempt("streams").end("success", "streams")
        connection.connected()
        connection.connected()
        fixture.flush()
        val facts = fixture.facts().filter { it.data["phase"]?.jsonPrimitive?.content == "end" }
        assertEquals(1, facts.count { it.name == "connection" })
        assertEquals(3, facts.count { it.name == "connection.attempt" })
    }
}
```

- [ ] **Step 2：运行ConnectionObservationTest，确认失败。**
- [ ] **Step 3：实现逻辑Operation与attempt上下文。** 给A的Operation公开只读 `id: String`，用于attempt的context.operation_id；attempt自身context.attempt_id为独立UUID，不把Operation对象暴露为序列化字段。连接初始request创建30秒逻辑Operation；每轮endpoint/health/全部streams流程仅一个attempt，在stage变化时更新受控阶段，不能每条SSE流算attempt。

```kotlin
fun connected() {
    logical?.end("success", "streams")
    recovery?.end("success", "streams", fields = buildJsonObject {
        put("intervention", intervention)
        put("attempts", attempts)
    })
    logical = null
    recovery = null
    ready = true
}
```

`logical/recovery: Operation?`、`ready: Boolean`、`attempts: Int`、`intervention: String`是该类字段；所有更新在连接状态机同一串行上下文完成，跨SSE回调先投递而不直接竞争修改。request(manual)必须取消旧逻辑操作并开新operation；已存在recovery的手动介入只改intervention，不另增一次恢复区间。
- [ ] **Step 4：接入两种provider。** cs-cloud的`connect`公有用户入口与private自动重试路径分开，只传内部trigger，不改变KiloConnection公共业务接口；schedulePoll重入不能begin新的逻辑操作。`openSse`全部必需流打开才connected；多流同时失败只lost一次。ready前失败是连接失败，不是恢复。正常shutdown/closeTransport重建不生成disconnect；只允许实际观测到的reason，epoch变化绝不写daemon_restart。
- [ ] **Step 5：运行真实HTTP/SSE回归。** 三个根目录两流断开仅1次恢复；健康200但缺流不能成功；手动恢复标manual；超时后网络成功只诊断；正常dispose无故障；kilo-cli也验证同语义。

```powershell
./gradlew.bat :cs-cloud:test --tests 'ai.kilocode.cscloud.ConnectionObservationTest' --tests 'ai.kilocode.cscloud.CsCloudConnectionServiceTest'
./gradlew.bat :backend:test --tests 'ai.kilocode.backend.app.KiloConnectionServiceTest'
```

- [ ] **Step 6：提交 `feat(jetbrains): separate connection attempts and recovery outcomes`。**

## Task B3：会话新建与恢复（M11）

**Files:**
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt:415`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/SessionCreationTest.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/SessionRecoveryTest.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/HistoryLoadingTest.kt`

**Interfaces:** controller中的`opening: Operation?`、`restoring: Operation?`属于当前SessionLoadState.Loading token；旧token不能结束新操作。session.open的session_mode=create；session.restore为open/reconnect。

- [ ] **Step 1：在SessionRecoveryTest写真实恢复状态断言，并附实际事实断言。** 沿用现有pendingPermissionList测试数据和controller("ses_test")，延迟pending返回但先返回history，期间不得存在success end；完成后既有权限卡片出现且只有一次success。不要自建第二套session模型。

```kotlin
// 在已建立的真实恢复场景中，复用基座的flush和断言。
flush()
val ends = fixture.facts().filter {
    it.name == "session.restore" && it.data["phase"]?.jsonPrimitive?.content == "end"
}
assertEquals(1, ends.size)
assertEquals("success", ends.single().data.getValue("result").jsonPrimitive.content)
assertEquals("ui", ends.single().data.getValue("stage").jsonPrimitive.content)
```

`fixture`为B1测试基座构造并注入的真实临时采集环境，不使用service替换或mock Recorder。所有事实读取前调用fixture.flush，避免线程时序偶然通过。
- [ ] **Step 2：运行三项现有测试过滤器，确认新增断言失败。**
- [ ] **Step 3：接入createSession、loadSession、importCloud、recoverPending、subscribeEvents。**

```kotlin
// createSession入口：新建分母直到服务端ID与订阅/UI都建立。
opening = operations.begin("session.open", 30_000,
    buildJsonObject { put("session_mode", "create") })

// loadSession(token)入口：保存到此token作用域，不能被另一历史切换覆盖。
val restore = operations.begin("session.restore", 30_000,
    buildJsonObject { put("session_mode", "open") })

// history、subscription、recoverPending均完成，且token仍然当前，UI应用后的EDT分支：
restore.end("success", "ui")
```

新建成功不等prompt成功，两者独立。恢复失败stage=history/subscription/pending/ui；recoverPending内部降级捕获必须返回可判定结果，不能吞错后恢复操作success。会话切换/关闭项目cancelled；超时不取消恢复业务；旧token迟到结果不结束新token。恢复需要订阅已建立信号，单纯调用subscribeEvents不够。
- [ ] **Step 4：验证历史成功但pending失败、订阅失败、快速切换两会话、重复重连通知和取消；重跑三个类并提交 `feat(jetbrains): observe complete session restoration`。**

## Task B4：五种关键操作（M12）

**Files:**
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt:314`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/settings/base/BaseSettingsUi.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/settings/base/SettingsDraftState.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/settings/KiloLogSettingsService.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/PromptLifecycleTest.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/PermissionQueueTest.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/CommandLifecycleTest.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/settings/base/SettingsDraftStateTest.kt`
- Create: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/controller/action-observation-test.kt`

**Interfaces:** 每个已接收的用户意图一个Operation；五个action为prompt_submit/stop/permission_reply/question_reply/settings_save。end成功必须在对应请求返回和本地状态更新两者之后。

- [ ] **Step 1：复用PromptLifecycleTest的真实`prompted()`场景，在ActionObservationTest验证成功事实及内容禁采。**

```kotlin
fun `test prompt result excludes prompt content`() {
    prompted()
    flush()
    fixture.flush()
    val end = fixture.facts().single {
        it.name == "action" && it.data["action"]?.jsonPrimitive?.content == "prompt_submit" &&
            it.data["phase"]?.jsonPrimitive?.content == "end"
    }
    assertEquals("success", end.data.getValue("result").jsonPrimitive.content)
    assertFalse(end.data.containsKey("prompt"))
    assertFalse(end.data.containsKey("text"))
}
```

该类继承SessionControllerTestBase；fixture按B3安装到现有依赖构造，不从旧appRpc.telemetry断言新链路。
- [ ] **Step 2：运行ActionObservationTest确认失败。**
- [ ] **Step 3：在dispatch/abort/replyPermission/replyQuestion各自的意图边界begin。**

```kotlin
val operation = operations.begin("action", 30_000,
    buildJsonObject { put("action", "permission_reply") })
try {
    sessions.replyPermission(requestId, directory, reply)
    runEdt {
        // 保留现有权限卡片状态更新语句，完成更新后再结算。
        operation.end("success", "ui")
    }
} catch (error: CancellationException) {
    operation.end("cancelled", "rpc", "user")
    throw error
} catch (error: Exception) {
    operation.end("failure", "rpc", "unknown", "other")
    throw error
}
```

此片段嵌入已有catch/状态恢复逻辑，不新增第二次请求。自动批准不是用户permission_reply分母；排队发送重试保留原operation，不把每个SSE事件视作动作。校验失败、未修改保存、用户思考时间不begin。成功必须在实际UI更新后，disposed/sid不匹配early return结算cancelled而非success。
- [ ] **Step 4：接入真实settings保存。** shared draft调用前检查modified和validation；远端保存需返回持久化结果并重读；本地日志设置以持久状态可重读为终点。不要在Configurable.apply返回即成功，异步保存必须等待其正常生产回执。

```kotlin
val operation = operations.begin("action", 30_000,
    buildJsonObject { put("action", "settings_save") })
// 将现有保存和重读成功分支接到这里；不在遥测层再调用一次保存。
operation.end("success", "readback")
```

已核实BaseSettingsUi.applyDraft使用`save(change) { result -> ... }`，`result != null`时取`base(result)`；SettingsDraftState.complete在returned不匹配target时会回退到token.target。因此必须在complete之前用` saved(base(result), token.target)`检查真实返回状态，不能检查complete之后的baseline。保存回执不是重读的页面，在其已有服务读取流程完成后才结算；BaseSettingsUi通过后续acceptBase收到匹配目标的实际快照结束等待，Operation的30秒deadline兜底，不能第二次调用save。

```kotlin
// result非空分支，在state.complete之前计算；不改变原有UI容错行为。
val current = base(result)
val confirmed = saved(current, token.target)
state.complete(token, current)
clearProgress()
syncContent()
if (confirmed) operation.end("success", "readback")
```

保存结果不等目标时保持该operation等待真实acceptBase快照；在acceptBase匹配后成功，失败回执failure，关闭UI但保存仍在运行不强制cancel业务。日志设置的本地持久存储另读校验，失败不被UI关闭遮盖。
- [ ] **Step 5：逐操作验证：服务端失败、UI更新失败、超时后迟到、用户取消、自动批准、无修改保存均有准确分母；运行上述六类定向测试。**
- [ ] **Step 6：提交 `feat(jetbrains): measure key action completion outcomes`。**

## Task B5：项目活跃时间、不可用区间与覆盖状态（M13）

**Files:**
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/availability.kt`
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/visibility-service.kt`
- Create: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/stability/availability-test.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/settings/KiloSettingsConfigurable.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle.properties`
- Modify: `packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle_zh_CN.properties`

**Interfaces:** `Availability(clock: Clock, emit: (Draft)->Unit)`；`update(workspace: String, visible: Boolean, foreground: Boolean, state: String): Unit`、`tick(): Unit`、`pause(): Unit`。workspace已是随机ID。VisibilityService聚合项目下全部插件面板；不是每面板各建一个Availability。

- [ ] **Step 1：写不重叠区间测试。**

```kotlin
@Test fun `state changes split active intervals`() {
    var now = 0L
    val clock = object : Clock { override fun wall() = now; override fun mono() = now }
    val events = mutableListOf<Draft>()
    val availability = Availability(clock, events::add)
    availability.update("ws-a", true, true, "ready")
    now = 10_000
    availability.update("ws-a", true, true, "connecting")
    now = 30_000
    availability.update("ws-a", false, true, "connecting")
    assertEquals(listOf(10_000L, 20_000L), events.map { it.data.getValue("duration_ms").jsonPrimitive.long })
    now = 60_000
    availability.tick()
    assertEquals(2, events.size)
}
```

- [ ] **Step 2：运行AvailabilityTest确认失败。**
- [ ] **Step 3：实现每项目区间切分。** 记录单调begin和UTCbegin；tick、state/可见性/前台切换关闭当前区间，再按最新状态开新区间。active只计算visible&&foreground，ready/connecting/blocked/error都计活跃，其中非ready累计不可用由consumer派生。

```kotlin
val data = buildJsonObject {
    put("begin_timestamp", wall)
    put("end_timestamp", clock.wall())
    put("duration_ms", clock.mono() - mono)
    put("state", state)
}
emit(Draft("availability", "interval", "critical", data,
    context = mapOf("workspace_id" to workspace), purposes = setOf("metrics")))
```

暂停/休眠/调度中断无法确认的空白不生成巨大区间；丢弃未可信闭合部分并重建观察起点；后台/睡眠不当不可用。每30秒tick，在EDT读取UI快照后由后台record。多个同项目面板对可见性取并集；不同workspace独立，两个项目活跃时间可相加但不是机器在线时长。
- [ ] **Step 4：接公开监听和状态展示。** ApplicationActivationListener.TOPIC、ToolWindowManagerListener公开stateChanged及已有组件HierarchyListener；回调若不在EDT用ToolWindowManager.invokeLater再读可见性。覆盖状态用总计划A5的Coverage；设置页增加“稳定性采集：已接入／未授权／策略过期／前端未接入／自定义配置未支持”，不向普通用户显示内部锁名或spool细节。无前端本地consumer时不得显示全链路健康。
- [ ] **Step 5：跑AvailabilityTest及真实平台可见性/双面板/后台测试，再跑frontend detekt和JetBrains typecheck；提交 `feat(jetbrains): observe active availability and coverage`。**

## P0 验收边界

M14/M16由A6提供，M17必须由cs-cloud分别为metrics/logs实现，不在插件写“上传成功”事实。B1的M03可与A及外部消费者先组成最小链路；B2～B5完成用户旅程后才可宣称11组P0覆盖。

```powershell
./gradlew.bat typecheck
./gradlew.bat :frontend:detekt :backend:detekt :cs-cloud:detekt
./gradlew.bat :frontend:test --tests 'ai.kilocode.client.stability.*' --tests 'ai.kilocode.client.session.controller.ActionObservationTest'
```

各任务列出的现有回归类必须同时通过。端到端95/3/2、未知缺口、双用途隔离和时效按总计划G1执行；不以局部事件数量验证代替业务分母验证。
