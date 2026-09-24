# 插件「请求失败 / server_error」排查结论（2026-09-24）

- 现象：sendPrompt 后聊天面板频繁出现「请求失败」错误卡片，正文为裸错误码 `server_error`。
- 被测环境：IDEA IU-262.9437.185 + 插件 1.0.0-rc.3 + cs-cloud daemon（端口 18443）+ `csc serve` 后端（端口 59936）+ chat-rag 网关（zgsm.sangfor.com），模型 costrict/GLM-5.3-Flash-Zhipu。
- 样本会话：`e380791f-23ca-4ccc-8600-c84cfc3769e0`（16:45 建会）。

## 结论总览

| 层 | 结论 | 说明 |
|---|---|---|
| 上游网关（根因） | 503 限流 | chat-rag 返回 `chat-rag.too_many_requests`（"请求数/token 已达官方上限"），非服务端故障，见 §1 |
| csc 错误分类 | 误分类 | 网关错误码在响应 body，`getChatRagCode` 只读响应头 → 落到 `status>=408 → server_error`（应为 rate_limit），见 §2 |
| csc 事件外发 | 误报终态 | content 以 `API Error:`/`CoStrict API Error:` 前缀开头的 assistant 消息会外发 `session.error`（含重试中、最终成功的瞬时错误）；插件渲染为终态错误卡片且成功后不撤销，见 §3 |

## 1. 上游 503 限流（证据）

transcript 原始报文（`~/.costrict/projects/F--ai-coding-kilocode/e380791f-....jsonl` L18）：

```json
{"type":"system","subtype":"api_error","level":"error",
 "error":{"status":503,"error":{"code":"chat-rag.too_many_requests",
 "message":"The number of requests to the model or the number of tokens has reached the official limit.",
 "type":"ai_model_error"}},"retryAttempt":1,"retryInMs":0,"maxRetries":3}
```

- 当日 2 个会话共 6 次 `chat-rag.too_many_requests`（主会话 e380791f 2 次 + stability worktree 会话 742e0a7d 4 次）；daemon 日志 84 条 `stdout_error`（16:41–16:54 约 19 条密集，当日更密的簇在 09:22–09:29 与 11:23–11:30）。
- 诱因背景：当日稳定性 E2E 多会话并行 + 418 文件大上下文，配额打满合理。

## 2. 误分类：限流显示成 server_error

- `csc/src/services/api/openai/retry.ts:168` `getChatRagCode` 只读 `x-chat-rag-code` 响应头；网关把 code 放在 JSON body（`error.error.code`），头缺失。
- `csc/src/services/api/errors.ts:1240` 兜底 `status !== undefined && status >= 408 → 'server_error'`；`errors.ts:1222` 的 `TooManyRequests → rate_limit` 分支因此未命中。
- 补充：csc 已存在 body→头补写机制——`retry.ts:198-207` `wrapOpenAIErrorForRetry` 会调 `extractChatRagCode`（retry.ts:35-69，可从 `error.error.code` 提取）与 `inferChatRagCodeFromApiError`（retry.ts:136）把 code 写回 `x-chat-rag-code` 头，但本例 503 链路未经过该包装（结果仍是 server_error）。修复应排查该机制为何在此链路未生效，而非新增 body 解析。
- 附加错乱：turn1 终态 daemon 侧为 `unknown`，chat 内合成文案却是 "CoStrict API Error: Unauthorized access to model services"（src grep 不到；来源未证实——本机部署 csc dist、csc 仓库 dist、cs-cloud Go 源码均未定位到，存疑）——错误文案映射表需整体复核。

## 3. 误报终态：可重试错误弹「请求失败」

链路（证据为 kilo.log 16:46:20/21、16:50:02/03 两条 `session.error message="server_error"` 与 transcript 对照）：

1. `csc/src/server/sessionMessageRouter.ts:302/400`：assistant 消息 content 以 `API Error:`/`CoStrict API Error:` 前缀开头（`isApiErrorContent`）即外发 `session.error`（`errors[]` 仅是 result 帧路径 L534-546 的触发数据），message 为裸错误码；`isRetryable` 仅在文本带 429/503/529 状态码前缀时置 true，本例裸码该字段实为 undefined，且下游（插件全库零引用）未用。
2. daemon 原样转发 SSE。
3. 插件 `frontend/.../controller/SessionController.kt:2139` `error()`：`msg = event.error?.message ?: ...` → `SessionState.Error(msg)` → 「请求失败」卡片（`SessionOutcomeView.showError`，标题键 `session.error.title`）。
4. **同一轮随后重试成功**（16:50:02 报错 → 16:50:15 该轮 assistant 正常完成 → 16:52:36 end_turn），错误卡片不撤销。插件已有 `SessionState.Retry` 状态但 csc 从不发射 retry 状态。

## 修复建议（按层）

| 层 | 改动 |
|---|---|
| 网关/运营 | 核查 chat-rag 配额策略；并行 E2E 错峰（治本） |
| csc | 排查既有 body→头补写机制（`wrapOpenAIErrorForRetry`）为何在 503 链路未生效；`sessionMessageRouter` 对 `isRetryable=true` 且重试未耗尽的错误不外发终态 `session.error`（或改发 retry 状态事件）；复核错误码→用户文案映射表 |
| 插件（本仓库） | `SessionController.error()` 对带 retryable 标记的错误映射到 `SessionState.Retry`；或后续 Busy/成功事件到达时撤销错误卡片 |

## 证据位置

- transcript：`~/.costrict/projects/F--ai-coding-kilocode/e380791f-23ca-4ccc-8600-c84cfc3769e0.jsonl`
- daemon 日志：`~/.costrict/cs-cloud/app.log`（`stdout_error` / `[csc-events]` / proxy 行）
- 插件日志：`%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.2\log\kilo.log`
- 相关代码：`csc/src/services/api/{errors.ts,openai/retry.ts}`、`csc/src/server/sessionMessageRouter.ts`、本仓库 `packages/kilo-jetbrains/frontend/.../SessionController.kt`

## 修复记录（2026-09-24，插件分支 fix/jetbrains-prompt-delay-and-error；csc 分支 fix/retry-signal-and-chatrag-classification）

| 层 | 状态 | 改动 |
|---|---|---|
| csc §3 误报终态 | ✅ | `emitSessionError`（system/api_error、api_retry）不再外发 `session.error`，改发 `session.status {type:'retry', attempt, message, next}` 并 `setBusyStatus`——插件既有 `status()` 处理链直接渲染 Retry 状态。终态错误仍由 result 帧（`handleResultMessage`）与 assistant API-Error 消息路径外发 |
| csc §2 误分类 | ✅ | 根因确认为**对象形态**：错误经 session store 序列化读回后是普通对象，`Headers` 实例丢失，`getChatRagCode` 只读头必然落空。修复 = `getChatRagCode` 头缺失时回落到既有 `extractChatRagCode`（body `error.error.code`），非新增解析；`mapErrorToSDKError` 先解包 `CannotRetryError.originalError` 再按 code 分类（503+too_many_requests → rate_limit），解决 turn 终态 `unknown`/`server_error` 错乱 |
| csc 文案 | ✅ | `buildErrorFromContent` 从 JSON body 提取 `error.error.code`，`chat-rag.*` 可重试码集合（too_many_requests 等 9 个）置 `isRetryable=true`，message 优先用网关可读文案 |
| 插件 | ✅ | `SessionController` PartUpdated 恢复列表加入 `SessionState.Error`：轮内 API 错误后若流恢复（重试成功），终态错误卡片撤销转 Busy。csc 改发 retry 状态后双保险（旧版 csc 也适用） |
| 网关/运营 | ⏸ | 配额策略核查与 E2E 错峰属运营项，未动 |
