# Costrict JetBrains MVP 设计

## 目标与范围

本设计定义 Costrict JetBrains 插件的第一阶段 MVP。插件基于 Kilo Code JetBrains split-mode 结构改造，连接用户已经运行并完成登录的 `cs-cloud` daemon，由其管理 csc Agent。

MVP 必须支持以下闭环：

1. 用户打开 JetBrains 项目，插件发现本机 `cs-cloud` 并完成健康检查。
2. 用户输入 prompt，看到 csc 的 ReAct 事件流、工具调用和状态。
3. 文件写入、命令执行等危险工具沿用 Kilo 现有权限/问题 UI，用户批准后继续执行。
4. csc 在当前项目目录生成原生 Web 五子棋小游戏的 HTML/CSS/JS 文件，插件刷新 IntelliJ VFS 并展示 diff。

本阶段不包含插件托管或安装 `cs-cloud`/csc、完整序号快照恢复、幂等协议、IDE MCP 能力桥、远程开发专用逻辑、内置浏览器预览、自动启动静态服务器、Kilo 账号/云同步和品牌发布改造。

## 设计原则

- **复用优先：** 保留 Kilo 的会话模型、RPC、审批交互和前端 UI，仅替换 Agent 连接实现。
- **适配隔离：** Costrict 逻辑全部放入新的 `cs-cloud` Gradle 模块，避免散落到上游共享代码。
- **单一协议边界：** JetBrains 只依赖 cs-cloud 的稳定本地控制面，不直接连接 csc。
- **工作区绑定：** 每个会话固定绑定一个规范化的 JetBrains 项目目录。
- **安全默认：** API key 不落日志；只连接 loopback；危险操作必须得到用户批准。

## 模块架构

在 `packages/kilo-jetbrains` 增加第四个模块：

```text
shared/       # RPC 契约和最小连接提供者接口
frontend/     # Kilo 原生 UI、SessionController、审批和 diff
backend/      # 应用状态、会话管理、前端 RPC、默认 Kilo provider
cs-cloud/     # Costrict 专用 endpoint、HTTP/SSE 和协议适配
```

### `shared`

只定义稳定的 `ConnectionProvider`/`TransportFactory` 接入契约及必要的跨模块 DTO，不包含 cs-cloud 实现、HTTP 细节或前端类型。

### `backend`

继续拥有应用生命周期、会话管理、事件分发和前端 RPC。它通过 IntelliJ extension point 或 service lookup 查找连接 provider，保留原 Kilo provider 作为兼容/开发模式；MVP 选择 Costrict provider。backend 不直接依赖 `:cs-cloud`，以避免与上游模块形成强耦合。

### `cs-cloud`

实现 Costrict provider，包含：

- `server_url`、配置文件和环境变量解析；
- API key 注入和 loopback 校验；
- Kilo CLI 路径到 cs-cloud 路径的统一重写；
- health envelope、错误响应和 SSE 事件归一化；
- workspace header 管理、断线重连和连接测试。

该模块只依赖 `:shared` 和必要的 IntelliJ backend/HTTP 能力，不依赖 `frontend`。

模块 wiring 需要同步更新：

- `settings.gradle.kts` 增加 `include("cs-cloud")`；
- 根 `plugin.xml` 增加 `kilo.jetbrains.cs-cloud` content；
- 新增 `cs-cloud/src/main/resources/kilo.jetbrains.cs-cloud.xml`；
- 在新模块 descriptor 中注册 provider 实现。

## 数据流

```text
JetBrains prompt
  -> frontend SessionController
  -> shared RPC
  -> backend ConnectionProvider
  -> cs-cloud local control plane
  -> csc session/tool loop
  -> cs-cloud SSE
  -> backend event normalizer
  -> existing SessionModel and UI
```

文件不经过插件中转。csc 通过 cs-cloud 在授权的当前项目目录读写；收到 `host.file.*` 或会话 idle 事件后，backend 触发 IntelliJ VFS 刷新，编辑器、项目树和 diff 重新读取落盘结果。

## HTTP 与 SSE 协议适配

当前 JetBrains 后端使用 Kilo CLI 路径，cs-cloud 对外使用 `/api/v1` 稳定控制面。适配层统一完成以下映射：

| Kilo 后端逻辑 | cs-cloud 路径 |
|---|---|
| `/session...` | `/api/v1/conversations...` |
| `/session/{id}/prompt_async` | `/api/v1/conversations/{id}/prompt/async` |
| `/session/{id}/message` | `/api/v1/conversations/{id}/messages` |
| `/global/event` | `/api/v1/events` |
| `/permission...` | `/api/v1/permissions...` |
| `/question...` | `/api/v1/questions...` |
| `/global/health` | `/api/v1/runtime/health` |

请求适配规则：

- 所有会话请求携带规范化的 `X-Workspace-Directory`。
- API key 使用 `Authorization: Bearer <key>` 或 `X-API-Key`，不拼接到 URL。
- `runtime/health` 的 `{ok,data}` envelope 解包为现有 health DTO。
- `{ok:false,error:{code,message}}` 转换为现有 backend 异常，并保留可诊断错误码。
- SSE 的 `GlobalEvent`、扁平事件和 host 文件事件归一化为现有 `SseEvent`；未知事件只记录 debug，不阻塞主事件流。

cs-cloud 侧需要稳定保证 `/api/v1` 路径、认证 header、workspace header 和 SSE envelope。csc 不增加新的插件专用入口。

## 连接生命周期与恢复

状态流为：

```text
disconnected -> discovering -> connecting -> ready
                         \-> unavailable
```

1. **发现：** 读取 cs-cloud 的 `server_url` 文件；读取配置文件或环境变量中的 API key。任一缺失则进入 `unavailable`。
2. **健康检查：** 请求 `/api/v1/runtime/health`。401 表示 API key 无效，503 表示 csc agent 未就绪，网络错误表示 daemon 未运行或不可达。
3. **建立事件流：** 健康检查通过后启动带 workspace header 的 SSE，连接成功后才发布 `ready`。
4. **断线重连：** 使用指数退避重连；不自动重发正在执行的 prompt。重连成功后重新读取活动会话的 messages、status、pending permissions/questions，再恢复 UI。
5. **关闭：** 项目关闭或 backend 销毁时只取消 HTTP/SSE 连接，不调用 `cs-cloud stop`。

MVP 不承诺完整的事件序号、快照恢复或 prompt 幂等。prompt 已提交但响应未知时，界面显示执行状态未知，用户查看历史后自行决定是否重试，避免重复修改文件。

## 用户流程与安全边界

用户新建会话时，项目根目录作为唯一 workspace 绑定。用户发送五子棋 prompt 后，csc 的文本、工具调用、状态和 diff 通过现有 Kilo UI 展示。文件写入或命令执行请求进入现有权限 UI，用户批准后通过 reply endpoint 返回。

安全约束：

- 只接受 `127.0.0.1`、`localhost` 和 `::1` endpoint；非 loopback 地址拒绝连接。
- workspace 路径必须是当前 JetBrains 项目根目录或其合法子目录，插件不提供越界路径输入。
- API key 仅保存在 backend 内存和 HTTP header；日志不记录 key、prompt 内容或完整凭证路径。
- 插件不实现文件写入旁路，所有变更必须由 csc/cs-cloud 的路径沙箱执行。
- MVP 不提供全局自动批准开关。

连接失败必须区分 daemon 未运行、凭证缺失/失效和 csc agent 未就绪三种诊断。

## 测试策略与验收

### JetBrains 测试

- resolver：server URL、配置/env key、缺失凭证和 loopback 拒绝；
- transport：路径映射、workspace/API key header、health envelope 和错误转换；
- SSE：真实事件样本的 ReAct、权限、问题、session status 和 host 文件事件解析；
- lifecycle：连接状态、断线重连、历史/待处理请求恢复、prompt 不重复发送；
- 集成流程：会话创建、prompt、SSE、权限 reply 和 diff 请求的真实 HTTP 交互。

### cs-cloud 测试

运行受影响的 `internal/localserver` 测试，覆盖 API key middleware、conversation proxy、workspace 过滤和 SSE envelope。若兼容测试发现 csc 事件字段不匹配，只修复 cs-cloud adapter/fixture，不在 JetBrains 复制 Agent 解析逻辑。

### 真实验收

启动本地 cs-cloud 和可用 csc，打开临时 JetBrains 测试项目，发送五子棋 prompt，批准必要操作，确认：

- ReAct 过程持续显示；
- 权限确认可见且批准后继续；
- HTML/CSS/JS 文件真实落盘；
- IntelliJ 项目树和编辑器可见最新文件；
- 断开并恢复后历史与待处理审批可恢复；
- 非 loopback、错误 key 和 csc 未就绪均给出对应诊断。

默认只执行受影响模块测试，不做全量仓库测试。

## 后续阶段接口

后续可在 `cs-cloud` 模块内部增加事件序号/快照、幂等 key、MCP IDE bridge 和可选的 daemon 托管，而不改变 frontend 的会话模型。若需要兼容上游 Kilo 连接，继续通过 `ConnectionProvider` 选择，不把 Costrict 分支逻辑回填到 `KiloBackendCliManager`。
