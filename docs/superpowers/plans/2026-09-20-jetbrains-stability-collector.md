# JetBrains 稳定性采集基础 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供默认关闭、非阻塞、可恢复交接的本机事实采集器，为 M14/M16/M22 和后续业务观测提供基础。

**Architecture:** 轻量 shared app service 在单 JVM 中拥有唯一 recorder；frontend/backend 只提交不可变安全事实。后台 writer 执行许可复核、NDJSON序列化、封存、登记和配额，独立清理任务回收死亡 producer。

**Tech Stack:** Java 21、Kotlin、kotlinx.serialization、平台 CoroutineScope、JDK NIO 文件锁、JUnit真实临时目录。

**Spec:** [设计5～9、11.1～11.2、14.1](../../jetbrains-stability-design.md)，[指标M14/M16/M22](../../jetbrains-plugin-stability-metrics.md)，[总计划G0/G1](./2026-09-20-jetbrains-stability.md)。

## Global Constraints

- “普通记录最大32KiB，message安全摘要最大512字节”；UTF-8无BOM、LF。
- “队列同时限制2000条及4MiB，以先达到者为准”；critical分别预留400条及20%字节。
- “同进程多项目共享writer，用随机workspace_id区分。”
- “统一锁顺序为writer.lock→exchange.lock”；插件永不触碰 `.claimed`。
- “每producer未交接文件”：10MiB、24小时；不实施根目录级强制配额。
- “同一operation仅一个end”；“跨账户切换的操作保留开始时的epoch”。
- “不解析或上传整份idea.log/kilo.log。”
- 公共策略缺失／过期／未知major关闭两用途；各用途到期独立判断。

---

## 文件结构与接口总表

新源码均在 `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/`；测试在 `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/`。这一目录为Kilo自有代码，无需 `kilocode_change`。不添加新 Gradle 模块，不依赖 frontend/backend。

| 文件 | 责任 | 对外接口 |
|---|---|---|
| `fact.kt`、`dictionary.kt` | 不可变草稿、公共字段和逐name字段闭集 | `Draft`、`Fact`、`Dictionary.validate(Draft): Boolean` |
| `policy.kt` | 原子许可快照、逐用途过期、永久退役 | `Policy`、`Permit`、`PolicyStore.current(): Policy?` |
| `queue.kt`、`recorder.kt` | 非阻塞准入、双维容量、seq、健康累计 | `Recorder.record(Draft): Admission` |
| `operation.kt` | 单调时间和唯一终态 | `Operations.begin(...): Operation`、`Operation.end(...): Boolean` |
| `storage.kt`、`writer.kt`、`retention.kt` | 权限、原子文件、锁、封存、清理 | `Writer.flush(): Unit`、`Retention.sweep(): Unit`（后台调用） |
| `producer.kt`、`stability-service.kt` | JVM所有权、登记、安装标识、公开状态 | `StabilityService.recorder`、`StabilityService.status` |
| `fault.kt`、`health.kt` | 安全异常、详情限频、累计健康 | `Faults.report(...)`、`Health.snapshot()` |
| `stability-fixture.kt`（测试） | 真文件、真实recorder、可控时钟和读取结果 | `Fixture`，供shared测试复用 |

测试中的可控时钟为明确的产品依赖，不暴露私有内部状态或测试专用生产方法。跨模块平台集成测试用真实服务、临时策略文件和 ready 文件；不能将 recorder 替换为仅统计调用的 mock。

## Task A1：安全事实模型与字典

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/fact.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/dictionary.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/fact-test.kt`
- Modify: `packages/kilo-jetbrains/shared/build.gradle.kts`

**Interfaces:** 消费G0冻结的schema；产出下列模型。`Draft`不接受JWT、路径或业务payload；校验拒绝未知键和类型，不能只是裁掉key后继续保留任意value。

- [ ] **Step 1：写真实白名单测试。**

```kotlin
@Test fun `action rejects raw content`() {
    val data = buildJsonObject {
        put("phase", "end"); put("action", "prompt_submit")
        put("result", "failure"); put("duration_ms", 20)
        put("stage", "rpc"); put("cause", "network"); put("error_code", "network")
        put("prompt", "secret source code")
    }
    assertFalse(Dictionary.validate(Draft("action", "operation", "critical", data)))
}
```

增加非枚举stage、负duration、超长message、多于5帧、context未知键、字符串内换行、32KiB UTF-8边界用例。消息模板内容由 `Faults` 生成；不得接受任意异常message。
- [ ] **Step 2：运行 `./gradlew.bat :shared:test --tests 'ai.kilocode.stability.FactTest'`，确认模型缺失／断言失败。**
- [ ] **Step 3：定义不可变字段和完整事件字典。**

```kotlin
data class Draft(
    val name: String,
    val kind: String,
    val channel: String,
    val data: JsonObject,
    val context: Map<String, String> = emptyMap(),
    val epoch: String? = null,
    val purposes: Set<String> = setOf("metrics", "logs"),
)

@Serializable
data class Fact(
    val schema_version: String = "1.0",
    val event_id: String,
    val timestamp: Long,
    val producer_id: String,
    val run_id: String,
    val channel: String,
    val seq: Long,
    val account_epoch: String,
    val policy_revision: Long,
    val purposes: Set<String>,
    val source: String = "jetbrains-plugin",
    val device_id: String,
    val plugin_version: String,
    val ide_product: String,
    val ide_build: String,
    val ide_build_major: String,
    val os_family: String,
    val arch: String,
    val env: String,
    val mode: String,
    val side: String,
    val connection_provider: String,
    val kind: String,
    val name: String,
    val context: Map<String, String> = emptyMap(),
    val data: JsonObject,
)
```

`Dictionary`使用设计第9章每个name的专属键＋公共operation键；在 `phase=start` 要求 `deadline_ms>0`，end要求result/duration_ms/stage/cause/error_code，progress不能充当end。sample与interval分别校验独立字段。error最小计数与日志详情为两个schema分支：前者允许handled/component，后者仅允许固定message/frames/fingerprint/count；不误把第6.2详情白名单套到全部diagnostic。name列表须覆盖设计所有事件，尚未接入不代表可以接收任意新name。

依赖只加已有版本目录中的序列化库：

```kotlin
dependencies {
    implementation(libs.kotlinx.serialization.json)
}
```

所有入参集合复制为不可变快照；限制键数、数组长度、字符串长度和嵌套层数，拒绝对象业务payload。writer最后以真实UTF-8编码再次检查32KiB；任何拒绝都不抛到业务调用方。
- [ ] **Step 4：重跑FactTest与`:shared:detekt`，验证非法输入拒绝、合法fixture逐字段一致。**
- [ ] **Step 5：提交 `feat(jetbrains): define stability facts and field allowlists`，仅stage本任务四个文件。**

## Task A2：许可、用途和账户代际

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/policy.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/policy-test.kt`

**Interfaces:** 消费控制文件、G0用户许可和业务身份来源；产出`Policy.permit(now: Long, name: String): Set<String>`。所有expiry为UTC毫秒；revision只在同一发布身份内单调，生产方重启的规则由G0确定。

- [ ] **Step 1：写对称独立过期测试。**

```kotlin
@Test fun `expired logs do not stop metrics`() {
    val policy = Policy(1, 12, true, "acct-a", "ready", 9000,
        Permit(true, 8000, setOf("action")), Permit(true, 1000, setOf("action")))
    assertEquals(setOf("metrics"), policy.permit(2000, "action"))
    assertEquals(emptySet(), policy.copy(state = "pending").permit(2000, "action"))
    assertEquals(emptySet(), policy.copy(enabled = false).permit(2000, "action"))
}
```

- [ ] **Step 2：运行 `:shared:test --tests 'ai.kilocode.stability.PolicyTest'` 确认失败。**
- [ ] **Step 3：实现纯许可函数和后台文件读取。**

```kotlin
data class Permit(val enabled: Boolean, val expires: Long, val names: Set<String>)
data class Policy(
    val major: Int, val revision: Long, val enabled: Boolean,
    val epoch: String, val state: String, val expires: Long,
    val metrics: Permit?, val logs: Permit?,
) {
    fun permit(now: Long, name: String): Set<String> {
        if (major != 1 || !enabled || state != "ready" || now >= expires) return emptySet()
        return buildSet {
            if (metrics?.let { it.enabled && now < it.expires && name in it.names } == true) add("metrics")
            if (logs?.let { it.enabled && now < it.expires && name in it.names } == true) add("logs")
        }
    }
}
```

`PolicyStore`后台最多每30秒读取一次真实控制文件并原子替换快照；JSON解析与权限验证不在record中。有效期检查必须每次record及writer入盘前进行，不能等下一次轮询才停止过期用途。wire字段按G0 schema映射，不将上述内部 `expires` 名称写成协议字段。

许可=用户许可∩公共策略∩事件类别∩用途策略∩开始时用途；用途只能缩小。总撤销先发布禁用快照，再清空内存；在exchange锁下删除本producer可删的open/ready，claimed交consumer处理。账户变化清空旧epoch内存，sealed混合epoch文件不改写、不整文件误删，由consumer永久退役集合逐行结算。业务连接无法证明代际相符时不记录跨端业务事实。退役操作end不能改成新epoch。
- [ ] **Step 4：增加真实文件替换、未知major、畸形文件、缺失单用途、时间前跳、用户撤销、A→pending→B、同A重登测试并重跑。** 当前不可读策略立即fail closed；到期不依赖文件mtime。时钟后跳不得让已失效许可复活，可用单调剩余租期作额外上界。
- [ ] **Step 5：提交 `feat(jetbrains): enforce independent stability collection policies`。**

## Task A3：非阻塞队列与唯一操作终态

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/queue.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/recorder.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/operation.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/queue-test.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/operation-test.kt`
- Modify: `packages/kilo-jetbrains/shared/build.gradle.kts`

**Interfaces:** `Admission { QUEUED,DROPPED,DISABLED }`；`Recorder.record(Draft): Admission`；`Clock.wall(): Long`、`Clock.mono(): Long`；`Operations.begin(name: String, deadline: Long, fields: JsonObject = JsonObject(emptyMap()), context: Map<String,String> = emptyMap()): Operation`；`Operation.id: String`；`Operation.end(result: String, stage: String="unknown", cause: String="unknown", code: String="none", fields: JsonObject=JsonObject(emptyMap())): Boolean`；`Operation.progress(stage: String): Unit`。Operations构造接收Recorder、Clock、CoroutineScope。end的fields只允许本name专属字段，禁止覆盖phase/result/duration_ms/epoch等公共字段。

- [ ] **Step 1：写操作超时后业务继续的测试。** 使用kotlinx-coroutines-test虚拟时钟，其 `mono()` 和 `wall()` 均由testScheduler派生；构建测试 recorder 时用真实后续fixture。

```kotlin
@Test fun `timeout is terminal but does not cancel business`() = runTest {
    val results = mutableListOf<String>()
    val operation = Terminal(0, 30_000) { result, _ -> results += result }
    assertTrue(operation.finish("timeout", 30_000))
    assertFalse(operation.finish("success", 30_001))
    assertEquals(listOf("timeout"), results)
    val business = async { "completed" }
    assertEquals("completed", business.await())
}
```

该单测验证终态核心；还需OperationTest用真实Recorder验证start/end、deadline定时器与业务Job不共用取消路径、原epoch保留。
- [ ] **Step 2：运行上述两个Test，先得到缺失实现失败。**
- [ ] **Step 3：实现有界准入。** 只做常量上限的白名单校验、时间/ID、不可变快照及内存操作；用`ReentrantLock.tryLock()`避免等待生产者锁，争用直接DROPPED。队列按critical/diagnostic分开，统一原子维护条数/字节；序列在准入前按run/channel递增。diagnostic最多1600条、`4*1024*1024 - ceil(4*1024*1024*0.2)`字节；critical可使用全部容量。critical需空间时优先驱逐最老diagnostic，仍不足拒绝新记录。

```kotlin
enum class Admission { QUEUED, DROPPED, DISABLED }
interface Clock { fun wall(): Long; fun mono(): Long }

internal class Terminal(
    private val start: Long,
    private val deadline: Long,
    private val emit: (String, Long) -> Unit,
) {
    private val done = java.util.concurrent.atomic.AtomicBoolean()
    fun finish(result: String, now: Long): Boolean {
        if (!done.compareAndSet(false, true)) return false
        val elapsed = (now - start).coerceAtLeast(0)
        val outcome = if (elapsed > deadline) "timeout" else result
        emit(outcome, if (outcome == "timeout") deadline else elapsed)
        return true
    }
}
```

`Terminal`由Operation使用，不是测试复制逻辑。deadline到点定时器传timeout；业务完成按单调完成时间结算，晚到callback也检查deadline。Operation保存开始时epoch/revision/purposes及操作ID，start带deadline，end自包含，progress不终结。总撤销时end可被禁采丢弃，不伪造正常shutdown。`Dictionary.purposes(name: String, data: JsonObject): Set<String>`按设计11.2选择可用出口：RPC和render成功仅metrics；关键操作start/end、生命周期可选logs；异常最小计数仅metrics、详情仅logs。最终再与Draft请求用途和A2许可求交集。禁用一种用途后不能通过默认Draft重新加回；不让高频成功RPC形成日志流。

字节预算采用保守上界覆盖保留字符串、JSON转义、数组和字段开销，writer串行编码再次校验真实32KiB。不得为计算字节在EDT序列化完整JSON；拒绝超过上界的草稿，不让队列存任意大对象。writer取出记录仍计入内存预算直至序列化写入结束，防止批次在队列外无界积压。健康计数使用AtomicLong，不经自身record递归。
- [ ] **Step 4：补并发测试并跑定向检查。** 1600条diagnostic后仍可放400条critical；多字节文本先触发字节限制；持续critical驱逐只影响diagnostic；并发record/end无重复ID/seq；shutdown后不再准入。benchmark属于G1，不用CI机器单次抖动断言P99。在shared加`testImplementation(libs.kotlinx.coroutines.test)`，不打包coroutines runtime。
- [ ] **Step 5：提交 `feat(jetbrains): bound stability admission and operation outcomes`。**

## Task A4：安全目录、单writer与原子封存

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/storage.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/writer.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/writer-test.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/stability-fixture.kt`

**Interfaces:** `Writer`消费Recorder队列、当前Policy和Producer；`flush()`封存非空通道，仅在IO线程调用；`Storage`提供原子写入和权限验证；无HTTP、无ACK伪造。

- [ ] **Step 1：写真实文件封存断言。** Fixture应创建临时目录、真实writer和有效测试Policy，不替换I/O。

```kotlin
@Test fun `sealed data is utf8 ndjson and immutable`() {
    Fixture().use { fixture ->
        assertEquals(Admission.QUEUED, fixture.recorder.record(
            Draft("plugin.started", "lifecycle", "critical", JsonObject(emptyMap()))))
        fixture.flush()
        val file = Files.list(fixture.root.resolve("critical")).use { files ->
            files.filter { it.toString().endsWith(".ready") }.findFirst().orElseThrow()
        }
        val bytes = Files.readAllBytes(file)
        assertEquals(10.toByte(), bytes.last())
        assertFalse(bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        assertEquals("plugin.started", Json.parseToJsonElement(bytes.toString(Charsets.UTF_8).trim())
            .jsonObject.getValue("name").jsonPrimitive.content)
    }
}
```

Fixture接口：`root: Path`为producer根；`recorder: Recorder`；`operations: Operations`；`clock: Clock`；`flush(): Unit`等待后台barrier；`facts(): List<Fact>`只读取ready；`close()`先结束writer再清理本fixture目录。barrier是writer正常停用/flush机制，不为测试公开私有队列。A3先以测试内真实Recorder和内存队列验证终态；本任务再将同一测试接真实writer，避免A3依赖尚未实现的A4 fixture。JUnit导入使用现有模块选择的测试引擎，所有示例省略的标准import由IDE解析补齐。
- [ ] **Step 2：运行WriterTest，确认没有ready／模型尚未接线导致失败。**
- [ ] **Step 3：实现NIO存储与后台循环。**

```kotlin
// storage.kt；调用者已关闭文件，路径在已校验的同一目录中。
fun seal(open: Path, ready: Path) {
    Files.move(open, ready, StandardCopyOption.ATOMIC_MOVE)
}

// writer.kt，顺序不能交换；channel由writer独占。
channel.force(true)
channel.close()
seal(open, ready)
```

真实代码捕获AtomicMoveNotSupportedException，保留open并记录write_error；不降级为普通rename后声称已封存。写入使用FileChannel循环直到ByteBuffer耗尽；只有完整LF写完才增加文件条数。open持有writer锁整个生命周期；关闭前buffer flush、force、close，再同目录原子改名。目录项断电持久性按平台实验记录，不宣称Java rename等价跨平台fsync目录。

封存：单文件达到1MiB；16条或64KiB；首条起critical30秒/diagnostic300秒，以先到为准；空文件不启定时封存。后台定时器只唤醒writer，不直接多线程写。下一条将突破1MiB时先封存旧文件再写新文件。最多32KiB记录不会造成未约束峰值。

目录使用PathManager.getLogDir()，登记用默认profile规定目录。创建前检查已存在祖先目录、真实路径范围、所有者、symlink/reparse；POSIX0700/0600；Windows使用AclFileAttributeView配置当前用户ACL并检查继承后的实际权限。发现无法可靠验证的reparse或权限直接禁用采集并本地限频报告。锁文件不unlink/recreate；FileChannel字节范围必须与G0通过的Go测试一致。
- [ ] **Step 4：测16条、64KiB、1MiB、临界30秒/300秒、零记录、写入失败、force失败、rename失败。** 使用真实临时文件及权限/磁盘故障集成环境；难以在当前OS制造的故障由G1平台矩阵完成，不用mock成功替代。重新执行WriterTest、`:shared:detekt`。
- [ ] **Step 5：提交 `feat(jetbrains): persist and seal stability outbox segments`。**

## Task A5：producer登记、生命周期与残留清理

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/producer.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/retention.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/stability-service.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/retention-test.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/producer-test.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/KiloToolWindowFactory.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/plugin/KiloBackendAppLifecycleListener.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/plugin/KiloBackendDynamicPluginListener.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/plugin/KiloFrontendDynamicPluginListener.kt`
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/lifecycle-listener.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/resources/kilo.jetbrains.frontend.xml`

**Interfaces:** `StabilityService(CoroutineScope)` app light service，`start(side: String): Unit`幂等且立即返回，`status: StateFlow<Coverage>`，`recorder: Recorder`，`operations: Operations`，`stop(kind: String): Unit`异步有界收尾。Coverage包含`mode/side/profile/metrics/logs/reason`安全状态，无JWT。

- [ ] **Step 1：写真文件清理测试。**

```kotlin
@Test fun `cleanup never removes claimed files`() {
    Fixture().use { fixture ->
        val dir = fixture.root.resolve("diagnostic")
        Files.createDirectories(dir)
        val claimed = Files.writeString(dir.resolve("old.claimed"), "claimed\n")
        Files.setLastModifiedTime(claimed, FileTime.fromMillis(0))
        Retention(fixture.root, fixture.clock).sweep()
        assertTrue(Files.exists(claimed))
    }
}
```

另用第二真实JVM持writer锁；mtime设旧，清理仍保留全部数据。释放锁／进程死亡后才允许过期open/ready清理。
- [ ] **Step 2：运行RetentionTest/ProducerTest，确认未实现时失败。**
- [ ] **Step 3：实现唯一所有权和启动流程。**

```kotlin
@Service(Service.Level.APP)
class StabilityService(private val scope: CoroutineScope) {
    private val started = java.util.concurrent.atomic.AtomicBoolean()
    fun start(side: String) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { initialize(side) }
    }
}
```

`initialize(side: String): Unit`在本文件定义：验证profile→读取策略→恢复安装随机device_id→固定Producer环境→取得writer锁→原子写producer.json与registration→启动writer/policy/health任务。无有效许可只启动控制读取、状态和旧源清理，不创建critical/diagnostic业务事实文件；首次获许可生成新run、plugin.started，同run只一次。短暂单用途关闭不伪造进程退出；公共授权撤销后如重开建立新采集run，设备ID不变。

在frontend工具窗入口和backend app初始化调用同一shared服务；单体两处重复start仍只有一个writer。mode/side使用已核验的平台运行模式来源，不能按“谁先调用start”把单体误标frontend。实例持有的环境快照在run内不变。设备随机ID用独立持久设置保存；workspace映射为项目生命周期内随机ID，不保存路径hash。

`stop(app_close/unload)`先禁止新业务准入，再在许可有效时由writer的收尾通道记录一次shutdown，后台限时flush；禁止普通record已经关闭后再调用普通record丢掉shutdown。JVM停机不能无限等待，也不能保证强杀不丢。app_close/unload从平台真实生命周期回调传入，不靠 `dispose()` 猜原因。现有backend的KiloBackendAppLifecycleListener/KiloBackendDynamicPluginListener及frontend的KiloFrontendDynamicPluginListener调用`serviceIfCreated<StabilityService>()?.stop(...)`，避免停机时创建新服务；frontend新增`StabilityLifecycleListener : AppLifecycleListener`接appWillBeClosed并在frontend.xml applicationListeners注册。重复stop用CAS去重。该监听只调用stop，不新增全局uncaught handler。
- [ ] **Step 4：实现后台Retention。** 活跃源10MiB/24小时，优先最旧diagnostic ready再critical ready，exchange锁内选择并删除；ENOENT只表示竞争。无可删空间拒绝新写入。旧源在启动及每小时独立扫描，禁采也执行；验证登记/用户/范围/PID+启动时刻，再非阻塞取得旧writer锁→exchange锁，删除过期open/ready。未过期open留正常consumer救援；claimed/done仅daemon可动。无活跃writer且数据清空才移除registration/metadata，保留锁文件。未能取得锁或启动身份不明则跳过，不持自己writer锁等待他人锁。

旧run无正常结束证据时记unclean/unknown仅在当前许可允许且来源可信时产生生命周期事实，保留previous_run_id/evidence；不将旧账户数据重新归当前账户，不重复认领同一旧run终态。消费者也有同一去重规则，G0冻结唯一归属。默认由consumer根据已接收run及锁证据派生；插件不重复生成同一run的unclean。
- [ ] **Step 5：运行ProducerTest/RetentionTest，新增多项目、两次start、禁采重启、同根多producer、PID复用、到期claimed保留、锁顺序测试；提交 `feat(jetbrains): manage stability producer lifetime and retention`。**

## Task A6：安全异常详情与采集健康

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/fault.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/health.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/fault-test.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/health-test.kt`

**Interfaces:** `Faults(recorder: Recorder, clock: Clock)`；`report(error: Throwable, component: String, handled: Boolean, fault: String = UUID.randomUUID().toString()): Unit`；`Health.snapshot(): JsonObject`输出本run累计drop/write_error以及depth_bytes/oldest_age_ms。

- [ ] **Step 1：写不泄漏异常原文且不丢最小计数测试。**

```kotlin
@Test fun `detail throttling preserves fault counts`() {
    Fixture().use { fixture ->
        val faults = Faults(fixture.recorder, fixture.clock)
        repeat(10) { faults.report(IllegalStateException("token=secret C:/Users/alice"), "frontend", true) }
        fixture.flush()
        val facts = fixture.facts()
        assertEquals(10, facts.count { it.name == "error.reported" && it.channel == "critical" })
        assertEquals(3, facts.count { it.name == "error.reported" && it.channel == "diagnostic" })
        val text = facts.joinToString { it.toString() }
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("alice"))
    }
}
```

- [ ] **Step 2：运行FaultTest/HealthTest确认失败。**
- [ ] **Step 3：实现最小计数与详情分离。** fault_id在故障边界生成并跨重复报告传递；同fault只计一次，去重缓存有界且与run生命周期绑定。每次独立故障可有相同fingerprint，但必须不同fault_id。

```kotlin
val frames = error.stackTrace.asSequence()
    .filter { it.className.startsWith("ai.kilocode.") }
    .map { "${it.className}#${it.methodName}" }
    .take(5).toList()
val fingerprint = MessageDigest.getInstance("SHA-256")
    .digest((error.javaClass.name + "\n" + frames.joinToString("\n")).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
```

只保留插件类/方法白名单，不含文件名、行号、原message、cause文本或完整堆栈。异常类别映射指标文档3.2受控值，NoClassDefFoundError/LinkageError可观测；CancellationException排除，VirtualMachineError/ThreadDeath及其他致命错误保留原传播语义，不因采集而吞掉。

critical计数仅metrics；diagnostic详情仅logs，两个event_id共享fault_id。日志开启但指标关闭仍可记录详情。每fingerprint每分钟最多3份，额外数在下一窗口生成固定安全摘要，摘要count不再计入异常指标。限频键表须有容量上限（实现选择1024个，满时归固定overflow摘要），不因异常风暴无限分配。
- [ ] **Step 4：接入每30秒及损失变化的累计health。** drop/write_error按reason/channel累计，只在后台生成；写盘失败向独立KiloLog限频输出安全模板，不重新record；恢复后累计快照让consumer取差值，第一快照及重放规则交外部验证。Health不收集业务内容。
- [ ] **Step 5：验证同fault去重、同fingerprint10次计数/3详情/7摘要、取消排除、致命错误传播、只有单用途、health重放和run重置；运行 `typecheck` 与A全部定向测试，提交 `feat(jetbrains): collect safe faults and collector health`。**

## 本子计划验收

```powershell
./gradlew.bat typecheck
./gradlew.bat :shared:detekt
./gradlew.bat :shared:test --tests 'ai.kilocode.stability.*'
```

平台注册或依赖改变后加 `buildPlugin`、单体/双端服务实例检查。这里只证明插件侧事实通道；真实Go锁、ACK持久性、两出口及告警时延仍须总计划G1验收。
