# JetBrains 稳定性契约与交付 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 JetBrains 稳定性设计分解为可验证的插件交付，并以明确的契约门槛连接 cs-cloud 与指标／日志服务。

**Architecture:** 插件仅采集安全事实并写本机 outbox；cs-cloud 负责可靠接收、身份、双出口转换和发送。按采集基础、P0 用户旅程、P1 诊断拆分实施；跨仓库能力先冻结和联调，不能由插件伪造。

**Tech Stack:** Kotlin 2.3.20、Java 21、IntelliJ Platform 2026.1、kotlinx.serialization 1.11.0、平台 coroutines、JUnit、真实临时文件；外部消费者为 Go。

**Spec:** [jetbrains-stability-design.md](../../jetbrains-stability-design.md)、[jetbrains-plugin-stability-metrics.md](../../jetbrains-plugin-stability-metrics.md)。HTTP 契约为 [user-indicator-spec.md](../../user-indicator-spec.md) 和 [日志采集API.md](../../日志采集API.md)。设计仍是协议唯一维护位置，本计划不另立协议版本。

## Global Constraints

- “插件不实现云端认证、上报重试、流量控制或存储查询。”
- “单体IDE同一JVM内前后端共享一个采集器。”
- “v1自动接入仅针对双方确认的默认profile”。
- “UTF-8无BOM，NDJSON，每行一个JSON对象，以LF结束”。
- “普通记录最大32KiB，message安全摘要最大512字节”。
- “队列同时限制2000条及4MiB，以先达到者为准”；critical 分别预留 400 条和 20% 字节预算。
- “每producer未交接文件”：10MiB，保留期24小时；v1 无跨 producer 根目录强制配额。
- “统一锁顺序为writer.lock→exchange.lock”。
- “queued仅表示入内存，不保证落盘”；“EDT（事件分发线程）不写文件、不等待锁、不压缩、不联网”。
- “插件修改保持在JetBrains/Kilo自有模块，不需要修改共享OpenCode或Agent Core。”
- “执行受影响模块定向测试与JetBrains typecheck，不默认跑全量”。
- “正式用户功能交付再添加changeset”。

---

## 范围与执行入口

本次只编写计划，不执行实现、提交、部署或修改 cs-cloud。设计包含独立消费者和两个服务端出口，建议按下表拆分评审，避免把尚未冻结的云端接口硬编码进插件。cs-cloud 内部实现计划应在其仓库按实际源码单独编写；这里提供它必须交付的契约和可判定的验收条件，不替其选择数据库。

| 顺序 | 计划 | 可独立评审的交付 | 前置条件 |
|---|---|---|---|
| G0 | 本文 Task G0 | 契约冻结与机器可读测试向量 | 双方协议决策及服务端证据 |
| A | [采集基础](./2026-09-20-jetbrains-stability-collector.md) | 默认关闭的事实、许可、writer、清理、运行健康 | G0；纯本地模型测试可先行 |
| B | [P0 用户旅程](./2026-09-20-jetbrains-stability-p0.md) | 全部插件侧 P0 观测及最小可用链路 | A；身份绑定契约 |
| C | [P1 诊断](./2026-09-20-jetbrains-stability-p1.md) | 前置供给、协议、RPC、EDT、渲染及资源事实 | A；B 的操作边界 |
| G1 | 本文 Task G1 | 两链路端到端证据与灰度发布 | A、B、外部消费者；P1 灰度再要求 C |

三个子计划是交付边界，不是要求同时修改同一文件。A 顺序执行；B 和 C 都会修改 SessionController、AppService，默认串行。阶段1最小链路为 A + B1 中 M03 + 外部 M17；阶段2完成 B；阶段3执行 C 并积累两周基线。

平台 API 核验：本次环境无 `INTELLIJ_REPO`。已查公开源码的 `ApplicationActivationListener`（`platform/ide-core/...`）和 `ToolWindowManagerListener`（`platform/platform-api/...`）；只使用公开的 TOPIC、激活／失活、`stateChanged(ToolWindowManager)`，不使用标为 Internal 的三参数重载。实现时以目标 2026.1 对应源码再次确认，并执行 DevKit 的 Frontend and Backend API Usage 检查。休眠 API 未确认前不得声称可识别休眠，按 unknown 处理。

## 文件职责总图

新文件名使用小写连字符；保留现有 Kotlin 文件名称。类名仍遵循 Kotlin 类型习惯。所有路径均相对当前 worktree，禁止编辑另一 checkout。

| 所属 | 文件／目录 | 职责 |
|---|---|---|
| A | `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/` | 事实模型、许可、单 JVM recorder、writer、生命周期和安全诊断 |
| B | `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/` | 前端激活、可用性及用户操作观测 |
| B | 现有 backend app、两种 connection provider、session controller、settings 保存入口 | 在真实业务完成点调用观测器 |
| C | 同一 stability 目录中的 probe/resources 文件及自有服务边界 | 高频受控采样、资源计数、P1 边界 |
| G0 | `packages/kilo-jetbrains/shared/src/test/resources/stability/` | 跨语言 wire 样例、schema、锁／身份／输出 ID 向量 |
| G1 | `packages/kilo-jetbrains/script/stability-acceptance.ps1` | 启动真实 JVM／Go 测试入口并保存脱敏证据 |
| G1 | `docs/jetbrains-stability-acceptance.md` | 实际版本、能力覆盖、查询、性能及故障注入记录 |

## Task G0：冻结会影响数据归属和交接的契约

**Files:**
- Modify: `docs/jetbrains-stability-design.md`，仅将经验证的决策回填 5.2、7.2、8、9.1、10.1、13。
- Create: `packages/kilo-jetbrains/shared/src/test/resources/stability/contract.json`
- Create: `packages/kilo-jetbrains/shared/src/test/resources/stability/fact-schema.json`
- Create: `packages/kilo-jetbrains/shared/src/test/resources/stability/control-schema.json`
- Create: `packages/kilo-jetbrains/shared/src/test/resources/stability/output-vectors.json`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/contract-test.kt`
- Modify: `packages/kilo-jetbrains/shared/build.gradle.kts`（若A1纯本地工作尚未先行，在此加入已有版本目录中的serialization依赖）

**Interfaces:** 消费设计第5～11章。产出 wire schema、固定 UUIDv5 命名空间与 UTF-8 规范数组编码、锁范围、业务身份绑定证据和平台序列键决策。不是引入可直接调用的 cs-cloud API。

- [ ] **Step 1：把未成立的能力编码为默认拒绝的契约状态。** 以下是初始 gate 内容，不是最终 wire schema。外部证据完成前保持 false。

```json
{
  "schema_major": 1,
  "identity_verified": false,
  "metrics_authority_verified": false,
  "series_key_verified": false,
  "late_query_verified": false,
  "output_ids_verified": false,
  "cross_language_locks_verified": false,
  "durable_ack_verified": false,
  "logs_contract_verified": false,
  "default_profile_verified": false
}
```

- [ ] **Step 2：运行拒绝启用的契约测试。** 在 `ContractTest` 读取真实资源，验证任一 false 阻止启用。正式 release gate 必须要求全部 true；不能把此负向测试通过当作已冻结。

```kotlin
@Test fun `unverified contract cannot enable collection`() {
    val input = checkNotNull(javaClass.getResource("/stability/contract.json"))
    val json = Json.parseToJsonElement(input.readText()).jsonObject
    val flags = json.filterKeys { it.endsWith("_verified") }
    assertTrue(flags.isNotEmpty())
    assertFalse(flags.values.all { it.jsonPrimitive.boolean })
}
```

执行 `./gradlew.bat :shared:test --tests 'ai.kilocode.stability.ContractTest'`（工作目录`packages/kilo-jetbrains`）。先运行一次，确认缺少依赖／fixture导致失败；加入 `implementation(libs.kotlinx.serialization.json)` 和上述fixture后重跑。后续将测试改为逐项覆盖 false/true 输入矩阵，而真实 contract 必须由证据决定。schema 加 `additionalProperties:false`、枚举、长度／整数范围及必填约束；未知 minor 只允许协议明确列出的可选字段。

- [ ] **Step 3：冻结以下决策，每一项附可复现实验，不能以文档存在代替完成。**

| 决策 | 必须交付的内容 | 失败时边界 |
|---|---|---|
| 身份和业务连接 | 可信 issuer/universal_id/tenant；原子退役→pending→ready；TokenProvider 与业务请求同代际；插件如何确认响应代际 | 不依赖 JWT payload、`sub`、machineID 或 connectionEpoch；无法确认时停归属采集 |
| 许可 | 用户许可真实来源；metrics 权威配置来源和完整 URL；logs 发现许可；类别白名单和限频字段的准确 JSON 形状 | 默认禁采，不凭现有 capture 的 setEnabled(true) 放行 |
| profile | 默认 profile 唯一控制发布者；自定义 data-dir/auth-path 的识别方式 | 无显式绑定则显示不支持，不读默认账户策略 |
| 锁 | 建议 JVM FileChannel 字节范围 `[0,1)`；Go Unix fcntl／Windows LockFileEx 的匹配实现、共享打开模式和源消费者锁 | Windows/Linux/macOS 真实跨进程互斥未过不启用 |
| ACK | fsync／目录项持久性、消费去重／进度／输入快照／输出恢复的提交原子性；故障注入入口 | 同步失败不可 .done；不使用现有 workflow outbox 充当证明 |
| 输出 ID | 固定 namespace UUID；数组字符串 UTF-8、转义与规范编码；counter/histogram/log 三个 golden vector | 不临时生成 namespace，不直接复用 input ID |
| 指标序列 | 顶层字段／登记 label／平台专用序列机制三者实际选一；插件环境 plugin_env；Spec、labels、bucketBounds 全量登记 | 不把 run 仅塞进 metadata 后宣称正确 |
| 迟到与累计 | pending 查询、24小时宽限、历史批次归窗、同源乱序和已发快照不可变 | 未提供能力的看板明确标记不支持 |
| 两出口容量 | 共享输入总体预算、指标独立预算与重试期≤实际幂等窗口；日志50MiB／事件起24小时 | 32MiB/72小时仅候选，不能当已生效值 |
| IDE能力覆盖 | 当前存在open_diff/vfs_refresh/mcp_register；源码中没有插件自有apply_edit，MCP白名单也无编辑工具 | M23该子项标为未覆盖，禁止将配置文件创建当作应用编辑 |

- [ ] **Step 4：与外部真实实现跑最小正向样例并保存证据。** 用一个 start/end、安全异常及两个 producer；验证 A 3→5、B 8→9 得3，A 新 run 0→2 另得2；日志空200整批受理，指标部分成功逐项确认。公开联调材料不得包含 JWT 或原始用户信息。阶段0测试只写合成数据到临时目录；尚未部署服务时记录“未通过”，不生成伪成功证据。
- [ ] **Step 5：逐项通过后更新 contract、golden vectors 和设计，提交该独立交付。**

```powershell
git add docs/jetbrains-stability-design.md packages/kilo-jetbrains/shared/src/test/resources/stability packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/contract-test.kt
git commit -m "test(jetbrains): freeze stability handoff contracts"
```

外部仓库必须另行完成：daemon 在 agent 初始化前组装消费者、共享身份/TokenProvider、可靠接收事务、JetBrains metrics adapter、独立日志发现与 Sender、M17 自观测。前端无 Agent Core 的消费模式可单独交付，未交付时显示“前端未接入”。当前计划不授权对外发消息，也不要求为遥测启动额外 agent。

## Task G1：真实交接、双出口联调及灰度

**Files:**
- Create: `packages/kilo-jetbrains/script/stability-acceptance.ps1`
- Create: `docs/jetbrains-stability-acceptance.md`
- Create: `.changeset/jetbrains-stability.md`（正式用户交付时）

**Interfaces:** 消费 A 的真实 NDJSON、G0 固定契约和外部 JVM／Go 测试入口；产出按设计14章分类的 PASS/FAIL/UNSUPPORTED 证据，不在插件实现 Sender。

- [ ] **Step 1：建立故障矩阵并让缺证据的项目失败。** 报告逐项包含操作系统、JDK、插件／cs-cloud／服务端版本、命令、退出码、输入ID、预期、实际。每个实验隔离测试账户和临时目录。
- [ ] **Step 2：实现进程驱动器入口。** 外部 gate 提供 `Writer` 和 `Consumer` 可执行测试程序，协议：接受 `--scenario <name> --root <dir> --peer <executable>`；每个程序启动另一个语言的peer子进程，通过标准输出握手 `LOCKED`、`CLAIMED`、`SYNCED`、`ACKED`，在确认状态后触发中断或恢复，禁止 sleep 猜测进度。peer子进程用额外`--role peer`标志，禁止递归启动对方。PowerShell脚本只驱动完整跨语言场景，必须参数化而不硬编码兄弟仓库位置。

```powershell
param(
  [Parameter(Mandatory=$true)][string]$Writer,
  [Parameter(Mandatory=$true)][string]$Consumer,
  [Parameter(Mandatory=$true)][string]$Root
)
$ErrorActionPreference = 'Stop'
$dir = [IO.Path]::GetFullPath($Root)
if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
  New-Item -ItemType Directory -Path $dir | Out-Null
}
foreach ($scenario in @('lock-contention','dead-writer','pid-reuse','claim-race','sync-failure','replay-after-ack')) {
  & $Writer '--scenario' $scenario '--root' $dir '--peer' $Consumer
  if ($LASTEXITCODE -ne 0) { throw "writer failed: $scenario" }
  & $Consumer '--scenario' $scenario '--root' $dir '--peer' $Writer
  if ($LASTEXITCODE -ne 0) { throw "consumer failed: $scenario" }
}
```

上面循环负责逐场景启动；每个测试入口必须按协议自行启动对方角色的子进程，才能完成锁争用。顺序运行两个单语言测试不能验收锁。场景输出打印参与进程PID、拿锁／拒绝／死亡释放证据，两个进程均须在watchdog内退出且返回0；未提供这些入口时该任务不能标完成。

- [ ] **Step 3：运行设计14.1全部文件场景。** 覆盖半行、支持版本中的坏行、未知 major 整文件隔离、锁持有期间暂停超过30分钟、进程强杀、PID复用、多daemon、跨盘复制、ACK前后中断、同源累计恢复、配额与认领竞争、旧 producer 清理、symlink/reparse、POSIX／Windows权限。休眠及断电目录项持久性需真平台实验，临时文件单测不能替代。
- [ ] **Step 4：运行设计14.2～14.4全部出口场景。**

| 输入／故障 | 必须观察到的结果 |
|---|---|
| 100次发送95成功／3失败／2超时 | 技术成功率95%；再加入 blocked/cancelled/unknown，成功到达比例和完整率同步变化 |
| deadline前end晚交接、宽限后end、同源乱序 | 不误判 timeout、不回减累计；最终 unknown 只结算一次；历史窗口正确 |
| 指标200部分成功／重复ID／413／stale_event | 逐项确认、稳定重试、不二次派生；超时效永久拒绝 |
| 日志300分钟发现缓存／空200／非法后行 | 缓存5小时；整批受理；校验失败不提前确认前行 |
| 日志响应丢失／401／403／429／503 | 稳定ID和内容，允许重复日志；权限暂停不绕过，Retry-After和冷却生效 |
| A→pending→B、外部凭据替换、同账户重登 | A永久退役；插件旧策略滞后30秒也不误归B；清理引用时保留退役判定 |
| 两用途逐一关闭／过期／满额 | 另一出口继续；不追溯扩大 purposes；公共撤销停采停发并清理 |
| dev/test、历史插件版本、远程前端无consumer | 环境不混prod、原版本和时间保留；前端明确未接入且不写上传事实 |

- [ ] **Step 5：测量成本和时效并运行最小相关检查。** 入队100,000次，预热后记录 P50/P95/P99、最大值、分配、GC和线程；普通及风暴都要求 P99<1ms。逐事件记录产生→封存→发现→spool→接口受理→查询可见。critical首次发送约40秒、diagnostic约310秒仅调度预算，不能替代实际P0告警SLO。验证2k条/4MiB、10MiB/24小时、双出口独立预算及多producer根目录增长。

```powershell
# 工作目录 packages/kilo-jetbrains；Windows 使用 gradlew.bat，Unix 使用 ./gradlew
./gradlew.bat typecheck
./gradlew.bat :shared:detekt :frontend:detekt :backend:detekt :cs-cloud:detekt
./gradlew.bat :shared:test --tests 'ai.kilocode.stability.*'
```

再运行各子计划列出的受影响现有测试及新增测试；构建／服务注册发生变化时运行 `./gradlew.bat buildPlugin`。不跑根目录 bun test；不要把文档检查报告成实现测试通过。
- [ ] **Step 6：灰度两周并写正式 changeset 后提交。** 先单体、再双机器；P0按真实样本校准阈值，P1单独观察覆盖和资源回落。退回只需关闭有效策略并按保留规则清理，不回滚已接受事实，不篡改旧版本数据。changeset 内容：

```markdown
---
"@kilocode/kilo-jetbrains": minor
---

支持按授权采集插件稳定性指标与安全诊断日志，帮助定位打开、连接和会话操作问题。
```

该包当前为 private，实施时先确认 JetBrains 发布流程消费 changeset 的方式；不能为省事把发布说明登记到 `kilo-code`（VS Code 包）。按既有 JetBrains release 流程同步其实际 changelog。

```powershell
git add packages/kilo-jetbrains/script/stability-acceptance.ps1 docs/jetbrains-stability-acceptance.md .changeset/jetbrains-stability.md
git commit -m "feat(jetbrains): validate stability collection rollout"
```

## 覆盖自审

| 设计要求 | 对应任务 |
|---|---|
| 路径、格式、队列、文件生命周期、权限和清理（5～7） | A1～A5，G0，G1 |
| 两用途许可与账户归属（8） | G0，A2，A5，B1 |
| 全事件字典／输出身份（9） | A1、A6、B1～B5、C1～C5；派生身份由G0/G1外部验收 |
| 累计、迟到、Spec与指标HTTP（10） | G0/G1外部交付门槛；插件不承担转换和Sender |
| 安全诊断、限频与日志HTTP（11） | A6；外部发现／九字段／确认由G0/G1验收 |
| 11组P0 | B1:M01/M02/M03；B2:M04/M05；B3:M11；B4:M12；B5:M13；A6:M14/M16；G1:M17 |
| 13组P1 | C1:M06～M10；C2:M15/M18/M19；C3:M20；C4:M21；A5:M22；C5:M23/M24 |
| 远程、独立消费、profile、故障注入（12～14） | G0/G1；A5/B5展示能力缺口 |

完成标准区分：计划已写入、插件实现通过定向检查、端到端验收通过、两周灰度完成是四个独立状态。只有最后两项有实际证据，才能宣称完整稳定性链路已上线。
