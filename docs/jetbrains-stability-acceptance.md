# JetBrains 插件稳定性采集验收记录（G1-local）

- 记录日期：2026-09-21；分支：`feat/jetbrains-stability`（HEAD `6d5da88905`）。
- 记录性质：Task G1 的**本地可交付部分**。跨语言端到端矩阵、部署服务场景与两周灰度属外部证据门槛，本环境不可执行；按计划纪律"尚未部署服务时记录'未通过'，不生成伪成功证据"，本文如实记录。
- 状态口径：
  - **PASS（插件范围）**——该行插件侧义务已被本分支测试真实覆盖，且在第 2 节命令下实际运行通过；服务端/消费端残留义务在"外部依赖清单"（第 10 节）登记。
  - **未通过**——该行核心验收需外部证据；缺失证据逐项写明，本地已覆盖子项一并注明。
  - **UNSUPPORTED**——无对应业务入口，不适用（仅 apply_edit）。

## 1. 实际版本与环境

| 项 | 值 |
|---|---|
| 插件版本 | `kilo.jetbrains.version=1.0.0-rc.1`（`packages/kilo-jetbrains/gradle.properties`） |
| 模块 | shared / frontend / backend / cs-cloud（同仓同版本） |
| JDK | OpenJDK 21.0.12.1 LTS（Microsoft-14941484，build 21.0.12.1+1） |
| Gradle | 9.4.1（wrapper） |
| 操作系统 | Windows 10 Pro 10.0.19045（本文全部本地检查为单机 Windows 实测；Linux/macOS 未跑） |
| cs-cloud daemon / 服务端 | 未部署（外部依赖，见第 10 节） |
| 契约夹具 | `shared/src/test/resources/stability/contract.json`：9 个 `*_verified` gate flag 全部 `false`（G0 交付，无外部证据不翻转） |

## 2. 实际运行的命令与退出码

工作目录 `packages/kilo-jetbrains`，`JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"`。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./gradlew typecheck` | 0 | BUILD SUCCESSFUL in 40s |
| `./gradlew :shared:test --tests 'ai.kilocode.stability.*'` | 0 | BUILD SUCCESSFUL in 12s；13 个测试类合计 **165 tests / 0 failures / 0 skipped** |
| `KILO_STABILITY_BENCHMARK=1 ./gradlew :shared:test --tests 'ai.kilocode.stability.EnqueueBenchmarkTest' --rerun` | 0 | BUILD SUCCESSFUL in 1m 32s（输出见第 7 节） |
| `powershell -NoProfile -File script/stability-acceptance.ps1`（无参数） | 1 | `MissingMandatoryParameter`：Writer/Consumer/Root 强制参数按设计生效 |
| 同上，另传假 Writer/Consumer 路径与真实临时 Root | 1 | `CommandNotFoundException`：外部可执行缺失时脚本按设计失败，不伪造通过 |

`:shared:test` 分测试类实测计数（来自 JUnit XML）：

| 测试类 | tests | failures |
|---|---|---|
| ContractTest | 10 | 0 |
| EnqueueBenchmarkTest（本任务新增，默认空操作） | 1 | 0 |
| FactTest | 29 | 0 |
| FaultTest | 11 | 0 |
| HealthTest | 6 | 0 |
| OperationTest | 18 | 0 |
| PolicyTest | 20 | 0 |
| ProducerTest | 9 | 0 |
| QueueTest | 19 | 0 |
| ResourcesTest | 9 | 0 |
| RetentionTest | 11 | 0 |
| RpcObservationTest | 7 | 0 |
| WriterTest | 15 | 0 |
| **合计** | **165** | **0** |

插件侧其他观测模块的测试（Readiness/Connection/Availability/Probe/Render/IdeObservation 等）由各任务报告记录并曾在各自提交时运行通过（见 `.superpowers/sdd/2026-09-20-jetbrains-stability/task-*-report.md`）；本次 G1-local 复跑范围为上述三条 Gradle 命令。本机已登记的环境性伪失败（路径分隔符族 UI 测试、KiloCliDownloaderTest 无网、KiloWorktreeRpcApiImplTest 沙箱 git 等）与 base 完全一致，均不在本次三条命令范围内。

## 3. 设计 14.1 共用采集与交接

| 场景 | 状态 | 本地证据（本分支实际运行通过） | 缺失证据（外部，精确到交付物） |
|---|---|---|---|
| 正常封存、认领、复制、ACK | 未通过 | WriterTest：封存仅 ATOMIC_MOVE、UTF-8 无 BOM NDJSON、预算恰好释放一次；QueueTest：tryClaim 跨通道取最老、claim 后预算至 release | Go consumer 的复制→持久→ACK、`.done` 派生与 `.ready`→`.done` 全链路实测（依赖第 10 节 1、2 项） |
| writer 写一半崩溃 | 未通过 | WriterTest：写失败半行留 `.open`、不计 records、write_error 计数、禁用该段 | consumer 取得 writer 锁后救援完整行的跨进程实测 |
| IDE 休眠/暂停超过 30 分钟 | 未通过 | WriterTest：封存由"首条起 30s/300s + 字节阈值"驱动（可控时钟），不依赖 mtime | 真平台休眠/断电后目录项持久性实验；平台休眠通知不可用（C3，`suspended` 词表位保留待 G1 校准） |
| 多 IDE、PID 复用、多个 daemon | 未通过 | RetentionTest：`alive writer with matching start is skipped but pid reuse is swept`（ProcessHandle+startInstant 三态，同 JVM 等价） | 同机多 IDE 进程、多 daemon 单一消费者的跨进程实测 |
| consumer 在复制、累计、ACK 前后崩溃 | 未通过 | （插件侧无此职责） | daemon 崩溃重放不重复累计、稳定输出不变、已 ACK 输入可重建两出口 |
| .ready 淘汰与认领竞争 | 未通过 | RetentionTest：最旧 diagnostic→critical 淘汰、锁文件绝不 unlink/recreate、`.claimed`/`.done` 永不触碰；WriterTest：同根第二 writer DISABLED | Go consumer 认领与插件淘汰的真实跨语言竞争（锁内只有一方成功） |
| writer 淘汰与 consumer 救援并发 | 未通过 | RetentionTest：`own source sweep takes exchange only while the writer lock is already held`（writer→exchange 顺序） | Go consumer 不持 exchange 等 writer 的并发救援实测 |
| 磁盘满、队列满、异常风暴 | 未通过 | QueueTest 19 条（2000 条/4MiB 先到、critical 预留 400 条/20% 字节、驱逐最老 diagnostic、争用即弃不变量）；R9 存储满准入闸（quota 计数、outbox_full 状态）；RetentionTest 10MiB 淘汰；record() 全路径无阻塞无 I/O | daemon 侧容量预算、真实磁盘满与异常风暴的全链路实测 |
| 版本升级、账户切换且插件尚未刷新策略 | **PASS（插件范围）** | PolicyTest：epoch 更替永久退役、同账户重登不复活、时钟回跳防护、30s 轮询生效；ProducerTest：撤销结束 run 不伪造 shutdown、重开新 run_id；A5 Coverage reason 闭集 | 服务端归属审计与外部凭据切换 e2e（第 10 节 6、7 项） |
| daemon 不可用、IDE 多次重启并关闭采集 | 未通过 | RetentionTest：死亡 producer 过期文件清理、身份不明跳过、孤儿登记移除；ProducerTest：`collection stays off without permit` | 真实 daemon 不可用 + 多次 IDE 重启的长周期实测 |
| Split Mode 前端无消费器 | 未通过 | ProducerTest：mode/side 唯一来源平台 IdeProductMode（split/frontend、split/backend）；B5 设置页覆盖标签闭集（前端未接入/未授权） | Split Mode 真实两机部署验证（前端覆盖缺口不能宣称完整） |
| 输入含路径/Token/异常消息 | **PASS（插件范围）** | FactTest：路径分隔符/控制字符/UTF-8 字节边界/上下文闭集拒绝；FaultTest：固定模板+受控枚举、frames 白名单、verbatim 限频用例无 "secret"/"alice"；WriterTest：落盘即 UTF-8 无 BOM NDJSON | 上传请求不含机密的出口审计（插件不实现上报，归外部 Sender，第 10 节 3、4 项） |
| Windows/Linux/macOS | 未通过 | WriterTest（Windows/NTFS 实测）：ACL 配置后逐条比对核验（仅当前用户+SYSTEM）、ATOMIC_MOVE、0700/0600 POSIX 分支同实现 | Linux/macOS 实机矩阵（原子封存、锁、救援、清理、目录越界） |
| JVM writer 与 Go consumer 同时持锁/救援 | 未通过 | WriterTest：FileChannel 字节范围 [0,1) 独占、CREATE_NEW 不 unlink（同 JVM/同进程互斥已证） | 外部 Go Writer/Consumer 可执行与互操作锁原语实验（fcntl/LockFileEx 与 FileChannel 互斥，不能用两套单测替代） |
| 非默认 data-dir/auth-path、多 daemon 发布策略 | 未通过 | 自定义 profile 表现为默认控制文件缺失 → `no_policy` fail-closed（PolicyTest 不可读/缺失即拒；ProducerTest 无许可不激活） | daemon 侧多 daemon 发布者唯一性与非默认路径行为实测 |

## 4. 设计 14.2 指标链路

| 场景 | 状态 | 本地证据 | 缺失证据（外部） |
|---|---|---|---|
| end 在截止前完成，下一文件晚到（宽限/pending/乱序） | 未通过 | OperationTest：Terminal CAS 唯一终态、elapsed>deadline 改判 timeout；B2：`initial request after timeout opens a new journey`、真实 deadline 超时后成功无第二条 end | pending→可信终态的历史窗口结算与查询证据（10.2 属 daemon/查询侧） |
| EDT 阻塞 3 秒、休眠、探针调度暂停 | 未通过 | ProbeTest 11 条：单一 pending、序号仅实际投递递增、失效轮换 observation_id、scheduler_gap>2000ms 作废、迟到序号不计 valid；VisibilityServiceTest：真实 EDT 阻塞期间零事实、释放后按队列完成 | "有效 3 秒样本计一次 stall"的合并派生属 cs-cloud（消费向量已交付于 task-C3-report.md），未验证 |
| 同设备不同进程及 run 重置 | 未通过 | QueueTest：seq 按 run/channel 准入前递增、顺序==seq、并发唯一；ProducerTest：每实例轮换 producer_id、重开新 run_id、每 run 恰 1 条 plugin.started | 消费端按 producer/run 增量汇总的正确性实测（3→5、8→9 得 3 等） |
| 从用户操作到告警（完整时延） | 未通过 | （插件侧无云端链路） | 部署环境逐事件"产生→封存→发现→spool→受理→可见"实测；critical 约 40s/diagnostic 约 310s 仅调度预算，不能替代 P0 告警 SLO |
| 指标 JSON 批次部分成功及重复 ID | 未通过 | （服务端行为） | 指标服务逐项 errors 解析、接受项不随失败项重新派生、幂等窗口重传不增计（第 10 节 3 项） |
| daemon 重启但 IDE 的 producer/run 不变 | 未通过 | （daemon 持久化行为） | daemon 恢复该源已提交累计与去重状态、process_epoch 变化不生成插件重置的实测 |
| ide.operation=**apply_edit**（M23 覆盖项） | **UNSUPPORTED** | C5 覆盖记录：v1 无对应业务入口（`WorkspaceRpcApiImpl` 缺省配置创建不可映射、`COSTRICT_IDE_TOOLS` 无编辑工具）；登记于 `shared/.../stability/dictionary.kt` IDE_OPERATIONS 注释与 `backend/src/test/.../rpc/ide-observation-test.kt` KDoc；词表保留以备未来契约 | 不适用（不伪造零成功率；未来该业务独立实现时在其自有处理器补同一接口） |

## 5. 设计 14.3 日志链路

以下各行均依赖日志服务与日志 Sender（插件不实现 Sender），全部未通过；本地仅有不构成接口行为证据的插件侧子项。

| 场景 | 状态 | 本地证据 | 缺失证据（外部） |
|---|---|---|---|
| endpoint 缓存 300 分钟、空 200、413、429、401/403 | 未通过 | 仅插件侧安全详情限频（FaultTest 3 条/指纹/分钟）与 `log_detail_rate_limit` 夹具冻结（ContractTest）——不构成接口行为证据 | endpoint 发现与缓存 5 小时换算、整批确认、拆批退避、权限暂停不绕过、Retry-After 与冷却实测 |
| 日志前行合法、后行非法 | 未通过 | （服务端行为） | 整批未受理、隔离/修正后重组、合法前行不提前标 accepted、更改内容用新输出 ID |
| 正常日志 NDJSON 批次与空 200 | 未通过 | （服务端行为） | 整批标记接口受理、不宣称逐条落库/立即可查询 |
| 日志响应丢失后重试 | 未通过 | （Sender 行为） | 输出 ID 及内容稳定、允许服务端重复、按输出 ID 排障、不产生业务指标增量 |
| 通用云客户端默认 /cloud-api 与 JSON 请求头 | 未通过 | （云端客户端行为） | 指标/发现使用配置完整 URL、日志使用发现返回 URL 与 NDJSON、不携带 device token 的审计 |

## 6. 设计 14.4 两链路隔离

| 场景 | 状态 | 本地证据 | 缺失证据（外部） |
|---|---|---|---|
| 指标 200 部分成功、日志超时 | 未通过 | 插件侧双用途独立：PolicyTest verbatim `expired logs do not stop metrics`；FaultTest logs-open-metrics-closed 仅记录详情 | 服务端逐输出确认、日志重复不污染指标的 e2e |
| 同一 end 派生次数、耗时、日志（三个不同输出 ID） | 未通过 | G0 output-vectors.json 已冻结向量格式，`namespace: null, status: "pending_freeze"`，vectors 为空（不临时生成） | namespace 冻结（固定 UUIDv5）+ counter/histogram/log 三个 golden vector + consumer 派生与重试稳定性实测 |
| 服务端关日志、撤销授权、策略过期 | **PASS（插件范围）** | PolicyTest：单用途过期互不影响、用户撤销可发布/解除、epoch 退役；ProducerTest：`revocation ends the run without faking shutdown...`；WriterTest：入盘前重判期丢弃失效事实（禁采期间不落盘） | 服务端第 8 章强制执行的 e2e |
| 指标关/日志开，日志关/指标开 | **PASS（插件范围）** | PolicyTest：`missing single purpose closes only that purpose`（单用途折叠互不影响）；FaultTest：metrics 关闭时 logs 详情仍记录；QueueTest：logs-only/metrics-only 互拒；ProducerTest：单用途关闭不拆 run | 服务端不补生成历史输出的 e2e |
| 日志有效期到期，指标策略仍有效 | **PASS（插件范围）** | PolicyTest verbatim `expired logs do not stop metrics`（逐字保留并实际运行通过） | 发送侧对称行为（指标策略单独过期只停 metrics）的 e2e |
| 单一出口持续 503、满额或冷却 | 未通过 | （Sender/服务端行为） | 仅失败出口受影响的发送、预算与冷却实测（插件无退避/重试职责） |

## 7. 入队基准（单机指示性数据）

- 方式：`shared/src/test/kotlin/ai/kilocode/stability/enqueue-benchmark-test.kt`（`KILO_STABILITY_BENCHMARK=1` 时执行，默认空操作、无时序断言）。Fixture 真实 Recorder + 真实 Writer（真实临时目录落盘、每 16 条封存一次）；合法许可（默认控制文件）；合法 `rpc` end 草稿（准入真实执行字典校验与用途交集）；预热 20,000 次后计量 100,000 次，`System.nanoTime` 仅包住 `record()` 调用；队列深度 >1024 时短暂让出（`LockSupport.parkNanos`）以反映准入路径而非容量拒绝。
- 结果（2026-09-21 单机 Windows 实测，单次运行）：

| 指标 | 值 |
|---|---|
| 样本数 | 100,000（预热 20,000） |
| P50 | 3.500 µs |
| P95 | 7.300 µs |
| P99 | 20.400 µs |
| 最大值 | 2938.000 µs |
| 准入计数 | QUEUED=99,933；DROPPED=67（争用，0.067%）；DISABLED=0 |
| GC | 计量期间收集 11 次 |
| 线程 | 8 → 8（无泄漏） |

- **强制保留意见**：以上为单机指示值，**不是 G1 契约测量**。"普通及风暴 P99<1ms" 的契约判定必须在标定环境（校准硬件、无测试夹具开销、包含分配/GC/线程画像与逐事件全链路时延）由外部实验取证；本记录不主张该断言。max 尖峰与 GC 及 writer 磁盘 I/O 抖动相关，单次运行不构成统计结论。

## 8. 配额与时限验证指针（本地已实测）

| 约束 | 覆盖测试（本分支，实际运行通过） |
|---|---|
| 队列 2000 条且 4MiB 先到者为准；critical 预留 400 条与 20% 字节 | QueueTest：`1600 diagnostics leave room for 400 critical records`、多字节先触字节限、持续 critical 只驱逐 diagnostic、超总容量驱逐后仍拒 |
| 每 producer 未交接 10MiB、保留 24 小时 | RetentionTest：`own source quota evicts oldest diagnostic ready before critical`、`expired open and ready of a dead producer are swept while fresh files stay`、`expired claimed and done files are never touched` |
| 单记录 32KiB、message 摘要 512 字节 | FactTest：UTF-8 字节口径 512/128/32KiB 边界、frames≤5；WriterTest：落盘前真实编码核对（超限丢弃计数） |
| outbox 满拒绝新写入并计数（7.4） | QueueTest R9 三条（storage-full 闸优先级 closed>full>capacity、quota 计数不入 buffer_full）；ProducerTest outbox_full 状态可观测 |
| 双出口独立预算（服务端侧 metrics/logs 各自配额） | 未通过——依赖第 10 节 3、4 项 |

## 9. 灰度状态

- **两周灰度：未开始。** 前置条件：第 10 节外部门槛全部通过 + owner 决策；流程为先单体、再双机器；P0 按真实样本校准阈值、P1 单独观察覆盖与资源回落。
- **changeset：未创建。** 按计划约束"正式用户功能交付再添加changeset"；灰度未开始，本轮不添加 `.changeset/jetbrains-stability.md`。

## 10. 外部依赖清单

| # | 外部交付物 | 缺失证据/状态 | 阻塞的验收项 |
|---|---|---|---|
| 1 | 外部 Go Writer/Consumer 可执行测试程序 | 协议 `--scenario <name> --root <dir> --peer <executable>`；握手 `LOCKED`/`CLAIMED`/`SYNCED`/`ACKED`；确认状态后才触发中断/恢复（禁止 sleep 猜测）；peer 用 `--role peer` 且禁止递归启动对方；双进程 watchdog 内退出 0。驱动脚本 `script/stability-acceptance.ps1` 已就位，因缺此交付物今日运行即失败（第 2 节实测） | 六场景矩阵（lock-contention/dead-writer/pid-reuse/claim-race/sync-failure/replay-after-ack）、14.1 锁/救援各行、Step 3 全部文件场景 |
| 2 | cs-cloud daemon consumer | 复制→持久→ACK、`.done`/`.claimed` 处置、认领竞争、durable ACK、崩溃重放不重复累计、旧 producer 清理、多 daemon 单一消费者 | 14.1 第 1/2/5/6/10 行、第 13 章联调确认 |
| 3 | 统一指标 Sender 与指标服务 | Spec/labels 接受样例、逐项部分成功、重复 ID 幂等、413/stale_event 永久拒绝、多源累计查询与序列键承载定案（10.1） | 14.2 第 4/5 行、14.4 第 1 行、G0 指标侧 flag |
| 4 | 日志 Sender 与日志服务 | endpoint 发现、九字段校验、expires_in 换算、空 200、413/429/401/403/503、Retry-After、X-Request-ID 关联 | 14.3 全部、14.4 第 6 行 |
| 5 | M17（cs-cloud 两出口自观测） | cs_bridge 提案仍 Draft、未接入统一指标 Sender 及日志 Sender；不把候选名当已发布指标 | Step 4 出口指标、"数据何时进入上报服务" |
| 6 | 身份提供方/已验证代际 | TokenProvider 代际校验、universal_id/tenant 校验不回退 sub/machineID、并发刷新/登出/文件覆盖不复活旧账户 | 14.1 第 9 行 e2e、14.4 第 3 行服务端侧 |
| 7 | G0 九个 `*_verified` flag 翻转 | 本轮全部 false；其中 **credentials.ready success 接线是 G0 落地任务的义务**（C1 已在 M08 留 KDoc 接线点，success 分支本期不接，迟到 ready 仅日志） | 全部 e2e 验收的启用前提 |
| 8 | legacy_v5 Spec 侧登记 | C1 已在插件字典代码侧登记 `migration_kind=legacy_v5`；Spec/配置仓库侧登记 pending | M10 验收、契约侧对齐 |
| 9 | 输出向量 namespace 冻结与 golden vectors | `output-vectors.json` status=pending_freeze、vectors 空（不临时生成 namespace） | 14.4 第 2 行、9.1 输出身份 |
| 10 | 真平台/跨平台实验 | 休眠>30 分钟、断电后目录项持久性、跨盘复制、POSIX/Windows 权限矩阵的 Linux/macOS 实机 | 14.1 第 3/13 行、Step 3 |
| 11 | 标定环境成本测量 | 100,000 次入队 P99<1ms 契约口径（含分配/GC/线程画像）、逐事件产生→封存→发现→spool→受理→可见、critical 40s/diagnostic 310s 的实际 P0 告警 SLO 样本 | Step 5 契约测量（本记录第 7 节数据不替代） |
| 12 | owner 决策与两周灰度 | 先单体后双机、退回预案、发布流程消费 changeset 的方式确认 | Step 6、changeset 交付 |

## 11. 结论汇总

| 分类 | PASS | 未通过 | UNSUPPORTED |
|---|---|---|---|
| 14.1 共用采集与交接（15 行） | 2（均插件范围） | 13 | 0 |
| 14.2 指标链路（6 行 + apply_edit 覆盖项） | 0 | 6 | 1（apply_edit） |
| 14.3 日志链路（5 行） | 0 | 5 | 0 |
| 14.4 两链路隔离（6 行） | 3（均插件范围） | 3 | 0 |
| **合计** | **5** | **27** | **1** |

- 插件实现侧定向检查（`typecheck` + `:shared:test --tests 'ai.kilocode.stability.*'`）全部通过（第 2 节，退出码 0）。
- 灰度未开始，changeset 未创建。
- 纪律声明：本记录未生成任何伪成功证据。全部 PASS 均为本分支实际运行测试得出的插件范围结论；全部端到端/部署/跨语言/服务端结论如实记为未通过，缺失证据在第 10 节逐项列明。
