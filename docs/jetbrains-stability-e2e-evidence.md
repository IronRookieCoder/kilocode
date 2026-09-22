# JetBrains 插件稳定性采集端到端证据（真实 IDE，G1 补充）

- 记录日期：2026-09-21（首两场景）～2026-09-22（§9 查漏补缺轮、§10 全字典真实 IDE 落盘轮）；分支：`feat/jetbrains-stability`（工作区 HEAD `018b403e50` + 本次新增测试代码）。
- 测试载体：新增 Starter/Driver 集成测试 [StabilityE2eTest.kt](../packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityE2eTest.kt)（2 个场景，各 1 次真实 IDE 启动）与 Go 锁探针 [lockprobe/main.go](../packages/kilo-jetbrains/src/integrationTest/go/lockprobe/main.go)。
- 与 [jetbrains-stability-acceptance.md](./jetbrains-stability-acceptance.md) 的关系：该记录的"未通过"行中有 6 行当时缺失的是**真实 IDE 内运行、跨进程、跨语言**证据（第 3 节表格中标"本地证据"为同 JVM 单测的行）。本文补齐这部分；cs-cloud 消费端/服务端仍未部署，外部依赖清单（该文第 10 节）不变。
- 环境：Windows 10 Pro 19045；OpenJDK 21.0.12.1；Gradle 9.4.1；IU-2026.1（IU-261.22158.277，本地缓存 IDE）；插件 `1.0.0-rc.1`（buildPlugin 产物 zip 安装进沙箱）；Go 1.26.4；沙箱 IDE `user.home` 为隔离临时目录（真实 `~/.costrict` 不受影响）。

## 1. 运行命令与结果

```text
工作目录 packages/kilo-jetbrains，JAVA_HOME=~/.jdks/ms-21.0.12.1
./gradlew integrationTest --tests "ai.kilocode.jetbrains.StabilityE2eTest"   # BUILD SUCCESSFUL in 7m 51s
```

JUnit XML（`build/test-results/integrationTest/TEST-ai.kilocode.jetbrains.StabilityE2eTest.xml`）：**tests=2 failures=0**，总耗时 433.8s。

| 场景 | 耗时 | 结果 |
|---|---|---|
| valid permit from cold start collects seals and survives a consumer claim | 182.9s | PASS |
| policy lifecycle gates collection fail-closed without faking shutdown | 250.5s | PASS |

## 2. 场景 1：冷启动有效许可 → 采集、封存、消费交接

控制文件在 IDE 启动前发布（`<隔离home>/.costrict/telemetry/control/jetbrains.json`，revision=1，enabled，双用途 critical+diagnostic）。断言全部通过：

- **登记与布局（§5.2）**：`registrations/pr-*.json` 字段恰为闭集 6 项；`outbox_path` 与真实 IDE 日志目录 `<logsDir>/costrict-telemetry/v1/<producer-id>` 完全一致；`producer.json` 12 字段闭集，`IU-261.22158.277/2026.1/windows/x64/monolith/monolith/env=prod/plugin_version=1.0.0-rc.1`；`writer.lock`、`exchange.lock` 在位。
- **跨进程锁（§7.2/§14.1）**：IDE 存活期间，另一个 JVM 进程 `tryLock(0,1,false)` 被拒；**Go 探针（LockFileEx 独占 [0,1)）同样被拒**——`HELD_BY_PEER`，JVM↔Go 互操作锁互斥成立。IDE 退出后 Java 与 Go 探针均可获取（`ACQUIRED`）——进程死亡释放锁（§7.3）。这补上了验收记录中"不能用两套各自通过的锁单测替代"的一行（Windows/NTFS 分支；Linux/macOS 仍缺）。
- **封存（§7.1）**：首个 critical 段在 16 条记录处封存（实测封存延迟 ≈ 最后一条记录后 120ms）；全部段 UTF-8 无 BOM、LF 结尾、无 CR。
- **消费交接（§7.2）**：测试进程在 `exchange.lock` 内把最旧 `.ready` 原子改名为 `.claimed` 后：插件继续产出新事实（82 > 16），`.claimed` 内容全程逐字节不变，IDE 关闭后仍存在——插件永不触碰 `.claimed`。
- **优雅关闭（§7.1 收尾排空）**：关闭后无任何 `.open` 残留；恰 1 条 `plugin.started`、1 条 `plugin.shutdown`（`end_kind=app_close`，时间为全部 82 条中最新）。
- **NDJSON 线格式（§6.1）**：82 条记录逐条通过独立副本契约校验——25 个公共字段闭集、`schema_version=1.0`、event_id UUID 且全局唯一、timestamp 落在测试窗口、channel 与目录一致、seq 跨 6 个段文件（含 `.claimed`）按 critical 通道 1..82 **连续无缺口**、`account_epoch`/`policy_revision` 与控制文件一致、purposes 非空子集、source/device_id 全程稳定、kind/name/context 键全部在登记闭集内。
- **事件分布（真实 IDE 一次会话）**：rpc×25、edt.delay×36、connection.attempt×4、connection×2、toolwindow.setup×2、plugin.readiness×2、availability×2、backend.load×2、resource.snapshot×3、telemetry.health×2、plugin.started×1、plugin.shutdown×1。

语义样例（真实终态，非伪造）：

```json
{"schema_version":"1.0","event_id":"524904a3-5d7a-4d5f-80b3-49211197d359","timestamp":1789986389197,"producer_id":"pr-315ff953b957","run_id":"run-0535bec98321","channel":"critical","seq":40,"account_epoch":"acct-e2e-01","policy_revision":1,"purposes":["metrics","logs"],"source":"jetbrains-plugin","device_id":"device-395b141f586b","plugin_version":"1.0.0-rc.1","ide_product":"IU","ide_build":"IU-261.22158.277","ide_build_major":"2026.1","os_family":"windows","arch":"x64","env":"prod","mode":"monolith","side":"monolith","connection_provider":"unknown","kind":"operation","name":"plugin.readiness","context":{"operation_id":"0533fe56-8f03-46f6-9124-33a284e600b4"},"data":{"phase":"end","result":"blocked","duration_ms":2387,"stage":"readiness","cause":"environment","error_code":"none","reason":"credentials_missing"}}
```

`plugin.readiness` 终态 `blocked/credentials_missing`（沙箱 mock daemon 无凭据）与 `toolwindow.setup` 终态 `success/2087ms`：M01/M03 的"唯一终态 + 自包含"口径在真实 IDE 成立。

## 3. 场景 2：策略生命周期（fail-closed → 激活 → 撤销 → 重开）

单次 IDE 启动内操纵控制文件（同一 `account_epoch`，revision 1→2→3）：

| 阶段 | 实测 | 断言 |
|---|---|---|
| 无控制文件（启动后 5s） | 无任何登记 | PASS（fail closed，§8 首次无策略） |
| 发布 revision=1 | **21.2s** 后登记出现（含 30s 轮询预算内） | PASS |
| 撤销 enabled=false（revision=2） | `.open` 全部封存（writer 关闭收尾）；静默 12s 无新文件/无 mtime 变化；run A 无 `plugin.shutdown`（撤销不伪造退出，§8） | PASS |
| 重发 enabled=true（revision=3） | 新 `run_id`（run-a816424783d6 → run-a8671526ebbe），run B 有 started + 关闭时 `plugin.shutdown`（app_close） | PASS |
| run A 停采时限 | 最后一条 run A 事实在撤销后 65s 预算内 | PASS |

52 条事实全部通过同一套线格式校验（run A 全部 revision=1、run B 全部 revision=3；两 run 共享 producer/device_id/单一登记文件）。事件分布：edt.delay×43、plugin.started×2、availability×2、resource.snapshot×3、telemetry.health×1、plugin.shutdown×1。

## 4. 相对验收记录的证据增量（仍为插件范围）

| 验收记录行 | 本次新增证据 |
|---|---|
| 14.1 正常封存、认领、复制、ACK | 真实 IDE 内封存/`.claimed` 交接/继续采集（daemon 侧复制→持久→ACK 仍属外部） |
| 14.1 多 IDE、PID 复用、多个 daemon | 单实例登记/pid/process_start 落盘（跨进程多 IDE 仍外部） |
| 14.1 JVM writer 与 Go consumer 同时持锁/救援 | **Windows 实测 JVM↔Go LockFileEx/FileChannel [0,1) 互斥 + 进程死亡释放**（探针以 cs-cloud 同款 CGO=0 约束实现） |
| 14.1 版本升级、账户切换 | 真实 IDE 内撤销/重开/revision 切换（epoch 退役 e2e 仍外部） |
| 14.2 同设备不同进程及 run 重置 | 真实 run 轮换：新 run_id、seq 重起、单登记 |
| 验收第 2 节命令清单 | 新增 `integrationTest --tests ai.kilocode.jetbrains.StabilityE2eTest`（BUILD SUCCESSFUL，2/2） |

外部依赖清单（验收记录第 10 节）**无变化**：cs-cloud 消费器、指标/日志 Sender 与服务端、G0 flag 翻转、跨平台矩阵（Linux/macOS）、真休眠/断电实验等仍待外部交付。

## 5. 备注与坑

- **`go run` 退出码陷阱**：`go run` 把子进程退出码 2 包装为自身退出码 1 并向 stderr 打印 `exit status 2`。探针必须先 `go build` 成 exe 再执行（测试内已如此实现），否则退出码不可判。首轮运行即因该问题误报失败，产品链路本身首轮已全绿（56 条事实、seq 1..56 连续）。
- `connection_provider=unknown`：沙箱中 `noteConnectionProvider` 在 run 快照固定前未被调用（cs-cloud 连接由 mock daemon 服务，非真实 csc 启动路径），符合"best effort，run 快照固定后不生效"的实现语义。
- 两次运行均观察到 `availability` 区间按 `error → connecting` 演进、`backend.load` 成功——事件语义与 mock daemon 冷启动旅程一致。
- 测试断言 timestamp 窗口为 ±10s 机器内时钟余量；未跨机，不构成对时钟偏移的验证。
- 探针与测试对 `writer.lock` 的探测在 Windows（LockFileEx）与任意平台（FileChannel）分别有效；无 Go 工具链时 Go 探针自动跳过（Java 跨进程探针仍执行），CI 无 Go 不致红。

## 6. 全字典落盘扫描（所有类型的指标与日志事实）

真实 IDE E2E 只能自然覆盖会话中出现的事件名（两场景合计 15 种）。为满足"插件端所有类型"的口径，新增 [dictionary-sweep-test.kt](../packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/dictionary-sweep-test.kt)：真实 Recorder→Writer→临时目录管线，对**全部 30 个登记 name** 各产出至少一条字典合法事实，flush 封存后只从 `.ready` 断言。三个用例全部通过（2026-09-21）：

| 用例 | 覆盖 |
|---|---|
| all registered names land on disk | 30 name 全落盘；kind/channel/purposes 与 Dictionary 投影逐条一致；7 种 kind 齐备；seq 按通道从 1 连续；operation 三段配对（progress 的 stage 受 name 级词表约束——rpc 无 stage 键其 progress 被字典拒绝即活证明）；error 计数形态（critical/**metrics**）与详情形态（**diagnostic 通道**/**logs**）分道落盘，data 键闭集核对，原始异常 message 不入事实 |
| logs-only policy | metrics-only name（rpc）与 error 计数不落盘；dual name 落盘仅 `["logs"]`；error 详情照常落盘 |
| metrics-only policy | diagnostic 通道整通道为空；dual name 仅 `["metrics"]`；error 计数照常落盘 |

`plugin.unclean` 无插件发射点（设计 §7.3 属消费端判定），扫描经 Draft 验证其登记形状可落盘；`ide.operation=apply_edit` 仍为 UNSUPPORTED（用 `vfs_refresh` 代表真实业务值）。

## 7. 插件端相关模块定向测试汇总（2026-09-21 实测）

| 命令（`JAVA_HOME=~/.jdks/ms-21.0.12.1`） | 结果 |
|---|---|
| `:shared:test --tests 'ai.kilocode.stability.*'` | **172 tests / 0 failures**（含新增 DictionarySweepTest 3 条；14 个测试类） |
| `:frontend:test --tests 'ai.kilocode.client.stability.*' --tests '*render-observation*' --tests 'ai.kilocode.client.KiloToolWindowFactoryTest'` | BUILD SUCCESSFUL |
| `:backend:test --tests '*migration-observation*' --tests '*ide-observation*' --tests 'ai.kilocode.backend.app.KiloAppStateTest'` | BUILD SUCCESSFUL |
| `:cs-cloud:test --tests '*connection-observation*' --tests 'ai.kilocode.cscloud.{CscInstallerTest,CscCloudStarterTest,CscLoginTest}'` | BUILD SUCCESSFUL |
| `integrationTest --tests 'ai.kilocode.jetbrains.StabilityE2eTest'`（第 1 节） | 2/2 PASS |

## 8. 复现

```bash
cd packages/kilo-jetbrains
export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"
./gradlew :shared:test --tests "ai.kilocode.stability.DictionarySweepTest"   # 全字典落盘扫描
./gradlew integrationTest --tests "ai.kilocode.jetbrains.StabilityE2eTest"  # 真实 IDE E2E
# 证据残留（断言后保留）：out/ide-tests/tests/IU-locally-installed-ide/stabilityE2e{Collection,Policy}/
```

## 9. 查漏补缺轮（2026-09-21 晚，对照设计文档第 2/6/7/8/14 章插件侧要求）

对既有 14 个单测类 + 2 个 E2E 场景做覆盖盘点后的补齐。本轮**新增 1 处实现修复 + 2 条单测 + 2 个真实 IDE E2E 场景**；证据统一保留在 `packages/kilo-jetbrains/out/stability-evidence/`（落盘文件供人工检查）。

### 9.1 实现修复：§8.1 旧 epoch 排队事实的写入闸守卫

`PolicyStore.retiredEpochs` 原注释声明"供 producer 清空旧 epoch 的排队事实"，但**无任何消费方**；writer 入盘前重判期只重判 purposes，不判 epoch——排队中的旧 epoch 事实会在轮询窗口内被改绑语义落盘（落的是旧 epoch 字段，但许可已属于新 epoch 代际）。按设计 §8.1"插件观察到账户变化先暂停…清空尚未写出的旧 epoch 事实"补齐：

- `writer.kt` `encodeLine`：入盘前先对照 `policies.retiredEpochs`，命中即丢弃并计入 `droppedPolicy`（§8.1）；轮询窗口内已落盘的旧 epoch 记录仍由 consumer 按退役集合丢弃（设计归属不变）。
- 单测：`WriterTest."queued facts of a retired epoch are dropped at the write gate"`——排队→`rotateEpochControl("acct-b")`→flush：旧 epoch 事实不落盘、`droppedPolicy=1`；换代后新事实携带 `acct-b`/revision 14 落盘。

### 9.2 单测补齐：outbox_full 状态转换直接断言（验收记录第 135 行自标注缺口）

`ProducerTest."outbox full gate closes admission and reopens after the age seal and eviction"`：2KiB 校准预算 + 200ms sweep 周期注入服务级 Harness——.open 超预算且无 .ready 可淘汰 → `outbox_full` 状态发布 → storage 闸门 DROPPED；时钟推过 critical 30s 封存线 → 下一轮 sweep 淘汰 → 状态回 `ok` → 记录重新 QUEUED。覆盖服务层"预算→状态→闸门→恢复"全链（此前仅 QueueTest 的闸门单元与状态机间接覆盖）。

配套注入：`StabilityService.create` 增加 `retentionIntervalMs`/`retentionMaxBytes` 测试参数（默认值不变：1 小时/10MiB，生产路径零影响）；Fixture 增加 `rotateEpochControl`。

### 9.3 E2E 场景 3（epoch 轮换）——PASS

`StabilityE2eTest."epoch rotation retires the old account without rebinding or faking shutdown"`（§8.1/§14.1 行 9 插件半边；daemon 侧代际协调仍属外部）。单次 IDE 会话内操纵控制文件 revision 1→5：

| 阶段 | 实测 | 断言 |
|---|---|---|
| rev1 epoch-01 → 采集 | run A 建立 | started×1 |
| rev2 直接换 epoch-02（不经 pending，30s 轮询观察不到过渡） | **run A 存续**，同 run_id 下 epoch-02 事实继续落盘 | epoch-01 事实在换出后 20s 静默（事件 id 集合不变）；run A 内 epoch-01 最后一条早于 epoch-02 首条（无改绑交错） |
| rev3 account_state=pending | run A 结束 | 无伪造 plugin.shutdown；65s 预算内停采 |
| rev4 epoch-03 ready | 新 run B | started×1，全部事实 epoch-03/rev4 |
| rev5 回写已退役 epoch-01 | fail closed | run B 无 shutdown、65s 预算内停采；全库无 rev3/rev5 事实 |

线格式校验放行 **seq 缺口并新增缺口边界断言**（`assertSeqGapsAtPolicyBoundaries`）：每个缺口必须落在一次策略写入的窗口内（§6.1"可用缺口辅助发现丢失"——写入闸丢弃消耗 seq 是设计行为，稳态丢号才是不变式违反）。本轮实测 68 条 critical 事实 seq 1..68 连续（换代窗口恰无排队事实），边界断言机制在该场景持续生效。

落盘证据（人工检查）：`out/stability-evidence/epoch/telemetry-home/`（控制文件+登记）；outbox 保留在 `out/ide-tests/tests/IU-locally-installed-ide/stabilityE2eEpoch/log/costrict-telemetry/v1/pr-9d89c6e8fcf1/`（run A 跨 epoch 5+12 条、run B 1 条，全部 .ready）。

### 9.4 E2E 场景 4（死亡 producer 残留清理）——已实现，当前被环境级弹窗阻塞

`StabilityE2eTest."dead producer residue is swept by a later instance even while collection is off"`（§7.4/§14.1 行 4+10）。两次启动：A 正常采集退出；B 无控制文件启动（fail-closed），其启动 sweep 面对 A 的残留。栽种矩阵：A 的真实 outbox（最旧段回拨 25h、保留新段、外加新 .open 与 .claimed 副本）+ 全过期合成 producer `pr-deadresidue01`（登记/producer.json 均复用 A 的真实已死 pid/process_start 作所有权证据）。

**设计已验证到弹窗为止**：观察线程按"本次进程 kilo.log"触发栽种成功（`planted=true`），栽种树在 launch B 运行期间完整存续（无 change 事件 = §9.5 坑 2 的清空只发生在 launch 准备期，机制理解正确），断言链就绪。

**阻塞点（2026-09-21 22:00 起）**：JetBrains 试用反馈调查弹窗（EvaluationFeedback，窗口标题恰为 "Feedback"）开始在沙箱会话开始约 3 分钟时弹出。它是 EDT 模态框：驱动 UI 调用会死锁其后方，WM_CLOSE 关闭后会再次弹出，合成鼠标点击被 Swing 忽略——三种处置均告失败，Starter 以 `ExecTimeoutException: due to a dialog being shown` 判 10 分钟超时。该弹窗与插件代码无关（idea.log 无对应堆栈；早于弹窗出现的会话不受影响——epoch 场景当晚早些时候已通过）。外部 PowerShell 守护（EnumWindows 定位标题=Feedback 的 SunAwtDialog）能定位窗口但点击不生效。

**解除路径（任选其一后重跑 `integrationTest --tests '*residue*'` 即可）**：
1. 人工方式：任意一次沙箱 IDE 弹出该调查时手动点一次 "No, Thanks"（机器级记忆后永久消失）；
2. 等待调查触发期（通常数天一次的概率/冷却窗口）过去；
3. CI 或其他机器上运行（评估状态独立）。
首次应答的落盘证据已保留：`out/stability-evidence/`（residue 场景的沙箱树在最近一次运行后完整存活，可直接人工核对栽种矩阵）。

### 9.5 Starter 沙箱机制坑（影响测试设计，记录备查）

1. **`idea.log.path` 无法 pin 共享日志根**：Starter 在用户 vmOptions 之后追加自己的 `-Didea.log.path=<sandbox>/log`，后者生效（residueB 沙箱 idea.log 的 JVM options 行为证）。
2. **Starter 每次启动清空既有沙箱目录**：同名重跑只保留最后一次文件（stabilityE2eEpoch 沙箱两次运行仅存第二次产物为证）。任何在 launch 准备完成前落进目标沙箱的文件都会被清掉。
3. **沙箱 IDE 直接运行在 `out/ide-tests/cache/builds/`**：异常终止的沙箱 IDE 进程会锁住该目录下的 jar，下一轮 Starter 拷贝即报"文件正在使用"。另：测试 JVM 的 cwd 是 `packages/kilo-jetbrains`，而 Starter 沙箱根在 **git 仓库根**的 `out/ide-tests/`（场景 4 由 launch A 的真实 outbox 路径上溯反推沙箱位置，不做根目录猜测）。
4. **场景 4 栽种时序（最终形态）**：启动前先删除陈旧沙箱 B（遗留的 kilo.log 会触发误栽种——曾致三轮失败）；观察线程等待 launch B **本次进程**的 `kilo.log`（mtime > 启动时刻）出现后 300ms 栽种；主线程等 planted 标志后再开工具窗口（首次注入 StabilityService → 启动 sweep），时序确定为：launch 清空 → 栽种 → 开窗 → sweep。栽种后 120s 监视打印树变化，作为"无清除者"的证据。

### 9.6 汇总测试命令（2026-09-21 晚实测）

| 命令（`JAVA_HOME=~/.jdks/ms-21.0.12.1`） | 结果 |
|---|---|
| `:shared:test --tests 'ai.kilocode.stability.*'` | **174 tests / 0 failures**（14 类，含 2 条新增） |
| `integrationTest --tests '*epoch*' --tests '*residue*'` | epoch PASS；residue 见 §9.4 |

### 9.7 待确认问题（不改语义，上报裁决）

- 设计 §8 表格"用户撤销授权/总enabled=false → 插件**清理待交接数据**"与现有实现（撤销=关闸+密封已有段，文件保留至保留期/daemon 交接）存在解读分歧；验收记录第 105 行已按"停采+禁采期不落盘"判 PASS。若按字面执行清理，需 cs-cloud 侧确认撤销后不再认领旧文件，否则会互相竞争。建议在阶段 0 契约冻结时澄清。

## 10. 全字典真实 IDE 落盘轮（2026-09-22，"所有类型的指标和日志"验收）

§6 的全字典扫描是单元级（临时目录）；真实 IDE E2E 自然会话只覆盖约 15 个 name。本轮补齐最后一层：**全部 30 个登记 name 在真实 IDE 内经真实采集管线落盘，落盘文件保留供人工检查**。

### 10.1 载体

- 驱动面：[selftest.kt](../packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/selftest.kt) `emitDictionarySweep()`——除 `plugin.started`/`plugin.shutdown`（服务级每 run 恰一条，自检不得重复）与 `telemetry.health`（run 内累计快照，伪造会污染 M16 相邻差值推导）外，27 个 name 全部经真实 `Recorder`/`Operations`/`Faults`/`Resources` 入口产出字典合法事实；自检事实统一携带 `context.workspace_id=ws-selftest` 标记（error 族经显式 fault_id 辨识），盘上可与自然事实区分。
- 触发入口：隐藏动作 `Kilo.StabilitySelfTest`（frontend 注册，不挂任何菜单组；默认 no-op，仅 `-Dcostrict.stability.selftest=true` 时执行——生产 IDE 不产生业务事实，不污染指标分母；不走旧 Telemetry capture 链路）。actionPerformed 在 EDT 执行，全程非阻塞入口，无文件 IO。
- 测试：[StabilityDictionaryE2eTest](../packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityDictionaryE2eTest.kt)（真实 Starter IDE，182.2s，PASS）+ 单元级 [selftest-test.kt](../packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/selftest-test.kt)（先证明驱动内容本身正确）。

### 10.2 断言与实测（全部通过）

双用途有效控制文件（revision=1，critical+diagnostic）→ 冷启动 → `invokeAction("Kilo.StabilitySelfTest")` → 优雅关闭排空后从 `.ready`/`.claimed` 还原：

- **30 个登记 name 全部落盘**（27 自检 + started/shutdown/health 自然产出），112 条事实；
- 每条事实 kind/channel/purposes 与事件字典投影一致（含 error 族计数 critical/**metrics** 与详情 **diagnostic**/**logs** 分道，同一 fault_id 关联）；
- 7 种 kind 齐备；critical 与 diagnostic 两个通道目录均有封存文件；metrics-only / logs-only / dual 三类用途投影齐备；
- §6.1 线格式独立副本校验通过：25 字段闭集、event_id UUID 全局唯一、seq 按通道连续无缺口（自检与自然事件交错）、epoch/revision 与控制文件一致、无 BOM/CR、LF 结尾、32KiB 内；
- 恰 1 条 `plugin.started`、1 条 `plugin.shutdown`（app_close）；关闭后无 `.open` 残留（diagnostic 通道含 error 详情也经收尾排空封存——E2E 首次覆盖该路径）；
- 封存节奏：critical 段以 16 条批量阈值封存（7 段：16×6+14），diagnostic 段 2 条由关闭排空封存；
- producer：`pr-99eed8c277dd` / `run-1642c423f8ea` / IU-261.22158.277 / 1.0.0-rc.1 / windows / x64 / monolith。

事件分布（自检 27 name 的独占/重叠混合自然流）：action×2、availability×3、backend.load×4、cli.download×2、connection×5、connection.attempt×7、connection.recovery×2、connection.state_changed×1、credentials.ready×2、csc.install×2、csc.start×2、edt.delay×19、edt.violation×1、error.reported×2、error.uncaught×2、ide.operation×2、migration.required×1、plugin.readiness×4、plugin.shutdown×1、plugin.started×1、plugin.unclean×1、protocol.error×1、render.apply×1、resource.snapshot×6、rpc×28、session.dispose_risk×1、session.open×2、session.restore×2、telemetry.health×1、toolwindow.setup×4。

### 10.3 落盘文件保留（人工检查入口）

`packages/kilo-jetbrains/out/stability-evidence/dictionary/`：

| 内容 | 路径 |
|---|---|
| outbox 全树（7 critical 段 + 1 diagnostic 段 + producer.json + 双锁文件） | `outbox/` |
| 登记文件 + 控制文件 | `telemetry-home/` |
| 每个 name 一条完整 NDJSON 样本（自检事实优先） | `samples.md` |

沙箱原始位置 `out/ide-tests/tests/IU-locally-installed-ide/stabilityE2eDictionary/log/costrict-telemetry/v1/pr-99eed8c277dd/`（同名重跑会被 Starter 清空，evidence 目录为稳定保留点）。

### 10.4 本轮测试汇总（2026-09-22 实测，`JAVA_HOME=~/.jdks/ms-21.0.12.1`）

| 命令 | 结果 |
|---|---|
| `:shared:test --tests 'ai.kilocode.stability.*'` | **175 tests / 0 failures**（新增 SelfTestTest 1 条） |
| `:frontend:test`（stability/render-observation/ToolWindowFactory 定向） | BUILD SUCCESSFUL |
| `:backend:test` / `:cs-cloud:test`（稳定性定向，同第 7 节命令） | BUILD SUCCESSFUL |
| `integrationTest --tests '...StabilityDictionaryE2eTest' --tests '...StabilityE2eTest'` | Dictionary 1/1 PASS（182s）；collection 245s / policy 332s / epoch 468s PASS；**residue 仍被试用反馈弹窗阻塞**（§9.4 同一环境级问题，堆栈 `JetBrainsFeedbackReporter.showFeedbackForm`，1043s 超时） |

### 10.5 residue 场景 2026-09-22 追加复测（3 次）

| 尝试 | 结果 |
|---|---|
| 全套跑（10.4 行） | ResidueB 被 Feedback 弹窗阻塞（ExecTimeout 10m） |
| 单独重试 1 | launch A 98s 即中断：IDE 退出期平台噪声 `Can't write state '…JVM DTrace based profiler'` 被基座 CIServer 钩子升级为断言失败——已加入 IntegrationTestBase 豁免清单（与传输拆除噪声同类的平台退出期产物，非插件错误） |
| 单独重试 2（豁免后） | launch A 完整通过（采集/栽种/清理全部执行），**ResidueB 再次被同一弹窗阻塞**（10m 超时） |

观察：今日 6 个其余场景（dict/collection/policy/epoch/两轮 ResidueA）从不弹窗，唯 ResidueB 会话 3/3 稳定触发——其驱动块有长达数分钟的空闲等待（等栽种标志最长 12 分钟），疑似空闲态触发试用调查调度。解除路径不变（§9.4）：人工点一次 "No, Thanks" 或换机器/CI。

### 10.6 复现

```bash
cd packages/kilo-jetbrains
export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"
./gradlew :shared:test --tests "ai.kilocode.stability.SelfTestTest"   # 驱动面单元级
./gradlew integrationTest --tests "ai.kilocode.jetbrains.StabilityDictionaryE2eTest"  # 全字典真实 IDE
# 人工检查：out/stability-evidence/dictionary/（outbox + telemetry-home + samples.md）
```
