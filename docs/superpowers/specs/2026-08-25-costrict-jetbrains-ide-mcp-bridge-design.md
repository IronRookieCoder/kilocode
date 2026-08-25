# Costrict JetBrains IDE MCP 能力桥设计

## 背景

Costrict JetBrains MVP 已完成 Plugin -> cs-cloud -> csc 的对话、事件、审批和文件落盘闭环。本设计定义下一阶段 P0：让 csc 通过 JetBrains 内置 MCP Server 使用当前 IDE 的代码分析、符号、项目文件、构建和运行能力。

本阶段不改变 MVP 的模块边界：Costrict 逻辑继续位于 `packages/kilo-jetbrains/cs-cloud/`，通用 backend 只提供最小的 provider 接入点，frontend 和现有 `SessionModel` 不承载 MCP 领域状态。

## 目标

本阶段必须实现以下能力：

1. csc 无需用户配置 MCP 客户端，即可在 conversation 执行期间连接 JetBrains 私有 MCP session。
2. 每个 conversation 获得独立临时 token、固定工具白名单和明确的 JetBrains Project 路径。
3. csc 可使用诊断、符号、搜索、只读文件、构建和运行配置工具。
4. 文件创建、编辑、删除和补丁应用继续走 csc 原有文件工具、路径沙箱和权限链，不建立第二条文件写入通道。
5. conversation 恢复、cs-cloud 重连、IDE 重启、idle、删除和项目关闭均有确定的绑定与撤销语义。
6. 用户在 JetBrains MCP Server 设置中禁用的工具不会被 Costrict 私有会话重新启用。

## 非目标

本阶段不包含：

- `create_new_file`、`apply_patch`、`reformat_file`、`rename_refactoring` 等 MCP 修改工具；
- MCP terminal、通用 tool router、调试器、数据库或 VCS 写能力；
- 自建 MCP 工具或复制 JetBrains 内置工具实现；
- 对恶意或失陷 csc 强制执行 JetBrains Project 路径隔离；
- Agent Manager 中未作为独立 JetBrains Project 打开的 worktree；
- Diff、历史、错误页、连接页和设置入口等 P1 日常可用性改造；
- 事件序号快照、prompt 幂等和完整回放协议；
- 自动修改用户或项目中的第三方 MCP client 配置文件。

## 平台与依据

- 插件最低目标平台保持仓库当前的 JetBrains `2026.1`。
- JetBrains 官方文档说明 MCP Server 自 `2025.2` 起内置，支持 SSE、stdio 和 HTTP Stream，并允许用户管理 Exposed Tools：<https://www.jetbrains.com/help/idea/mcp-server.html>。
- 本设计使用 `idea/2026.1` 标签 `088fc74da710f5a4dc177b4ca539e11b669959f7` 中的 `McpServerService.authorizedSession()`、`McpSessionOptions` 和 `McpToolFilter.AllowList` 行为作为实现基线。
- `McpServerService` 位于 `com.intellij.mcpserver.impl`。它在该基线中未标记 `@ApiStatus.Internal`，但仍属于易变实现包；所有直接引用必须收敛到 `cs-cloud` 模块的单一适配边界。
- 插件通过可选运行时集成使用 JetBrains 捆绑的 `com.intellij.mcpServer`。全局 MCP Server 开关不需要启用；MCP Server 插件被禁用时，Costrict 核心插件仍须可加载并报告 IDE 能力不可用。

## 关键决策

### 使用私有授权会话

每个活跃 conversation 调用 `McpServerService.authorizedSession()` 获取：

- 私有 loopback listener 的实际端口；
- JetBrains 返回的认证 header 名称；
- 该 conversation 独有的临时 token。

私有 listener 可由多个授权会话共享，因此不能用端口表示会话身份。conversation 身份只由 token、会话绑定代次和 cs-cloud conversation ID 共同确定。

传输固定使用 HTTP Stream：

```text
http://127.0.0.1:<port>/stream
```

不采用全局 MCP Server、SSE 或 stdio。全局 Server 依赖用户设置且不提供本设计要求的临时会话认证；stdio 会启动独立 IDE 进程，不适合附加到当前 IDE。

### 文件修改继续走 csc

MCP 只负责 IDE 特有能力。普通文件创建、文本编辑、删除和补丁应用继续走 csc，原因是现有链路已经统一承担：

- 用户审批；
- workspace 写入沙箱；
- conversation 工具事件；
- Diff 归属；
- 断线诊断和审计。

直接开放 MCP 文件写工具会形成第二个修改事实来源，并引入审批重复、Diff 归属不清、并发写冲突和恢复复杂度。`rename_refactoring`、`reformat_file` 等具有 IDE 语义价值的修改能力可在后续独立阶段评估，但前置条件是统一审批、修改事件回传、会话级修改互斥和 Diff 归属均已具备。

### 尊重用户工具设置

有效工具集合为：

```text
Costrict 固定白名单 ∩ JetBrains 当前启用的 Exposed Tools
```

用户明确禁用的工具不会被私有会话绕过。所有工具均被禁用时，conversation 仍可使用普通 csc 对话和文件能力；该情况不属于 MCP 基础设施错误，也不阻止 prompt。

### 信任 cs-cloud 和 csc

`authorizedSession()` 将 token 与工具选项绑定，但不会把 token 与某个 Project 绑定。MCP 客户端仍可提交 `IJ_MCP_SERVER_PROJECT_PATH`。

本阶段明确选择将 cs-cloud 和 csc 纳入可信计算基：插件在绑定时校验并下发固定 Project 路径，但不增加本地反向代理来覆盖每个 MCP 请求的 Project header。因此，会话级项目隔离是受测试的正常行为和协议约束，不是针对恶意 csc 的插件侧强制安全边界。

若未来需要抵御失陷 csc，必须增加只负责认证和固定 Project header 的 loopback MCP gateway；该升级不需要实现或复制 MCP 工具。

## 架构

```text
frontend prompt
  -> backend session RPC
  -> provider session capability hook
  -> CsCloudMcpBridge.ensure(conversation, workspace)
  -> JetBrains McpServerService.authorizedSession()
  -> cs-cloud conversation capability API
  -> csc MCP client
  -> JetBrains private /stream
  -> built-in IDE tools
```

### 通用 backend 接入点

通用 backend 增加一个可选的 provider 会话能力接口，由当前连接暴露。接口语义为：

```kotlin
interface KiloSessionCapabilities {
    suspend fun ensure(id: String, directory: String): CapabilityResult
    suspend fun release(id: String, reason: CapabilityReleaseReason)
    suspend fun releaseAll(reason: CapabilityReleaseReason)
}
```

具体类型和包位置在实施计划中按现有 provider 结构确定，但必须满足：

- Kilo CLI provider 不实现该能力，现有行为不变；
- 通用 backend 不导入 JetBrains MCP Server 实现类型；
- prompt、恢复、idle、删除和关闭只调用抽象能力接口；
- shared RPC 和 frontend 不增加 MCP token、URL 或 transport DTO；
- 对共享 upstream 文件的修改保持最小并使用 `kilocode_change` 标记。

### `CsCloudMcpBridge`

`cs-cloud` 模块新增 `CsCloudMcpBridge`，负责：

- 将规范化 conversation directory 精确解析为一个已打开 Project；
- 计算 Costrict 白名单与 JetBrains 启用工具的交集；
- 创建和持有 `authorizedSession()` 长生命周期协程；
- 维护 `conversation ID -> lease`；
- 将 transport、token、Project 路径和有效工具列表绑定到 cs-cloud/csc；
- 轮换、撤销和脱敏 token；
- 隔离 `com.intellij.mcpserver.impl` API 变化。

`CsCloudMcpBridge` 自身不直接引用 JetBrains MCP 实现类型。直接调用 `McpServerService` 的 session factory 通过 MCP Server plugin 的可选依赖配置条件加载；该实现不存在时，Bridge 返回稳定的 `mcp_plugin_unavailable`。这样用户禁用捆绑插件不会导致整个 Costrict 插件加载失败。

每个 lease 至少包含 conversation ID、规范化 workspace、绑定代次、有效工具集合、授权协程和不对外打印的 token。lease 状态为：

```text
absent -> starting -> binding -> ready -> revoking -> absent
                          \-> failed
```

同一 conversation 的 `ensure` 必须串行化。已有 `ready` lease 且 workspace、工具集合和连接代次均未改变时直接复用；其余情况创建新代次并原子替换旧绑定。

### 模块依赖

`cs-cloud` 模块继续依赖 backend 和 shared，并新增对 JetBrains MCP Server plugin/module 的可选集成依赖。只有 MCP session factory 的实现和条件注册配置引用 JetBrains MCP 类型；Bridge facade、cs-cloud transport 和 frontend 不依赖这些类型。frontend 不新增 MCP Server 依赖。

在 monolithic IDE 中，cs-cloud、csc 和 JetBrains MCP listener 位于同一主机。在 split mode 中，本功能运行在 JetBrains backend 主机；cs-cloud 和 csc 也必须位于该主机。frontend 不接收 MCP URL、端口或 token，不实现跨主机端口转发。

## cs-cloud/csc 协议

### 能力绑定

cs-cloud 提供：

```text
PUT /api/v1/conversations/{conversation_id}/capabilities/ide
```

请求继续使用 cs-cloud API key，并携带该 conversation 已有的 workspace header。请求体为：

```json
{
  "version": 1,
  "generation": "<opaque-uuid>",
  "workspace": "<canonical-project-path>",
  "transport": {
    "type": "streamable_http",
    "url": "http://127.0.0.1:<port>/stream",
    "headers": {
      "<authorizedSession-returned-auth-header>": "<temporary-token>",
      "IJ_MCP_SERVER_PROJECT_PATH": "<canonical-project-path>"
    }
  },
  "tools": ["<effective-tool-name>"],
  "approval": {
    "build_project": "control_plane",
    "execute_run_configuration": "jetbrains_ask"
  }
}
```

响应为：

```json
{
  "ok": true,
  "data": {
    "generation": "<opaque-uuid>",
    "accepted": true
  }
}
```

协议规则：

- `(conversation_id, generation)` 是幂等键；相同 generation 和相同内容重复提交返回首次结果；相同 generation 携带不同内容返回 `409 capability_generation_conflict`。
- 更新 generation 时，cs-cloud/csc 必须先接受新配置，再关闭旧 MCP client，保证替换原子可见。
- URL 必须是 `http`、loopback host 和 `/stream` 路径；禁止重定向到非 loopback 地址。
- 请求 workspace 必须与 conversation 的不可变 workspace 完全一致。
- cs-cloud 和 csc 只在内存中保存 transport headers，不写入配置、数据库、日志、遥测、SSE 或诊断包。
- csc 在 MCP 初始化和后续 HTTP 请求中持续携带授权 header，并始终携带绑定的 `IJ_MCP_SERVER_PROJECT_PATH`。
- csc 以实际 `tools/list` 结果为准；请求体中的 `tools` 用于能力声明、诊断和防止调用未授权工具。

### 能力撤销

cs-cloud 提供：

```text
DELETE /api/v1/conversations/{conversation_id}/capabilities/ide?generation=<generation>
```

撤销只影响匹配 generation。旧 generation 的延迟撤销不得删除更新后的绑定。重复撤销返回成功。cs-cloud 通知 csc 关闭对应 MCP client 后清除内存凭证。

### 能力协商

cs-cloud health/capability 响应必须声明是否支持 `conversation_ide_capability_v1`。不支持该能力的版本组合在首次 `ensure` 时返回明确的不兼容错误，不把绑定请求发送到未知 endpoint。

## 工具范围与审批

### 固定白名单

| 类别 | 工具 |
|---|---|
| 分析 | `analyze_calls`, `get_file_problems`, `lint_files`, `get_project_dependencies`, `get_project_modules` |
| 符号与搜索 | `get_symbol_info`, `search_file`, `search_regex`, `search_symbol`, `search_text` |
| 文件与编辑器上下文 | `read_file`, `list_directory_tree`, `get_all_open_file_paths`, `open_file_in_editor` |
| 构建与运行 | `build_project`, `get_run_configurations`, `execute_run_configuration` |

明确排除：

```text
create_new_file
apply_patch
reformat_file
rename_refactoring
execute_terminal_command
execute_tool
```

调试器、数据库和其他未列出的工具同样默认拒绝。JetBrains 新版本增加的工具不会自动进入白名单。

### 用户设置交集

Bridge 在 background coroutine 中读取 JetBrains 当前可用工具，并计算交集。不得在 EDT 上进行首次工具转换或网络操作。用户修改 Exposed Tools 后，下一次 `ensure` 必须重新计算集合；活跃 conversation 在工具集合改变时轮换 binding generation。

### 审批规则

- `McpSessionOptions.commandExecutionMode` 固定为 `ASK`，不继承 brave mode。
- `execute_run_configuration` 使用 JetBrains 原生确认，不再由 csc 重复发起一次相同审批。
- `build_project` 可能执行项目构建脚本，而 JetBrains 不保证对该工具显示 command execution 确认，因此 csc 必须先通过现有控制通道请求批准。
- 读取、搜索、符号、诊断和编辑器导航不要求逐次审批。
- P0 不提供全局自动批准开关。

## Project 与数据边界

conversation directory 规范化后必须精确匹配一个未释放、非 default 的开放 JetBrains Project `basePath`。禁止：

- 选择最近使用项目；
- 在多个开放项目中猜测；
- 将子目录自动扩大为父 Project；
- 将未打开的 worktree 映射到主项目。

无法精确匹配时，MCP 能力绑定失败，prompt 不发送。用户将对应目录作为独立 Project 打开后可原地重试。

csc 写权限仍以 conversation directory 为边界。JetBrains 内置只读工具可按 IDE 自身规则读取项目内容以及 IDE 可解析的 library/SDK 源码；这比 csc 的写入沙箱更宽，但不授予文件修改权限。

## 生命周期

### Prompt 前绑定

conversation 创建时不启动 MCP。每次 prompt HTTP 请求之前执行 `ensure`：

1. 校验 Project 和 cs-cloud capability 版本；
2. 计算有效工具集合；
3. 若工具集合为空，记录 `available=false, reason=tools_disabled` 并允许 prompt 继续；
4. 否则创建或复用 lease；
5. 等待 cs-cloud/csc 确认 generation；
6. 绑定成功后才发送 prompt。

MCP 插件缺失、私有 listener 启动失败、Project 不匹配或 capability API 绑定失败时，prompt 不发送。错误通过现有会话错误通道展示，修复后使用相同 conversation 重试。

### 活跃与恢复

- 普通 SSE 短暂断开不撤销 lease，避免正在执行的 csc 失去 IDE 能力。
- 插件恢复连接后，对状态为 `busy` 或 `retry` 的 conversation 主动执行 `ensure`。
- cs-cloud/csc 重启后使用新 generation 和 token 替换旧绑定。
- 只读历史会话不启动 MCP。
- `authorizedSession` 意外结束时将 lease 标记为 `failed`；允许重建绑定，但不自动重放 prompt 或失败的 MCP tool call。

### 撤销

以下事件触发匹配 generation 的撤销：

- conversation 进入权威 `idle`；
- 用户中止 conversation；
- 删除 conversation；
- 对应 Project 关闭；
- provider 断开且不会立即恢复；
- 插件卸载或 backend 关闭。

下一次 prompt 会创建新的 lease。撤销和关闭必须可取消、可重复，并在 service scope 取消时清理 JetBrains `authorizedSession` 协程。

## 错误处理与可观测性

错误至少区分：

| 错误 | 行为 |
|---|---|
| MCP Server plugin 不可用 | 阻止 prompt，提示启用捆绑插件 |
| Project 不匹配 | 阻止 prompt，提示打开对应目录为 Project |
| 私有 listener 启动失败 | 阻止 prompt，保留 conversation 供重试 |
| cs-cloud/csc 版本不兼容 | 阻止 prompt，报告缺少 `conversation_ide_capability_v1` |
| capability 绑定失败 | 阻止 prompt，不并行或提前发送 prompt |
| 用户禁用全部工具 | 允许 prompt，csc 不获得 IDE MCP 能力 |
| 运行中 token 失效 | 展示普通工具错误，不自动重放 |
| 撤销失败 | 本地 token 仍立即失效，记录脱敏 warning |

日志只允许记录：

- conversation ID 的脱敏摘要；
- generation 的脱敏摘要；
- lease 状态；
- 工具数量和工具名；
- loopback 端口；
- 稳定错误码。

日志禁止记录临时 token、完整 transport headers、API key、完整 prompt、工具参数中的源码正文或用户绝对主目录。异常对象在写日志前必须经过凭证脱敏。

## 并发与一致性

- `ensure`、generation 替换和 `release` 按 conversation 串行化，不使用全局锁阻塞其他 conversation。
- Project 工具设置变化与 prompt 竞争时，以 prompt 发送前最后成功确认的 generation 为准。
- 新 generation 确认前保留旧 lease；确认后先发布新 lease，再取消旧授权协程。
- 旧 generation 的完成、失败或撤销回调不得覆盖当前 generation 状态。
- prompt 不与首次绑定并行，避免 csc 在能力尚未就绪时开始规划。
- MCP tool call 由 csc/JetBrains 负责取消；插件不对具有副作用的调用做自动重试。

## 测试策略

默认只执行受影响的 JetBrains 模块测试和相关外部仓库测试，不运行全量仓库测试。

### JetBrains 单元测试

覆盖：

- conversation directory 与开放 Project 的精确匹配；
- 子目录、未打开 worktree、多个 Project 和已释放 Project 的拒绝；
- 固定白名单与用户启用工具的交集；
- 空工具集合允许 prompt；
- 同代 `ensure` 幂等和工具变化后的 generation 轮换；
- 并发 `ensure` 只建立一个当前 lease；
- stale release 不撤销新 generation；
- idle、abort、delete、Project close 和 service dispose 的清理；
- token、header 和异常文本脱敏。

### JetBrains MCP 集成测试

使用真实 IntelliJ Application、真实 `McpServerService.authorizedSession()` 和真实 HTTP Stream client，验证：

- MCP initialize 和 `tools/list`；
- 白名单内只读工具的真实调用；
- 错误 token 返回 401；
- 取消授权协程后旧 token 失效；
- 两个 conversation 使用不同 token 和 Project header；
- 全局 MCP Server 开关关闭时私有授权会话仍可工作；
- `commandExecutionMode=ASK` 不受 brave mode 影响。

测试不得复制 JetBrains MCP Server 实现，也不得用 mock server 代替上述集成覆盖。

### cs-cloud 契约测试

在 cs-cloud 仓库使用真实测试 server 覆盖：

- capability PUT 的认证、loopback URL、workspace 和 schema 校验；
- 相同 generation 幂等与冲突；
- 新 generation 原子替换旧 MCP client；
- stale DELETE、重复 DELETE 和进程关闭；
- token 不持久化、不进入日志或事件；
- 不兼容 capability version 的稳定错误。

### csc 集成测试

覆盖：

- 动态增加、替换和移除 conversation MCP 配置；
- 使用 headers 完成 HTTP Stream 握手；
- 以 `tools/list` 为实际能力来源；
- 工具缺失时不调用；
- `build_project` 先发控制通道审批；
- `execute_run_configuration` 不产生重复 csc 审批；
- token 轮换后关闭旧 client；
- MCP 调用失败不自动重放。

## 真实验收

准备一个包含编译错误和失败测试的真实 JetBrains 项目，启动真实 cs-cloud 和 csc，保持全局 MCP Server 开关关闭但启用捆绑的 MCP Server plugin。

验收步骤：

1. 用户不编辑任何 MCP client 配置，直接在插件中创建 conversation 并发送诊断修复 prompt。
2. csc 通过 `search_symbol`、`get_symbol_info` 和 `get_file_problems` 定位问题。
3. csc 通过原有文件工具申请权限并修改文件；事件记录证明未调用 MCP 写工具。
4. csc 调用 `build_project` 前出现控制通道审批；批准后返回真实构建结果。
5. csc 使用 `get_run_configurations` 查找测试，并通过 `execute_run_configuration` 执行；JetBrains 显示原生确认且不出现重复 csc 审批。
6. 诊断消失、构建成功、测试通过，聊天记录包含真实 MCP 工具结果。
7. conversation 进入 idle 后撤销绑定，旧 token 请求返回 401。
8. 再次发送 prompt 后生成新 generation 和 token，并可继续调用工具。
9. 同时打开两个 Project 和两个 conversation，确认正常 csc 配置分别收到正确 Project path；本项不模拟恶意 csc 篡改 header。
10. 禁用一个白名单工具后重新发送 prompt，确认该工具不出现在有效列表；禁用全部工具后普通 csc prompt 仍可执行。
11. 在 split-mode Development IDE 中重复最小握手和一个只读工具调用，确认 token 不经过 frontend，loopback 连接发生在 backend 主机。

验收证据保留脱敏 conversation ID、generation 摘要、有效工具列表、审批事件、工具调用名、构建/测试结果和撤销后的 401。不得保存 token、完整 headers、API key、完整 prompt 或源码正文。

## 完成标准

本阶段完成必须同时满足：

- JetBrains、cs-cloud 和 csc 三端协议实现与相关测试通过；
- 真实端到端验收完成；
- 不需要用户配置全局 MCP Server 或第三方 client；
- MCP 文件写工具未进入有效白名单；
- 用户 Exposed Tools 设置得到尊重；
- idle、重连、重启、删除和关闭均无残留有效 token；
- 日志与诊断证据中不存在凭证泄漏；
- JetBrains `2026.1` typecheck、相关测试和 split-mode 冒烟通过。

通过以上门禁后，插件可声明具备 P0 IDE MCP 能力桥；仍不得据此声明已完成 P1 日常可用性或针对恶意 csc 的强制 Project 隔离。
