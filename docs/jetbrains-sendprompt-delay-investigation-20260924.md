# sendPrompt 首轮明显延迟排查结论（2026-09-24）

- 现象：新会话首次 sendPrompt 后约 33s 才出现首个可见输出；同会话后续轮次正常（112ms 级）。
- 样本会话：`e380791f-23ca-4ccc-8600-c84cfc3769e0`，三方日志对齐（kilo.log + daemon app.log + csc transcript）。

## 延迟逐段分解

| 时刻(+0800) | 段 | 耗时 | 判定 |
|---|---|---|---|
| 16:45:46,518 | Enter → 会话创建 + echo 气泡 | 20ms | 正常 |
| 16:45:46,545 → 56,738 | 插件 `ensureCapability` → bind PUT 串行阻塞 | 10.2s | ❌ 纯浪费 |
| 16:45:56,786 | prompt POST 被 csc 接受 | 3ms | — |
| 16:45:56,8 → 16:46:12,07 | csc `waitReady()` 等子进程初始化 | 15.3s | ❌ 结构性 |
| 16:46:12,1 → 16:46:19,8 | 模型首轮响应 | 7.7s | ⚠️ 503 限流时 20~30s |

对照：第二轮（16:48:59,901 → 16:49:00,013）插件侧仅 112ms。延迟全部集中在**新会话首轮**。

## 根因一：插件侧 10.2s —— 注定失败的 bind 串行等待

1. `packages/kilo-jetbrains/backend/.../KiloSessionRpcApiImpl.kt:372`：`prompt()` 在发 prompt 前串行执行 `ensureCapability`。
2. → `packages/kilo-jetbrains/cs-cloud/.../mcp/CsCloudMcpBridge.kt:183-221` `bind()` PUT daemon 的 IDE capability 路由。
3. → `cs-cloud/internal/localserver/ide_capability.go:161-166`：`SetIDECapability` 转发 csc 子进程；**子进程尚未初始化完，阻塞 ~10s 后返回 `502 capability_bind_failed`**。
4. 插件记 `IDE MCP bind failed ... HTTP 502`，`ensureCapability` 吞异常放行（prompt 未被挡，但**该会话丢失 IDE 能力**，下轮 ensure 重试才补绑）。

- 非 daemon 重启特有：16:41:48 会话 a591d201 同样付 10.2s（当时 daemon 未重启）。**新会话首 prompt 时子进程必未 ready，10s 超时必然发生**。
- 该路由 daemon 侧无日志（既有观测盲区），黑盒至今。

## 根因二：csc 侧 15.3s —— 子进程冷初始化 ≈25.5s

- 子进程在**会话创建时才 spawn**（16:45:46,538 POST /session → 16:45:46,597 daemon status 注册），到 ready（16:46:12,07 transcript 首条）共 ≈25.5s（Windows 冷启动 + 完整 agent 运行时加载：skills/MCP/auth）。
- `csc/src/server/routes/session.ts:828-849`：`prompt_async` 返回 200 后异步链 `waitReady()` → `setModel` → 入队；路由注释自认 "create+waitReady (3–30s)"；`csc/src/server/childSpawn.ts` `INIT_TIMEOUT_MS` 默认 120s，慢时等待上限远超 30s 且无 UI 反馈。
- 两段等待部分重叠（bind 等待期间子进程也在 init），总墙钟 ≈ 子进程 init 全长 + 模型时间。
- 加重因素（仅本次）：daemon 16:45:21~35 自动重启（插件日志 Connection refused 重连序列始于 16:45:21,584），冷上加冷。

## 修复建议（按收益排序）

| 序 | 改动 | 层 | 收益 |
|---|---|---|---|
| 1 | 会话预热：面板打开/聚焦时预创建会话并 spawn+init，或 csc serve 维护 warm pool | 插件 + csc | 隐藏全部 ~25s init |
| 2 | bind 不串行阻塞 prompt：`ensureCapability` 改后台异步绑定 + 缩短超时；或 daemon 对未就绪子进程快速失败/init 完成后自动补绑 | 插件 或 daemon/csc | 消灭 10s 感知延迟，同时修丢 IDE 能力问题 |
| 3 | daemon capability 路由补日志（耗时/结果/502 原因） | cs-cloud | 消除观测盲区 |
| 4 | waitReady 期间 UI 显示「正在启动会话…」而非无输出忙碌 | 插件 | 感知改善 |

## 证据位置

- 插件日志：`%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\kilo.log`（16:45:46,545~56,738 的 `kind=prompt` → `IDE MCP bind failed` → `prompt RPC` 序列；16:48:59 二轮 112ms 对照）
- daemon 日志：`~/.costrict/cs-cloud/app.log`
- transcript：`~/.costrict/projects/F--ai-coding-kilocode/e380791f-23ca-4ccc-8600-c84cfc3769e0.jsonl`
- 关联文档：[service error 排查结论](./jetbrains-service-error-investigation-20260924.md)（同会话、503 重试对延迟的叠加部分）

## 修复记录（2026-09-24，分支 fix/jetbrains-prompt-delay-and-error）

| 序 | 状态 | 改动 |
|---|---|---|
| 2 | ✅ | `KiloSessionRpcApiImpl.prompt()` 不再串行 await `ensureCapability`：改为在 `app.capabilityScope` 后台 launch `ensureCapabilityRetry`（5 次尝试、间隔 5s，成功即停），prompt 立即发出。消灭 10s 感知延迟并修复首轮丢 IDE 能力（后台重试覆盖 ~25s 冷启动）。集成测试 M-2 断言同步改为「prompt 不等待绑定、PUT 最终到达」 |
| 3 | ✅ | cs-cloud 分支 `fix/ide-capability-route-logging`：`handleIDECapability` PUT 成功/502/DELETE 清理各补一条带耗时与会话/generation 的日志 |
| 1 | ⏸ 未做 | 会话预热（面板打开预创建 + spawn/init 或 warm pool）为跨插件/csc 的结构改动，另行立项 |
| 4 | ⏸ 未做 | waitReady 期间「正在启动会话…」UI 文案需 csc 外发可区分的启动状态事件，随预热方案一并设计 |
