# feat-cs-plugin 分支终审报告（2026-09-04）

- **审查范围**：`6320e8122d19c655ca09c6204dcdc92eb0b44243..HEAD`（复核实测 99 个提交，245 文件，+18,706 / -630 行）
- **方式**：6 个专项并行审查（cs-cloud 连接核心 / IDE MCP 桥 / 品牌替换与入口隐藏 / cloud hub + code review / 新用户引导与会话路由 / 测试基建与构建）。其中 MCP 桥专项用 `javap` 反编译了仓库实际依赖的 IU-2026.1（261.22158.277）`mcpserver.jar` 核实反射层，并与 `F:/ai-coding/cs-cloud/internal/localserver/ide_capability.go` 逐字段比对 REST 契约；测试专项实跑了 4 个测试类确认通过。
- **总量**：P0 × 1，P1 × 23（去重后），P2 × 约 50。标 ★双验证 的条目由两个专项独立发现。
- **复核**：2026-09-04 已对本报告全量复核（六路并行验证 + 关键事实二验）。主体结论与行号均成立；修正了 P2-17（两处「缺断言」指控被现有测试反证）、P2-18（_dark 图标并非逐字节相同）、P2-26（SerializationException 实际映射 INTERNAL 而非 UNAVAILABLE）及若干数字/行号偏差。

---

## 总览：按主线结论

| # | 主线 | 结论 |
|---|---|---|
| 1 | csc+cs-cloud 适配（基础能力） | 功能闭环成立；**连接状态机并发防护与 Windows 进程收尾不达标** |
| 2 | JetBrains IDE MCP 能力桥 | 「协议对、时序错」——REST 契约逐字段一致，但生命周期四个承诺（重启重绑/关闭撤销/项目关闭撤销/硬错误阻止）全部失效或缺失，**含唯一 P0** |
| 3 | Costrict 品牌替换 | en/zh_CN/zh_TW 用户可见文案零残留、通知组收口钉死；**16 个小语种包仍随包发布 "Kilo Code" 文案** |
| 4 | Costrict cloud 服务合入（cloud hub + code review） | 贴合 spec「最薄 IDE 面」，测试齐备；残差集中在错误恢复最后一公里与静默失败 |
| 5 | 不适用功能入口隐藏 | 八处入口均按「藏入口留代码」落地且注释基本如实；**Reinstall 齿轮菜单漏藏一半** |
| 6 | 未装 csc/cs-cloud 用户引导 | 主链路（发现→引导→安装→启动→登录→路由）代码+测试闭环；边缘态与 Windows 真机路径有缺口 |

---

## 一、P0（1 条）

### P0-1 capability lease 复用键用 endpoint URL 充当连接代次，daemon 重启后旧 lease 误判有效

`packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridge.kt:74-77`

```kotlin
val epoch = endpoint()?.base ?: return@withLock CapabilityResult.Unavailable("ide_capability_unsupported")
leases[id]?.takeIf { it.workspace == workspace && it.tools == tools && it.epoch == epoch && it.job.isActive }?.let {
    return@withLock CapabilityResult.Ready(it.generation, it.tools)
}
```

- 实现计划（`docs/superpowers/plans/2026-08-25-costrict-jetbrains-ide-mcp-bridge.md` Task 5 Step 7）明确要求 "Include a monotonically increasing connection epoch from `CsCloudConnectionService`"，落地时改用了 `endpoint()?.base`。
- **触发场景**：daemon 重启但端口不变（常见，且 cs-cloud 有自动升级重启行为）→ base 不变 → `ensure` 短路返回 Ready，不再向新 daemon 进程 PUT 绑定。`KiloBackendSessionManager.recover`（315-319 行，重连后对 busy/retry 会话重绑）恰好为此设计，却在此处空转。
- **后果**：重启后该会话的 csc 拿不到任何 MCP 配置，模型静默失去 IDE 工具；叠加 P1-2（门控全放行）用户完全无感知。
- **建议**：由 `CsCloudConnectionService` 在每次 `connect()` 成功后递增 epoch 计数并传入 bridge 构造。
- 已复核（主线审）：`grep -n epoch` 确认 74 行即为 `endpoint()?.base`。

---

## 二、主线 1：csc + cs-cloud 适配（连接核心）

### P1

**P1-1 connect/reconnect/poll 三者无互斥，guard 字段在 job 体内被置 null，并发连接**
`cs-cloud/.../CsCloudConnectionService.kt:215-237`（结合 74-107、244-254）

```kotlin
reconnect = cs.launch {
    reconnect = null
    val wait = (250L shl attempt.coerceAtMost(3))...
```

`reconnect`/`poll` 在 job 一启动就被置 null，因此 `connect()` 顶部的 `reconnect?.cancel()`、`poll?.cancel()`（76-79 行）在重连退避窗口（250ms~2s）内拿到的是 null，取消不掉正在等待/执行的 job。
触发场景：① SSE 断线进入重连退避时用户点 Retry/Restart，或 `startCsCloud()` 成功后调用 `connect()`——两条路径并行执行 `openSse`，`sse = streams` 互相覆盖，先到的一组 SSE 流与 4 个 OkHttpClient 永不 shutdown（泄漏 socket/线程），事件重复投递；② 连接失败后有 pending poll 时 `startCsCloud()` 成功连接，随后 poll 醒来再次 `connect()`，把刚建立的连接拆掉重建（Connected→Discovering→Connected 抖动）。
建议：代际计数器（AtomicLong generation，job 捕获 gen、delay 后比对）或对整个连接状态机加 `Mutex`，并让 job 完成前保持 guard 非空。

**P1-2 `CsCloudHttpClients.create` 位于 try 之外，异常未被状态机吸收**
`CsCloudConnectionService.kt:94`

```kotlin
val next = CsCloudHttpClients.create(found, roots)
clients = next
try {
    checkHealth(next)
```

`create` 内 `endpoint.base.toHttpUrl()`（CsCloudHttpClients.kt:23）对 URI 合法但 HttpUrl 拒绝的字符串抛 `IllegalArgumentException`——已实测 `java.net.URI` 接受 `http://127.0.0.1:99999`（端口越界不校验），而 OkHttp 拒绝。`server_url` 被污染/写坏即触发：异常直接冲出 `connect()`，poll job 以未处理异常死亡，重试链永久中断，状态卡在 `Connecting`（死区），且 `endpoint` 已被赋值留下脏状态。
建议：把 create 纳入 try，或改用 `toHttpUrlOrNull()` 并映射为 `CsCloudDiscoveryError.MalformedUrl`。

**P1-3 ★双验证 Windows 上 destroy 不级联孙进程，孤儿 node 继续运行**
`cs-cloud/.../ProcessAwait.kt:38-41` + `CsCloudStarter.kt:49/62`、`CscInstaller.kt:47/60`（49/47 为超时路径 `destroyForcibly()`、62/60 为取消路径 `terminate()`；文件名注意是 `CsCloudStarter.kt`，对象名为 `CscCloudStarter`）

```kotlin
internal fun Process.terminate(graceMillis: Long = TERMINATE_GRACE_MILLIS) {
    destroy()
    if (!waitFor(graceMillis, TimeUnit.MILLISECONDS)) destroyForcibly()
}
```

Windows 下 `csc.cmd`/`npm.cmd` 由 cmd.exe 包装（本机 JDK21 实测：`ProcessBuilder` 可直接 spawn `.cmd`；`destroy()`/`destroyForcibly()` 后 `descendants()` 中 `PING.EXE` 仍 `alive=true`）。取消安装/启动时只杀了 cmd.exe 壳，孙进程 node.exe 成为孤儿，继续装包或起 daemon——「取消不留残留进程」修复在主要目标平台并未堵住。且锁已释放允许立刻重装，可与残留 npm 并发写全局目录。
建议：取消/超时路径追加 `proc.descendants().forEach(ProcessHandle::destroyForcibly)`（先杀后代再杀本体），Windows 可用 `taskkill /PID <pid> /T /F`（树杀）。`CscInstallerTest.kt:75` 的取消用例用 `exec sleep 60` 使被杀进程就是叶子进程且 `if (isWindows()) return`——正是手工验证清单声明「本机（Windows）直接跳过」的链路。

**P1-4 ★双验证 子进程输出无人并发读取，输出超管道缓冲即互相死锁（假超时误杀）**
`CsCloudStarter.kt:48-52`、`CscInstaller.kt:46-50`

```kotlin
if (!proc.awaitExitOrTimeout(timeoutSeconds)) { ... }
val out = proc.inputStream.bufferedReader().readText().trim()
```

`redirectErrorStream(true)` 合并了 stderr，但输出在进程退出后才读。`csc cloud start`/`npm install` 一旦输出超过管道缓冲（Windows 匿名管道更小），子进程阻塞在 write 永不退出，180s/300s 后被误报为 "did not finish within Ns" 并 `destroyForcibly` 杀掉一个其实没死的安装。同包 `CscLogin.kt:46-50` 已用后台 drain 线程并注释了原因（"Drain output on a background thread so a chatty command cannot fill the pipe buffer"），实现不一致。
建议：把 CscLogin 的 drain 模式抽成公共 helper 复用到 installer/starter。

**P1-5 重连不复用 resolver，daemon 换端口/换 API key 后永久连不上**
`CsCloudConnectionService.kt:223-229`

```kotlin
endpoint ?: return@launch
val bundle = clients ?: return@launch
...
checkHealth(bundle)
closeSse()
openSse(bundle)
```

首次连接成功后所有断线都走 `scheduleReconnect`，复用内存里的旧 `endpoint`/`clients`，只有 `connect()` 才 `resolver.resolve()`。daemon 重启换了 `server_url` 端口（或重新生成 `config.json` 的 api_key）后，health 永远 IOException/401，每 2s 无限重试打旧地址，永不自愈。
建议：重连 N 次失败（或每次失败）后降级为 `connect()` 重新发现。

**P1-6 取消/dispose 路径泄漏刚建好的 transport（无 finally）**
`CsCloudConnectionService.kt:96-105`、`196-212`、`154-164`

```kotlin
} catch (error: CancellationException) {
    streams.forEach(CsCloudSseClient::close)
    sse = emptyList()
    throw error
```

`connect()` 创建 `next` 后，取消路径（98-99）直接 rethrow 不调 `closeTransport()`；`openSse` 的 CancellationException 分支同样只关 SSE 不关 4 个 OkHttp client。dispose 与 in-flight connect 交错时（dispose 置 `disposed=true` 后 connect 继续执行到 `clients = next`、`_state = Connecting`），新 client 无人 shutdown 且状态被改回 `Connecting`。
建议：`connect()` 用 try/catch 包住创建之后的全部逻辑并统一 `closeTransport()`；`openSse` 各 catch 补关 client。

### P2

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| P2-1 | `CsCloudConnectionService.kt:180` | `_events.tryEmit(event)` 满缓冲（128）静默丢弃，丢的是 host.file.*/session.idle（工作区刷新与代码评审触发）；同仓 CodeReviewReportWatcher 丢弃时有 warn | 至少补 `log.warn(... emit=false ...)` |
| P2-2 | `CsCloudSseClient.kt:44-49` | SSE `callTimeout(0)/readTimeout(0)` 完全无超时，daemon 半死（TCP 不断不发数据）时既不 onFailure 也不重连，状态停在 Connected、事件永久缺失；onFailure→scheduleReconnect 对 401 等确定性失败同样无限重试 | 加远大于 keepalive ping 周期的 readTimeout 兜底；按 status 区分重试策略 |
| P2-3 | `CsCloudHttpClients.kt:28-29` | apiClient 无任何超时，承载普通 JSON 调用（模型列表、会话列表等），daemon 卡死时调用协程永久挂起（阻塞 `execute()` 不可被协程取消中断） | 非流式调用单独给带 callTimeout 的 client |
| P2-4 | `CscLogin.kt:52`、`66-70` | 阻塞 `waitFor` 不响应取消（最多占 IO 线程 300s）；`catch (Throwable)` 会吞 CancellationException 转成失败 DTO 而非重抛（对比 Starter/Installer 均先单独捕获） | 复用 `awaitExitOrTimeout` + 显式取消分支 |
| P2-5 | `CsCloudRoute.kt:84-100` | `models()` 对畸形响应直接抛非受控异常（`connected` 非数组/元素非对象），与 `CsCloudError` 分类体系脱节 | runCatching 包裹并回退透传 |
| P2-6 | `CsCloudStarter.kt:84-116`（defaultDirs 84-98、expand 110-116）+ 超时路径 48-49 | ① `expand()` 假定 nvm 布局 `versions/node/<ver>/bin`，Windows 的 nvm-windows 全局 bin 在版本目录本身，extraDirs 扫描在 Windows 大概率落空；② 超时路径直接 `destroyForcibly()` 不先优雅 terminate，与取消路径（62 行 `terminate()`）不一致 | 修正 Windows nvm 布局；超时路径统一走 `terminate()` |

### 核实通过项

- xml 注册核对无误：`ai.costrict.jetbrains.ideMcpSessionFactory` EP 与 `IdeMcpSessionFactory.EP` 一致，`connectionProvider` 实现类签名匹配，主 plugin.xml 已含 `kilo.jetbrains.cs-cloud` content 模块。
- Favorites 的 INTERNAL 修复（9de2ba5c6d）与 stage 标注修复均有对应用例（`load and unload never throw on invalid ids`、`installCsc reports the start stage...`）。
- 测试是真实的（MockWebServer 全链路、fake 进程脚本、route 重写矩阵），但 SSE 断线重连/超时/并发 connect-dispose 竞态零覆盖；进程管理测试在 Windows 全部 `if (isWindows()) return` 跳过——恰是 P1-3/P1-4 的盲区。

---

## 三、主线 2：JetBrains IDE MCP 能力桥

**协议面结论**：插件侧完全不实现 MCP 线协议——Content-Length/LD 帧、initialize/initialized 握手、tools/list、tools/call 全部由 JetBrains 内置 MCP Server（HTTP Stream `/stream`）与 csc 的 MCP client 承担；插件侧只产出 REST 能力绑定。该 REST 协议已逐字段核对一致：`version=1`、UUID generation、`workspace`、`transport.type=streamable_http`、`http://127.0.0.1:<port>/stream`、动态 auth header + `IJ_MCP_SERVER_PROJECT_PATH`（daemon 侧 `ide_capability.go:103` 校验 `IJ_MCP_SERVER_PROJECT_PATH` 与 `workspace` 相等；auth header 仅做非空/非空白校验，无相等性校验）、17 工具、`approval` 字面量、同代 409 `capability_generation_conflict`、幂等 DELETE。无帧协议偏差。

### P1

**P1-7 能力门控被整体拆除，所有 Unavailable 硬错误都放行 prompt 且无任何用户可见信号**
`backend/.../rpc/KiloSessionRpcApiImpl.kt:333-342`

```kotlin
internal suspend fun ensureCapability(capabilities: KiloSessionCapabilities?, id: String, directory: String, log: KiloLog) {
    try { capabilities?.ensure(id, directory) }
    catch (...) { ... }
```

`CapabilityResult` 被直接丢弃。设计错误表要求 `mcp_plugin_unavailable` / `project_not_open` / `mcp_listener_failed` / `ide_capability_bind_failed` 均「阻止 prompt」，仅 `tools_disabled` 放行。提交链：6f34466fe2 还保留 `capabilityRequired` 硬错误区分（有测试），随后 28a1d37450（9/3）连结果判读一并删除，并把原回归测试改为断言「不阻断」。
为兼容无 capability 的旧 daemon 可以理解，但至少：非 `tools_disabled`/`ide_capability_unsupported` 时应向前端发一条一次性提示，否则「打开了错误目录的 worktree」等场景永远静默无 IDE 工具。

**P1-8 撤销在 transport 关闭之后才发起，`clear()` 必然早退，IDE 侧 token 撤销请求永远发不出去**
`CsCloudConnectionService.kt:161-162` 与 `279-280`

```kotlin
closeTransport()                                    // clients=null; endpoint=null; executor shutdown
if (bridge.isInitialized()) cs.launch { bridge.value.releaseAll(CapabilityReleaseReason.SHUTDOWN) }
```

而 `CsCloudMcpBridge.clear()`（142-144 行）开头 `val base = endpoint()?.base ?: return@withContext`、`val http = client() ?: return@withContext`。插件卸载 / IDE 关闭路径（`KiloBackendDynamicPluginListener` → `shutdownForUnload` → `dispose`）实际只做了本地 `job.cancel()`，daemon 与 csc 内存中的 binding + 临时 token 原样保留。违反设计完成标准「关闭均无残留有效 token」。
建议：先 `releaseAll`（用捕获的 client/base）再 `closeTransport()`。

**P1-9 全仓库无 Project 关闭监听，`CapabilityReleaseReason.PROJECT_CLOSED` 无人发射**
grep 确认 `cs-cloud/src`、`backend/src` 均无 `ProjectManagerListener`/`projectClosed`。设计生命周期「对应 Project 关闭 → 撤销」、计划 Task 6 Step 5「listen for project close in the cs-cloud module」未实现；项目关闭后 lease（authorizedSession 协程 + daemon binding + token）滞留到下一次 ensure/shutdown。

**P1-10 工具过滤器反射失败为 fail-open，用户禁用的工具可能被私有会话重新暴露**
`cs-cloud-mcp/.../JetBrainsMcpSessionFactory.kt:36-46 / 50-66`

```kotlin
McpToolFilterProvider.EP.extensionList.forEach { provider ->
    runCatching { ... }.onFailure { LOG.warn("MCP filter provider failed ...") }
}
return tools(context, "getAllowedTools") + ...
```

单个 provider 失败只记日志并跳过其过滤。当前 2026.1 上唯一 provider 是 `DisallowListBasedMcpToolFilterProvider`（已反编译确认其 `getFilters(Implementation)` 忽略参数、可安全传 null）；一旦它因 `McpToolDisallowListSettings` 服务初始化失败或未来平台 `applyFilters`/`DIRECT` 缺失（`methods.single{}` 抛 `NoSuchElementException`）而失败，用户 Exposed Tools 禁用名单将被绕过——违反设计目标 6「用户明确禁用的工具不会被 Costrict 私有会话重新启用」。
建议改 fail-closed：任一 provider 失败即返回空集（或独立错误码）。

**P1-11 supported()/bind() 复用无超时的 apiClient，daemon 半死时会话互斥锁永久挂起**
`CsCloudMcpBridge.kt:108-140`（bind 108-127、supported 129-140，均在 `ensure()` 的 `locks[id].withLock` 内同步执行）

`CsCloudHttpClients` 对 apiClient 显式设置 `callTimeout(0) / readTimeout(0)`（即无超时，`CsCloudHttpClients.kt:28-29`）；`supported()` 在每次 `ensure` 的锁内同步执行。TCP 已 accept 但 daemon 不响应时 HTTP 永不返回，该 conversation 后续所有 prompt 全部无限排队。
建议：能力探测/bind 用带 3-5s 超时的 `healthClient` 类客户端，或对整段 `ensure` 加外层 `withTimeout`。

**P1-12 计划中的 lease 生命周期与集成测试全部缺失**
`cs-cloud/src/test/.../mcp/CsCloudMcpBridgeTest.kt` 实际只有 72 行：`runLease` 的取消/失败日志语义 + `capabilityBindReason` 映射。计划 Task 5 Step 1 列的项目精确匹配/子目录拒绝/并发 ensure 合并/工具变化轮换/候选失败保留旧 lease/stale release/脱敏，Task 6 的 `CsCloudMcpLifecycleTest`、Task 7 的 `JetBrainsMcpSessionIntegrationTest`/`IdeMcpEndToEndTest` 均不存在；`cs-cloud-mcp` 模块零测试（且其 `build.gradle.kts` 未声明任何 test 依赖）。桥的并发与轮换逻辑目前完全靠人工验证。（复核注记：审查范围 HEAD 之外，工作区已新增未跟踪的 `src/integrationTest/.../McpBridgeLifecycleTest.kt`（165 行，基于 FakeCsCloudDaemon 覆盖无能力放行/PUT 先于 prompt/busy→idle 触发 DELETE/凭据不落日志），部分收窄本条缺口，但不覆盖 P0-1 的 epoch 重启场景，计划点名的三个测试类仍不存在。）

### P2

| # | 位置 | 问题 |
|---|---|---|
| P2-7 | `CsCloudMcpBridge.kt:88-91` | `runCatching` 吞掉调用方 CancellationException：`withTimeout(30_000)` 的 getOrElse 不区分超时与外部取消；prompt 被 abort 时 `ensure` 正常返回 Unavailable，取消信号延迟到下一个挂起点。建议显式 `catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }` |
| P2-8 | `CsCloudMcpBridge.kt:120` | `bind()` 未校验响应体 generation/ack（仅靠 responseInterceptor 的非 2xx 兜底）；设计要求 "matching generation acknowledgment"，2xx 响应体的 `accepted`/`generation` 未比对。当前 daemon 无此路径，属前瞻性偏差 |
| P2-9 | `KiloSessionRpcApiImpl.kt:151-155` | `command()`（以及 `compact`/`revert`）不执行 `ensure`，斜杠命令发起的 agent 回合若用 IDE 工具将没有 MCP 租约（★另一专项交叉确认） |
| P2-10 | `JetBrainsMcpSessionFactory.kt:49` | 反射硬编码 2026.1 构造参数顺序（已反编译核实 IU-261 为 `(disallowedTools, allowedTools)`，当前正确）；未来版本若换序，allowed 被置空 → 永远空集 → 被折算为 `tools_disabled` 并顺带 `releaseLocked`，特性静默失效且伪装成「用户禁用了全部工具」。建议空集语义区分独立错误码 |
| P2-11 | `CsCloudMcpBridge.kt:62` | `locks` Map 只增不减，长驻 IDE 会话量大时缓慢泄漏；`runLease` 只 warn 不落 `failed` 状态（设计状态机含 failed） |
| P2-12 | `CsCloudMcpBridge.kt:153` | 非 EDT 调 `ProjectManager.openProjects` 无 readAction（与仓库既有惯例一致，非新引入）；另外每次 prompt 都执行 `supported()`（一次 HTTP 往返）+ `enabled()`（反射+设置读取），设计只要求「首次」协商，可缓存 |

### 核实通过项

- 懒初始化：`CsCloudConnectionService.kt:53-55` 用 `lazy {}`（默认 SYNCHRONIZED），失败不缓存、可重试 ✓
- `IdeMcpTransport.toString()` 输出 `token=<redacted>`/`authHeader=<redacted>` 全遮蔽（`IdeMcpProtocol.kt:29`，桥侧日志另只出 token SHA-256 前 6 hex），token/authHeader 不落日志 ✓
- generation 替换顺序「先 put 新 lease 再 cancel 旧 job、再 DELETE 旧代」（CsCloudMcpBridge.kt:97-99）符合设计 ✓
- `KiloConnection.capabilities` 默认 null，Kilo CLI provider 不受影响 ✓
- 模块边界：`com.intellij.mcpserver.*` 类型仅出现在 `JetBrainsMcpSessionFactory.kt`；cs-cloud 不依赖 MCP 插件（符合 split-mode 勿捆绑平台库约束）；可选 content module `loading="optional"` + `<plugin id="com.intellij.mcpServer"/>`、EP 命名空间 `ai.costrict.jetbrains.ideMcpSessionFactory` 均正确 ✓

---

## 四、主线 3 + 5：品牌替换与入口隐藏

### P1

**P1-13 User Profile 注册摘除后多条活代码路径按 id 跳转该页，平台抛 ISE，「Sign in」死按钮**
`frontend/.../settings/base/BaseSettingsUi.kt:276-297`（同型：`session/SessionUi.kt:1090-1098`、`actions/ShowProfileAction.kt`）

```kotlin
private fun openProfile(src: JComponent) {
    ...
    val cfg = settings.find(UserProfileConfigurable.ID)
```

- 机制：本分支把 `applicationConfigurable id="ai.kilocode.jetbrains.settings.profile"` 从 `kilo.jetbrains.frontend.xml` 删除（base 提交 38-44 行存在，HEAD 已无），`SettingsTreeOrderTest.kt:53-57` 钉死「注册必须缺席」。但平台 `ShowSettingsUtilImpl.kt:221`（IU-2026.1 源码）是 `ConfigurableVisitor.find(predicate, ...) ?: error("Cannot find configurable for specified predicate")`——谓词匹配不到直接抛异常。
- 触发场景 A（最常见）：cs-cloud 已启动但未登录时，后端把 profile 401 视为正常（`KiloBackendAppService.kt:90` "Profile is optional — 401 (not logged in) is not an error"），app READY 且 `profile == null` → `ModelsSettingsUi.kt:126/156` + `ModelsSettingsState.kt:82`（`ready && !authenticated`）渲染登录横幅，点 "Sign in" → `openProfile` 先 `settings.find(...)` 得 null，落到 `ShowSettingsUtil.showSettingsDialog(project, predicate, …)` → ISE（被动作系统吞掉，表现为点了没反应 + IDE 红色错误日志）。
- 触发场景 B：付费模型 401（`PAID_MODEL_AUTH_REQUIRED`，仍是 Kilo 网关契约，迁移向导会把 legacy Kilo gateway provider 迁进来，`LegacyMigrationConverters.kt:233-234`）→ `SessionController.kt:1723` 置 `LoginKind.Profile` → `LoginRequiredView.kt:42/59` 主按钮 `openProfile()` → 同样抛 ISE。
- 佐证：`actions/OpenSettingsAction.kt:28-32` 恰好 `catch (err: IllegalStateException)` 后回退到根设置页——团队已知该异常形态，但只有这一个调用点加了防护。
- 建议：给 `BaseSettingsUi.openProfile`、`SessionUi.openProfileSettings` 补同样 ISE 回退；或改为跳转根设置页/直接触发 `loginCsCloud`；`ShowProfileAction` 加注释说明不可达。
- 已复核（主线审）：frontend.xml grep profile 无注册；`BaseSettingsUi.kt:279` 确为 `settings.find(UserProfileConfigurable.ID)`。

**P1-14 16 个语言包仍以旧品牌 "Kilo Code" 出现在用户可见文案（每包 13 条）**
`frontend/src/main/resources/messages/KiloBundle_{ar,bs,da,de,es,fr,ja,ko,nl,no,pl,pt_BR,ru,th,tr,uk}.properties`

抽样 `KiloBundle_de.properties`：`session.empty.welcome=Kilo Code ist ein KI-Coding-Assistent…`、`settings.kilo.displayName=Kilo Code`、`notification.group.kilo=Kilo Code`、`profile.login.title=Sign in to Kilo Code`、`settings.login.message=Sign in to Kilo Code…`、`settings.agentBehavior.agents.import.description=…exported from Kilo Code.` 等。统计：16 包各 70 处 "kilo"（其中 13 处在 value）、各 13 处 "Kilo Code"；en/zh_CN/zh_TW 的 value 已 0 残留。
`scripts/brand-consistency-scan.mjs:26-31` 已把这一 backlog 写成 report-only（每包 "39 line(s) still carry Kilo copy"），属已知欠账而非遗漏，但当前是「会随插件一起发布」的真实残留。
建议：按 13 条键做一次机械替换（成本低）；若按「语言包不裁剪」策略则应翻译而非删包。

### P2

| # | 位置 | 问题 |
|---|---|---|
| P2-13 | messages/ 三语言 | 本分支新增 key 在 zh 侧缺翻译：en 有、zh_CN/zh_TW 都缺——`settings.cli.unavailable.title/message`（KiloReadyConfigurable.kt:126-128，所有设置页未就绪时的整页占位）、`settings.connection.ready/retry/retrying/connecting/disconnected/error`、`session.editor.undo/redo`、`prompt.slash.help/settings`、`prompt.completion.action`、`session.attachment.path`、`worktree.session.fileType.*`、`profile.pass.*`、`session.connection.downloading*`（B2 隐藏横幅，可缓）。仅 zh_TW 缺——`codereview.*` 全套、`settings.agentBehavior.cloudHub.*` 全套、`model.picker.creditRate/creditDiscount`、`action.Kilo.CodeReview.*`。上列仅为抽样，全量 diff 实际 zh_CN 缺 456 / zh_TW 缺 490 个 key（en 共 982）。`DisplayNameI18nTest` 只覆盖 csCloud 错误码+登录卡（全量对齐需 `-Dkilo.tests.bundleStrict=true`，默认只打印漂移计数） |
| P2-14 | `HistoryPanel.kt:148-152` + `HistoryController.kt:62-66` | Cloud 标签藏了，但云历史请求仍每次打开 History 必发（`reloadCloud()`），失败只写进隐藏列表并上报 "History Load Failed"。注释只解释选中机制未提网络副作用 |
| P2-15 | `kilo.jetbrains.frontend.xml:196` + `ConnectionPanel.kt:296-300` + `ReinstallKiloAction.kt` | A5「cs-cloud 模式隐藏 Reinstall」只做了一半：恢复弹层按 `providerId != "cs-cloud"` 藏，但齿轮菜单 `Kilo.SettingsGroup → Kilo.CliGroup` 仍无条件引用 `Kilo.Reinstall`，`ReinstallKiloAction.update()` 无 providerId 门控 |
| P2-16 | `KiloSettingsConfigurable.kt:23-35`（KDoc）+ `KiloToolWindowFactory.kt:169-171` | 两处注释与机制不一致（团队上次专项修过的同类问题）：KDoc 说 profile 注册在 XML——已删除；工厂注释说齿轮菜单含 "Config Files, and Core"——CliGroup 已更名 cs-cloud 且 CoreInfo 无引用 |
| P2-17 | `src/test/.../history/` 等 | A4/B2/B5 隐藏「防回归钉子」覆盖不齐（复核修正：原稿三缺指控过宽）——B2、B5 已有钉子：`ConnectionPanelTest.kt:42-49` 断言发 `ShowDownloading` 后面板不可见，`HistoryLoadingTest.kt:77-83` 断言事件流含 `AccountOverlayChanged hide` 且无 show；真正缺的是 A4：无任何测试断言 History cloud 标签 `isHidden`，删一行 `cloudInfo.isHidden = true` 即无声复显而测试全绿 |
| P2-18 | 图标资产 | ① `kilo-content.png`（5287B）与 `icons/costrict/logo.png` md5 完全相同（`987e0e3f…`）且源码零引用——本分支新增的死资源，建议删除；② `kilo@20x20.svg` 与 16x16 的 `kilo.svg` 逐字节相同（md5 `dec130dd…`），`kilo@20x20_dark.svg` 是真正的暗色版（fill `#CED0D6` vs 浅色 `#6C707E`，非逐字节相同——复核修正）但 viewBox 同为 16×16——两个 @20x20 文件名承诺 20px、内容 16px，平台取 @20x20 变体时按 16 图放大。其余：discord/kilo-content svg 删除无残留；logo 128px RGBA 浅深主题均可读，无需 dark 变体 |
| P2-19 | `scripts/brand-consistency-scan.mjs` | 规则合理且能跑（Rule ① PASSED；Rule ② 普查 Costrict ×3 / CoStrict ×204），但盲区：不扫根 plugin.xml 的 name/vendor/description，不扫 Kotlin 硬编码文案；75 行把全大写 name 属性当内部 id 豁免，未来写错的 name 会溜过 |
| P2-20 | `plugin.xml:5` 等 | 拼写双轨：用户可见 value 侧 0 处 "Costrict"（检查通过），但 `<name>Costrict</name>`、`<vendor>Costrict</vendor>`、frontend.xml 的 `Costrict.CodeReview`/toolWindow id `Costrict` 都是少写大写 S 版本，出现在 Plugins 页、工具窗切换器、Window 菜单；`BrandSmokeTest.kt:29` 还把 `descriptor.getName() == "Costrict"` 钉死。建议尽快定标（至少 name/vendor 先归一为 CoStrict） |
| P2-21 | `KiloSettingsConfigurable.kt:46-59` | 根设置页状态行是一次性快照：`createComponent()` 只读 `app.state.value` 未订阅，Retry 点击后 `status.text` 永远停在 "Retrying…"，恢复 READY 也不会自动消失 |
| P2-22 | `LegacyProviderMapping.kt:62` | 迁移后 provider 显示名 `"Kilo (Gateway)"` 用户可见（Providers 设置页）。指向第三方 Kilo 网关本身，保留旧名有辨识度合理性，但需产品确认（如改 "Kilo Gateway (legacy)"） |

### 核实通过项

- 品牌替换：`ai.costrict.jetbrains`/vendor/描述、toolWindow id + 图标、en/zh_CN/zh_TW bundle value 0 处 "Kilo"、`CostrictBrand.kt`/`CostrictLinks.kt` 收口、`/help` → docs.costrict.ai、反馈弹窗无 zgsm、内置 6 个模式提示词全部 "You are CoStrict"（LegacyMigrationConverters.kt:581-616）✓
- 通知组收口：全部走 `CostrictBrand` 常量，XML 注册 + `CostrictBrandTest`/`NotificationGroupIsolationTest` 双向钉死，无旧 ID 漏改 ✓
- 隐藏质量：A4（cloudForced 机制，注释已修准）、B2（事件/方法/文案保留，`ShowDownloading -> Unit`）、B5（`showAccountOverlay()` 恒 `acctAllowed=false` + Hide，无自动 Show 路径）、B6（small picker 保留仅不挂行）、C2（`autocomplete=false` 链路保留）、B1（CliGroup 更名、CoreInfo 不再被引用）、A1（链接保留不挂载 + 测试钉住）✓
- plugin.xml：所有 `<reference>` 目标均先于引用定义，233-235 行新增注释说明平台约束；`Kilo.InstallCsc/StartCsCloud/SignInCsCloud/CodeReview.Changes` 注册齐备 ✓
- 测试实跑通过：`CostrictBrandTest`、`DisplayNameI18nTest`、`NotificationGroupIsolationTest`、`SettingsTreeOrderTest`（BUILD SUCCESSFUL）；brand-scan Rule ① PASSED ✓

---

## 五、主线 4：cloud hub + code review

### P1

**P1-15 文件/目录审查目标在 Windows 大小写失配时静默降级为「审查当前变更」**
`frontend/.../actions/CodeReviewActions.kt:22-23`

```kotlin
val root = (access.workspaceDirectory ?: project.basePath)?.let { File(it).canonicalPath } ?: return
val target = target(e, root) ?: ReviewTarget.Changes
```

`root` 经 `File.canonicalPath`（返回磁盘真实大小写/解析符号链接），而 `ReviewTarget.relative`（ReviewTarget.kt:25-30）用**大小写敏感**的字符串前缀比较：

```kotlin
val p = path.replace('\\', '/').trimEnd('/')
if (p == r || !p.startsWith("$r/")) return null
```

触发场景（Windows 极常见）：项目以 `f:\repo` 打开而磁盘真实大小写为 `F:\Repo`，或后端 resolve 的 `workspaceDirectory` 与 VFS 路径经 symlink/8.3 短名后不一致 → 右键「审查此文件/目录」实际发的是裸 `/review`（审查工作区变更），**无任何用户提示**，仅 telemetry 记 `target=Changes`。
建议：改用 `FileUtil.pathsEqual`/Windows 大小写不敏感比较（或 `VfsUtil.getRelativePath`），并在 fallback 到 Changes 时 log + telemetry 记录原始意图。

**P1-16 「审查」动作在历史页/只读会话下静默无操作**
`frontend/.../session/SessionHost.kt:95-98`

```kotlin
@RequiresEdt
override fun sendCommand(command: String, args: String) {
    currentUi()?.sendCommand(command, args)
}
```

`SessionSidePanelManager.showHistory()` 会调 `clearCurrent()`，此时 `currentUi()` 为 null；`SessionUi.sendCommand` 的 `if (readonly) return` 同理。而 `CodeReviewAction.update()`（CodeReviewActions.kt:29）只要 `KiloChatAccess.manager != null` 就启用动作——历史页/只读态点「审查此文件」**什么都不发生**（无 tooltip、无报错、无 telemetry）。接口默认空实现（SessionManager.kt:31，6b9862021f 引入）固化了这条静默路径。
建议：`sendCommand` 无 current 时回退 `newSession()` 后再发，或让 `update()` 在 `currentUi()==null/readonly` 时禁用并提示。

### P2

| # | 位置 | 问题 |
|---|---|---|
| P2-23 | `CodeReviewReportWatcher.kt:126-135` | 去重为 check-then-act 非原子：同一文件 `created`+`updated` 两个事件几乎同时各起协程（124 行 `cs.launch`），各自 `awaitStable` 1s 后近似同时通过 `lastEmitted` 检查 → 同一报告版本可能 emit 两次，前端无二次去重，用户看到两条相同气球（违背 spec §3）。建议 `ConcurrentHashMap.putIfAbsent`/`compute`，或检查提前到 `awaitStable` 之前 |
| P2-24 | `CodeReviewActions.kt:29` | ① `project.service<>()` 在已 dispose 的 project 上抛 `AlreadyDisposedException`（关闭窗口时 update 仍触发），建议 `?.takeIf { !it.isDisposed }`；② spec D3 要求「无活动会话时禁用」，实现是 manager 存在即启用，无会话时点击隐式新建会话发 `/review`（比 spec 宽，需确认是否有意）；③ `KiloChatAccess.manager` 只在主面板 host 注册，`WorktreeSessionEditorManager`/`SubagentSessionEditorHost` 未注册 → Agent Manager 场景对 worktree 文件右键审查，命令发进主工作区会话 |
| P2-25 | `ReviewTarget.kt:14-19` | args 无任何转义：含空格/中文/`:` 的相对路径按原样拼 `@/my docs/a.ts`。args 进 JSON body 不走 shell，但 review skill 端按空白切分 `@/` token 的行为无法在本仓验证（spec 仅声明「语法对齐 costrict reviewContext.ts」）。建议补空格/中文路径实测项或加引号。另 CodeReviewActions.kt:70 选区恰好结束于行尾（含换行）时 endLine 多一行 |
| P2-26 | `CsCloudFavoritesApi.kt:72-81` | 错误码映射（复核修正：原稿称 SerializationException 落 `else -> UNAVAILABLE`，实际它继承 `IllegalArgumentException`、命中在前的 `:78 -> INTERNAL`，该半条指控不成立）。仍成立的两处：`else -> UNAVAILABLE` 会把其他无关 IOException（如连接被重置）也归为「守护进程未运行」，语义偏窄；`SocketTimeoutException -> INTERNAL` 与 spec §5 相悖（spec §7 又写超时→INTERNAL，spec 自身矛盾，代码+测试遵循了 §7），建议统一 spec。非法 id 覆盖完整（validate 两入口统一拦截，测试 pin）；残角：id 含 `?`/`#` 会被 OkHttp 截断路径（风险低，建议一并入黑名单） |
| P2-27 | `CloudHubConfigurable.kt:53,71` | 唯一绕过 `durable {}` 包装直调 RPC 的调用方（仓内其余前端 RPC 都走 `*Service.durable`）。后端重连期间 fetch/load 直接失败，面板不自愈，必须手动点刷新。建议仿 `KiloAgentBehaviorService` 加薄包装 |
| P2-28 | `CloudHubConfigurable.kt:136-144` | 错误映射文案对但缺恢复动作，与 spec §7 三处偏差：UNAUTHORIZED 未指向登录入口、UNAVAILABLE 未给 `Kilo.StartCsCloud`/`Kilo.InstallCsc` 按钮（`SettingsProgressOverlay.showProgress` 已有按钮槽位，`showError` 当前写死无按钮，扩展成本低）；「网络/5xx → KiloNotifications 提示」未实现；「404 → 行标记移除 + 通知」未实现（行保留原状靠下次刷新自愈）。INTERNAL 分支把 daemon 原始 errorMessage 写 warn log 不外显，符合「不泄漏」意图 |
| P2-29 | `CloudHubConfigurable.kt:157-165` | daemon 返回重复 id 时两行都渲染且 key 相同，`onCell` 的 `cache.find { it.id == key }` 永远命中第一行：行状态徽标与实际操作可能互相矛盾。建议 fetch 后按 id 去重（spec 点名的边界） |
| P2-30 | i18n | hub/review 文案仅 en + zh_CN，zh_TW 回退英文（spec 写明其余语言回退英文，不算违规，但 zh_TW 属半维护状态，建议补繁中） |

### 核实通过项

- RPC 面：`KiloAppRpcApi` 新增 4 方法与 Impl、`KiloBackendAppService`、`KiloConnectionProvider` 默认降级（非 cs-cloud provider 返回 UNAVAILABLE）逐层一致；`FakeAppRpcApi` 补齐；`CodeReviewReportDto` 字段与 spec §4 一一对应（含 degraded）；`cloudFavorites` 不做 `requireReady()` 正确 ✓
- watcher 生命周期：无原生 WatchService，只挂 app 级 `cs` scope 协程，dispose 随 scope 取消无线程泄漏；`startWatchingGlobalSseEvents` 幂等，SSE 重连不叠加；「双通道」实为单通道（watcher 消费同一 SSE 流），不存在双重通知 ✓
- 路径校验：`reportPath` 用 `canonical()+startsWith(root)` 过滤（含 toRealPath），`security-review_result` 不命中（400680759f 已修），越根路径在测试 outside 分支 pin ✓
- EDT：update/actionPerformed 轻量读；sendCommand 链 `@RequiresEdt` + `assertEdt()`；CodeReviewNotifier.onReport 用 invokeLater(nonModal) 回 EDT；`refreshAndFindFileByUrl` 按 SDK javadoc 允许 EDT 调用（已查证平台源码）✓
- 行序：`HubRowLogic.ordered` 实现 spec §6（section → Active 优先 → 字母序），`HubRowLogicTest` 三个 ordered 用例覆盖（另两用例测 cellId；复核修正：原稿写四用例）✓
- 集成测试：CloudHubPanelTest 四类用例与 SessionLoopTest code review flow 覆盖主线旅程（但见横切 P2-42 翻转断言恒真问题）✓

---

## 六、主线 6：未装 csc/cs-cloud 用户引导 + 会话路由

### P1

**P1-3 ★双验证**（见主线 1，Windows 进程树）
**P1-4 ★双验证**（见主线 1，管道排空）

### P2

| # | 位置 | 问题 |
|---|---|---|
| P2-31 | `CsCloudConnectionService.kt:263` | daemon 已起但 agent 未就绪（health ok=false / HTTP 503）时 `code=null`：引导卡与 Start cs-cloud 按钮全部消失，恢复菜单回退 Restart。`CsCloudConnectionServiceTest.kt:168` 明确断言 503 场景 `code == null`（现状被 pin）。首装链路「`csc cloud start` 已返回但 agent 还在下载/启动」是真实中间态。建议给 5xx/`unavailable`/`agent_down` 映射 Start cs-cloud 动作 |
| P2-32 | `KiloBackendAppService.kt:440` | 未装 csc 时无限轮询使 appState 每 ~2s 在 Connecting↔Error 交替，`warnAppError` 每周期打一条 WARN 无限刷（StateFlow 相等去重因 Connecting 夹在中间失效；前端 1s connectionDelay 吸收不闪屏，纯日志问题）。建议连续相同错误码不翻转 Connecting、同一错误只 warn 一次 |
| P2-33 | `KiloAppService.kt:315` | `loginCsCloudAsync` 用 `catch (e: Exception)` 吞 CancellationException（另两个任务都显式 rethrow）——IDE 关闭/作用域取消时会弹「登录失败」并回调 onDone(false)；且 `:291` `compareAndSet` 占用锁时静默返回无提示（评审文档 P2-7 要求 busy 提示，另两个任务已做） |
| P2-34 | `CscLogin.kt:52` | 阻塞 `waitFor` 不可协作取消（同 P2-4，★两专项独立发现） |
| P2-35 | `KiloBundle_zh_CN/_zh_TW.properties` | `common.dont.show.again` 缺 zh 两包，中文 IDE 下气球「不再提示」按钮回落英文（消费方 `CsCloudSetupNotifier.kt:78`、`KiloNotifications.kt:61`） |
| P2-36 | `SessionController.kt:1742` | cs-cloud 登录卡判定用「最后一条 assistant 文本」：任何 `isError=true` 的 `session.result`（额度不足、模型错误等）都会复用上一条 assistant 文本；只要会话里 assistant 曾提到 "not logged in"/"run /login"（比如用户问过登录问题），后续任意错误都弹登录卡。建议只在本次 result 携带文本上判断，或加 cs-cloud provider 前置条件 |
| P2-37 | `KiloSessionRpcApiImpl.kt:151` | `command`（以及 `compact`/`revert`）不做 `ensureCapability`，与 prompt 路径不一致（同 P2-9，★交叉确认） |
| P2-38 | `KiloBackendAppService.kt:955-962`（refreshWorkspaces；复核修正：原稿写 971，该行在 `clear()` 内） | WorkspaceRefresh 每事件 per-project 新建实例、无去抖：每次构造重新 `canonical(root)`（含 toRealPath IO）并重复解析 event JSON；agent 批量写文件时每个 host.file.* 各排一个 pooled 同步 refresh + 一个 EDT 异步 refresh；`session.idle` 时对整个 root 递归 `file.refresh(true, true)`，大仓库每回合结束成本不低。建议按 (project, path) 合并去抖、复用实例。生命周期竞态本身守得干净（isDisposed 双检） |
| P2-39 | `CsCloudRoute.kt:42` | 86c11d66af 的 `X-Session-Client` 兼容里，缺 `directory` 参数时在 OkHttp 拦截器内直接抛 IAE，绕过错误分类链路（落到 `code=null`）。当前所有调用点都带 `?directory=`，属残留假设；建议拦截器内转受控失败 |
| P2-40 | `KiloBackendSessionManager.kt:108` | `stop()` 用 `cs.launch { releaseAll(DISCONNECT) }` 异步释放，若 `cs` 已取消（关闭/卸载序列）释放静默丢失；`capabilities = null` 先于 release 完成生效，与 `recover()` 并发时 recover 侧会看到 null 而跳过。`dispose()` 同样问题。建议 runCatching + 记录，或调用方协程内同步释放 |
| P2-41 | `CsCloudGuideCard.kt:27` | 文档链接 `https://docs.costrict.ai/cli/guide/installation` 页面包名口径是 `@costrict/cs`（cs TUI），与插件安装的 `@costrict/csc` 不一致；已直接落地为 NPM_NOT_FOUND 双按钮主按钮目标与引导卡文档链接，用户照文档装出来的是 `cs` 而非 `csc`，装完仍连不上。评审文档附 3 已标注需与官方确认。建议确认目标页或改 `https://costrict.ai/download` |

### 核实通过项

- 错误码路由兜底：未知/缺失 code 走通用 "Connection failed"（SessionController.kt:2520-2540 + ConnectionErrorLocalizationTest 覆盖）；401/403 同映射 UNAUTHORIZED，菜单只留 Retry + Sign in；cs-cloud 下隐藏 Reinstall 有测试覆盖 ✓
- providerId 注入（e18fc84fea）已闭环：全仓仅 state 流与 updateConfig 两处返回 KiloAppStateDto，均注入（KiloAppRpcApiImpl.kt:120,149）；前端唯一赋值点 SessionController.kt:1042，无遗漏入口 ✓
- 锁与取消绑定（57cc7a9786/ca6d4213d5）：`runCsCloudTask` 用 `job.invokeOnCompletion { release() }`，注释对「取消落在首次 dispatch 前」的 finally 泄锁场景处理正确；CsCloudTaskRunnerTest 三用例覆盖「等 job 完全 settle 才返回」✓
- 气球一次性（c30b7bdb9b）：静态 OFFERED CAS + PropertiesComponent 持久化，测试覆盖同会话去重/跨会话抑制 ✓
- EDT 布局（733f58620b）：`refresh()` 同时 revalidate parent 与自身，`getPreferredSize` 计入 guide 高度；ConnectionPanelTest:404,425 有真实 bounds 断言 ✓
- 空会话引导与错误横幅非互斥（设计如此）：同屏可能出现两个 "Install csc" 主按钮，属有意共享，视觉略冗余
- stage 标注：`CsCloudStartDto.stage` + 前端分流 "csc installed, but cs-cloud failed to start"，有测试覆盖 ✓

---

## 七、横切：测试基建 + 构建配置 + 版本管理

### P1

**P1-17 「回归钉子」没有任何 CI/流程挂接，且门禁清单与构建排除清单不对称**
`scripts/plugin-zip-dependency-check.mjs:23-25`

```js
const FORBIDDEN_IN_LIB = [
  "kotlinx-serialization", // ships with the platform; see PrepareSandboxTask exclusion
];
```

全仓 grep 显示该脚本只被两份 spec 文档引用，`.github/workflows/*`（含 prepare-jetbrains-release.yml）、`package.json`、`turbo.json` 均不调用；`brand-consistency-scan.mjs` 同样如此。有人改依赖重新把平台库打进 zip（正是 R-4 要防的 cloudFavorites LinkageError 复发）时门禁静默失效。另外 build.gradle.kts:170-171 同时排除了 `kotlinx-serialization-*` 与 `kotlin-stdlib*`，而门禁只查前者——stdlib 从 okhttp/okio 传递混入（注释自述的真实事故）恰恰不被拦截；平台自带的 kotlinx-coroutines 也未列入。
建议：挂入 CI（linux 一步 `buildPlugin + node 脚本`）或至少 `gradlew buildPlugin` 的 finalizedBy；补齐 FORBIDDEN 清单。

**P1-18 新模块 cs-cloud / cs-cloud-mcp 的测试结果不进 CI JUnit 报告**
`script/test-ci.ts:42`

```ts
const modules = [".", "shared", "frontend", "backend"]
```

`gradlew clean test` 会执行 cs-cloud 的 66 个测试（复核修正：原稿写 47），但聚合目录列表漏掉这两个模块 → junit.xml 无 cs-cloud 条目。叠加同文件 Windows 分支「恒 exit 0」注释，cs-cloud 测试在 Windows CI 上失败既不红也不可见。建议 modules 补 `"cs-cloud"`（cs-cloud-mcp 无测试源集可不入）。

**P1-19 integrationTest 源集游离在 typecheck / detekt / check / CI 之外**
`build.gradle.kts:337-349 + 155-179`

```kotlin
tasks.register("typecheck") {
    dependsOn(":shared:compileKotlin", ..., ":cs-cloud-mcp:compileTestKotlin")  // 无 compileIntegrationTestKotlin
}
```

detekt `source.setFrom("src/main/kotlin")` 也不含 integrationTest。测试代码签名写错时 `bun turbo typecheck`、`test:ci` 全绿，直到手动跑 `gradlew integrationTest`（2 分钟/次 IDE 启动）才爆。实证：验证报告 §4.3 缺陷 #1（`PLUGIN_ID` 未随改名更新导致 BrandSmoke 必失败）正是这条维护缺口的直接后果。
建议：typecheck 追加 `:compileIntegrationTestKotlin`，detekt source 追加 integrationTest 目录。

**P1-20 CLI pin 与 changesets 版本共用同一字段**
`package.json:11` + `backend/build.gradle.kts:40-43`

```kotlin
val pinnedCliVersion = providers.fileContents(rootProject.layout.projectDirectory.file("package.json")).asText.map { text ->
    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: error(...)
```

`version: 7.4.23` 由 00640da67a 手动 pin，但 `.changeset/config.json`（`privatePackages` 未配置、`ignore: []`）会对 14 个 `"@kilocode/kilo-jetbrains": patch/minor` changeset（复核修正：原稿写 6）执行 bump——CHANGELOG.md 顶部 7.5.0（全为 JetBrains 条目）与手动 pin 回 7.4.23 的操作痕迹说明两套版本语义已在互相覆盖。changesets 版本 PR 把 version 推到 7.5.x/7.4.24 → openapi 生成/CLI staging 拉取不存在或非预期的 CLI 版本。
建议：CLI pin 移到独立属性文件，或将 `@kilocode/kilo-jetbrains` 移出 changesets 版本范围。

**P1-21 CloudHub Enable/Disable 的 UI「翻转」断言恒真**
`src/integrationTest/.../CloudHubPanelTest.kt:63-65,77-79`

初始 fixtures 里 fav-skill-1 本就是 `Active`（:32）、fav-command-1 本就是 `Unloaded`（:35），故两个 `awaitSettingsText({ it == "Active"/"Unloaded" })` 在点击前即满足——徽章是否真的翻转无从验证（状态化断言只剩 daemon 侧 POST 计数）。同理 :104 的降级断言 `contains("Costrict") || contains("Cloud Hub")` 对任何正常设置页恒真。建议按行定位（先断言 Enable 前 fav-command-1 行文本为 Unloaded，再断言翻转为 Active）。

**P1-22 插件 ID 变更是断更级用户可见变更，却没有任何 changeset 记录**
`src/main/resources/META-INF/plugin.xml:4`

```xml
<id>ai.costrict.jetbrains</id>
```

`grep costrict .changeset/*.md` 无一条提及 ID/品牌迁移。已安装 `ai.kilocode.jetbrains` 的用户升级后 IDE 视为不同插件（并存或断更），属于必须出现在 changelog/迁移说明的变更。建议补 changeset + 迁移说明。

### P2

| # | 位置 | 问题 |
|---|---|---|
| P2-42 | `mock/FakeCsCloudDaemon.kt:45` | 固定端口 49187 落在 Windows 默认动态端口范围（49152-65535），其他进程随机出站连接即可占用；BindException 时整套 T2 失败无回退，叠加 `maxParallelForks = 1` 串行，ConnectionLifecycleTest 还依赖 stop→同端口 start 重绑。建议默认改随机端口并把 baseUrl 回写进隔离 home 的 server_url |
| P2-43 | `FakeCsCloudDaemon.kt:221` | 未知路径一律 200 `{}` 兜底，协议面漂移静默通过（`/api/v1/runtime/find/file`、telemetry、`/ai-gateway/models` 等未模拟端点全被吞）。Scenario.kt:139 `healthyBody` 恒带 capabilities，集成层无「无 capabilities」变体（仅 CsCloudHealthTest.kt:57 单测覆盖）；Scenario.kt:213 `textPart` 的 part 内 `sessionID` 误填 `messageId`（当前解析器取外层 props.sessionID 故无影响，但 fixture 数据失真）。建议兜底改 404 + 白名单放行 telemetry；补空 capabilities 集成变体 |
| P2-44 | `IntegrationTestBase.kt:283-287` 等 | 「settle 后断言」固定窗口（默认 sleep 2000ms 后取反）是漏检方向的假绿窗口：M22 断言同理，IDE 关闭钩子若在 2 秒后发出 abort，断言已通过。建议对可等待信号改「先等正向事件到达，再取反断言」 |
| P2-45 | `IntegrationTestBase.kt:94-101` | CIServer 传输中断白名单按子串匹配（"Connection reset" 等），产品代码在连接被重置时抛出的未处理异常同样命中被静默。建议收窄为结构化判定，或至少记录被吞条目 |
| P2-46 | `IntegrationTestBase.kt:300-309` | M20a 凭据泄漏断言近乎恒真：`configureDaemonEndpoint()` 写入的 config.json 恒为 `{"api_key":""}`，未设相关环境变量时 secrets 为空列表、循环零次——「凭据不落日志」断言无输入可泄漏。建议注入已知假 key（如 `it-test-secret`）并断言日志无该值 |
| P2-47 | `MultiProjectSmokeTest.kt:32` | gate 专用第二项目目录（`-b` 后缀 sibling）不参与 tearDown 清理（仅 multiProject gate 触发时残留）；PluginTest（:74-81）未做 home 隔离，与基类隔离策略不一致（懒连接语义下无网络副作用，Starter 沙箱仍读真实 user.home） |
| P2-48 | `build.gradle.kts:34-77` | Release/releases/release/selected 约 45 行死代码：changelog notes 改为固定 Costrict 文案后，`selected()`/`releases()`/`Release` 数据类与 `release(String)` 解析函数全部失去调用者（Kotlin 顶层函数无未使用告警，detekt 扫不到） |
| P2-49 | `gradle/libs.versions.toml:10` | JUnit 版本三处不一致：catalog 5.11.4（integrationTest 实际被 Starter bom 提升到 5.13.4，docs/integration-test.md:50 自证），cs-cloud 硬编码 5.13.4 / launcher 1.13.4 绕过 catalog |
| P2-50 | `.idea/gradle.xml:19` | gradleJvm 从 `#JAVA_HOME` 硬编码为 `21`，与 18dbe7b34f 让 turbo 透传 JAVA_HOME 的方向相反；本机无名为 "21" 的已注册 JDK 时 IDEA 解析失败回落默认。建议保留 `#JAVA_HOME` 宏 |

### 核实通过项

- turbo JAVA_HOME（18dbe7b34f）：`globalEnv` + `globalPassThroughEnv` 双声明正确——既参与 hash 又在 strict 模式下透传给 gradlew ✓
- 6125f3be2d home 隔离对插件有效：产品端 `CsCloudConnectionProvider.kt:22` 确用 `user.home` 解析 `~/.costrict`，vmOptions 注入能真正重定向网络边界；fixture/临时 home tearDown 递归删除 ✓（注意 `CsCloudStarter.defaultDirs` 仍继承真实 PATH，沙箱可探到真实 csc，当前用例不触发）
- 测试不会进发布 zip：integrationTest 是独立 source set，PrepareSandboxTask 只装 main 输出，G5 门禁遍历 zip 条目也不会有测试类 ✓
- split-mode 模块声明一致：settings.gradle include 五模块 ↔ 根 build pluginModule 五项 ↔ plugin.xml content 四 module、EP 命名空间一致；类型检查已入 typecheck ✓
- T2 基建八项修复真实落地（单线程 executor → cachedThreadPool、awaitNewRequest off-by-one、session-modes 必填字段、懒连接基线、locale 强制 en 等）与代码逐一对应 ✓
- 无 capabilities 兼容：`CsCloudHealth.kt:27` `?.jsonArray?...orEmpty()` 兜底（复核修正：该兜底由 8e8691c437 引入；28a1d37450 改的是请求路由与 capability 判读，不含此文件）+ 单测覆盖 ✓

---

## 八、主线审查补充（小文件自查，非 subagent 范围）

| # | 位置 | 问题 |
|---|---|---|
| P2-51 | `KiloSessionRpcApiImpl.kt:157-163` | `abort()` 语义变更（非 404/410 抛异常，KiloBackendChatManager）合理，但 `try { chat.abort(...) } finally { release(ABORT) }` 中 abort 抛异常时仍无条件释放 session capability——极端情况下旧 run 仍在跑而锁已放行，允许新 prompt 与旧 run 并发 |
| — | `KiloBackendChatManager.start()` | 改收 base URL 经 `ConnectionTarget` 归一（属会话路由专项范围，已由专项 4 覆盖） |
| — | `shared/log/ChatLogSummary.kt` | `SessionResult` 事件摘要/错误分支三处同步补齐，与测试一致 ✓ |
| — | `shared/KiloPlugin.kt` | 插件 ID 常量更新干净 ✓ |
| — | `packages/opencode/test/kilocode/session-list.test.ts` | 遗留正斜杠目录行匹配测试（对应既有伪失败清单的路径分隔符族），实现合理 ✓ |
| — | `.gitignore` | 新增 `out/`、`bug/` ✓ |

---

## 九、合并建议

**必修（合并前）**
1. P0-1 MCP lease epoch（daemon 重启不重绑 → 会话静默丢 IDE 工具）
2. P1-7 + P1-8（MCP 门控全放行无提示；关闭路径 token 撤销必然早退）
3. P1-1（连接状态机并发互斥，SSE 覆盖 + OkHttp 泄漏）
4. P1-3 + P1-4（★双验证：Windows 进程树击杀、管道排空——真实机路径且恰是测试盲区）
5. P1-13（Profile 死按钮 ISE）
6. P1-15（审查目标 Windows 大小写失配静默降级，或至少加提示）

**应修（低成本高收益）**
- P1-17 ~ P1-22 流程类：CI 挂接、测试聚合补模块、typecheck 覆盖 integrationTest、插件 ID changeset、CLI pin 与 changesets 解耦
- P2-31（503 引导）、P2-41（文档链接包名）、P2-35（zh key 缺失）

**可带病合并**
- P1-14（16 小语种包 Kilo 文案——扫描脚本已有 backlog 开关）、其余 P2 清单

**测试可信度**
T0/T1 + BrandSmoke/ConnectionLifecycle 两个绿类可作合并依据；T2 受阻 4 例需空闲重跑（与 2026-09-03 验证轮结论一致）；CloudHub 翻转断言恒真需先修测试再言验证；门禁挂接前不宜把自动化当独立验收证据。

**逐主线一句话**
- 主线 1：功能闭环成立，连接状态机并发防护与 Windows 进程收尾是合并阻塞项。
- 主线 2：「协议对、时序错」——数据面契约高度一致，生命周期主干四个承诺全部失效或缺失，P0/P1 修复前不宜宣称 P0 能力桥达成。
- 主线 3：en/zh 双语零残留，16 小语种包是最大残留面。
- 主线 4：贴合 spec 最薄定位，残差在错误恢复最后一公里与静默失败。
- 主线 5：八处隐藏入口落地质量好（藏入口留代码、注释基本如实），Reinstall 漏藏一半。
- 主线 6：引导主链路闭环且优于评审文档要求，缺口集中在 Windows 真机路径与三个边缘态。
