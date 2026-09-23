# JetBrains 插件稳定性采集·人工测试指南（插件端）

- 依据：[稳定性设计与采集协议](./jetbrains-stability-design.md)（下称"设计文档"，章节号 §x 均指该文，本指南自身章节以"指南 §n"引用）；[outbox 高保真诊断设计](./superpowers/specs/2026-09-23-jetbrains-outbox-diagnostics-design.md)（下称"诊断设计"）及其[实施计划](./superpowers/plans/2026-09-23-jetbrains-outbox-diagnostics.md)。
- 范围：**插件端全流程**——从控制文件许可、IDE 启动采集、结构化事实采集，到 outbox 追加式 NDJSON 落盘与优雅关闭。cs-cloud 消费端（文件消费、指标/日志转换和发送）尚未交付，不在本指南范围内；测试验证到"事实已写入 outbox、可供消费"为止。v2 高保真诊断（`diagnostic.*` 三事件）由控制契约 `accepted_fact_schema_majors` 门控，场景 G 专项覆盖；诊断设计的发布顺序要求消费者先于插件验证（`fact_schema_v2_verified` 门），人工测试用控制文件直接启用即可，不受该发布顺序约束。
- 方式：真实 IDE、真实用户环境（不隔离 home），测试者**手工扮演 cs-cloud 发布控制文件**，操作 IDE 触发业务，检查磁盘产物。
- 全类型口径：**31 个 v1 登记事件名、7 种 kind、critical/diagnostic 两个通道、metrics-only / logs-only / dual 三类用途投影**全部落盘核对（场景 E）；另有 **3 个 v2 诊断事件名**（`diagnostic.reported` / `diagnostic.payload` / `diagnostic.redaction_failed`，schema 2.0），由场景 G 以真实故障核对。
- 版本适用：场景 G、C13/C14、D2 增强证据、D4 50MiB 预算与指南 §11 中 v2 相关校验，均以诊断专项（计划 2026-09-23）落地后的构建为准；专项落地前的构建继续按 v1 口径执行场景 A~F，此时缺 v2 相关行为**不是缺陷**。

## 0. 十分钟快速通道

只做一轮最短验证时，按顺序执行：

1. 按指南 §3 写好双用途控制文件（如需一并验证 v2 诊断，改用 §3.2「启用 v2」变体）；
2. 启动 IDE、打开项目、打开 Costrict 工具窗（指南 §4 场景 A 步骤 3~7）；
3. 发送一条消息给智能体（指南 §5 场景 B 第 1 行）；
4. 正常关闭 IDE；
5. 用指南 §11 命令核对：outbox 中有且仅有本 IDE scope 的 `<scope-id>.jsonl` 文件、恰 1 条 `plugin.started` + 1 条 `plugin.shutdown`、seq 连续、`action`/`rpc`/`render.apply` 均落盘（未启用 v2 门时无 `diagnostic.*` 属正常）。

全绿即通过最小链路；完整口径继续 §4~§10。

## 1. 范围与原理

### 1.1 插件端全流程（本指南要走的路）

```text
手工发布控制文件（扮演 cs-cloud）
        │
IDE 启动 → StabilityService 后台初始化 → 读控制文件（30 秒轮询）
        │  建立本次 producer/run（无有效策略时使用 unbound 占位）
        ├─ 打开或复用 outbox/<scope-id>.jsonl（当前 JVM 为唯一写者）
        ├─ 记 plugin.started（每 run 恰 1 条）
        │
业务操作 → Recorder 非阻塞入队 → writer 单线程追加 NDJSON（每行一次 write）
        │  非空队列最迟 30 秒 flush/fsync，或累计 16 条/64KiB 即 flush
        ├─ health 增量、edt.stall 与业务事实共同写入同一 outbox 文件
        └─ WARN/ERROR 日志 → DiagnosticBridge 非阻塞镜像 → 同一 outbox（v2 门启用后，重入保护防递归）
正常关闭 → plugin.shutdown(app_close) 有界排空追加 → 文件保留供消费
```

### 1.2 术语速查

| 术语 | 含义 |
|---|---|
| 控制文件 | `~/.costrict/telemetry/control/jetbrains.json`，cs-cloud 发布的采集策略；人工测试手工编写 |
| scope 文件 | `~/.costrict/telemetry/outbox/<scope-id>.jsonl`，目录即发现入口；每个 IDE 安装范围一个文件，跨重启和插件升级复用 |
| producer / run | producer=每 JVM 采集实例（`pr-` 前缀）；run=每次采集生命周期（`run-` 前缀），均作为行内字段。撤销许可重开会换新 run；重启 IDE 会在复用的 scope 文件中写入新的 producer/run |
| scope-id | 每个 IDE 安装范围持久的随机标识（存于 IDE 配置目录的 `kilo-stability-scope-id` 文件；首次创建复用旧 `ai.kilocode.stability.scope.id` 设置中的有效 ID），同一 IDE 多次启动及插件升级共享、不同 IDE 互不相同；它是追加文件名，用于在同一文件中识别前任 run（`plugin.unclean` 判定，§7.3）与同源清理归属（§7.4） |
| 通道 channel | critical=计数/结果/生命周期事实；diagnostic=限频诊断详情；两个 wire 通道在 v2 下不变，failure 优先级是内部队列概念 |
| 用途 purposes | metrics（指标链）/ logs（日志链），一条事实可兼有；v2 诊断行属 logs 用途链 |
| 追加文件 | 单写者追加式 NDJSON；无 `.open`/`.ready`/`.claimed`/`.done` 状态机，消费端按字节位移读取 |
| incident | 一次故障的诊断单元：1 条主记录（`diagnostic.reported` 等）+ 0..n 条 `diagnostic.payload` 分片，经 `context.incident_id` 关联 |
| 分片 chunk | 大文本/二进制载荷的切片：单片 wire ≤32KiB，`encoding=utf8/base64`，带 `chunk_index`/`chunk_count`/`original_bytes`/`sha256`，按 incident 可无歧义重组 |
| incident 预算 | 单 incident 载荷上限 1MiB；超出保留头尾各 512KiB，主记录与分片置 `truncated=true` 并保留完整原文 SHA-256 与原始字节长度 |
| `<redacted:type>` | 凭证脱敏替换标记（Authorization/Cookie/各类 token/密码/私钥/已登记敏感环境变量值），无论配置如何强制生效；过滤失败时不落原文、只落 `diagnostic.redaction_failed` |
| quality | `telemetry.health` 的指标质量位：任一 failure 记录或 operation end 丢失 → `degraded`，否则 `good` |
| device_id | 随机安装标识，持久保存在 IDE 持久设置中；重装是否更换取决于该设置是否保留，不能承诺重装必变（§5.2） |

## 2. 环境准备

### 2.1 构建并安装插件

```bash
cd packages/kilo-jetbrains
export JAVA_HOME="$HOME/.jdks/ms-21.0.12.1"    # 本机 JDK 21，按实际路径调整
./gradlew buildPlugin
# 产物：packages/kilo-jetbrains/build/distributions/costrict-<版本>.zip
```

IDE 中 `Settings → Plugins → ⚙ → Install Plugin from Disk...` 选择该 zip，重启 IDE。使用已发布安装版测试时，记录实际版本号（落盘事实的 `plugin_version` 应与之一致）。验证场景 G 需安装包含诊断专项的构建。

### 2.2 定位关键目录（Windows）

| 目录 | 位置 | 怎么找 |
|---|---|---|
| telemetry home | `%USERPROFILE%\.costrict\telemetry\` | 直接创建；含 `control\`、`outbox\` |
| IDE 日志目录 | 常规安装为 `%LOCALAPPDATA%\JetBrains\<产品><版本>\log` | IDE 菜单 `Help → Show Log in Explorer`，打开的资源管理器所在目录即日志目录 |
| outbox 根 | `%USERPROFILE%\.costrict\telemetry\outbox\` | 采集启动后自动出现；每个 IDE 安装范围有一个 `<scope-id>.jsonl` 文件 |

注意：IDE 日志目录随安装方式（Toolbox/独立安装/便携模式）不同，**不要猜路径**，一律以 Show Log in Explorer 的结果为准。

### 2.3 工具

- 文本编辑器（VS Code 等，按 UTF-8 打开）；
- PowerShell（校验命令见指南 §11，无需额外安装）或 git-bash + `jq`。

### 2.4 环境说明

- 本机部署的 cs-cloud daemon 当前版本**没有**遥测消费能力，不会读写这些测试文件；测试结束后仍按指南 §13 清理，避免将来 daemon 升级后消费到测试数据。
- 场景 B（业务事实）需要可用的 cs-cloud 连接。凭据缺失期间，`availability` 必须为 `blocked`，相关操作可表现为 `blocked/credentials_missing`，这本身是合法终态，可照常核对。

## 3. 控制文件手册（手工扮演 cs-cloud）

### 3.1 标准模板（双用途，全套类别）

保存为 `%USERPROFILE%\.costrict\telemetry\control\jetbrains.json`（UTF-8，无 BOM）：

```json
{
  "schema_major": 1,
  "revision": 1,
  "enabled": true,
  "metrics_enabled": true,
  "metrics_expires_at": <NOW_PLUS_2H>,
  "logs_enabled": true,
  "logs_expires_at": <NOW_PLUS_2H>,
  "account_epoch": "acct-manual-01",
  "account_state": "ready",
  "expires_at": <NOW_PLUS_2H>,
  "metrics_allowed_categories": ["critical", "diagnostic"],
  "logs_allowed_categories": ["critical", "diagnostic"],
  "log_detail_rate_limit": {"per_fingerprint_max_per_minute": 3}
}
```

生成"当前时间 + 2 小时"的 UTC 毫秒时间戳：

```powershell
# PowerShell
[DateTimeOffset]::UtcNow.AddHours(2).ToUnixTimeMilliseconds()
```

```bash
# git-bash
$(( $(date +%s%3N) + 7200000 ))
```

**时间戳必须是未来时间**——过期文件等同无策略，采集不启动。测试超过 1 小时后建议整体重写文件（同时 `revision` +1）。

### 3.2 变体模板

| 变体 | 改动 | 用途 |
|---|---|---|
| 仅指标 | `"logs_enabled": false` | 场景 C5 |
| 仅日志 | `"metrics_enabled": false` | 场景 C6 |
| 撤销 | `"enabled": false`（或删文件，效果见 C1/C3 差异） | 场景 C3 |
| 换账户 | `"account_epoch": "acct-manual-02"`，`revision` +1 | 场景 C8 |
| 账户未就绪 | `"account_state": "pending"`（或 `"disabled"`） | 场景 C10 |
| 单用途过期 | 仅把 `logs_expires_at`（或对称地 `metrics_expires_at`）改为过去，其余期限保持未来 | 场景 C11 |
| 详情限频关闭 | `"log_detail_rate_limit": {"per_fingerprint_max_per_minute": 0}`（合法区间 0~60，0 不采详情或摘要） | 场景 C12 |
| 启用 v2 诊断 | 顶层加 `"accepted_fact_schema_majors": [1, 2]`（数组，元素为唯一正整数；包含 2 才准入 v2 诊断） | 场景 G |
| 显式仅 v1 | `"accepted_fact_schema_majors": [1]`（缺省等价于 `[1]`） | 场景 C13/C14 |
| 单类别 | `*_allowed_categories` 只留 `["critical"]`（diagnostic 类别不放行，同名事件的诊断详情不采） | 可选 |

### 3.3 控制文件与安全边界

文件缺失、为空、JSON 畸形或 `schema_major` 未知时按无有效策略处理：插件使用 `account_epoch=unbound`、`policy_revision=0`，默认不限制采集；这不是 fail-closed。只有有效控制文件中的显式关闭、`pending/disabled`、用途开关或有效期才停止相应用途。凭据缺失不伪造认证失败，而由 `availability=blocked` 和 `credentials.ready` 的合法终态表达。

`accepted_fact_schema_majors` 缺失时按 `{1}` 处理：v1 指标照常、v2 高保真诊断被抑制；仅当数组包含 `2` 时 v2 诊断被准入，且该门只影响诊断，不连带影响 v1 指标（场景 C13/C14）。诊断设计要求消费者先验证 v2 输出后插件才发布写端（`fact_schema_v2_verified`），这是交付/发布流程约束；人工测试不受限，可直接用控制文件启用。

## 4. 场景 A：冷启动 → 采集 → 追加落盘（主线全流程）

前置：清理 `%USERPROFILE%\.costrict\telemetry\outbox\` 下的既有测试文件，按 §3.1 写控制文件。

| # | 操作 | 预期（核对点） |
|---|---|---|
| A1 | 启动 IDE，打开任意项目 | `outbox` 尚未出现也正常（控制策略轮询中）；IDE 功能不受影响 |
| A2 | 等待 ≤ 45 秒（轮询周期 30 秒 + 余量） | `outbox\<scope-id>.jsonl` 出现；无登记目录或额外状态文件 |
| A3 | 读取追加文件 | 每行一个 JSON 对象、UTF-8 无 BOM、LF 结尾；首个 run 恰 1 条 `plugin.started`（`data` 为空对象） |
| A4 | 抽查事实公共字段 | `producer_id`、`device_id`、`plugin_version`、IDE/平台字段、`mode`、`side`、`env`、`connection_provider` 均存在且值受控；连接建立后 `connection_provider` 不得为 `unknown` |
| A5 | 观察追加文件 | `plugin.readiness`、`connection`/`connection.attempt`、`availability`、`telemetry.health` 等事实追加到同一 `.jsonl`，没有 `.open`/`.ready` 文件 |
| A6 | 打开 Costrict 工具窗，正常等待 1~2 分钟 | 自然事实持续追加：`plugin.readiness`、`connection`/`connection.attempt`、`connection.state_changed`、`availability`、`backend.load`、`toolwindow.setup`、`rpc`、`edt.delay`、`render.apply`、`telemetry.health`（约 30 秒节奏）、`resource.snapshot`（每 30 秒 3 条：subscription/controller/editor） |
| A7 | 观察追加时效 | 非空队列最迟 30 秒 flush/fsync，或累计 16 条/64KiB 即 flush；文件持续追加，不存在分段封存动作 |
| A8 | 正常关闭 IDE | `plugin.shutdown` 恰 1 条、`end_kind=app_close`；文件最后一行是该终态，未确认内容仍留在 outbox 供消费 |
| A9 | 抽查 NDJSON 线格式 | 用指南 §11 校验：公共字段闭集、UTF-8 无 BOM、LF 结尾无 CR、`schema_version=1.0`（启用 v2 门后诊断行为 `2.0`，见场景 G）、`event_id` 唯一、seq 按 run+通道连续（策略切换窗口除外）、`source=jetbrains-plugin`、`device_id` 全程一致 |

判定：A1~A9 全部符合 → 场景 A 通过。

## 5. 场景 B：业务操作 → 结构化事实

前置：场景 A 的运行中 IDE（采集已激活），真实 daemon 可用。每个操作后等 5~30 秒再查盘（队列 + flush 延迟）。

| 操作 | 预期落盘事实 | 专项核对 |
|---|---|---|
| 在提示框输入并回车发消息 | `action`（start→end）+ `rpc`（多条）+ `render.apply` | `action.data`：start 带 `action=prompt_submit`、`deadline_ms`；end **自包含**——`result`/`duration_ms`/`stage`/`cause`/`error_code` 五项齐全；`render.apply` 带 `sample_rate` 与 `batch_size_bucket` |
| 消息生成中点停止按钮 | `action`（`action=stop`） | 终态 `cancelled`，不是 error |
| 智能体请求工具权限/提问并回复 | `action`（`permission_reply`/`question_reply`） | 正常回复不产生 error 级事实 |
| 新建会话 | `session.open`（`session_mode=create`） | — |
| 关闭并重开 IDE，恢复会话 | `session.restore`（`session_mode=open`） | — |
| 让智能体修改代码，打开 diff 查看 | `ide.operation`（`operation=open_diff`） | `operation` 取值在受控词表内 |
| 触发 VFS 刷新 / 新增 MCP 绑定 | `ide.operation`（`vfs_refresh`/`mcp_register`） | — |
| 在提示框输入 `@` 并选择文件 | file-search 包装响应正常解码，补全结果可用 | 包装型 file search 正常响应不产生 `protocol.error` |
| 让 file-search 返回坏响应 | `protocol.error`（`transport=http`，`stage=decode`）恰 1 条 | 同一坏响应经过多层解码/应用时只计一条，不重复落盘 |
| 退出/杀掉 csc daemon（或断网）再恢复 | `connection.state_changed`（`from`/`to`/`reason`/`streams_open`/`streams_total`）；`connection` 终态 `failure` 或 `blocked`；`availability` 出现 `error`/`blocked` 区间；恢复后 `connection.recovery`（`intervention=automatic`） | 退化不重复计恢复；`availability` 区间首尾相接不重叠（抽查相邻两条） |
| 登录/凭据探测 | `credentials.ready`（`stage=probe/wait`） | 等待凭据不伪造认证失败 |
| 缺失或撤销凭据后打开工具窗 | `availability` 的当前区间为 `blocked`，操作可为 `blocked/credentials_missing` | 不得把 `READY` 或空 profile 误记为可用 |
| 连接 cs-cloud 后开始一次会话，再切换/重启连接后继续 run | 所有新事实 `connection_provider=cs-cloud`（或 `kilo-cli`） | 不得出现 `connection_provider=unknown`；预热阶段和后续 run hint 均应归因到实际 provider |
| 真实 MCP 绑定返回 HTTP 502 | `ide.operation`（`operation=mcp_register`）有 `start` + `end(result=failure)` | 绑定请求有界结束，不挂起会话；若能力不可用则终态为 `blocked`，会话关闭等在途取消为 `cancelled` |
| 同一事实同时允许 metrics 与 logs | outbox 仅落盘一条 `event_id` 相同的输入事实 | 消费端按 `purposes=["metrics","logs"]` 分流，插件不重复采集/落盘 |
| 点插件的重装/重启入口（如可用） | `csc.install`、`csc.start` | `stage`、`package_manager`、`error_code` 字段合规 |
| 长时间空闲但保持面板可见、IDE 前台 | `edt.delay` 持续产出 | `validity=valid`；IDE 最小化/面板隐藏后停止产出 |
| 系统休眠/挂起后唤醒 IDE，保持面板可见 | `edt.delay`：被打断的未完成样本以非 `valid` 的 `validity`（如 `unknown`/`scheduler_gap`）各落一条事实，不计卡顿；唤醒后换新 `observation_id`，不跨观测区间拼接出 `edt.stall` | 唤醒后采集照常追加（休眠不做死亡推断，§14.1）；无 `plugin.unclean`（进程未重启） |

**v2 诊断镜像**（仅当控制文件 `accepted_fact_schema_majors` 含 2；核对点详见场景 G）：

- KiloLog 的 WARN/ERROR 自动镜像为 `diagnostic.reported`（`severity=warn/error`），INFO/DEBUG 不镜像；v2 行属 logs 用途，仅指标许可下不落盘；
- file-search 坏响应 → `code=json_decode_failed` incident（主记录含 `json_path`/`expected_type`/`actual_type`，原始响应载荷在分片中）；
- MCP 绑定 502/超时 → `code=ide_capability_bind_failed` incident，与 `ide.operation(mcp_register)` 的 end 共享 `operation_id`；
- HTTP 404 类失败 → `code=http_not_found` incident（`route` 无查询参数、`http_status`、请求/响应载荷，header 凭证已脱敏）。

专项语义核对（任抽一条 operation end）：

- `result` ∈ {success, failure, timeout, blocked, cancelled, unknown}；
- 超时判定：`result=timeout` 的记录同时有 `cause`（如 `unknown`）与 `error_code`（如 `deadline_exceeded`），不依赖原日志即可解释；
- v1 计数/指标事实的 `data` 内**没有**路径、Token、原始异常 message（指南 §11 安全抽查）；v2 诊断行按诊断设计允许出现路径与原始异常，但凭证必须已脱敏（`<redacted:type>`）；
- 高频事实（`rpc`/`edt.delay`/`render.apply`/`resource.snapshot`/`availability`）的 `purposes` 恒为 `["metrics"]`。
- `session.status`、SSE 信封字段形状与 file-search 解码错误各自产生一条 `protocol.error`，跨层重复解析不重复计数；包装型 file search 正常响应不产生协议错误。
- `connection_provider` 在实际连接建立后不得为 `unknown`；provider 提示在 recorder 创建时冻结本次 run——之前到达的提示用于本次，writer 启动等待期间及之后到达的提示只用于下一次 run，且均归因到实际 provider。
- 凭据缺失期间 `availability=blocked`，不得以 `READY` 或 `profile=null` 继续宣称可用。
- MCP 绑定 HTTP 502 必须观察到 `mcp_register` 的 start 与 failure；能力不可用为 `blocked`，会话关闭等在途取消为 `cancelled`；绑定本身在边界内结束。
- 双用途事实仅有一个输入 `event_id` 和一条落盘记录；用途分流属于消费端，不能用两条插件记录替代。

RPC 终态复测：测试 RPC 时须保持 metrics 许可至少超过其 deadline；同一 `operation_id` 应有且仅有一条 `start` 与一条 `end(result=timeout)`。若仅撤销一个用途且另一用途仍获准、run 未被整体停用，缺少终态可由最近一条 `telemetry.health` 的 `disabled_policy` 增量表达；公共关闭、双用途到期等全停采会结束 run 并清理文件，不要求随后仍有该 health 增量，不能仅凭缺少 `end` 判定 RPC 生命周期故障。

## 6. 场景 C：策略生命周期（运行中改控制文件）

单个 IDE 会话内按下表顺序操作；每步改文件时**同时递增 `revision`**，改完等 ≤ 45 秒再观察。所有阶段的落盘事实 `policy_revision` 应与产生它时的控制文件一致。

| # | 操作 | 预期 |
|---|---|---|
| C1 | 先删控制文件再启动 IDE（或新会话） | fail open：使用 `account_epoch=unbound`、`policy_revision=0`，建立 outbox 并采集；IDE 功能不受影响 |
| C2 | 运行中发布 §3.1 文件 | ≤ 45 秒内 outbox 追加文件出现并开始落盘（即场景 A 从 A2 起的重演） |
| C3 | 改 `enabled=false`（revision+1） | 显式撤权：当前 run 结束并删除本实例待交接文件；之后无新业务事实、**无** `plugin.shutdown`（撤销不伪造退出）；IDE 继续可用 |
| C4 | 改回 `enabled=true`（revision+1） | 新 `run_id`：新 run 记 1 条 `plugin.started`；新旧 run 的 `producer_id`/`device_id`/追加文件不变（同一 producer）；新 run 的 seq 从 1 重新起算 |
| C5 | 改 `logs_enabled=false` | 指标用途继续：`rpc`/`edt.delay` 等 metrics-only 事实照常；dual 用途事实落盘 `purposes=["metrics"]`；diagnostic 通道无新文件；error 计数（critical/metrics）照常 |
| C6 | 恢复 logs、改 `metrics_enabled=false` | 对称：diagnostic 通道恢复；metrics-only 事实消失；dual 事实 `purposes=["logs"]` |
| C7 | 把三个 `expires_at` 改为过去时间 | ≤ 45 秒内停采（到期判定不依赖轮询，实际更快）；表现同 C3。继续 C8 前，先重新发布一份三个期限均在未来的有效 §3.1 控制文件 |
| C8 | 恢复未来有效期限后换 `account_epoch=acct-manual-02`（revision+1） | 同一 run 存续，之后新事实 `account_epoch` 变为新值；换出后旧 epoch 事实不再增加。再把 `account_epoch` 改回已退役的 `acct-manual-01`：策略被拒绝，后续按无有效策略使用 `account_epoch=unbound`、`policy_revision=0` 默认采集；旧 epoch 不复活 |
| C9 | 依次写入：`schema_major=2` / 加一个多余键 `"foo":1` / 写成 `{坏json` | 每种都回到无有效策略：`unbound` 占位并默认采集；恢复合法文件后按新 revision 执行显式策略 |
| C10 | `account_state` 改为 `pending`（revision+1）；观察后改回 `ready`（再 +1） | 显式账户未就绪：两种用途同时停止（表现同 C3，但属显式限制而非 fail-open，§3.3）；改回 `ready` 后恢复采集。`disabled` 同理 |
| C11 | 仅把 `logs_expires_at` 改为过去（`metrics_expires_at`/`expires_at` 保持未来，revision+1）；观察后恢复，再对称地仅过期 `metrics_expires_at` | 用途独立判期（§13 用途隔离）：日志停、指标继续（metrics-only 照常、dual 投影 `purposes=["metrics"]`）；对称方向亦然。每项用途的有效截止取自身截止与公共 `expires_at` 的较早值（§8） |
| C12 | 先经场景 E 或真实异常触发过 error 详情，再把 `per_fingerprint_max_per_minute` 改为 `0`（revision+1） | 窗口内收紧立即生效（§11.1）：0 后不再新增 error 详情与限频摘要；critical 通道的 error 计数事实不受影响。注意：自检 fault_id 是跨重复报告的去重键——**同一 IDE 进程内再次触发不会重发 error 事实**（计数与详情都不发），检验 C12 须改完限频后重启 IDE 用全新进程触发 |
| C13 | 改用 §3.2「显式仅 v1」变体（revision+1），随后触发任一 WARN/ERROR（如场景 B 的 file-search 坏响应） | 无任何 v2 `diagnostic.*` 行落盘；v1 指标（`rpc`/`edt.delay` 等）与 error 计数照常——v2 门只抑制诊断，不连带抑制 v1。再改用「启用 v2」变体（revision+1）：同一 run 存续、seq 连续，新触发故障的 `diagnostic.*` 行 `schema_version=2.0` |
| C14 | 对已启用 v2 的会话把 `accepted_fact_schema_majors` 改回 `[1]`（revision+1） | ≤ 45 秒后不再新增 v2 诊断行；已落盘的 v2 行保留可读；v1 照常。缺省（删掉该键，revision+1）效果等同 `[1]` |

## 7. 场景 D：崩溃、追加文件与残留

| # | 操作 | 预期 |
|---|---|---|
| D1 | IDE 运行中强杀：从任务管理器取得 PID 后 `taskkill /PID <pid> /F` | 当前 `<scope-id>.jsonl` 可能留下无 LF 的残缺尾行；无 `plugin.shutdown`，不把强杀误记为插件主动退出 |
| D2 | 重新启动同一 IDE | 复用同一个 `<scope-id>.jsonl`，行内写入新的 `producer_id` 和 `run_id`，`device_id` 保持安装身份；插件启动时 `UncleanDetector` 扫描该 scope 文件，若前任 run 有 `plugin.started` 但无同 run `plugin.shutdown`，同一文件应追加一条 `plugin.unclean`，核对 `previous_run_id` 和受控 `evidence=no_shutdown_after_started`；读取端仍应跳过残缺尾行并计入 health。诊断专项落地后还应核对增强证据：前一 run 的末尾 seq、最后事实时间、最后 writer flush 时间、未完成 operation ID 列表（最多 32 个；更多时经 payload 分片保存，按 incident 重组核对） |
| D3 | （可选）在 outbox 放入同 scope 的旧布局 `<scope-id>-<producer-id>.jsonl`，另放入不同 scope 的同类文件后启动 IDE | 启动即删除同 scope 的旧 producer 文件，不迁移其中事实；不同 scope 的旧文件不得删除。当前 `<scope-id>.jsonl` 继续按正常语义追加 |
| D4 | 容量（一般跳过） | 每 IDE 的 `<scope-id>.jsonl` 事实文件上限 50MiB（诊断专项落地后的默认；此前为 10MiB）；超限淘汰按优先级先保 failure 记录及其完整分片组，淘汰计入 health 按原因丢弃计数，不阻塞 IDE 操作 |
| D5 | （可选）IDE 运行中删除当前 `<scope-id>.jsonl` 文件 | 下次追加时按同一 scope 文件名重建，不视为错误；采集继续、IDE 不受影响（§7.4 容忍清理方删除） |

## 8. 场景 E：全字典覆盖（所有类型的指标与日志）

目标：**31 个登记事件名、7 种 kind、两通道、三类用途投影全部在盘上出现**。自然业务流只能稳定产生约 20 个名字（迁移、协议错误、异常族、释放风险等需要特定故障），其余经插件自检动作补齐。自检走与业务观测完全相同的采集管线（准入→队列→writer→追加 outbox），不是旁路写入。口径说明：31 名为 v1 全字典；v2 的 `diagnostic.reported`/`diagnostic.payload`/`diagnostic.redaction_failed` **不由自检产生**，由场景 G 以真实故障核对。

### 8.1 准备

1. 控制文件用指南 §3.1 双用途模板（须含 diagnostic 类别，否则 error 详情不落盘）；
2. `Help → Edit Custom VM Options...`，追加一行 `-Dcostrict.stability.selftest=true`，保存并重启 IDE（Windows 下生成的文件是 `%APPDATA%\JetBrains\<产品><版本>\idea64.exe.vmoptions`；手工编辑写成 `idea.vmoptions` 不会被读取）；
3. 确认采集已激活（场景 A 的 A2~A5）。

### 8.2 触发隐藏自检动作 `Kilo.StabilitySelfTest`

该动作是设计上的隐藏入口：不挂任何菜单、默认 no-op（无系统属性时误触发也不产生事实）、**触发成功也无任何 UI 反馈**——判定是否生效只能靠查盘（触发后 ~40 秒内 outbox 应出现自检事实）。人工触发按顺序尝试：

- **方式一**：双击 Shift（Find Action）搜索 "Stability"。当前构建未给动作设置标题，大概率搜不到——搜不到属正常，转方式二。
- **方式二（确定可用）**：为它挂快捷键——
  1. `Settings → Keymap`，复制当前 keymap（Duplicate），命名如 `stability-test`，应用；
  2. 关闭 IDE；
  3. 编辑 `%APPDATA%\JetBrains\<产品><版本>\keymaps\stability-test.xml`，确保内容包含（元素必须是 `keyboard-shortcut`，写成 `shortcut` 会被静默忽略）：
     ```xml
     <keymap version="1" name="stability-test" parent="$default">
       <action id="Kilo.StabilitySelfTest">
         <keyboard-shortcut first-keystroke="control alt shift F12"/>
       </action>
     </keymap>
     ```
  4. 启动 IDE，确认 Keymap 选中 `stability-test`，按 `Ctrl+Alt+Shift+F12` 触发。

### 8.3 触发后核对

多数自检操作和直接构造的事实带 `context.workspace_id="ws-selftest"`。例外：error 族不带 workspace，以显式 `fault_id`（`fault-selftest-reported`/`fault-selftest-uncaught`）辨识；`resource.snapshot` 是进程级资源计数，不带 workspace，按触发时间及 `resource` 对照；真实 `StallMerger` 生成的 `edt.stall` 也不带 workspace，以 `data.observation_id="obs-selftest"` 和 `duration_ms=2500` 辨识。不要只筛选 workspace，否则会漏掉这些自检事实。

1. 触发后等 ≤ 40 秒（入队 + flush），按指南 §11 汇总 name 分布；
2. 对照 §8.4 清单：28 个自检名字全部出现；`plugin.started`/`plugin.shutdown`/`telemetry.health` 由服务和后台自然产出——合计 31/31；
3. 明确核对 `edt.stall`：由真实 `StallMerger` 合并首尾相接样本产出，`duration_ms` 达到至少 2 秒，不能用手写 Draft 代替；
4. 7 种 kind 齐备；critical/diagnostic 两通道事实都出现在同一追加文件中；
5. 三类用途投影齐备：metrics-only（如 `rpc`）、logs-only（error 详情形态）、dual（如 `connection`）；
6. error 族两形态分道：`error.reported`/`error.uncaught` 的**计数形态**在 critical 通道、`purposes=["metrics"]`；**详情形态**（`message`/`frames`/`count`）在 diagnostic 通道、`purposes=["logs"]`；两者经同一 `fault_id` 关联；
7. 正常关闭后：追加文件最后有 1 条 `plugin.shutdown(app_close)`；
8. 全量跑一遍指南 §11 线格式校验。

说明：`plugin.unclean` 由插件下一实例启动时的 `UncleanDetector` 扫描前任文件生成；自检另外落盘一条**`plugin.unclean` 形状验证事实**（`previous_run_id=run-prev-selftest`），不是崩溃结论。真实启动检测须核对 `previous_run_id` 与固定 evidence token；`ide.operation` 的 `apply_edit` 当前无业务入口，自检用 `vfs_refresh` 代表。

### 8.4 覆盖核对表（31 name × 形态 × 来源）

| name | kind | 通道 | 用途 | 人工来源 |
|---|---|---|---|---|
| plugin.started | lifecycle | critical | dual | 自然 |
| plugin.shutdown | lifecycle | critical | dual | 自然 |
| plugin.unclean | lifecycle | critical | dual | 自然（下一实例启动检测）+自检（形状） |
| toolwindow.setup | operation | critical | dual | 自然（开工具窗）+自检 |
| backend.load | operation | critical | dual | 自然+自检 |
| plugin.readiness | operation | critical | dual | 自然+自检 |
| connection | operation | critical | dual | 自然+自检 |
| connection.attempt | operation | critical | dual | 自然+自检 |
| connection.state_changed | transition | critical | dual | 自然（断连）+自检 |
| connection.recovery | operation | critical | dual | 自然（恢复）+自检 |
| csc.install | operation | critical | dual | 自然（装 daemon）或自检 |
| csc.start | operation | critical | dual | 自然+自检 |
| credentials.ready | operation | critical | dual | 自然+自检 |
| cli.download | operation | critical | dual | 自然（CLI 缺失）或自检 |
| migration.required | transition | critical | **metrics** | 自检 |
| session.open | operation | critical | dual | 自然+自检 |
| session.restore | operation | critical | dual | 自然+自检 |
| action | operation | critical | dual | 自然（发消息等）+自检 |
| availability | interval | critical | **metrics** | 自然+自检 |
| error.uncaught | diagnostic | critical=计数 / diagnostic=详情 | metrics / **logs** | 自检 |
| error.reported | diagnostic | critical=计数 / diagnostic=详情 | metrics / **logs** | 自检 |
| protocol.error | diagnostic | critical | dual | 自检 |
| telemetry.health | health | critical | dual | 自然 |
| session.dispose_risk | transition | critical | **metrics** | 自检 |
| rpc | operation | critical | **metrics** | 自然+自检 |
| edt.delay | sample | critical | **metrics** | 自然+自检 |
| edt.stall | sample | critical | **metrics** | 自检（真实 StallMerger）+自然 |
| edt.violation | diagnostic | critical | dual | 自检 |
| render.apply | sample | critical | **metrics** | 自然（发消息）+自检 |
| ide.operation | operation | critical | dual | 自然（diff 等）+自检 |
| resource.snapshot | sample | critical | **metrics** | 自然+自检 |

## 9. 场景 F：多实例与覆盖边界

设计文档第 13/14 章把实例隔离与覆盖声明列为交接验收事项。F1 常做；F2/F3 视环境可选，未执行时在记录中标注"未验证"，不得默认宣称对应覆盖。

| # | 操作 | 预期 |
|---|---|---|
| F1 | 同时运行两个不同 IDE 安装范围（如两个不同产品），均等待采集激活 | 每个 IDE 各有一个 `<scope-id>.jsonl` 文件，scope-id 互不相同；文件各自独立追加、互不接管，关闭其一不影响另一个。每个文件内以行内 `producer_id`/`run_id` 区分生命周期；旧布局清理只会删除自身 scope 的 producer 后缀文件。共享同一配置目录的多 JVM 运行依赖 IDE 单实例约束，不在 v1 支持范围，不能作为本场景的并发验收 |
| F2 | （可选，需 Split Mode 远程开发环境）前端机器不部署 cs-cloud，仅 backend 机器部署 | backend 机器 outbox 正常落盘；前端机器无本机控制文件，按无有效策略默认采集（`account_epoch=unbound`、`policy_revision=0`）落盘**本机** outbox；两侧事实的 `mode`/`side` 如实标注；覆盖状态明确"前端未接入"，不得把缺失当无故障、不得宣称完整链路（§5.1） |
| F3 | （可选）cs-cloud 配置非默认 `data-dir`/`auth-path` 后重启 daemon，默认控制文件仍保留且有效 | 插件不回退使用默认控制文件、不读取另一 profile 凭据（§5.2）：表现为默认控制文件缺失，按无有效策略处理（`unbound`、`policy_revision=0`、默认不限制采集），新事实不携带默认 profile 的 `policy_revision`/`account_epoch` |

## 10. 场景 G：v2 高保真诊断与 outbox 单一来源排障

目标：验证诊断设计的核心验收——**仅凭 outbox 副本即可解释代表性插件故障**（异常类型、原始 message、完整堆栈、路径/载荷、HTTP/RPC 上下文与关联 operation），不需要任何其他日志文件。

前置：

1. 安装包含诊断专项的构建（见文首"版本适用"）；
2. 控制文件用指南 §3.1 双用途模板 + §3.2「启用 v2 诊断」变体（`accepted_fact_schema_majors: [1,2]`，须含 diagnostic 类别与 logs 用途）；
3. 可选：准备合成凭据（假 JWT `eyJ…`、假 Cookie、假 password 字符串）注入到会触发失败落盘的业务流程，验证脱敏；无法注入时在记录中标注"未注入合成凭据"。

| # | 操作 | 预期（核对点） |
|---|---|---|
| G1 | 启用 v2 控制文件，等待采集激活（同 A2~A5） | 采集正常；触发故障前无 `diagnostic.*` 属正常 |
| G2 | 触发 JSON 解码失败（同场景 B「file-search 坏响应」手段） | 1 条 `diagnostic.reported`（`severity=error`、`code=json_decode_failed`、`kind=diagnostic`、`channel=diagnostic`、`purposes=["logs"]`、`schema_version=2.0`）+ ≥1 条 `diagnostic.payload`（`payload_kind=response`、`encoding=utf8`）；主记录含 `json_path`/`expected_type`/`actual_type`、`thread_name`/`thread_id`、`payload_refs`、`truncated`；主记录 `context.incident_id` 存在，且 `operation_id` 与失败 `rpc` end 的相同 |
| G3 | 触发 HTTP 404（真实环境曾现于 recent-session / telemetry capture 端点缺失；可用旧版 daemon 复现） | `diagnostic.reported`（`code=http_not_found`）含 `route`（无查询参数）与 `http_status=404`；请求/响应载荷在分片中；header 中的凭证（如有）呈 `<redacted:…>` 标记 |
| G4 | 触发 MCP 绑定失败（502/超时，同场景 B 手段） | `diagnostic.reported`（`code=ide_capability_bind_failed`）与 `ide.operation(mcp_register)` 的 end 共享同一 `operation_id`；完整堆栈在分片中可重组（指南 §11 #10） |
| G5 | 杀 daemon/断网触发连接类失败 | 分类码为具体类别（socket/connect/timeout 之一），不再一律 `other`/`unknown`；与 `connection` 终态同 run 可关联 |
| G6 | 制造一次真实 ≥2 秒 EDT 阻塞（如超大文件操作、调试器挂起 EDT；无法稳定触发则标注"未验证"） | `edt.stall`（`duration_ms` ≥2000）经 incident 关联到含堆栈的 diagnostic 记录；堆栈为**阻塞期**采样（线程名形如 `AWT-EventQueue…`），含完整帧与线程标识 |
| G7 | 复用场景 D 强杀→重启流程（v2 启用） | `plugin.unclean` 带增强证据：前一 run 末尾 seq、最后事实时间、最后 writer flush 时间、未完成 operation ID（≤32 个）；证据可经 `incident_id` 关联，D2 增强核对同此 |
| G8 | 正常关闭 IDE，**只复制 outbox 文件**到空白目录核对 | G2~G7 每个故障仅凭该副本可完整解释：类型、原始 message、完整堆栈（含 cause/suppressed 链）、路径/载荷、关联 operation；全程不读 `kilo.log`/`idea.log` |
| G9 | 混合版本与凭据扫描 | 同一文件内 v1 行 `schema_version=1.0`、v2 行 `2.0` 逐行可解析，指南 §11 校验全绿（含 event_id 唯一、seq 连续）；凭据特征扫描零命中；`<redacted:type>` 标记（如有真实敏感字段经过）格式合规 |
| G10 | （可选）构造 >1MiB 载荷（daemon 返回大响应） | 主记录与分片 `truncated=true`；分片保留头尾各 512KiB；`original_bytes` 为完整原始长度、`sha256` 为完整原文哈希；难以构造时标注"未验证" |

补充核对：

- **主记录必备字段**：`severity`、`component`、`code`、`message`、`thread_name`、`thread_id`、`payload_refs`、`truncated` 恒在；`component` 受控非空（形如 `backend.rpc`/`rpc.<group>`）；
- **分片重组**：任取一个 incident，按 `chunk_index` 升序拼接 `content`，字节数等于 `original_bytes`、SHA-256 等于 `sha256`（指南 §11 #10）；
- **去重与限频**：同一异常被反复记录时共享同一 incident，不逐条重复展开完整载荷；既有 per-fingerprint 限频（§3.1 `log_detail_rate_limit`）对诊断详情继续生效；
- **失败保留**：连续制造多个失败后观察文件重写/清理，故障主记录与其分片组同存同亡，不出现孤儿分片；failure 不被周期采样挤出；
- **health 质量**：`telemetry.health` 出现按原因丢弃计数与 `quality` 字段；未注入饱和故障时 `quality=good`（饱和/淘汰的量化验收由自动化 E2E 覆盖，人工只做字段存在性与取值合理性的抽查）；
- **`diagnostic.redaction_failed`**：仅在过滤器自身失败时出现，且不含任何原文；人工无常规触发手段，出现即按形状记录，正确性由自动化测试覆盖。

判定：G1~G5、G7~G9 全部符合（G6/G10 及"未注入合成凭据"等子项可标注"未验证"，须记录）→ 场景 G 通过。

## 11. 落盘校验工具箱

以下 PowerShell 片段在 telemetry outbox（`%USERPROFILE%\.costrict\telemetry\outbox\`）执行；`$facts` 为入口，先定义一次。片段对 v1/v2 混合文件通用，v2 专属项已标明。

```powershell
# 读入所有追加式事实文件（容忍最后一条未写满的行）
$files = Get-ChildItem -File -Filter *.jsonl | Sort-Object FullName
$facts = foreach ($f in $files) {
  $text = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
  $lines = $text.Split([char]10)
  # 最后一项在末尾有 LF 时为空，否则是未完成尾行；两种情况均不解析。
  for ($i = 0; $i -lt $lines.Length - 1; $i++) {
    if ([string]::IsNullOrWhiteSpace($lines[$i])) { continue }
    try { $lines[$i] | ConvertFrom-Json -ErrorAction Stop }
    catch { Write-Error ("{0}:{1}: 完整 NDJSON 行格式错误" -f $f.FullName, ($i + 1)) -ErrorAction Continue }
  }
}

# 1) name 分布（对照 §8.4；v2 启用时另应出现 diagnostic.* 三名）
$facts | Group-Object name | Sort-Object Name | Format-Table Name, Count -AutoSize

# 2) seq 连续性：按 run+channel 应从 1 连续无缺口（策略切换窗口内允许缺口；v2 行计入所在通道）
$facts | Group-Object run_id, channel | ForEach-Object {
  $seq = @($_.Group.seq | Sort-Object -Unique)
  $ok = -not (Compare-Object $seq (1..$seq.Count))
  "{0}  {1} 条  seq {2}" -f $_.Name, $seq.Count, ($ok ? '连续' : '!!! 缺口')
}

# 3) event_id 全局唯一（期望 0）
@($facts | Group-Object event_id | Where-Object Count -gt 1).Count

# 4) 字段闭集（期望 0）
$allowed = 'schema_version','event_id','timestamp','producer_id','run_id','channel','seq',
  'account_epoch','policy_revision','purposes','source','device_id','plugin_version','ide_product',
  'ide_build','ide_build_major','os_family','arch','env','mode','side','connection_provider',
  'kind','name','context','data'
@($facts | Where-Object { @($_.PSObject.Properties.Name | Where-Object { $_ -notin $allowed }).Count }).Count

# 5) context 键闭集（期望 0；incident_id 供 v2 诊断行使用）
@($facts | Where-Object { $_.context -and @($_.context.PSObject.Properties.Name |
  Where-Object { $_ -notin 'operation_id','attempt_id','fault_id','trace_id','workspace_id','incident_id' }).Count }).Count

# 6) 单条记录 ≤ 32KiB（v1/v2 通用，期望 0）
@($facts | Where-Object { ($_.PSObject.Properties | ForEach-Object { $_.Value } | ConvertTo-Json -Compress -Depth 5).Length -gt 32KB }).Count

# 6a) v1 行 data 值无路径分隔符（期望 0；v2 诊断行按设计允许出现路径，勿套用本项）
@($facts | Where-Object { $_.schema_version -eq '1.0' -and $_.data.PSObject.Properties.Value -match '[/\\]' }).Count

# 6b) v1 diagnostic 详情 message 安全摘要 ≤512 字节（期望 0；v2 主记录 message 不受此限，受 32KiB 行限约束）
@($facts | Where-Object { $_.schema_version -eq '1.0' -and $_.data.message -and [Text.Encoding]::UTF8.GetByteCount($_.data.message) -gt 512 }).Count

# 7) BOM / CR / LF 结尾逐文件检查（期望全 False/False/True）
Get-ChildItem -File -Filter *.jsonl | ForEach-Object {
  $b = [IO.File]::ReadAllBytes($_.FullName)
  '{0}  BOM={1}  CR={2}  LF结尾={3}' -f $_.Name,
    ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF),
    ([Text.Encoding]::UTF8.GetString($b) -match "`r"),
    ($b.Length -eq 0 -or $b[-1] -eq 0x0A)
}

# 8) 安全抽查：无 Token/凭据特征（期望无输出；模式对 v1/v2 都适用）
Select-String -Path (Get-ChildItem -File -Filter *.jsonl).FullName -Pattern 'eyJ|Bearer |AKIA|password=|Set-Cookie|BEGIN [A-Z ]*PRIVATE KEY'

# 8b) v2 脱敏标记抽查：命中行应为 <redacted:type> 形态、不残留原文（看前 5 条样例）
Select-String -Path (Get-ChildItem -File -Filter *.jsonl).FullName -Pattern '<redacted:[a-z-]+>' | Select-Object -First 5

# 9) v2 行 schema_version 一致（三个 diagnostic.* 名应为 2.0，期望 0）
@($facts | Where-Object { $_.name -like 'diagnostic.*' -and $_.schema_version -ne '2.0' }).Count

# 10) incident 分片完整性：每 incident 的分片索引 0..count-1 无缺、无重、无越界（期望全"完整"）
$facts | Where-Object { $_.context.incident_id } | Group-Object { $_.context.incident_id } | ForEach-Object {
  $chunks = @($_.Group | Where-Object name -eq 'diagnostic.payload' | Sort-Object { [int]$_.data.chunk_index })
  if ($chunks.Count -eq 0) { return }
  $count = [int]$chunks[0].data.chunk_count
  $idx = @($chunks | ForEach-Object { [int]$_.data.chunk_index } | Sort-Object -Unique)
  $ok = ($idx.Count -eq $count) -and ($idx[0] -eq 0) -and ($idx[-1] -eq ($count - 1))
  '{0}  分片 {1}/{2}  {3}' -f $_.Name, $chunks.Count, $count, ($ok ? '完整' : '!!! 缺片/重复/越界')
}

# 10b) 文本分片重组抽查：按序拼接后字节数=original_bytes 且 SHA-256=sha256（仅抽 utf8 分片；base64 需先解码，PowerShell -eq 比对大小写不敏感）
$g = $facts | Where-Object { $_.name -eq 'diagnostic.payload' -and $_.data.encoding -eq 'utf8' } |
  Group-Object { $_.context.incident_id } | Select-Object -First 1
if ($g) {
  $chunks = @($g.Group | Sort-Object { [int]$_.data.chunk_index })
  $bytes = [Text.Encoding]::UTF8.GetBytes((($chunks | ForEach-Object { $_.data.content }) -join ''))
  $sha = [BitConverter]::ToString([Security.Cryptography.SHA256]::HashData($bytes)).Replace('-', '').ToLowerInvariant()
  'incident {0}  字节 {1}/声明 {2}  SHA-256 匹配={3}' -f $g.Name, $bytes.Length, $chunks[0].data.original_bytes, ($sha -eq $chunks[0].data.sha256)
}
```

git-bash + jq 等价速查：

```bash
cat *.jsonl | jq -r .name | sort | uniq -c                      # name 分布
cat *.jsonl | jq -r .event_id | sort | uniq -d | wc -l          # 期望 0
cat *.jsonl | jq -r 'select(.context.incident_id) | .context.incident_id' | sort | uniq -c   # incident 分布
head -c 3 *.jsonl | xxd                                          # 无 ef bb bf
```

人工目检一条完整事实（公共字段样例，节选自真实落盘）：

```json
{"schema_version":"1.0","event_id":"524904a3-...","timestamp":1789986389197,"producer_id":"pr-315ff953b957","run_id":"run-0535bec98321","channel":"critical","seq":40,"account_epoch":"acct-manual-01","policy_revision":1,"purposes":["metrics","logs"],"source":"jetbrains-plugin","device_id":"device-395b141f586b","plugin_version":"1.0.0-rc.1","ide_product":"IU","ide_build":"IU-261.22158.277","ide_build_major":"2026.1","os_family":"windows","arch":"x64","env":"prod","mode":"monolith","side":"monolith","connection_provider":"cs-cloud","kind":"operation","name":"plugin.readiness","context":{"operation_id":"0533fe56-..."},"data":{"phase":"end","result":"blocked","duration_ms":2387,"stage":"readiness","cause":"environment","error_code":"none","reason":"credentials_missing"}}
```

## 12. 判定标准与记录

### 12.1 通过标准汇总

| 场景 | PASS 条件 |
|---|---|
| A 冷启动 | A1~A9 全部符合；追加文件和身份字段正确 |
| B 业务事实 | 每行操作的预期事实落盘且专项字段合规；无路径/凭据泄露 |
| C 策略 | C1~C12 全部符合；各阶段事实 `policy_revision` 与当时控制文件一致 |
| D 崩溃残留 | D1/D2 符合（D3/D5 可选、D4 跳过）；诊断专项构建上 D2 增强证据与 50MiB 预算语义生效 |
| E 全字典 | 31/31 name、28 个自检名字、7 kind、两通道、三投影齐备；包含 `edt.stall`，error 双形态同 fault_id；线格式校验全绿 |
| F 多实例/覆盖 | F1 符合；F2/F3 可选，未执行时记录"未验证" |
| G v2 诊断 | G1~G5、G7~G9 符合（G6/G10 等可选子项未执行时记录"未验证"）；outbox 单副本可解释全部触发故障；凭据扫描零命中 |

### 12.2 记录模板

每个场景一行：`场景 | 插件版本/IDE 构建 | 关键操作时间点 | 盘上证据路径 | 结论（PASS/FAIL+现象）`。建议把 outbox 文件和当时的控制文件复制留档。场景 G 留档前先跑一遍 §11 #8 凭据扫描。

### 12.3 已知边界（判定时不要误报缺陷）

- 强杀 IDE 不承诺零丢失；无 LF 的尾行由消费端跳过并计入 health（§7.1、§7.3）。
- 没有 `plugin.shutdown` ≠ 一定是插件崩溃；下一插件实例的 `UncleanDetector` 负责生成 `plugin.unclean`，其 `evidence` 仅说明缺少受控 shutdown 证据（§7.3）。
- 撤销许可不写 `plugin.shutdown` 是设计行为（§8）。
- 策略切换窗口内 seq 允许缺口；稳态丢号才是问题。
- diagnostic 事实与 critical 事实写入同一追加文件，按 writer flush 节奏可见。
- 无有效策略期间默认不限制采集，但凭据缺失仍须以 `availability=blocked` 表达（§3.3）。
- 显式 `pending`/`disabled` 与策略过期同属显式限制，与"无有效策略默认不限制"的 fail-open 相区别（§3.3、§8）。
- 系统休眠/暂停不做死亡推断；探针中断样本以非 `valid` validity 收尾并轮换 `observation_id`，不跨区间拼接卡顿（§10.3）。
- 24 小时是可交接保留期，不是无人运行时的物理删除保证：插件与 daemon 均停用期间无法清理，恢复运行后的首轮清理删除过期残留（§7.4）。
- 控制文件未含 `accepted_fact_schema_majors:2` 时无 `diagnostic.*` 是设计行为（v1 照常），不是缺陷；该门只抑制诊断，不抑制 v1 指标。
- 单 incident 载荷预算 1MiB：超出保留头尾各 512KiB 并置 `truncated=true`、附完整原文 SHA-256 与原始长度；截断不是缺陷，缺片/孤儿分片才是。
- 过滤器失败不 fail open：只落不含原文的 `diagnostic.redaction_failed`，此时拿不到该故障的载荷详情属预期。
- WARN/ERROR 镜像与显式边界报告均无 UI 反馈，判定一律查盘；INFO/DEBUG 不镜像，collector 自身日志不回灌。
- 队列饱和时被牺牲的是 sample/critical 档，failure 不被驱逐；饱和与并发保留的量化验收在自动化 E2E，人工只做抽查。
- scope 文件预算 50MiB 为诊断专项落地后的默认；旧构建产生的超 10MiB 历史文件在新构建下按新预算继续追加。

## 13. 测试后清理

1. 删除 `%USERPROFILE%\.costrict\telemetry\control\jetbrains.json`（避免残留许可）；
2. 删除测试产生的 `outbox\*.jsonl`（或等 24 小时保留期后由后台清理）；
3. 若改过自定义 VM Options，移除 `-Dcostrict.stability.selftest=true`（防止后续误产自检事实）；若新建了 keymap，切回原 keymap；
4. 场景 G 若注入过合成凭据：确认留档副本通过指南 §11 #8 扫描（零命中或仅 `<redacted:>` 标记）后再归档；
5. 留档证据移至团队证据目录后再清理原位置。
