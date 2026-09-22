# JetBrains 稳定性采集器迁移追加式协议 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把插件侧稳定性采集器从旧版"分段文件+双锁+状态机"交接协议迁移到设计文档（工作区版本）定义的"追加日志+位移消费"协议，并补齐 fail-open、scope-id、health 增量、edt.stall、plugin.unclean 插件侧判定五项语义。

**Architecture:** 观测面（Recorder/Queue/Dictionary/Operations/Faults/各业务接入点）保持不变；重写落盘层（Storage/Writer/Retention）为 `~/.costrict/telemetry/outbox/<scope-id>-<producer-id>.jsonl` 单写者追加文件；策略层加 unbound 占位实现 fail-open；服务层组装新布局并接管 unclean 判定。cs-cloud 侧不在本计划范围。

**Tech Stack:** Kotlin、JetBrains 平台（App 级轻服务、PropertiesComponent、invokeLater）、kotlinx.serialization/coroutines、Gradle 定向测试 + 真实 IDE integrationTest（JetBrains Starter）。

**Spec:** `docs/jetbrains-stability-design.md`（工作区未提交版本，以下章节号均指它）+ `docs/jetbrains-plugin-stability-metrics.md`（工作区版本）。规格与计划一起阅读；两文档当前有未提交修改，**实现前不要提交/回退它们**。

## Global Constraints

- 根路径：`~/.costrict/telemetry/`（默认 profile）；outbox 文件名 `^[a-z0-9][a-z0-9-]*\.jsonl$`，格式 `<scope-id>-<producer-id>.jsonl`（§5.2）。
- 行格式：UTF-8 无 BOM、NDJSON、LF 结尾、一行（含行尾 LF）一次 write、普通记录 ≤32KiB（§6.1）。
- 无登记目录、无 producer.json、无锁文件、无 .open/.ready/.claimed/.done 状态机（§5.2/§3.1）。
- flush：队列非空且有积压时最迟 30 秒 flush 并 fsync 一次；或累计 16 条/64KiB 即 flush；只有非空批次参与定时 flush；diagnostic 随 critical 同批写出、不设更长延迟（§7.1）。
- 每 producer 事实文件上限 10MiB：写者后台重写淘汰最旧行（截取至预算内写临时文件→原子替换→重开追加），被淘汰行计入 health drop；文件被清理方删除时下次追加按原名重建，不视为错误（§7.4）。
- 陈旧清理：只删整个文件，条件为自最后追加起超过 24 小时；插件实例只清理自己 scope-id 前缀的文件，跨 scope 归 cs-cloud；绝不阻塞业务（§7.4）。
- fail-open：无有效策略（文件不存在、为空、畸形、未知 major）默认不限制——purposes 全标 metrics+logs、policy_revision=0、account_epoch 占位值 `unbound`；限制只来自当前有效的显式策略；显式 enabled=false/公共 expires_at 过期 → 停采并清理待交接数据、不补报（§8）。
- unbound 占位 epoch 不绑定账户代际、不触发退役丢弃；账户切换仍永久退役旧 epoch（§8.1）。
- plugin.unclean 由插件下一实例判定：按 scope-id 前缀找前任文件，最后一条 plugin.started 之后没有 plugin.shutdown 即产出（§7.3）。
- telemetry.health 的 drop/write_error 为自上一条 health 事实以来的增量，cs-cloud 直接求和；run 重启后增量从零起算（§6.2/§9）。
- edt.stall：插件合并 valid 样本排队区间——同观测区间内仅合并明确相交或首尾相接的阻塞区间，序号缺失、中断标记或 observation_id 变化打断合并；单个区间持续 ≥2 秒产出一条（duration_ms、observation_id）（§10.3）。
- seq 每 producer+run+channel 从 1 递增，入队前分配，丢弃产生空洞不复用（§6.1）。
- 内存队列 2000 条且 4MiB（先到为准），critical 预留 20%（§7.1）——现状已满足，不得改动。
- 权限：POSIX 0700/0600；Windows 用户 ACL；拒绝 symlink/reparse 越界（§5.2）——`Storage` 现有校验保留。
- EDT 不写文件、不等待锁；record 非阻塞只返回 queued/dropped/disabled（§7.1）——现状已满足。
- 测试用真实临时文件与现有测试基座（`Fixture`/`FixtureClock`/故障注入）；涉及 Swing 用真实 Application/EDT；只跑受影响模块定向测试与 JetBrains typecheck，不跑全量（§12）。
- typecheck 与 gradle 均需 `JAVA_HOME=~/.jdks/ms-21.0.12.1`（Windows bash：`export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"`）。
- wire 契约文件改动与 Kotlin 孪生常量必须同任务同步（`fact-schema.json` ↔ `Dictionary`/`ContractTest` 常量）。
- 不添加 changeset（正式用户功能交付时再加，§12）。

## 现状基线（执行者必读）

- 采集器在 `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/`（17 文件）；EDT 探针在 `frontend/src/main/kotlin/ai/kilocode/client/stability/probe.kt`；测试在 `shared/src/test/kotlin/ai/kilocode/stability/`（14 类 175 tests 全绿）与 `frontend/src/test/kotlin/ai/kilocode/client/stability/`。
- 旧协议实现位置：`storage.kt`（锁/封存原语）、`writer.kt`（段封存，diagnostic 300 秒延迟）、`retention.kt`（登记校验链+双锁清理）、`producer.kt`（producer.json+registration 落盘）、`stability-service.kt:503`（`v1Root()`=IDE 日志目录下 `costrict-telemetry/v1`）。
- fail-closed 位置：`policy.kt:139`（`current()` 无策略返回 null）→ `recorder.kt:137-141`（DISABLED）。
- health 累计语义：`health.kt:53-55`（KDoc 明确累计、consumer 差值）。
- `edt.stall`/scope-id/unclean 插件判定：全仓不存在。
- E2E：`src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityE2eTest.kt`（1292 行 4 场景，residue 场景被环境级试用反馈弹窗阻塞）+ `StabilityDictionaryE2eTest.kt` + Go 锁探针 `src/integrationTest/go/lockprobe/main.go`。
- 已知环境坑：E2E residue 场景的 Feedback 弹窗阻塞（人工点一次 "No, Thanks" 即解）；`go run` 退出码陷阱（本计划删除 Go 探针后不复存在）。

---

### Task 1: wire 契约文件修订（edt.stall + fail-open + 输出ID/锁条目重组）

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/test/resources/stability/fact-schema.json`
- Modify: `packages/kilo-jetbrains/shared/src/test/resources/stability/control-schema.json`
- Modify: `packages/kilo-jetbrains/shared/src/test/resources/stability/output-vectors.json`
- Modify: `packages/kilo-jetbrains/shared/src/test/resources/stability/contract.json`
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/contract-test.kt`（GATE_FLAGS/EVENT_NAMES 常量同步，:272/:296）

**Interfaces:**
- Consumes: 无（纯资源修订，本计划第一个任务）。
- Produces: 契约文件新键集——后续所有任务以 `fact-schema.json` 的 name enum（31 项，含 `edt.stall`）与 `Dictionary` 一致性为准；`contract.json` 门禁键集（8 项）被 `ContractTest.GATE_FLAGS` 冻结。

- [ ] **Step 1: 更新 ContractTest 常量与新增失败断言（先写测试）**

`contract-test.kt` 中局部常量 `GATE_FLAGS`（:272）改为新键集——删除 `cross_language_locks_verified`，`output_ids_verified` 改名 `output_identity_verified`，`durable_ack_verified` 改名 `offset_commit_verified`，新增 `append_rewrite_contention_verified`：

```kotlin
val GATE_FLAGS = listOf(
    "identity_verified",
    "metrics_authority_verified",
    "series_key_verified",
    "late_query_verified",
    "output_identity_verified",
    "append_rewrite_contention_verified",
    "offset_commit_verified",
    "logs_contract_verified",
    "default_profile_verified",
)
```

`EVENT_NAMES`（:296）在 `"edt.violation"` 之后插入 `"edt.stall"`（保持与 fact-schema name enum 相同顺序）。

- [ ] **Step 2: 跑测试确认失败**

```bash
cd packages/kilo-jetbrains
export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"
./gradlew :shared:test --tests "ai.kilocode.stability.ContractTest"
```
Expected: FAIL——`contract fixture keeps every gate flag unverified`（键集不匹配）与 `fact schema freezes the common field set`（name enum 少 edt.stall）。

- [ ] **Step 3: 修订四个契约文件**

`fact-schema.json`：`properties.name.enum` 在 `"edt.violation"` 后插入 `"edt.stall"`。

`control-schema.json`：
- `description` 中"公共策略缺失、过期或未知schema_major时关闭两种上传用途采集"替换为："无有效策略（文件不存在、为空、畸形或未知schema_major）时默认不限制采集：purposes全标、policy_revision=0、account_epoch使用占位值unbound；限制仅来自当前有效的显式策略（设计第8章fail open）。"
- `account_epoch` 的 description 追加一句："无有效策略期间采集的事实使用占位值unbound，不绑定任何账户代际、不触发退役丢弃"。

`output-vectors.json` 整体替换为（废止 UUIDv5 命名空间冻结，改为约束声明；§9.1 生成方案归 cs-cloud 内部）：

```json
{
  "schema_major": 1,
  "status": "pending_freeze",
  "constraints": {
    "determinism": "同输入重放生成的输出ID及内容不变，重试与崩溃恢复不换ID",
    "distinctness": "同事实不同sink或输出名得到不同ID；累计类输出使用持久化聚合键与输出序号（聚合键含producer/run、account_epoch、用途、指标及登记标签、映射版本）",
    "length_limits": {"metrics_event_id_max_chars": 64, "logs_event_id_max_chars": 128},
    "generation_owner": "cs-cloud内部实现细节，由其仓库自定并测试，不在本契约冻结"
  },
  "vectors": []
}
```

`contract.json` 整体替换为（8 flag，仍全 false——外部证据未齐，G0 门禁语义不变）：

```json
{
  "schema_major": 1,
  "identity_verified": false,
  "metrics_authority_verified": false,
  "series_key_verified": false,
  "late_query_verified": false,
  "output_identity_verified": false,
  "append_rewrite_contention_verified": false,
  "offset_commit_verified": false,
  "logs_contract_verified": false,
  "default_profile_verified": false
}
```

- [ ] **Step 4: 跑 ContractTest 确认通过**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.ContractTest"
```
Expected: PASS（10 tests）。注意：若 `unverified contract cannot enable collection` 因 flag 集变化报错，检查 GATE_FLAGS 与 contract.json 键集逐字一致。

- [ ] **Step 5: Commit**

```bash
git add packages/kilo-jetbrains/shared/src/test/resources/stability/ packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/contract-test.kt
git commit -m "test(jetbrains): revise stability wire contracts for append protocol"
```

---

### Task 2: scope-id 持久标识存储

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/producer.kt`
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/producer-test.kt`

**Interfaces:**
- Consumes: `randomId()`（producer.kt:36）、`PropertiesComponent`（既有用法 producer.kt:76-81）。
- Produces: `fun interface ScopeIdStore { fun loadOrCreate(): String }`、`fun platformScopeIdStore(): ScopeIdStore`——Task 5/7/11 依赖；scope-id 格式 `sc-<12位小写hex>`（满足文件名 pattern `^[a-z0-9][a-z0-9-]*`，§5.2）。

- [ ] **Step 1: 写失败测试（追加到 ProducerTest）**

```kotlin
@Test
fun `scope id persists across store instances and matches the file name pattern`() {
    val store = platformScopeIdStore() // 纯JVM环境：PropertiesComponent不可得时退化随机值
    val scopeId = store.loadOrCreate()
    assertTrue(scopeId.startsWith("sc-"), scopeId)
    assertTrue(Regex("^[a-z0-9][a-z0-9-]*$").matches(scopeId), scopeId)
    assertEquals(scopeId, store.loadOrCreate())
}
```

注：单元环境无平台应用，`platformScopeIdStore` 走 `getOrElse` 退化分支（同 `platformDeviceIdStore` producer.kt:76-81 语义：本轮身份不持久但不抛出），断言只覆盖格式与同实例稳定；跨实例持久性由 Task 13 E2E 覆盖。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.ProducerTest"
```
Expected: FAIL（unresolved reference `platformScopeIdStore`）。

- [ ] **Step 3: 实现（producer.kt，紧邻 DeviceIdStore 之后）**

```kotlin
private const val SCOPE_SETTING_KEY = "ai.kilocode.stability.scope.id"
private const val SCOPE_PREFIX = "sc-"

/**
 * IDE安装范围持久随机标识（设计5.2）：同一IDE多次启动共享，用于识别前任文件
 * （plugin.unclean判定）与同源清理归属；不同IDE安装互不相同。存IDE持久设置，
 * 与device_id分开存储；实现不得抛出。
 */
fun interface ScopeIdStore {
    fun loadOrCreate(): String
}

/** 生产实现：与[platformDeviceIdStore]同型的PropertiesComponent持久化。 */
fun platformScopeIdStore(): ScopeIdStore = ScopeIdStore {
    runCatching {
        PropertiesComponent.getInstance().getValue(SCOPE_SETTING_KEY)
            ?: (SCOPE_PREFIX + randomId()).also { PropertiesComponent.getInstance().setValue(SCOPE_SETTING_KEY, it) }
    }.getOrElse { SCOPE_PREFIX + randomId() }
}
```

- [ ] **Step 4: 跑测试确认通过并提交**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.ProducerTest"
git add -A packages/kilo-jetbrains/shared/src
git commit -m "feat(jetbrains): add persistent scope id for stability outbox naming"
```

---

### Task 3: PolicyStore fail-open 占位策略

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/policy.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/recorder.kt`（`record()` 的 null 分支语义注释）
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/policy-test.kt`

**Interfaces:**
- Consumes: `Policy`/`Permit`/`PolicyStore`（policy.kt:56/:65/:111）、`Dictionary.names`（dictionary.kt:171）。
- Produces: `const val EPOCH_UNBOUND = "unbound"`（internal）；`PolicyStore.current()` 契约变更——**永非 null**，无有效策略时返回占位 `Policy`（epoch=`unbound`、revision=0、enabled=true、state=ready、expires=Long.MAX_VALUE、双用途 Permit(enabled=true, expires=Long.MAX_VALUE, names=全部登记name)）。Task 5/9/11 依赖该非 null 契约。

- [ ] **Step 1: 写失败测试（追加到 PolicyTest）**

```kotlin
@Test
fun `missing control file falls open to the unbound placeholder policy`() {
    val dir = Files.createTempDirectory("policy-failopen")
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
    val dir = Files.createTempDirectory("policy-failopen2")
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
    val dir = Files.createTempDirectory("policy-explicit")
    val control = dir.resolve("control.json")
    // 有效schema、enabled=false：显式撤销，不得fall open
    control.writeText(validControlJson(enabled = false, expiresAt = 5_000L))
    PolicyStore(control, { 1_000L }, pollIntervalMs = 60_000L).use { store ->
        assertTrue(store.current().permit(1_000L, "plugin.started").isEmpty())
    }
    // 有效schema但公共expires已过：显式授权边界已过，停采
    control.writeText(validControlJson(enabled = true, expiresAt = 500L))
    PolicyStore(control, { 1_000L }, pollIntervalMs = 60_000L).use { store ->
        assertTrue(store.current().permit(1_000L, "plugin.started").isEmpty())
    }
}

@Test
fun `unbound placeholder epoch is never retired`() {
    val dir = Files.createTempDirectory("policy-unbound-retire")
    val control = dir.resolve("control.json")
    control.writeText(validControlJson(epoch = EPOCH_UNBOUND, enabled = true, expiresAt = 9_999_999L))
    val store = PolicyStore(control, { 1_000L }, pollIntervalMs = 60_000L)
    try {
        control.writeText(validControlJson(epoch = "acct-real", enabled = true, expiresAt = 9_999_999L))
        store.refresh()
        control.delete()
        store.refresh()
        // 回到无策略：占位策略仍可用，unbound未被退役拖累
        assertEquals(EPOCH_UNBOUND, store.current().epoch)
    } finally {
        store.close()
    }
}
```

`validControlJson` 为 PolicyTest 既有的控制文件构造辅助（若无则新增，产出 13 字段闭集合法 JSON；epoch/enabled/expiresAt 为参数）。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.PolicyTest"
```
Expected: FAIL（`current()` 返回 null → NPE/断言失败；`EPOCH_UNBOUND` 未定义）。

- [ ] **Step 3: 实现（policy.kt）**

```kotlin
/** 无有效策略期间的占位epoch（设计8/8.1）：不绑定账户代际、永不退役。 */
internal const val EPOCH_UNBOUND = "unbound"
```

`PolicyStore` 内新增伴生占位（文件级私有常量 + current 兜底）：

```kotlin
private val UNBOUND_POLICY: Policy = Policy(
    major = SUPPORTED_MAJOR,
    revision = 0L,
    enabled = true,
    epoch = EPOCH_UNBOUND,
    state = STATE_READY,
    expires = Long.MAX_VALUE,
    metrics = Permit(true, Long.MAX_VALUE, REGISTERED_NAMES),
    logs = Permit(true, Long.MAX_VALUE, REGISTERED_NAMES),
)

fun current(): Policy {
    raiseFloor()
    return visible(snapshot ?: return UNBOUND_POLICY, floorMs.get())
}
```

`visible`/`clampLease` 对 Long.MAX_VALUE expires 天然放行（`floor < MAX_VALUE`）。`retirePrevious` 加守卫：

```kotlin
private fun retirePrevious(parsed: Policy) {
    val previous = currentEpoch
    if (previous != null && parsed.epoch != previous && previous != EPOCH_UNBOUND) retired.add(previous)
}
```

同步更新类 KDoc：把"fail closed"段落（policy.kt:100-102）改为引用 §8 fail-open 语义；`current()` KDoc 的"无有效策略时为null"改为"无有效策略时返回unbound占位策略（§8默认不限制）"。

- [ ] **Step 4: recorder.kt 语义注释同步**

`Recorder.record`（recorder.kt:137-141）的 `policy == null` 分支现在只在防御性场景触达；把 `disabledPolicy` 分支的注释改为"显式策略关闭两用途（占位策略permit为空集不会走到这里，null仅为防御）"。**不改代码逻辑**（保留防御分支）。

- [ ] **Step 5: 跑 shared 全部稳定性测试确认无回归并提交**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.*"
git add -A packages/kilo-jetbrains/shared/src
git commit -m "feat(jetbrains): fail open with unbound placeholder policy when control file absent"
```

注意：此提交后 `writer-test`/`producer-test`/`queue-test` 等依赖"无策略=fail closed"的既有用例会红。**处理方式**：先跑 `./gradlew :shared:test --tests "ai.kilocode.stability.*"`，把失败清单逐条读出，再按语义改断言——凡断言"无控制文件⇒DISABLED/不落盘"的用例，改为断言"无控制文件⇒epoch=unbound 的事实落盘、purposes 双标、revision=0"；凡断言"writer 入盘前重判期丢弃"的用例，改为断言占位策略全放行（丢弃只在退役 epoch 或显式关闭时发生）。不得删用例来消红。允许拆为独立提交，但必须与本任务同 PR。

---

### Task 4: Storage 追加式原语重写

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/storage.kt`
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/writer-test.kt`（Storage 无独立测试类，原语经 Writer 测试覆盖；本任务先加最小直测再删旧 API）

**Interfaces:**
- Consumes: `StorageUnverifiedException`（storage.kt:44）、`verifyNoLinks`/`verifyPermissions`（私有，保留）。
- Produces（Task 5 消费的完整 API）:
  - `fun openAppend(path: Path): FileChannel`——CREATE+WRITE+APPEND 打开（不存在则创建），核验 0600/ACL；文件不存在时父目录必须已通过 `verifyLayout`。
  - `fun writeAll(channel: FileChannel, buffer: ByteBuffer): Int`（保留不变）
  - `fun force(channel: FileChannel)`（保留不变）
  - `fun atomicWrite(target: Path, bytes: ByteArray)`（保留，容量重写复用）
  - 删除：`channelDirectory`、`openSegment`、`seal`、`acquireWriterLock`、`ensureExchangeLock`、`openLockFile`、锁常量。
  - 故障注入口 `beforeWrite/beforeForce/beforeMove` 保留。

- [ ] **Step 1: 写失败测试（追加到 WriterTest，直测 Storage 新原语）**

```kotlin
@Test
fun `openAppend appends and recreates without truncation`() {
    val dir = Files.createTempDirectory("storage-append")
    val storage = Storage(dir)
    storage.verifyLayout()
    val file = dir.resolve("sc-ab12-pr-cd34.jsonl")
    storage.openAppend(file).use { channel ->
        storage.writeAll(channel, ByteBuffer.wrap("{\"a\":1}\n".encodeToByteArray()))
        storage.force(channel)
    }
    storage.openAppend(file).use { channel ->
        storage.writeAll(channel, ByteBuffer.wrap("{\"a\":2}\n".encodeToByteArray()))
        storage.force(channel)
    }
    assertEquals("{\"a\":1}\n{\"a\":2}\n", Files.readString(file))
    // 被外部删除后按原名重建，不视为错误
    Files.delete(file)
    storage.openAppend(file).use { channel ->
        storage.writeAll(channel, ByteBuffer.wrap("{\"a\":3}\n".encodeToByteArray()))
        storage.force(channel)
    }
    assertEquals("{\"a\":3}\n", Files.readString(file))
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.WriterTest"
```
Expected: FAIL（unresolved `openAppend`）。

- [ ] **Step 3: 实现（storage.kt）**

新增 `openAppend`（替代 `openSegment`，位置复用其函数体改后缀语义）：

```kotlin
/** 追加打开事实文件（存在则追加，绝不truncate；不存在则创建）并核验0600/ACL。 */
fun openAppend(path: Path): FileChannel {
    val channel = try {
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    } catch (exception: IOException) {
        throw StorageUnverifiedException("outbox file cannot be opened: ${exception.message}", exception)
    }
    return try {
        verifyPermissions(path, isDirectory = false)
        channel
    } catch (exception: StorageUnverifiedException) {
        runCatching { channel.close() }
        throw exception
    }
}
```

删除 `channelDirectory`/`openSegment`/`seal`/`acquireWriterLock`/`ensureExchangeLock`/`openLockFile` 与 `WRITER_LOCK_NAME`/`EXCHANGE_LOCK_NAME`/`LOCK_RANGE_*`/`CHANNEL_*` 常量、`OverlappingFileLockException`/`FileAlreadyExistsException`（若仅锁路径使用）等 import。保留 `atomicWrite`（临时文件→force→ATOMIC_MOVE REPLACE_EXISTING）。

此时 `writer.kt`/`retention.kt` 编译会断（引用已删 API）——**本步骤允许中间态**，Task 5/6 紧随修复；若需保持每步可编译，可先在本任务把 writer/retention 中断点处临时注释并在 Task 5/6 恢复（不提交中间态）。推荐做法：**本任务与 Task 5、6 在同一工作流连续执行，Task 4 的 Step 3 完成后立即进入 Task 5，提交时合并或按任务分别提交但保证各自测试绿（见各任务提交说明）**。

- [ ] **Step 4: 跑测试确认通过（在 Task 5 完成后）**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.WriterTest"
```
Expected: PASS。

- [ ] **Step 5: Commit（与 Task 5 一起或紧随其后）**

```bash
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/storage.kt
git commit -m "refactor(jetbrains): replace segment and lock primitives with append-only outbox io"
```

---

### Task 5: Writer 追加式单文件重写（含容量重写）

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/writer.kt`（整体重写）
- Modify: `packages/kilo-jetbrains/shared/src/testFixtures/kotlin/ai/kilocode/stability/stability-fixture.kt`（构造参数与 `facts()` 适配）
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/writer-test.kt`（重写）

**Interfaces:**
- Consumes: `Storage.openAppend/writeAll/force/atomicWrite/verifyLayout`（Task 4）、`EPOCH_UNBOUND`（Task 3）、`PolicyStore.current/retiredEpochs`（既有）。
- Produces: `class Writer(root: Path, fileName: String, identity: ProducerIdentity, recorder: Recorder, policies: PolicyStore, clock: Clock, storage: Storage = Storage(root), tickMs: Long = 1_000L, maxFileBytes: Long = 10MiB, batchFlushBytes: Long = 64KiB, maxFlushAgeMs: Long = 30_000L, batchFlushRecords: Int = 16)`；公开面不变：`start()/flush()/close()/stats()/state/disabledReason/onDisabled`；`WriterStats` 增加 `droppedEvicted: Long`（Task 8 消费，计入 health drop）。`WriterState` 枚举不变。

- [ ] **Step 1: 重写 WriterTest（先写失败测试）**

删除段/封存类断言，新测试集（复用 Fixture 新参数，见 Step 3；Fixture 适配与本步骤同批编写，否则无法编译）：

```kotlin
@Test
fun `facts append to one jsonl file across both channels with per-channel seq`() {
    Fixture(tickMs = 50L).use { fixture ->
        repeat(8) { fixture.recorder.record(criticalDraft()) }
        repeat(8) { fixture.recorder.record(diagnosticDraft()) }
        fixture.flush()
        val file = fixture.outboxDir.resolve(fixture.fileName)
        assertTrue(Files.isRegularFile(file))
        val lines = Files.readAllLines(file)
        assertEquals(16, lines.size)
        val facts = lines.map { factJson.decodeFromString(Fact.serializer(), it) }
        assertEquals((1L..8L).toList(), facts.filter { it.channel == "critical" }.map { it.seq })
        assertEquals((1L..8L).toList(), facts.filter { it.channel == "diagnostic" }.map { it.seq })
    }
}

@Test
fun `batch threshold of sixteen records flushes without waiting for the age deadline`() {
    Fixture(tickMs = 50L).use { fixture ->
        repeat(15) { fixture.recorder.record(criticalDraft()) }
        fixture.advanceClock(5_000L)
        fixture.writer.wakeForTest() // 暴露内部唤醒（见Step 3 Fixture辅助），30秒未到不flush
        // 文件存在但可能未fsync——断言以行为准：15条写入文件
        repeat(1) { fixture.recorder.record(criticalDraft()) } // 第16条触发批flush
        fixture.advanceClock(100L)
        assertEquals(16, Files.readAllLines(fixture.outboxDir.resolve(fixture.fileName)).size)
    }
}

@Test
fun `pending bytes flush at most thirty seconds after the first unwritten batch entry`() {
    Fixture(tickMs = 50L, maxFlushAgeMs = 30_000L).use { fixture ->
        fixture.recorder.record(criticalDraft())
        fixture.advanceClock(29_999L)
        fixture.writer.wakeForTest()
        // 30秒窗口内已有写入（tick排空队列），此处只断言数据在文件中
        fixture.advanceClock(2L)
        fixture.writer.wakeForTest()
        assertEquals(1, Files.readAllLines(fixture.outboxDir.resolve(fixture.fileName)).size)
    }
}

@Test
fun `oversize file rewrites keeping the tail and counting evicted lines`() {
    Fixture(tickMs = 50L, maxFileBytes = 2L * 1024).use { fixture ->
        repeat(40) { fixture.recorder.record(wideCriticalDraft()) } // 每条约100B，总量超2KiB
        fixture.flush()
        val file = fixture.outboxDir.resolve(fixture.fileName)
        assertTrue(Files.size(file) <= 2L * 1024)
        val lines = Files.readAllLines(file)
        assertTrue(lines.size in 1 until 40, "expected eviction, got ${lines.size}")
        assertTrue(fixture.writer.stats().droppedEvicted > 0)
        // 保留行不被改写：末行仍是完整合法事实
        factJson.decodeFromString(Fact.serializer(), lines.last())
        // 重写后继续追加正常
        fixture.recorder.record(criticalDraft())
        fixture.flush()
        assertEquals(lines.size + 1, Files.readAllLines(file).size)
    }
}

@Test
fun `deleted outbox file is recreated on the next append`() {
    Fixture(tickMs = 50L).use { fixture ->
        fixture.recorder.record(criticalDraft())
        fixture.flush()
        val file = fixture.outboxDir.resolve(fixture.fileName)
        Files.delete(file)
        fixture.recorder.record(criticalDraft())
        fixture.advanceClock(200L)
        fixture.writer.wakeForTest()
        fixture.flush()
        assertEquals(1, Files.readAllLines(file).size) // 重建后只有新事实
    }
}

@Test
fun `queued facts of a retired epoch are dropped at the write gate`() {
    Fixture(tickMs = 50L).use { fixture ->
        fixture.recorder.record(criticalDraft())
        fixture.rotateEpochControl("acct-b")   // Fixture既有辅助（证据文档§9.2）
        fixture.flush()
        assertEquals(0, fixture.facts().size)  // 排队中的旧epoch事实不落盘
        assertEquals(1, fixture.writer.stats().droppedPolicy)
        fixture.recorder.record(criticalDraft())
        fixture.flush()
        val facts = fixture.facts()
        assertEquals(1, facts.size)
        assertEquals("acct-b", facts[0].account_epoch)
    }
}
```

测试文件顶部新增三个 Draft 辅助（与既有测试同风格；critical/diagnostic 两通道各一，wide 用于容量重写用例）：

```kotlin
private fun criticalDraft(): Draft = Draft("rpc", "operation", "critical", buildJsonObject {
    put("phase", "end")
    put("api_group", "chat")
}, purposes = setOf("metrics", "logs"))

private fun diagnosticDraft(): Draft = Draft("error.reported", "diagnostic", "diagnostic", buildJsonObject {
    put("error_class", "java.lang.IllegalStateException")
    put("handled", false)
    put("count", 1)
}, purposes = setOf("logs"))

/** 约100字节负载，用于在小预算下触发容量重写。 */
private fun wideCriticalDraft(): Draft = Draft("rpc", "operation", "critical", buildJsonObject {
    put("phase", "end")
    put("api_group", "chat")
    put("padding", "x".repeat(60))
}, purposes = setOf("metrics", "logs"))
```

注：`rpc`/`error.reported` 的 data 键闭集以 `Dictionary.SPEC_TABLE` 为准；若实际白名单不同（如 rpc 无 stage 键、error.reported 需 fault_id），按 SPEC_TABLE 逐键校正，**不得改字典去迁就测试**。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.WriterTest"
```
Expected: 编译失败（Fixture 参数不存在）→ 修好 Fixture 后 FAIL（Writer 行为为旧协议）。

- [ ] **Step 3: Fixture 适配（stability-fixture.kt）**

```kotlin
class Fixture(
    val tickMs: Long = 50L,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,       // 10MiB，测试可注入小值
    val batchFlushBytes: Long = 64L * 1024,
    val maxFlushAgeMs: Long = 30_000L,
    base: Path? = null,
    private val cleanOnClose: Boolean = true,
    autoStart: Boolean = true,
) : AutoCloseable {
    val base: Path = base ?: Files.createTempDirectory("stability-writer")
    val outboxDir: Path = this.base.resolve("outbox")      // 原 root 更名
    val fileName: String = "sc-fx-${DEFAULT_IDENTITY.producerId}.jsonl"
    ...
    val writer: Writer = Writer(
        root = outboxDir,
        fileName = fileName,
        identity = DEFAULT_IDENTITY,
        recorder = recorder,
        policies = policies,
        clock = clock,
        storage = storage,
        tickMs = tickMs,
        maxFileBytes = maxFileBytes,
        batchFlushBytes = batchFlushBytes,
        maxFlushAgeMs = maxFlushAgeMs,
    )
    /** 读取追加文件全部行（按行序）；文件不存在返回空列表。 */
    fun facts(): List<Fact> =
        outboxDir.resolve(fileName).takeIf { Files.isRegularFile(it) }
            ?.let { file -> Files.readAllBytes(file).toString(Charsets.UTF_8)
                .lineSequence().filter { it.isNotBlank() }
                .map { line -> factJson.decodeFromString(Fact.serializer(), line) }.toList() }
        ?: emptyList()
```

`wakeForTest()`：在 Writer 上新增 `internal fun wakeForTest() = wake()`（生产不可达，仅测试驱动 tick 循环）。删除 `maxSegmentBytes` 参数与 `listReady`（若 `listReady` 被其他测试用，改为列 outbox 下 `*.jsonl`）。

- [ ] **Step 4: 实现 Writer（writer.kt 整体重写，保留公开面）**

**从现有 writer.kt 逐字迁移、不改语义的片段**（执行时直接搬，不要重写）：
- `wake()`（:181-192 的 tryLock 去重唤醒）、`drainQueue()`（:211-221 的取批循环与 `claim.release()` 恰好一次）、`recordsAborted()`（:224-235 的故障即放弃本批其余记录）
- `encodeLine()`（:248-265 的退役 epoch 闸门 → 入盘前重判期 → 32KiB 真实编码复核，三段顺序不变）
- `start()`（:118-146 的 verifyLayout → ACTIVE/disable 分支）、`flush()`（:152-161）、`close()`（:164-175）的 barrier 结构
- `stats()`（:178）、`disable()`（:356-361）、`openSegmentFor` 的异常分类思路（:284-292：`StorageUnverifiedException` → disable，`IOException` → writeErrors）
- 全部 `AtomicLong` 计数字段与 `WriterState`/`WriterStats` 声明
- **删除**：`Segment` 类、`segments`/`segmentIds` 映射、`sealExpired`/`sealSegment`/`sealAllNonEmpty`/`failSegment`/`closeSegmentFile`/`segmentFor`/`createSegment`、`CRITICAL_MAX_AGE_MS`/`DIAGNOSTIC_MAX_AGE_MS`/`DEFAULT_MAX_SEGMENT_BYTES`/`SEGMENT_MAX_RECORDS`、`lockChannel`

结构骨架（保留既有线程模型：单 IO executor + ticker + waking 去重 + start/flush/close barrier）：

```kotlin
class Writer(
    private val root: Path,
    private val fileName: String,
    private val identity: ProducerIdentity,
    private val recorder: Recorder,
    private val policies: PolicyStore,
    private val clock: Clock,
    private val storage: Storage = Storage(root),
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val batchFlushBytes: Long = DEFAULT_BATCH_FLUSH_BYTES,
    private val maxFlushAgeMs: Long = MAX_FLUSH_AGE_MS,
) {
    private val droppedEvicted = AtomicLong(0) // 其余计数字段保留

    private var channel: FileChannel? = null
    private var fileBytes = 0L               // 本会话已写字节（重写后重置为重写结果大小）
    private var pendingSinceMonoMs = -1L     // 首条未fsync写入的时刻；-1=无积压
    private var pendingRecords = 0
    private var pendingBytes = 0L

    // start(): verifyLayout → reopen() → ACTIVE（无锁；无 exchange.lock）
    // cycle(): maybeReopenIfDeleted() → drainQueue() → maybeFlushByAge()
    // drainQueue(): 取批（CLAIM上限保留）→ 逐条 encodeLine → writeLine
    // writeLine(bytes): 若 fileBytes+bytes > maxFileBytes → rewrite() 先行；
    //   maybeReopenIfDeleted(); storage.writeAll(channel, wrap(bytes));
    //   fileBytes += bytes; pending 计数更新（pendingSinceMonoMs<0 时置当前mono）
    // maybeFlushByAge(): pendingSinceMonoMs>=0 且 now-since>=maxFlushAgeMs → doFlush()
    // doFlush(): channel.force(true)（beforeForce 注入点保留）→ pending 清零、since=-1
    // rewrite(): 读现有文件尾部至行边界、总量截到 maxFileBytes 预算内
    //   （从文件头逐行累计，保留能放下的最后若干整行；更简单的实现：
    //    读全部行 → 从尾往前累计到 maxFileBytes 停 → 被丢弃行数计 droppedEvicted）
    //   → storage.atomicWrite(file, 拼接的保留行字节) → 关旧通道 → reopen() → fileBytes=新大小
    // maybeReopenIfDeleted(): 每 tick 与 rewrite 后调用——!Files.exists(file) 时关旧通道并 reopen()
    // close(): 排空 + doFlush() + 关通道（有界，语义同现有）
}
```

关键实现约束：
- **一行一 write**：`writeLine` 把整行（含 LF）装进单个 `ByteBuffer` 经 `storage.writeAll` 提交（与旧实现一致；崩溃残留语义归读取方 §7.2）。
- **encodeLine 保留**：退役 epoch 闸门（`policies.retiredEpochs`）、入盘前重判期（占位策略全放行，Task 3）、32KiB 真实编码复核——三段逻辑原样保留，仅迁移到新类结构。
- **诊断无独立延迟**：删除 `DIAGNOSTIC_MAX_AGE_MS`（300 秒）与通道分段逻辑；两通道同文件、同 flush 节奏（§7.1"diagnostic随批写出，不设更长延迟"）。
- **rewrite 的淘汰计数**：被淘汰行计入 `droppedEvicted`（§7.4"淘汰计入health drop"），不改写保留行内容。
- reopen 失败（IOException）→ `writeErrors` 计数并跳过本条（同旧 failSegment 语义）；`StorageUnverifiedException` → `disable(reason)`。

- [ ] **Step 5: 跑 WriterTest 与 shared 稳定性全量定向测试**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.*"
```
Expected: WriterTest 全 PASS。`producer-test`/`retention-test`/`dictionary-sweep-test` 此时**预期编译失败或红**（引用已删 Storage/Producer API）——记录失败清单，由 Task 6/11 修复；若想保持本步全绿，可把这三个测试文件中直接引用旧 API 的用例先迁移（推荐顺序：Task 4+5+6 连续执行后统一跑绿再分别提交）。

- [ ] **Step 6: Commit**

```bash
git add -A packages/kilo-jetbrains/shared/src
git commit -m "feat(jetbrains): single-writer append jsonl with capacity rewrite and unified flush"
```

---

### Task 6: Retention 重写为同 scope 陈旧清理

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/retention.kt`（整体重写，仅保留常量 DEFAULT_MAX_AGE_MS）
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/retention-test.kt`（重写）

**Interfaces:**
- Consumes: 无外部依赖（纯文件操作 + `Clock`）。
- Produces: `class Retention(outboxDir: Path, scopeId: String, activeFile: Path, clock: Clock, maxAgeMs: Long = 24h)`，唯一公开方法 `fun sweep()`——Task 11 组装。删除全部锁/登记/PID 逻辑与 `ProcessIdentity`/`PidEvidence`（后者定义在 producer.kt，一并删除，见 Task 11）。

- [ ] **Step 1: 重写 RetentionTest（先写失败测试）**

```kotlin
class RetentionTest {
    @Test
    fun `sweep deletes only stale same-scope files`() {
        val dir = Files.createTempDirectory("retention")
        val clock = FixtureClock()
        val active = dir.resolve("sc-live-pr-1111.jsonl").apply { writeText("{}\n") }
        val staleSameScope = dir.resolve("sc-live-pr-2222.jsonl").apply { writeText("{}\n") }
        val freshSameScope = dir.resolve("sc-live-pr-3333.jsonl").apply { writeText("{}\n") }
        val staleOtherScope = dir.resolve("sc-other-pr-4444.jsonl").apply { writeText("{}\n") }
        val staleJunk = dir.resolve("sc-live-notes.txt").apply { writeText("{}\n") }
        val staleDirectory = dir.resolve("sc-live-dir.jsonl").toFile().apply { mkdirs() }
        // 回拨：stale* 的 mtime 设为 25 小时前；fresh 保持当前
        val staleTime = FileTime.fromMillis(clock.wall() - 25L * 60 * 60 * 1000)
        listOf(staleSameScope, staleOtherScope, staleJunk).forEach { Files.setLastModifiedTime(it, staleTime) }
        staleDirectory.setLastModified(staleTime.toMillis())

        Retention(dir, scopeId = "sc-live", activeFile = active, clock = clock).sweep()

        assertTrue(Files.exists(active))          // 活跃文件不动（即使在保留期内也无条件保留）
        assertFalse(Files.exists(staleSameScope)) // 同scope过期删除（整文件，§7.4）
        assertTrue(Files.exists(freshSameScope))  // 未过期不动
        assertTrue(Files.exists(staleOtherScope)) // 他scope归cs-cloud
        assertTrue(Files.exists(staleJunk))       // 非jsonl不动
        assertTrue(staleDirectory.exists())       // 目录不动（只删平铺常规文件）
    }
}
```

`FixtureClock` 为 testFixtures 既有可控时钟（若其在 testFixtures 而 RetentionTest 在 test，沿用现有 import 方式；ProducerTest 已同样使用）。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.RetentionTest"
```
Expected: 编译失败（Retention 构造签名不匹配）。

- [ ] **Step 3: 实现（retention.kt 整体重写）**

```kotlin
/** 同scope陈旧文件保留期（设计7.4）：自最后一次追加起24小时。 */
private const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000
private val FILE_NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*\\.jsonl$")

/**
 * 陈旧清理（设计7.4）：只删整个文件——同scope-id前缀、自最后追加起超过保留期24小时的
 * 平铺常规.jsonl；本实例活跃文件与其余文件（他scope、非jsonl、子目录）一律不碰。
 * 跨scope残留由cs-cloud按保留期清理，不属于本类职责。无锁、无死亡推断、绝不阻塞业务。
 */
class Retention(
    private val outboxDir: Path,
    private val scopeId: String,
    private val activeFile: Path,
    private val clock: Clock,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    /** 后台入口：目录不存在即no-op（本实例尚未写出任何文件时常态）。 */
    fun sweep() {
        if (!Files.isDirectory(outboxDir)) return
        val now = clock.wall()
        Files.list(outboxDir).use { files ->
            files.filter { path -> Files.isRegularFile(path) }
                .filter { path -> path != activeFile }
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("$scopeId-") && FILE_NAME_PATTERN.matches(name)
                }
                .filter { path -> now - modifiedAt(path) > maxAgeMs }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun modifiedAt(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(Long.MAX_VALUE)
}
```

- [ ] **Step 4: 跑 RetentionTest 确认通过并提交**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.RetentionTest"
git add packages/kilo-jetbrains/shared/src
git commit -m "feat(jetbrains): scope-prefix stale file retention without locks or registries"
```

---

### Task 7: plugin.unclean 前任文件判定

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/unclean.kt`
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/unclean-test.kt`（新建）

**Interfaces:**
- Consumes: `Fact`（kotlinx 反序列化）、`Draft`（fact.kt:19）、`randomId` 不需要。
- Produces: `class UncleanDetector(outboxDir: Path, scopeId: String, producerId: String)`，方法 `fun detect(): List<Draft>`——Task 11 在 activateRun 时消费（在 plugin.started 之前 record）。Draft 形态：name=`plugin.unclean`、kind=lifecycle、channel=critical、purposes={metrics,logs}、data={previous_run_id, evidence="no_shutdown_after_started"}。

- [ ] **Step 1: 写失败测试（新建 unclean-test.kt）**

```kotlin
class UncleanTest {
    @Test
    fun `previous file ending after started without shutdown yields one unclean draft`() {
        val dir = Files.createTempDirectory("unclean")
        val previous = dir.resolve("sc-live-pr-old1.jsonl")
        previous.writeText(
            factLine(name = "plugin.started", runId = "run-a") +
                factLine(name = "rpc", runId = "run-a") +
                "{\"schema_version\":\"1.0\",\"event_id\":", // 残缺尾行，必须跳过（§7.2/7.3）
        )
        val drafts = UncleanDetector(dir, scopeId = "sc-live", producerId = "pr-new1").detect()
        assertEquals(1, drafts.size)
        assertEquals("plugin.unclean", drafts[0].name)
        assertEquals("run-a", drafts[0].data["previous_run_id"]?.jsonPrimitive?.content)
        assertEquals("no_shutdown_after_started", drafts[0].data["evidence"]?.jsonPrimitive?.content)
    }

    @Test
    fun `clean previous file yields nothing`() {
        val dir = Files.createTempDirectory("unclean2")
        dir.resolve("sc-live-pr-old2.jsonl").writeText(
            factLine(name = "plugin.started", runId = "run-b") +
                factLine(name = "plugin.shutdown", runId = "run-b"),
        )
        assertTrue(UncleanDetector(dir, "sc-live", "pr-new2").detect().isEmpty())
    }

    @Test
    fun `other scopes and self file are ignored`() {
        val dir = Files.createTempDirectory("unclean3")
        dir.resolve("sc-other-pr-x.jsonl").writeText(factLine(name = "plugin.started", runId = "run-c"))
        dir.resolve("sc-live-pr-self.jsonl").writeText(factLine(name = "plugin.started", runId = "run-d"))
        assertTrue(UncleanDetector(dir, "sc-live", "pr-self").detect().isEmpty())
    }
}
```

`factLine` 测试辅助：构造最小合法 `Fact`（复用各测试文件既有的最小 Fact 构造方式）序列化为一行 + LF。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.UncleanTest"
```
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现（unclean.kt 新建）**

```kotlin
package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 尾部扫描窗口：覆盖最大文件（10MiB）的最后256KiB即可判定最后一条started/shutdown。 */
private const val TAIL_WINDOW_BYTES = 256L * 1024
private val FILE_NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*\\.jsonl$")
private val NAME_STARTED = "plugin.started"
private val NAME_SHUTDOWN = "plugin.shutdown"
internal const val EVIDENCE_NO_SHUTDOWN = "no_shutdown_after_started"

/**
 * plugin.unclean判定（设计7.3/M22）：插件下一实例按scope-id前缀找前任文件——最后一条
 * plugin.started之后没有plugin.shutdown即产出一条unclean事实（previous_run_id、固定
 * evidence token）。残缺尾行按§7.2跳过（不算shutdown）；不做writer死亡推断、不救援。
 * 每个前任文件至多一条；本实例文件与他scope不参与。
 */
class UncleanDetector(
    private val outboxDir: Path,
    private val scopeId: String,
    private val producerId: String,
) {
    fun detect(): List<Draft> {
        if (!Files.isDirectory(outboxDir)) return emptyList()
        Files.list(outboxDir).use { files ->
            return files.filter { path -> Files.isRegularFile(path) }
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("$scopeId-") && FILE_NAME_PATTERN.matches(name) &&
                        name != "$scopeId-$producerId.jsonl"
                }
                .mapNotNull { path -> detectUncleanRun(path) }
                .toList()
        }
    }

    /** 尾部扫描：解析完整行，取最后一条started的run_id，其后无同run的shutdown即unclean。 */
    private fun detectUncleanRun(path: Path): Draft? {
        val facts = tailFacts(path)
        val lastStarted = facts.indexOfLast { it.name == NAME_STARTED }
        if (lastStarted < 0) return null
        val runId = facts[lastStarted].run_id
        val shutdownAfter = facts.drop(lastStarted + 1).any { it.name == NAME_SHUTDOWN && it.run_id == runId }
        if (shutdownAfter) return null
        return Draft(
            NAME_STARTED.let { "plugin.unclean" },
            "lifecycle",
            "critical",
            buildJsonObject {
                put("previous_run_id", runId)
                put("evidence", EVIDENCE_NO_SHUTDOWN)
            },
            purposes = setOf("metrics", "logs"),
        )
    }

    /** 读文件尾部窗口，按行边界对齐后解析合法Fact；残缺首行（窗口截断）与坏行跳过。 */
    private fun tailFacts(path: Path): List<Fact> {
        val bytes = Files.readAllBytes(path) // 10MiB上限内可整读；后续如需优化再改RandomAccessFile
        val text = bytes.toString(Charsets.UTF_8)
        return text.lineSequence().filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { factJson.decodeFromString(Fact.serializer(), line) }.getOrNull() }
            .toList()
    }
}
```

注意：首行若被窗口截断应跳过——整读实现天然无此问题；若实现改为尾部窗口，需丢弃第一行。`factJson` 复用测试/fixture 中的 Json 实例约定（若无共享实例则在文件内 `private val factJson = Json { encodeDefaults = true }`，与 writer.kt:106 一致）。

- [ ] **Step 4: 跑测试确认通过并提交**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.UncleanTest"
git add packages/kilo-jetbrains/shared/src
git commit -m "feat(jetbrains): plugin-side unclean detection from previous scope files"
```

---

### Task 8: telemetry.health 增量化

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/health.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/selftest.kt`（health 不伪造的理由注释，:180 附近）
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/health-test.kt`

**Interfaces:**
- Consumes: `WriterStats.droppedEvicted`（Task 5 新增）。
- Produces: `telemetry.health` 事实的 data 语义变更——drop/write_error 为**自上一条 health 以来的增量**（§6.2）；`Health.snapshot()`/`poll()` 签名不变。

- [ ] **Step 1: 写失败测试（改造 health-test 既有累计断言为增量断言）**

核心新用例（其余既有用例按同语义调整）：

```kotlin
@Test
fun `health facts carry the delta since the previous health fact`() {
    Fixture(tickMs = 50L).use { fixture ->
        val health = fixture.health()
        repeat(2) { fixture.recorder.record(invalidDraft()) }   // 结构性违规 → droppedInvalid
        assertTrue(health.poll())
        repeat(3) { fixture.recorder.record(invalidDraft()) }
        assertTrue(health.poll())

        val healths = fixture.facts().filter { it.name == "telemetry.health" }
        assertEquals(2, healths.size)
        assertEquals(2L, healths[0].data.getValue("drop").jsonPrimitive.long) // 首条=自本实例起算的增量
        assertEquals(3L, healths[1].data.getValue("drop").jsonPrimitive.long) // 距上一条的增量，不是累计5
    }
}

@Test
fun `baseline is per-health-instance so a new run starts from zero`() {
    Fixture(tickMs = 50L).use { fixture ->
        repeat(2) { fixture.recorder.record(invalidDraft()) }
        assertTrue(fixture.health().poll())                     // 实例1基线0，报2
        assertTrue(fixture.health().poll())                     // 实例2基线0（新run），再报2
        val healths = fixture.facts().filter { it.name == "telemetry.health" }
        assertEquals(listOf(2L, 2L), healths.map { it.data.getValue("drop").jsonPrimitive.long })
    }
}

@Test
fun `evicted lines count toward the drop delta`() {
    Fixture(tickMs = 50L, maxFileBytes = 2L * 1024).use { fixture ->
        repeat(40) { fixture.recorder.record(wideCriticalDraft()) }
        fixture.flush()
        fixture.health().poll()
        val drop = fixture.facts().filter { it.name == "telemetry.health" }
            .last().data.getValue("drop").jsonPrimitive.long
        assertTrue(drop >= fixture.writer.stats().droppedEvicted, "eviction must be visible in health")
    }
}
```

`invalidDraft()` 在测试文件内定义：data 携带一个任何 name 白名单都不含的键，`Dictionary.violations` 判违规 → `record` 返回 DROPPED 并计入 `droppedInvalid`（recorder.kt:142-146），无需生产侧测试钩子：

```kotlin
private fun invalidDraft(): Draft = Draft("rpc", "operation", "critical", buildJsonObject {
    put("phase", "end")
    put("api_group", "chat")
    put("zzz_not_in_any_whitelist", 1)
})
```

`fixture.health()` 为 Fixture 新增辅助：`fun health(): Health = Health(recorder, writer, clock)`（沿用既有三参构造）。两个 `Health` 实例共用同一 recorder/writer——这正是要断言的"基线随实例、不随源计数走"。

`assertNotNull` 类既有断言（现测"drop为累计值"的用例）改为增量预期；`snapshot()` 若有消费方同样调整。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.HealthTest"
```
Expected: FAIL（现输出累计值）。

- [ ] **Step 3: 实现（health.kt）**

`HealthSample` 改为在 `poll()` 内先取差再落盘：

```kotlin
internal fun poll(): Boolean {
    val nowMono = clock.mono()
    val sample = sample()
    val dropDelta = sample.drop - lastDrop
    val writeDelta = sample.writeError - lastWriteError
    if (writeDelta > 0) maybeWarn(writeDelta, sample.writeError, nowMono)
    val lossChanged = dropDelta > 0 || writeDelta > 0
    if (!lossChanged && nowMono - lastGenerateMonoMs < intervalMs) return false
    recorder.record(
        Draft(
            NAME_HEALTH, KIND_HEALTH, CHANNEL_CRITICAL,
            buildJsonObject {
                put("drop", dropDelta)          // 增量：cs-cloud直接求和（§6.2）
                put("write_error", writeDelta)
                put("depth_bytes", sample.depthBytes)
                put("oldest_age_ms", sample.oldestAgeMs)
            },
            emptyMap(), null, DUAL_PURPOSES,
        ),
    )
    lastDrop = sample.drop
    lastWriteError = sample.writeError
    lastGenerateMonoMs = nowMono
    return true
}
```

`sample()` 的 drop 求和加入 `stats.droppedEvicted`：

```kotlin
val drop = counters.droppedInvalid + counters.droppedContention + counters.droppedCapacity +
    counters.droppedQuota + stats.droppedPolicy + stats.droppedOversize + stats.droppedEvicted
```

KDoc（health.kt:48-62）更新：删"计数永远是累计值……consumer取相邻快照差值；首快照与重放规则由外部验证负责"，改为"drop/write_error为自上一条health事实以来的增量（§6.2），cs-cloud直接求和；run重启后增量自然从零起算（新Health实例基线为零）"。`snapshot()` 的对外语义同步改注释（若 `snapshot()` 有测试消费方，一并调整）。

- [ ] **Step 4: 跑测试确认通过并提交**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.HealthTest" --tests "ai.kilocode.stability.SelfTestTest"
git add packages/kilo-jetbrains/shared/src
git commit -m "feat(jetbrains): telemetry health reports per-fact deltas instead of run totals"
```

---

### Task 9: edt.stall 字典登记与 StallMerger 合并器

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/dictionary.kt`（SPEC_TABLE/names 投影）
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/edt-stall.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/probe.kt`（接线）
- Test: Create `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/edt-stall-test.kt`
- Test: Modify `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/stability/probe-test.kt`（集成一条）

**Interfaces:**
- Consumes: `Draft`（fact.kt）、`Dictionary` 登记机制（SPEC_TABLE 条目形状，参照 `edt.delay` 条目 dictionary.kt:505 附近）。
- Produces: `class StallMerger(private val emit: (Draft) -> Unit)`，方法 `fun onValidSample(observationId: String, seq: Long, scheduledMonoMs: Long, completedMonoMs: Long)`、`fun onObservationEnded()`——Task 10（selftest 驱动）与 probe.kt 消费。产出 Draft：name=`edt.stall`、kind=sample、channel=critical、purposes={metrics}、data={duration_ms, observation_id}。

- [ ] **Step 1: 写失败测试（新建 edt-stall-test.kt）**

```kotlin
class EdtStallTest {
    private fun merger() : Pair<StallMerger, MutableList<Draft>> {
        val out = mutableListOf<Draft>()
        return StallMerger { out.add(it) } to out
    }

    @Test
    fun `single sample blocking over two seconds yields one stall`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 1_000, 3_500)
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(2_500, out[0].data["duration_ms"]?.jsonPrimitive?.long)
        assertEquals("obs-1", out[0].data["observation_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `overlapping and touching samples merge within one observation`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 11_800)
        m.onValidSample("obs-1", 2, 11_800, 12_500) // 首尾相接（scheduled==end）合并
        m.onValidSample("obs-1", 3, 12_600, 13_100) // 相接合并
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(3_100, out[0].data["duration_ms"]?.jsonPrimitive?.long) // 10_000→13_100
    }

    @Test
    fun `sequence gap breaks the merge`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 12_500)   // 2.5s区间
        m.onValidSample("obs-1", 3, 12_600, 12_700)   // seq缺失（2丢失）→打断，新区间0.1s
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(2_500, out[0].data["duration_ms"]?.jsonPrimitive?.long)
    }

    @Test
    fun `observation change breaks the merge and ends the current window`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 12_500) // 2.5s窗口
        m.onValidSample("obs-2", 1, 12_600, 12_700) // 换观测区间：终结上一窗口并开新窗口
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(2_500, out[0].data["duration_ms"]?.jsonPrimitive?.long)
        assertEquals("obs-1", out[0].data["observation_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `windows shorter than two seconds yield nothing`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 10_500)
        m.onValidSample("obs-1", 2, 10_600, 11_200) // 合并后1.2s，仍不足2s
        m.onObservationEnded()
        assertTrue(out.isEmpty())
    }

    @Test
    fun `an idle gap between samples does not continue the window`() {
        val (m, out) = merger()
        m.onValidSample("obs-1", 1, 10_000, 11_500) // 1.5s
        m.onValidSample("obs-1", 2, 20_000, 22_600) // 序连续但排程起点远在上一侧结束之后：不接续
        m.onObservationEnded()
        assertEquals(1, out.size)
        assertEquals(2_600, out[0].data["duration_ms"]?.jsonPrimitive?.long) // 只有第二个区间达标
    }

    @Test
    fun `invalid samples never reach the merger`() {
        val (m, out) = merger()
        // 作废样本由探针侧拦在onValidSample之外：observing区间以onObservationEnded终结
        m.onValidSample("obs-1", 1, 10_000, 12_500)
        m.onObservationEnded() // 探针invalidate路径（失焦/暂停/调度断层/关闭）
        m.onObservationEnded() // 重复终结幂等
        assertEquals(1, out.size)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.EdtStallTest"
```
Expected: FAIL（类不存在；同时 Dictionary 若未登记 edt.stall，Draft 校验会拒绝——登记在 Step 3）。

- [ ] **Step 3: 实现（edt-stall.kt 新建 + dictionary.kt 登记）**

```kotlin
package ai.kilocode.stability

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 观测到的卡顿区间阈值（设计10.3）：合并后持续≥2秒才算一个stall。 */
internal const val STALL_MIN_DURATION_MS = 2_000L

/**
 * edt.stall合并器（设计10.3）：插件在本机合并valid样本的排队区间——同观测区间内仅合并
 * 明确相交或首尾相接且探针序号连续的阻塞区间；序号缺失、中断标记或observation_id变化
 * 打断合并，缺失部分不推断卡顿。区间终结时持续≥[STALL_MIN_DURATION_MS]才产出一条
 * edt.stall事实。线程纪律：由探针后台finalize线程单线程调用，无锁。
 */
class StallMerger(private val emit: (Draft) -> Unit) {
    private var window: Window? = null

    private class Window(
        val observationId: String,
        var lastSeq: Long,
        var startMono: Long,
        var endMono: Long,
    )

    fun onValidSample(observationId: String, seq: Long, scheduledMonoMs: Long, completedMonoMs: Long) {
        val current = window
        val continues = current != null &&
            current.observationId == observationId &&
            current.lastSeq + 1 == seq &&
            scheduledMonoMs <= current.endMono // 相交或首尾相接
        if (continues) {
            current!!.endMono = maxOf(current.endMono, completedMonoMs)
            current.lastSeq = seq
        } else {
            closeWindow()
            window = Window(observationId, seq, scheduledMonoMs, completedMonoMs)
        }
    }

    /** 观测区间终点（失焦/暂停/调度断层/探针关闭共用入口）；终结当前窗口。 */
    fun onObservationEnded() = closeWindow()

    private fun closeWindow() {
        val current = window ?: return
        window = null
        val duration = current.endMono - current.startMono
        if (duration < STALL_MIN_DURATION_MS) return
        emit(
            Draft(
                "edt.stall",
                "sample",
                "critical",
                buildJsonObject {
                    put("duration_ms", duration)
                    put("observation_id", current.observationId)
                },
                purposes = setOf("metrics"),
            ),
        )
    }
}
```

dictionary.kt：`SPEC_TABLE` 加 `edt.stall` 条目（kind=sample、channel=critical、purposes 出口=metrics-only、data 白名单 `duration_ms`(int)/`observation_id`(string)，条目形状逐字对照相邻 `edt.delay` 条目 :505）；`names` 列表（:55 附近）与 `METRICS_ONLY_NAMES`（:54）同步加入。

- [ ] **Step 4: probe.kt 接线**

`Probe` 构造增加可选 `stall: StallMerger? = null`（或 `EdtProbeService` 持有 merger 并在 emit 路径喂数据——取改动最小者：在 `Probe.complete` 的 finalize 块中 validity==valid 时调用 `stall?.onValidSample(...)`；`invalidate()` 开头调用 `stall?.onObservationEnded()`，再更换 observationId）。`EdtProbeService` 平台构造（:164）装配 `StallMerger { draft -> operations()?.record(draft) }` 传入 Probe。

probe-test.kt 追加一条集成用例：valid 样本两次（重叠、同 obs、seq 连续、合计 ≥2s）→ 断言 recorder 收到一条 `edt.stall` Draft（mock operations 收集）。

- [ ] **Step 5: 跑测试确认通过并提交**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.EdtStallTest" --tests "ai.kilocode.stability.DictionarySweepTest"
./gradlew :frontend:test --tests "ai.kilocode.client.stability.ProbeTest"
git add -A packages/kilo-jetbrains/shared/src packages/kilo-jetbrains/frontend/src
git commit -m "feat(jetbrains): derive edt.stall facts from merged edt probe windows"
```

注：DictionarySweepTest 若因 31 个 name 缺驱动而红，其修复属 Task 12——本步只需 EdtStallTest/ProbeTest 绿；若 sweep 测试已红，本步提交信息不变、不跑 sweep。

---

### Task 10: selftest 驱动面补 edt.stall 与 unclean 注释更新

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/selftest.kt`
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/selftest-test.kt`

**Interfaces:**
- Consumes: `StallMerger`（Task 9）。
- Produces: `emitDictionarySweep()` 覆盖 31 个 name（含 edt.stall，经真实 StallMerger 喂合成样本序列产出——不伪造 Draft 形状）。

- [ ] **Step 1: 写失败测试（selftest-test 追加断言）**

既有 `SelfTestTest`（1 条用例）断言驱动面覆盖全部登记 name——追加：断言 sweep 产出包含一条 `edt.stall` 且 `duration_ms ≥ 2000`（驱动序列：同一 observationId 下 seq 1、2 重叠样本合计 2.5s，再 `onObservationEnded()`）。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.SelfTestTest"
```
Expected: FAIL（30 name 无 stall）。

- [ ] **Step 3: 实现（selftest.kt）**

在 `emitDictionarySweep()` 的 name 循环外新增一段（沿用该文件"经真实入口产出"的既有风格）：

```kotlin
// edt.stall经真实StallMerger驱动：同一观测区间内两枚重叠样本合并为2.5秒窗口
val stalls = mutableListOf<Draft>()
val merger = StallMerger { stalls.add(it) }
merger.onValidSample("obs-selftest", 1, 10_000, 11_800)
merger.onValidSample("obs-selftest", 2, 11_900, 12_500)
merger.onObservationEnded()
stalls.forEach(recorder::record)
```

同时更新 `plugin.unclean` 驱动注释（:146 附近）：由"无插件发射点，设计§7.3属消费端判定"改为"生产由 UncleanDetector 于启动时判定（Task 7 已交付）；自检仍以占位 previous_run_id 直投字典形状"。

- [ ] **Step 4: 跑测试确认通过并提交**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.SelfTestTest"
git add packages/kilo-jetbrains/shared/src
git commit -m "test(jetbrains): drive edt.stall through the real merger in dictionary selftest"
```

---

### Task 11: StabilityService 组装迁移与 Producer 瘦身

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/stability-service.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/producer.kt`（删 Producer 登记落盘与 PID 证据类）
- Test: Modify `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/producer-test.kt`

**Interfaces:**
- Consumes: `ScopeIdStore`（Task 2）、`Writer(root, fileName, ...)`（Task 5）、`Retention(outboxDir, scopeId, activeFile, clock)`（Task 6）、`UncleanDetector`（Task 7）、`EPOCH_UNBOUND`（Task 3）。
- Produces: `StabilityService` 公开面不变（`start/stop/recorder/operations/faults/resources/status/noteConnectionProvider`）；`Coverage.reason` 闭集变更——`no_policy` → `unbound`（无策略 fail-open 采集中）。`create` 测试工厂签名：删 `logDirProvider` 参数（不再使用 IDE 日志目录），加 `scopeStore: ScopeIdStore` 参数。**所有 create 调用方（若测试/E2E 有）同批更新。**

- [ ] **Step 1: 写失败测试（producer-test.kt 改造 + 服务级新断言）**

`producer-test.kt` 已经承载服务级 Harness（证据文档 §9.2：`StabilityService.create` 注入 `retentionIntervalMs`/`retentionMaxBytes` 的服务级用例就在此文件）。本任务：
1. 删除登记文件/producer.json 形状用例与锁校验用例；
2. 把 `create` 调用点改为新签名（删 `logDirProvider`，加 `scopeStore`）；
3. 新增布局用例：

```kotlin
@Test
fun `outbox holds one scope-producer jsonl and no registrations`() {
    val home = Files.createTempDirectory("service-outbox")
    val logDir = Files.createTempDirectory("service-logdir")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val service = StabilityService.create(
        scope = scope,
        modeSource = { RunMode("monolith", "monolith") },
        scopeStore = ScopeIdStore { "sc-fixed" },
        telemetryHome = home,
        deviceStore = DeviceIdStore { "device-fixed" },
        clock = FixtureClock(),
        pollIntervalMs = 50L,
    )
    try {
        service.start("frontend") // 无控制文件：fail-open应建立run
        val outbox = home.resolve("outbox")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && listJsonl(outbox).isEmpty()) Thread.sleep(20)
        service.stop("app_close")
        val files = listJsonl(outbox)
        assertEquals(1, files.size)
        assertTrue(
            files[0].fileName.toString().matches(Regex("^sc-fixed-pr-[a-z0-9]+\\.jsonl$")),
            files[0].toString(),
        )
        assertFalse(Files.exists(home.resolve("registrations")))
        assertFalse(Files.exists(logDir.resolve("costrict-telemetry")))

        val facts = Files.readAllLines(files[0]).filter { it.isNotBlank() }
            .map { factJson.decodeFromString(Fact.serializer(), it) }
        assertEquals("plugin.started", facts.first().name)
        assertEquals("unbound", facts.first().account_epoch)
        assertEquals(0L, facts.first().policy_revision)
        assertEquals(setOf("metrics", "logs"), facts.first().purposes)
    } finally {
        scope.cancel()
    }
}

private fun listJsonl(dir: Path): List<Path> =
    if (!Files.isDirectory(dir)) emptyList()
    else Files.list(dir).use { it.filter { p -> p.fileName.toString().endsWith(".jsonl") }.toList() }

@Test
fun `revocation deletes the pending handover file without faking shutdown`() {
    val home = Files.createTempDirectory("service-revoke")
    val control = home.resolve("control").resolve("jetbrains.json")
    Files.createDirectories(control.parent)
    control.writeText(controlJson(epoch = "acct-r1", revision = 1L, enabled = true, expiresAt = 9_999_999_999L))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val service = StabilityService.create(
        scope, { RunMode("monolith", "monolith") }, ScopeIdStore { "sc-fixed" },
        home, DeviceIdStore { "device-fixed" }, FixtureClock(), pollIntervalMs = 50L,
    )
    try {
        service.start("frontend")
        val outbox = home.resolve("outbox")
        awaitUntil(10_000) { listJsonl(outbox).isNotEmpty() }
        val file = listJsonl(outbox).single()
        val before = Files.readAllLines(file)
        assertTrue(before.any { it.contains("\"plugin.started\"") })

        // 撤销：显式enabled=false（§8 停采并清理待交接数据，不保留补报）
        control.writeText(controlJson(epoch = "acct-r1", revision = 2L, enabled = false, expiresAt = 9_999_999_999L))
        awaitUntil(70_000) { listJsonl(outbox).isEmpty() }   // 30秒轮询 + 收尾预算
        assertTrue(listJsonl(outbox).isEmpty(), "revoked handover data must be cleaned")
        // 撤销不伪造shutdown：被删文件内不含 plugin.shutdown（end_kind闭集只有app_close/unload）
        assertTrue(before.none { it.contains("\"plugin.shutdown\"") })
    } finally {
        service.stop("app_close")
        scope.cancel()
    }
}

private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(50)
    assertTrue(condition(), "condition not met within ${timeoutMs}ms")
}

/** 13字段闭集控制文件（control-schema.json）；测试用最小合法构造。 */
private fun controlJson(epoch: String, revision: Long, enabled: Boolean, expiresAt: Long): String =
    JsonObject(
        mapOf(
            "schema_major" to JsonPrimitive(1),
            "revision" to JsonPrimitive(revision),
            "enabled" to JsonPrimitive(enabled),
            "metrics_enabled" to JsonPrimitive(enabled),
            "metrics_expires_at" to JsonPrimitive(expiresAt),
            "logs_enabled" to JsonPrimitive(enabled),
            "logs_expires_at" to JsonPrimitive(expiresAt),
            "account_epoch" to JsonPrimitive(epoch),
            "account_state" to JsonPrimitive("ready"),
            "expires_at" to JsonPrimitive(expiresAt),
            "metrics_allowed_categories" to JsonArray(listOf(JsonPrimitive("critical"), JsonPrimitive("diagnostic"))),
            "logs_allowed_categories" to JsonArray(listOf(JsonPrimitive("critical"), JsonPrimitive("diagnostic"))),
            "log_detail_rate_limit" to JsonObject(mapOf("per_fingerprint_max_per_minute" to JsonPrimitive(3))),
        ),
    ).toString()

（`controlJson`/`awaitUntil`/`listJsonl` 为 producer-test.kt 文件内私有辅助；若文件已有同型辅助则复用。）

`factJson` 与 `listJsonl` 按文件内既有约定定义（`Json { encodeDefaults = true }`）。

- [ ] **Step 2: 跑测试确认失败**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.ProducerTest"
```
Expected: FAIL（新断言；旧登记用例此时已删）。

- [ ] **Step 3: 实现（stability-service.kt + producer.kt）**

stability-service.kt 改动清单：
1. 构造参数：删 `logDirProvider: () -> Path`；加 `scopeStore: ScopeIdStore`；`create` 工厂同步。
2. 路径：删 `v1Root()`/`registrationsDir()`/`TELEMETRY_DIR`/`V1_DIR`/`REGISTRATIONS_DIR` 常量；新增 `private fun outboxDir(): Path = telemetryHome.resolve("outbox")`；`ensureCore` 时 `scopeId = scopeStore.loadOrCreate()` 存为字段。
3. `activateRun()`：
   - 删 `writeMetadataOnce` 调用与方法、`metadataWritten` 字段。
   - writer 构造改 `Writer(outboxDir(), fileName(identity), identity, recorder, store, clock)`；新增单一命名入口 `private fun fileName(identity: ProducerIdentity): String = "$scopeId-${identity.producerId}.jsonl"`（producerId 每 JVM 固定，故文件名跨 run 稳定；runId 变化不改名）。
   - writer ACTIVE 后、`recorder.record(startedDraft())` **之前**：`UncleanDetector(outboxDir(), scopeId, identity.producerId).detect().forEach(recorder::record)`。
4. `deactivateRun()`（撤销/过期停采）：在现有关闸排空后追加清理——

```kotlin
private fun deactivateRun() {
    standby?.first?.forwardTo = null
    activeRecorder?.close()
    activeWriter?.close()   // 有界排空：撤销后重判期使剩余事实不入盘
    activeWriter = null
    runActive = false
    // §8：用户撤销/总开关关闭/公共过期——停采并清理待交接数据，不保留补报
    val identity = baseIdentity
    if (identity != null) runCatching { Files.deleteIfExists(outboxDir().resolve(fileName(identity))) }
}
```

（撤销清理只针对本实例文件——`fileName(identity)` 用 baseIdentity 的 producerId，与写入路径同一命名入口。）注意 `outboxFull` 相关逻辑随闸门删除（见第 6 点）。
5. `permitted()` 不变（占位策略 permit 非空 → 无策略即建 run，fail-open 自动成立）。
6. 删 `outboxFull` 字段、`setStorageFull` **调用**与 `REASON_OUTBOX_FULL`、`Retention` 预算判断；`sweepOnce()` 改为：

```kotlin
private fun sweepOnce() {
    val identity = baseIdentity ?: return
    val outbox = outboxDir()
    runCatching {
        Retention(outbox, scopeId, outbox.resolve(fileName(identity)), clock).sweep()
    }
}
```

7. `Coverage`/reason：`REASON_NO_POLICY = "no_policy"` 改为 `REASON_UNBOUNDED = "unbound"`；`setStatus` 中 metrics/logs 布尔在无策略（epoch==unbound）时输出 true（占位策略 purposes 全开，`purposes()` 已自动返回双用途，无需特判——仅 reason 文案替换）。
8. KDoc：更新为追加协议描述（删 writer.lock/登记/producer.json 段落）。
9. **recorder.kt**：删除空间闸门——`storageFull` 字段、`setStorageFull(full: Boolean)` 方法、`record()` 中 `if (storageFull.get())` 分支与 `droppedQuota` 计数（§7.4 新设计以写者侧容量重写替代准入闸门）。**连带**：`RecorderHealth.droppedQuota` 字段与 `health.kt` drop 求和中的 `counters.droppedQuota` 一并删除；`queue-test.kt` 中断言闸门行为的用例（如"outbox full gate closes admission and reopens…"的服务级用例在 producer-test.kt）随之删除或改为断言容量重写路径（新语义由 Task 5 的 `oversize file rewrites…` 覆盖）。KDoc（recorder.kt:82-85 的"空间闸门"段）删除。

producer.kt 改动清单：
- 删除 `Producer` 类（`writeProducerJson`/`writeRegistration`）、`PidEvidence`/`ProcessIdentity`/`LiveProcesses`、`REGISTRATION_SCHEMA_MAJOR`、`createdAt/pid/processStart` 字段。
- 保留：`randomId`、`RunMode`/`PlatformRunMode`、`SystemClock`、`DeviceIdStore`、`platformDeviceIdStore`、`WorkspaceIds`、`ProducerEnvironment`、`SCOPE_*`（Task 2）。

- [ ] **Step 4: 跑 shared 稳定性定向测试（应全绿）**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.*"
```
Expected: 全部 PASS（Task 5 遗留的编译断点此时全部闭合）。若有残留红用例，逐个修正后重跑。

- [ ] **Step 5: frontend/backend/cs-cloud 定向测试 + typecheck**

```bash
./gradlew :frontend:test --tests 'ai.kilocode.client.stability.*' --tests '*render-observation*' --tests 'ai.kilocode.client.KiloToolWindowFactoryTest'
./gradlew :backend:test --tests '*migration-observation*' --tests '*ide-observation*' --tests 'ai.kilocode.backend.app.KiloAppStateTest'
./gradlew :cs-cloud:test --tests '*connection-observation*' --tests 'ai.kilocode.cscloud.CscInstallerTest' --tests 'ai.kilocode.cscloud.CscCloudStarterTest' --tests 'ai.kilocode.cscloud.CscLoginTest'
./gradlew :shared:jvmMainKotlinCompile  # 或仓库既有的typecheck任务（见AGENTS.md），JAVA_HOME已设
```
Expected: 全绿（观测接入点未动，应无回归）。

- [ ] **Step 6: Commit**

```bash
git add -A packages/kilo-jetbrains
git commit -m "feat(jetbrains): assemble stability service on the append protocol layout"
```

---

### Task 12: DictionarySweepTest 与全字典落盘扫描迁移

**Files:**
- Test: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/dictionary-sweep-test.kt`（改造）

**Interfaces:**
- Consumes: Task 5 的 Fixture（`facts()` 读 jsonl）、Task 10 的 31 name 驱动面。
- Produces: 无（测试收口）。

- [ ] **Step 1: 改造 DictionarySweepTest**

三个用例语义保留（all registered names land on disk / logs-only policy / metrics-only policy），断言面从"flush 封存后只从 .ready 断言"改为"flush 后从追加文件断言"（Fixture.facts() 已切换，测试只需删除 .ready 专属断言、`assertSeqGapsAtPolicyBoundaries` 类辅助保留）。name 计数 30→31（edt.stall）。

- [ ] **Step 2: 跑测试确认通过**

```bash
./gradlew :shared:test --tests "ai.kilocode.stability.DictionarySweepTest"
```
Expected: 3 用例 PASS。

- [ ] **Step 3: Commit**

```bash
git add packages/kilo-jetbrains/shared/src/test
git commit -m "test(jetbrains): dictionary sweep asserts on the appended jsonl file"
```

---

### Task 13: 真实 IDE E2E 迁移

**Files:**
- Modify: `packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityE2eTest.kt`（场景重写）
- Modify: `packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityDictionaryE2eTest.kt`（31 name）
- Delete: `packages/kilo-jetbrains/src/integrationTest/go/lockprobe/`（整个目录）

**Interfaces:**
- Consumes: Task 11 的服务装配（E2E 经隔离 `user.home` 注入 telemetryHome，既有机制不变）、`selftest` 驱动面（Task 10）。
- Produces: 验收证据（落盘文件保留至 `out/stability-evidence/`），供 Task 14 文档引用。

- [ ] **Step 1: 重写 StabilityE2eTest 场景断言面**

四个场景的行为编排（控制文件操纵、双次启动、invokeAction 等）保留，断言面替换：

| 场景 | 新断言面（替换旧断言） |
|---|---|
| collection（冷启动有效许可） | `telemetry-home/outbox/` 下唯一 `sc-*-pr-*.jsonl`；行级线格式校验（25 字段闭集、UUID、seq 按通道连续、epoch/revision 与控制文件一致、LF/无BOM/无CR/32KiB）；**无** registrations 目录、无 producer.json、无任何锁文件/状态机后缀；优雅关闭后最后一条为 plugin.shutdown（app_close）；IDE 存活期间文件持续追加（字节单调增长） |
| policy（策略生命周期） | 无控制文件 → **照常采集**：事实落盘、epoch=unbound、policy_revision=0、purposes=[metrics,logs]；发布 revision=1 → 30 秒轮询内新事实换用真实 epoch/revision（旧 unbound 事实不改绑）；enabled=false（rev2）→ 65 秒预算内停采 + **本 producer 文件被清理**（outbox 目录无该文件）、run 无伪造 shutdown；rev3 重开 → 文件按原名重建、新 run_id、事实携带 rev3 |
| epoch（轮换） | 既有 epoch 轮换断言保留，断言载体从段文件改为单追加文件；`assertSeqGapsAtPolicyBoundaries` 缺口边界断言保留；rev3 pending 停采、rev5 退役回写 fail closed 语义不变 |
| residue（残留清理） | 改为 scope 清理场景：launch A 正常退出（文件保留）→ 手工栽种同 scope 过期文件（回拨 mtime 25h）+ 他 scope 过期文件 → launch B → 断言同 scope 过期文件被删、他 scope 保留、A 的文件（24h 内）保留。已知风险：本场景仍可能被试用反馈弹窗阻塞（环境级），阻塞时按既有处置记录并跳过，不阻塞合流 |

删除全部锁相关断言与 Go 探针装配代码（`go build`/探针执行块）；场景 1 追加一条"强杀 A（不调 stop）后 launch B 产出 plugin.unclean"断言（可并入 residue 场景的第二次启动：launch A 以进程 destroy 方式结束 → B 的文件首条业务事实为 plugin.unclean，previous_run_id=A 的 run_id）。

- [ ] **Step 2: StabilityDictionaryE2eTest 31 name**

断言 `30` → `31`（edt.stall 经 `Kilo.StabilitySelfTest` 驱动面产出）；samples.md 汇总含 edt.stall 样本；封存节奏断言删除，改为"critical/diagnostic 两通道行均在同一文件且 seq 各自连续"。

- [ ] **Step 3: 删除 Go 探针**

```bash
git rm -r packages/kilo-jetbrains/src/integrationTest/go/
```

- [ ] **Step 4: 跑 E2E（长跑，注意 JAVA_HOME 与弹窗风险）**

```bash
cd packages/kilo-jetbrains
export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"
./gradlew integrationTest --tests "ai.kilocode.jetbrains.StabilityDictionaryE2eTest" --tests "ai.kilocode.jetbrains.StabilityE2eTest"
```
Expected: Dictionary 1/1、collection/policy/epoch PASS；residue 若被 Feedback 弹窗阻塞（`JetBrainsFeedbackReporter`，ExecTimeout 10m），按证据文档 §9.4 处置：记录阻塞、保留证据树、其余场景绿即可进入 Task 14；条件允许时人工点一次 "No, Thanks" 后重跑 `--tests '*residue*'`。

- [ ] **Step 5: Commit**

```bash
git add -A packages/kilo-jetbrains
git commit -m "test(jetbrains): e2e coverage for the append protocol handover surface"
```

---

### Task 14: 定向回归、typecheck 与证据文档更新

**Files:**
- Modify: `docs/jetbrains-stability-e2e-evidence.md`（追加迁移轮记录）
- Modify: `docs/jetbrains-stability-acceptance.md`（如引用旧协议断言——逐行核对后更新）

**Interfaces:**
- Consumes: Task 1-13 全部交付。
- Produces: 回归证据（命令+结果）与文档一致性。

- [ ] **Step 1: 全部定向测试回归（§12"不默认跑全量"）**

```bash
cd packages/kilo-jetbrains
export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"
./gradlew :shared:test --tests "ai.kilocode.stability.*"
./gradlew :frontend:test --tests 'ai.kilocode.client.stability.*' --tests '*render-observation*' --tests 'ai.kilocode.client.KiloToolWindowFactoryTest'
./gradlew :backend:test --tests '*migration-observation*' --tests '*ide-observation*' --tests 'ai.kilocode.backend.app.KiloAppStateTest'
./gradlew :cs-cloud:test --tests '*connection-observation*' --tests 'ai.kilocode.cscloud.CscInstallerTest' --tests 'ai.kilocode.cscloud.CscCloudStarterTest' --tests 'ai.kilocode.cscloud.CscLoginTest'
```
Expected: 全绿。同时跑 JetBrains typecheck（按 AGENTS.md 的任务名；JAVA_HOME 已设）。

- [ ] **Step 2: 更新证据文档**

`docs/jetbrains-stability-e2e-evidence.md` 追加"§11 追加协议迁移轮（2026-09-XX）"：记录命令、各场景结果、residue 弹窗状态、落盘证据路径（`out/stability-evidence/` 新目录）、与旧轮证据的差异声明（旧轮 §1-§10 描述的分段/锁/登记机制已被本迁移取代，历史记录不回改、以本节为准）。`docs/jetbrains-stability-acceptance.md` 中引用旧机制（registrations/.ready/.claimed/锁）的行标注迁移后状态。

- [ ] **Step 3: Commit**

```bash
git add docs/jetbrains-stability-e2e-evidence.md docs/jetbrains-stability-acceptance.md
git commit -m "docs(jetbrains): record append-protocol migration evidence"
```

---

## 执行顺序与依赖

- Task 1（契约）、Task 2（scope-id）互相独立，可先行。
- Task 3（fail-open）独立于 4/5，但 Task 5 的 writer 测试依赖占位策略——推荐顺序 1→2→3→4→5→6→7→8→9→10→11→12→13→14。
- Task 4+5+6 必须连续执行（Storage API 变更打断 writer/retention 编译）；中间允许不可编译态，但**提交粒度**按任务切分时需保证每笔提交所在时点测试绿（Task 4 与 5 允许合并为一笔提交，注明即可）。
- Task 11 依赖 2/5/6/7；Task 13 依赖 11；Task 14 收口。

## 风险与已知问题

- **residue E2E 弹窗**：环境级 JetBrains 试用反馈调查弹窗（非插件问题），处置见 Task 13 Step 4；不阻塞合流。
- **Windows 文件删除/重开竞态**：Writer 每 tick 检查 `Files.exists` 后重开；Windows 上已打开句柄会阻止删除（清理方删的是非活跃文件，无冲突）。
- **撤销清理的删除时点**：`deactivateRun` 在 writer.close() 排空后删文件；若 IDE 在此窗口强杀，文件残留至 24h 清理——符合"不承诺物理删除期限"（§7.4）。
- **task 3 波及面**：fail-open 会翻转全部"无策略=DISABLED"的既有测试预期（writer/producer/service 层）；逐用例改为 unbound 预期，属预期内大改动。
- **JAVA_HOME**：所有 gradle/typecheck 命令必须先 `export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"`。
