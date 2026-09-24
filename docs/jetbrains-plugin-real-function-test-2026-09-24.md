# Costrict JetBrains 插件真实功能测试报告（2026-09-24）

对已安装插件做真实功能测试（非 mock、非沙箱）。范围依据：

- [MVP 设计](./superpowers/specs/2026-08-22-costrict-jetbrains-mvp-design.md)（对话/审批/落盘闭环）
- [IDE MCP 能力桥设计](./superpowers/specs/2026-08-25-costrict-jetbrains-ide-mcp-bridge-design.md)（绑定/工具/审批/撤销）
- 稳定性设计按用户指示**跳过**（指标/日志/遥测验证不在本轮范围）

## 环境

| 项 | 值 |
|---|---|
| 插件 | `ai.costrict.jetbrains` **1.0.0-rc.3**（IntelliJIdea2026.2，monolith） |
| IDE | idea64.exe，打开项目 = 本 worktree（`jetbrains-stability-observability`） |
| daemon | cs-cloud `0.0.0-dev` 本地构建，`http://127.0.0.1:18443`，health 声明 `conversation_ide_capability_v1` |
| 模型 | 成功路径 `costrict/GLM-5.3-Flash-Zhipu`；`CoStrict-GLM-5-Local` 当日持续 503/server_error |
| 方法 | 人工键鼠（发 prompt/审批/删除）+ 自动采证（cs-cloud API、daemon app.log、idea.log、JetBrains MCP 复核） |

## 结论摘要

核心链路（会话闭环、审批、文件落盘、VFS 刷新、MCP 桥绑定与工具调用）**真实工作**；发现 **2 个 P1 缺陷**（status DTO `projectID` 解析失败；删除会话不触发 capability 撤销），两者疑似构成因果链，导致设计要求的"idle/删除 → 撤销"闭环断裂。

## 通过项与证据

### A. 连接发现与生命周期（MVP）

| # | 项 | 证据 |
|---|---|---|
| A1 | server_url 发现 + loopback 校验 | resolver 读 `~/.costrict/cs-cloud/server_url`；health 200 |
| A2 | daemon 停止的类型化诊断 | idea.log 10:34:07 与 11:24:06：`cs-cloud daemon is not running - start it with 'csc cloud start' (or use the Start action in the retry menu)` |
| A3 | 失败后自动重试恢复 | 11:24:10 `Application start failed: Failed to load config`（3 次退避）→ 11:24:22 `retryAsync: launching retry` → 11:24:33 SSE 重建 `Started watching global SSE events` |
| A4 | SSE 事件三层传播 | `chat-events → rpc-events → client-events`（backend→rpc→client）日志逐条可见 |
| A5 | 错误事件展示 | 模型 503 `session.error`（`APIError code=503 upstream connect error`）完整传播；用户在 UI 看到报错（多次失败会话） |

### B. 会话闭环（MVP 验收 1-4）

| # | 项 | 证据 |
|---|---|---|
| B1 | 会话创建绑定 workspace | 插件 `POST /session?directory=<worktree 根>`（会话 directory = project basePath，精确匹配） |
| B2 | prompt 携带编辑器上下文 | `agent=build model=... editorContext=true activeFileHash=875988b openTabs=2 visibleFiles=1 shell=cmd.exe` |
| B3 | ReAct 事件流 UI | 截图证据：用户消息卡、推理折叠卡、工具调用展开卡（Skill/SearchExtraTools/Read/ExecuteExtraTool/Edit）、结果文本、状态栏 `Build ★ / Costrict / GLM-5.3-Flash-Zhipu` |
| B4 | 权限审批闭环 | daemon log 11:26:21 `permission.asked`（5s 批窗口）→ 用户 Allow → 11:26:24 `POST /permissions/fbd1d62b.../reply 200`；两次 Edit 各一次审批，批准后继续执行 |
| B5 | 文件落盘 + VFS 刷新 | `Broken.java` 两处被修复（`a+b+1→a+b`、语法错误→`a*b`）；csc 侧复查 `errors: []`；本测试用 JetBrains MCP 独立复核 `get_file_problems` 亦为空（IDE 索引/诊断实时刷新） |
| B6 | 工具窗口 diff 统计 | UI 头部显示 `394 个文件 · +1248 -49738`（变更统计跟踪） |

### C. IDE MCP 能力桥（桥接设计验收 1-3、6-8 部分）

| # | 项 | 证据 |
|---|---|---|
| C1 | 无配置 MCP client 即可用 | 用户零配置；csc 侧工具名 `mcp__costrict-jetbrains__get_file_problems` / `__get_run_configurations`（私有授权会话绑定成功） |
| C2 | MCP 诊断工具真实调用 | 第一轮 prompt：`SearchExtraTools` 发现 → `ExecuteExtraTool(get_file_problems, errorsOnly:true)` 返回 1 个编译错误（`应为表达式` line 11） |
| C3 | 文件修改走 csc 原有工具链 | 修复使用 `Edit`（csc 原生文件工具 + 审批），**未出现任何 MCP 写工具调用**（`create_new_file/apply_patch` 未使用）✅ 设计约束 |
| C4 | 第二轮 prompt 工具连续可用 | 11:32:36 第二次 prompt RPC → `mcp__costrict-jetbrains__get_run_configurations` 返回 4 个真实 run config（Split Mode/Backend/Frontend），与 IDE 实际配置一致 |
| C5 | 凭证脱敏日志 | bind 失败日志仅记 `conversation=8d86db69c8be generation=d1519b25b703`（12 位摘要）+ 稳定错误码；无 token/完整 headers |
| C6 | bind 失败路径 | 昨日 15:00 `IDE MCP bind failed ... HTTP 502`（真实失败被捕获并记录，不崩溃） |

## 缺陷清单

| # | 级别 | 现象 | 证据 | 影响 |
|---|---|---|---|---|
| D1 | **P1** | status 种子解析失败：csc 返回 `projectID` 为字符串，插件 DTO 期望对象 | idea.log 多次：`kind=status seed=true failed Unexpected JSON token ... path: $['projectID']`（11:22/11:23/11:24/11:37 持续复现） | 会话状态映射拿不到 → `recoverPending` 第 3 优先级失效；**疑似阻断 idle → 撤销链路**（csc 每 12s 广播 `{"type":"idle"}`，插件侧因解析失败无法感知） |
| D2 | **P1** | 删除会话不触发 capability 撤销 | 11:39:00 删除 742e0a7d（有活跃绑定）：插件日志有 `delete session`，daemon proxy 仅 1 条会话 DELETE、**无 `DELETE .../capabilities/ide` 请求**，双侧日志均无 release/revoke 行 | 设计"删除 conversation 触发撤销"未实现或未生效；lease/token 残留有效期至代次更替 |
| D3 | P2 | 删除会话后列表不即时刷新，需重新进入会话历史才消失 | 用户实测报告 | UI 状态一致性 |
| D4 | P3 | `session.created` / `session.updated` SSE 事件 `parse=null` | idea.log 11:22:44 等 | 已知事件类型未被 DTO 映射（不阻塞，但属协议面缺口） |
| D5 | P3 | conversations 列表 API `message_count` 恒 0（详情接口正常） | API 对比（列表 0 / 详情 51） | cs-cloud 侧字段未实现，误导诊断 |
| D6 | P3 | 会话完成后 conversations API `status` 长期 `running`，与 csc 内部广播 `idle` 不一致 | csc stderr status 广播 vs API | 状态语义混淆，放大 D1/D2 的排查难度 |
| O1 | 观察 | capability 撤销失败仅发生在 daemon 重启窗口（`url.Error`，agent 未就绪） | 09:23:29 / 11:24:01 三条 WARN | 设计允许（本地 token 已失效），但 daemon 侧无后续重试/对账痕迹 |

## 未测/绕过项

| 项 | 原因 |
|---|---|
| build_project 控制通道审批、execute_run_configuration 原生确认 | 待用户确认执行（构建耗时长）；本项目 run configs 均为 IDE 启动器，执行代价高 |
| 旧 token 401 验证（验收 7 后半） | token 按设计不落盘不外泄，外部无法取到 |
| 工具禁用尊重（Exposed Tools 交集，验收 10） | 需改平台设置 UI，未安排 |
| 双 Project 双 conversation（验收 9） | 需第二个打开的项目，成本高 |
| 非 loopback / 错误 key 诊断分流 | 需篡改真实 `server_url`/config，影响在用环境；resolver 代码路径 + 既有 T1 覆盖 |
| split-mode frontend 不经过 token（验收 11） | 当前为 monolith 环境 |
| 审批卡片内容细节（Q1） | 用户未回应，待补 |

## 事件时间线（关键证据索引）

```text
10:34:07 插件诊断 daemon 未运行（正确错误 + retry 菜单提示）
10:34:21 daemon 起来 → SSE 建立
11:22:44 会话 ffb9ba36 创建 → prompt → server_error×2（模型 CoStrict-GLM-5-Local 故障）
11:23:20 会话 0374aa65 创建 → 同样 server_error
11:24:01 daemon 重启清理 2 个 stale capability（url.Error，agent 未就绪）
11:24:06 插件再次诊断 daemon down → 3 次退避失败 → 11:24:22 自动 retry 成功
11:24:41 会话 7bc807d7 创建（仍 Local 模型，失败）
11:25:08 会话 742e0a7d 创建（GLM-5.3-Flash）→ 成功执行
11:26:21 permission.asked #1 → 11:26:24 reply 200（Edit add）
11:26:31 permission.asked #2 → 11:26:33 reply 200（Edit multiply）
11:26:5x MCP get_file_problems 复查无错误；文件修复完成
11:32:36 第二轮 prompt → get_run_configurations 成功
11:36:49 删除 7bc807d7（DELETE 200，无 capability 撤销——从未绑定，合理）
11:39:00 删除 742e0a7d（DELETE 200，无 capability 撤销 → D2）
```

## 复现与环境注记

- 测试 fixture：`packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/selftest/Broken.java`（带 1 语法错误 + 1 逻辑错误），测试后已删除，worktree 无残留（IDE 索引已确认清空）。
- 当日 `CoStrict-GLM-5-Local` 模型服务间歇故障（503 upstream）是前几次会话失败根因，与插件无关；插件侧行为（错误展示、会话清理、重试）在该故障下均正常。
- 证据文件（本轮临时）：`C:\Users\demo\.claude\tmp\observe.log`（daemon 增量）、`observe-poll.log`（会话轮询）；持久证据在 idea.log 与 `~/.costrict/cs-cloud/app.log`。

## 修复记录（2026-09-24，分支 `feat-cs-plugin`，worktree `.worktrees/feat-cs-plugin`）

D1/D2/D3/D4 根因复核与修复（插件侧；D5/D6/O1 属 cs-cloud daemon 侧，未在本轮处理）：

| # | 根因复核 | 修复 |
|---|---|---|
| D1 | `CsCloudRoute.isConversationDetail` 把 `/api/v1/conversations/status` 的 `status` 当会话 ID，`conversation()` 向状态 map 根部注入 `projectID:""`/`title`/`version`/`time`，生成客户端 `Map<String, SessionStatus>` 在 `$['projectID']` 解码失败（idea.log 错误与注入顺序完全吻合；wire 上 csc 返回的是规范 map） | `isConversationDetail` 排除 `status` 子路径，状态 map 原样透传 |
| D2 | 插件 delete→release 链路本身存在；"无 DELETE capabilities" 部分是观测盲区（daemon 的 capability 路由不经 proxy logger）。真实缺陷：(a) 先删会话后撤销，daemon 的 ClearIDECapability 打向已死会话；(b) 释放成功全程零日志，闭环不可审计（正是本轮误判来源）。注意 SSE `session.status` idle 转换（busy→idle）解析与释放链路正常，报告"疑似因果链"不成立 | `delete()` 改为先 release 后删会话；`CsCloudMcpBridge.release` 记录 `IDE capability released ... reason=... cleared=<daemon回读>`，无 lease 时记 skipped |
| D3 | DELETE `/conversations/{id}` 响应 `{"deleted":true}` 被 detail 转换注入字段后，生成客户端按 `Boolean` 解码失败 → RPC 抛异常 → 前端 catch 走 `local.fail`，行不移除（idea.log 四次删除均无 `ok=true` 完成日志佐证）；重进历史触发 reload 才消失 | DELETE detail 响应解包 `deleted` 字段为裸布尔，删除即时成功 |
| D4 | daemon 全局 SSE 的 `session.created`/部分 `session.updated` 为扁平 ingress 形态（`session_id`/`created_at`/`status`/`title` 顶层，无 `info`/`sessionID` 包装），解析器要求 `props["info"]` → parse=null（live 抓帧证实） | 解析器回退：`info ?: props`，sid 兼容 `session_id`；前端 `SessionUpdated` 事件按字段合并（完整形态 id+directory+time 才整体替换，保留显式 revert 清空语义） |

回归测试：`CsCloudRouteTest`（status map 透传、delete 布尔解包）、`CsCloudMcpBridgeTest`（release 携带 generation + 日志记录 daemon 清理结果）、`KiloCliDataParserTest`（扁平 created/updated 帧）、`KiloSessionRpcApiImplTest`（delete 先释放后删除）、`SessionHeaderControllerTest`（稀疏/部分 SessionUpdated 合并）。
