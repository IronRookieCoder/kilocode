# Costrict 验证自动化改造方案（人工项 → 自动化映射）

> 日期：2026-09-02。
> 依据：`packages/kilo-jetbrains/docs/integration-test.md`（Starter/Driver 集成测试，已端到端跑通）+ JetBrains IDE MCP 工具（IDEA 内置 MCP server）。

## 1. 自动化层级定义

| 层级 | 载体 | 运行方式 | 特点 |
|---|---|---|---|
| T0 | 静态检查（grep / 文件断言） | `./gradlew` 外任意脚本 | 秒级，已有 §3.1 |
| T1 | 单元 / 组件测试（`BasePlatformTestCase`） | `./gradlew :frontend:test :cs-cloud:test :backend:test` | 秒-分钟级，测逻辑不测真 UI |
| T2 | **Starter/Driver 集成测试**（`src/integrationTest`） | `./gradlew integrationTest`（真实 IDE + 插件 ZIP，单次约 2 分钟） | 测真实安装启动、RPC 链路、真实 UI 状态；产物含 IDE 日志 / 截图 |
| T3 | **JetBrains IDE MCP 辅助**（Claude 驱动 dev IDE） | 会话内工具调用，非 CI | 对**真实云端**项做非挂起探针与终端操作，替代人眼盯屏 |
| 人工 | runIde 目检 | — | 仅视觉审美、多窗口、真实云端验收 |

> T0-T3 是**执行载体**维度；§4 另按**验证属性**分功能性（F）/ 非功能性（NF）两组，两维度正交——F/NF 各自按成本选择载体，执行顺序 F 先于 NF（§6/§8）。

## 2. 关键使能：FakeCsCloudDaemon（脚本化 mock daemon）

插件所有网络边界都收敛于 `~/.costrict/cs-cloud/server_url` 指向的 daemon（resolver 优先级 CS_BRIDGE_API_KEY > CS_CLOUD_API_KEY > config.json，key blank 即免鉴权——M1.1 已单测）。**集成测试将该文件重定向到测试进程内的 mock server**，即可在不依赖云端账号的前提下回放任意服务端行为。

```kotlin
// src/integrationTest/kotlin/ai/kilocode/jetbrains/mock/FakeCsCloudDaemon.kt
class FakeCsCloudDaemon(val port: Int = FIXED_PORT) {
    // JDK 内置 com.sun.net.httpserver.HttpServer，零新增依赖；SSE 用 chunked + text/event-stream
    // 端口固定（高位约定值）：M16.3「重启 mock → 插件重试原地址」依赖重启后端口不变，
    // 随机端口会迫使每次重启重写 server_url，引入额外时序。
    val requests = CopyOnWriteArrayList<RecordedRequest>()   // method/path/headers/body，供断言
    lateinit var scenario: Scenario                          // 每用例注入的响应剧本
    fun start() / stop()                                     // Scenario 内含：状态码、延迟、envelope、SSE 事件序列、中途断连
}
```

| 能力 | 支撑的验证项 |
|---|---|
| `/api/v1/runtime/health` + `/api/v1/events` SSE 正常剧本 | M10.1 就绪、M4 联动 |
| 基础只读端点（`/api/v1/agents/models`、`/agents/commands`、`/agents/config`、`/agents/session-modes`、`/runtime/path` 等） | M10.2 模型目录、J3/J5 目录数据及会话前置数据 |
| 延迟响应（可配 delay） | B9/B15 busy 态 |
| 401 / 404 / 503 / 5xx / `{ok:false}` envelope | B7、M18、M28、M26、B12 |
| 不监听端口 / 畸形 / 非 loopback server_url | B6、M17、M25、M20b |
| favorites 四类混合数据 + Enable/Disable POST 记录 | B8、B10/B11（UI 侧） |
| `/conversations*` CRUD（含重命名/删除/abort） + `/prompt/async` + **脚本化 SSE 事件流回放**（assistant delta / tool call / status / permission / question / compact / revert） | M11-M14 会话闭环、C7 阶段渲染 |
| `/api/v1/permissions*`、`/api/v1/questions*` 的 **POST 回执记录** | M13.1-M13.4 批准/拒绝/作答回执、J6 自动回执断言 |
| SSE 中途 close 再恢复 | M16.1-M16.3、M21 |
| IDE 关闭期间未收到 stop 类请求 | M22 关闭语义 |
| 请求记录断言 `X-Workspace-Directory` 恒等项目根 | M15、M23.1（单窗口侧） |

> 表中端点均为 daemon 侧真实路径（统一 `/api/v1` 前缀），以 `cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudRoute.kt` 映射表为准——插件侧别名（如 `/global/health`、`/session`）经 interceptor 映射后才到达 mock，FakeCsCloudDaemon 按映射后路径实现。

工程要点：

1. 抽 `IntegrationTestBase`：现有 `PluginTest` 的 DI 覆写（ExistingIdeInstaller / CIServer）上移，补充「**TempDir 临时生成测试项目**（README + 空目录，不依赖母方案 E6 机器路径）+ 启动 mock daemon（固定端口）+ 改写/还原 server_url + key 置空」的 setUp/tearDown；**测试必须串行**（`~/.costrict` 是机器级共享状态），并在 build.gradle.kts 显式固化 `maxParallelForks = 1` 防回归。
2. **用例组织模型**：Starter 模型下 1 个 `@Test` = 1 次完整 IDE 启动关闭（`PluginTest` 现状），故采用**「类 = 一个 IDE 会话，场景段 = 会话内顺序断言单元」**编排（每类 1-2 个长测试方法），否则用例数 ≈ IDE 启动次数（见 §5 预估）。约束：ConnectionService 状态机不回退，已 ready 的会话内重演的是「状态降级路径」而非「首次连接路径」——B6/B7/M17/M18 可等价重演（面板按当前连接状态渲染）；B5（未装 csc）不可重演，依赖遮蔽机制（要点 6）。
3. server_url 改写是本机破坏性操作——tearDown 必还原，另以**原始值记录 + JVM shutdown hook 双保险**（防进程被杀残留 mock 死端口地址）；且运行窗口内同机其他 Costrict 客户端（dev IDE、登录态环境）重连会读到 mock 地址，跑 T2 前应知悉。与母方案 §2 破坏性手册同源。
4. M20a（key 不落日志）顺势自动化：用例内对框架收集的 IDE 日志跑正则断言，不再人工 grep。
5. mock 回放测的是「插件侧对协议的处理」，**不证明真实 agent 语义**——该项留给 T3/人工抽样，通过标准已区分。
6. B5「未装 csc」在本机不可天然模拟（母方案 E1 已装 csc）：优先 **CI-only**（`assumeTrue`——CI runner 无 `@costrict/csc` 全局包，`CsCloudStarter.findCsc` 天然失败）；若需本机可回归，再给 `toolDirs` 加系统属性覆盖注入点（如 `kilo.cscloud.tool.dirs`，默认 `defaultDirs()` 行为不变，仅测试设置），属 main 代码一行级改动。

## 3. T3：JetBrains IDE MCP 辅助（真实云端项）

dev IDE（打开本仓的 IDEA 实例）内置 MCP server，Claude 可直接驱动，用于不 mock 的真链路抽样：

| MCP 能力 | 用法 |
|---|---|
| `get_run_configurations` / `execute_run_configuration` | 拉起 `runIde` 沙箱，全量输出落盘可 `read_file` 回读 |
| `xdebug_start_debugger_session(configurationName="runIde")` | 以调试模式启动沙箱 |
| `xdebug_set_breakpoint(suspendPolicy="NONE", logExpression=…)` | **非挂起 logpoint 流量探针**：钉在 `CsCloudRoute.interceptor`、ConnectionService、ReviewArgs 构建、Notifier 发射点，`DRAIN_EVENTS` 读真实 RPC/SSE/报告内容 |
| `xdebug_evaluate_expression` | 断点处核对连接状态、DTO 字段（如 favorites 解析结果、报告计数） |
| `execute_terminal_command` | curl daemon、`tail` app.log、`grep` 凭证泄漏 |
| `lint_files` / `build_project` | 改动后的回归 |

定位：T3 是「Claude 代人执行真云端验收」的探针手段，结论仍回填结果模板；它**不能进 CI**，只降低人工成本与漏看率。

## 4. 逐项映射（功能性 / 非功能性分组，功能性优先）

> 分组规则：**功能性（F）**=正常输入与正常环境下设计行为的正确性（品牌呈现、正常渲染、CRUD 与闭环）；**非功能性（NF）**=异常环境/畸形输入/安全/长时运行/兼容下的行为（诊断、容错、恢复、安全、性能、长稳）。执行顺序 F 先于 NF（§6/§8）。编号沿用母方案 A/B/C/M/X/R，与母方案逐节对照关系不变；同一测试文件可同时承载 F 与 NF 场景段（见 §5 标注）。

### 4.1 功能性（F）

#### 用户旅程（J，基础功能主线）

> J 编号为本分册新增（不对应母方案编号）：从最终用户视角的横向旅程组合，补齐纵向切片（A-M）之间的完整性缝隙。凡与既有项断言重复处已标锚点、不重复计数——**验收仍按母方案编号项判定，J 项全绿作为附加完整性检查**。

| 项 | 级别 | 目标 | 自动化方式 | 残留人工 |
|---|---|---|---|---|
| J1 首次使用引导 | P0 | T2+T1 | 未登录态（mock 对鉴权接口回 401）打开工具窗 → 连接面板显示登录引导而非裸错误（`LoginRequiredView`，T1 已有）；登录入口为恢复动作（`CscLogin` 退出码语义 T1 已有）→ 就绪后接 M10.1 | 登录真流程 T3/人工抽验 |
| J2 多轮对话上下文 | P0 | T2 | 同会话连发两轮 prompt → mock 断言第二次 `/prompt/async` 请求体携带前轮 messages（上下文连贯的协议基础）；消息列表两轮完整渲染 | 真实 agent 语义 → T3 抽验 |
| J3 模型/模式切换即时生效 | P1 | T2 | 会话中经选择器切换模型/模式（`ModePicker`）→ mock 断言后续请求体携带新值；UI 徽标同步更新 | — |
| J4 输入辅助（@提及/附件） | P1 | T1+T2 | @文件补全与引用组装（PromptMentionParts T1 已有）；T2 断言 prompt 请求体含被引用文件路径；附件链路以 MVP 设计 spec 为准 | — |
| J5 slash 命令使用 | P1 | T2 | mock 提供 commands 目录 → 输入 `/` 出命令列表（`SlashAction`）→ 选择后 mock 收到的 prompt 为命令展开内容（`/review` 特例已由 C7 覆盖） | — |
| J6 设置页自动批准规则 | P1 | T2 | 设置页（`AutoApproveConfigurable`）增/删规则 → 会话中同类操作不再弹卡 / 恢复弹卡，mock 记录自动回执 POST（区别于 M13.4 会话内 always 与 M20c「无全局批准」底线） | — |
| J7 空态与加载态 | P2 | T2 | mock 返回空集合（无历史/无收藏/无模型）→ 空态文案而非白屏（EmptySessionPanel 同族）；加载中显示 loading 占位 | 视觉 |

#### 品牌（A）

| 项 | 级别 | 目标 | 自动化方式 | 残留人工 |
|---|---|---|---|---|
| A7 插件名/vendor | — | T2+T0 | BrandSmokeTest 断言已装插件元数据 name/vendor=Costrict；A2/A3 静态兜底 | — |
| A8 工具窗标签+图标 | — | T2+人工 | Driver UI 查询工具窗按钮文本=Costrict；图标加载异常经 CIServer 致败 | 图标审美看测试产物截图 |
| A9 深色主题可读性 | 可降级 | 人工/截图 | —（T2 产物截图可切 Darcula 生成，供快速目检） | 是 |
| A10 通知组显示名 | — | T1 | bundle `notification.group.kilo=Costrict` 值断言（显示名即 bundle 渲染） | — |
| A11 设置页 displayName | — | T1 | 实例化各 Configurable 断言 `getDisplayName` | — |
| A12 插图/Logo | — | T2+人工 | 资源加载不抛 + 组件存在（BrandSmokeTest） | 视觉 |
| A13 中文界面 | — | T1+人工 | zh_CN bundle 与 en **key 集合对齐**脚本断言 | 中文渲染错位抽查 1 轮 |

#### MVP 基础链路（M10-M15）

| 项 | 级别 | 目标 | 自动化方式（ConnectionLifecycleTest / SessionLoopTest） | 残留人工 |
|---|---|---|---|---|
| M10.1 就绪 | P0 | T2 | health+SSE 正常剧本 → ConnectionPanel 隐藏 | — |
| M10.2 模型加载 | P0 | T1+T2 | 归一化已有 M2.3；T2 断言选择器非空带前缀 | — |
| M10.3 恢复动作注册 | P1 | T2 | 死端口 → 菜单含 StartCsCloud、无 Reinstall | — |
| M11.1-3 会话 CRUD | P0/P1/P2 | T2 | mock 回放列表/新建/重命名/删除/历史 messages | 云会话导入 P2 可选 |
| M12.1-4 prompt/ReAct/abort/compact | P0/P1/P2 | T2 | 脚本化 SSE 事件流回放：增量文本、工具卡片、状态徽标、abort 后 idle | 真实 agent 抽验（T3） |
| M13.1-4 审批/问答/always | P0/P1 | T2 | mock 推 permission/question 事件 → UI 弹卡；批准 → POST 回执被 mock 记录；always → 规则保存断言 | — |
| M14.1 项目树刷新 | P0 | T2 | mock 事件 + 测试进程真实写文件 → 断言 VFS 刷新（兼 R2 新建子目录） | — |
| M14.2-3 编辑器/diff | P1/P0 | T2 | 打开生成文件内容断言；diff 视图断言（较重，可降为 RPC 层断言） | — |
| M14.4 外部改动对照 | P2 | 人工 | — | 可选 |
| M15 workspace 绑定 | P0 | T2+T0 | mock 侧校验器：所有转发请求 header == 项目根，否则用例失败 | — |

#### Cloud Hub 正常态（B8-B11、B14）

| 项 | 级别 | 目标 | 自动化方式（CloudHubPanelTest，全部经 mock daemon） | 残留人工 |
|---|---|---|---|---|
| B8 四类分组 | P0 | T2 | favorites 剧本含 4 类×4 状态 → 断言 4 section、Active 置顶+徽标、次级样式类 | — |
| B9 搜索/刷新/busy | P1 | T2 | 延迟 3s 剧本 → busy 可见；输入过滤断言行数变化 | — |
| B10 Enable | P0 | T2+T3 | mock 200 → 行变 Active（UI 断言）；mock 断言收到 POST | **真实会话可调用** → T3/人工抽验 |
| B11 Disable | P0 | T2+T3 | 同上（Disable 路径） | 同上 |
| B14 中英文 | P1 | T1 | bundle 断言（en 缺失回退逻辑为资源束标准行为） | — |

> **Settings modal 前置 gate 与降级路径**（适用于全部 B 行，含 §4.2 的 B5-B7/B12 及 B15）：Cloud Hub 宿主为 Settings 对话框子页（`CloudHubConfigurable`），而 Driver 驱动 modal Settings（打开 → 树导航至子页 → 断言面板渲染 → 点行内按钮）在本仓无验证记录。基建批先跑通 **gate 用例**（§8 步骤 1）作为 B 组准入；若 Driver 无法驱动 Settings，B 组 UI 断言统一降级为 ConnectionService/favorites **服务层状态断言**（RPC 层），Settings 渲染退回人工截图 1 轮——降级后 B8 分组/B10-B11 行状态以服务层状态为准，并在结果模板标注「UI 层未覆盖」。权限/问答弹卡（M13）为工具窗内 inline 视图（`PermissionView`/`QuestionView`，非 modal），不在此降级范围内。

#### Code Review 闭环（C6-C10、C12、C14-C15）

| 项 | 级别 | 目标 | 自动化方式 | 残留人工 |
|---|---|---|---|---|
| C6 无会话禁用 | P1 | T1 | 平台单测调用 action update() 断言 enabled=false + tooltip | — |
| C7 触发运行 | P0 | T2+T3 | mock 回放五阶段 SSE：prompt 含 `/review` 入会话（mock 记录请求体）、busy、阶段文本渲染 | **真实 agent 语义** → T3 探针抽验 |
| C8 通知+预览 | P0 | T2+T1 | 用例向项目根写 `review-report.{md,json}` → 断言 Markdown 预览编辑器打开；计数一致性已有 T1 | 通知文案人读 1 轮 |
| C9 三入口 args | P1 | T1 | ReviewArgsTest 覆盖 args 构建（语法/路径/选区回退）；**三入口 action → args 绑定**补 action 级 T1 | — |
| C10 abort 无误报 | P1 | T2 | 中途 abort → 断言未落报告文件（=无误报前提） | — |
| C12 空结果 | P1 | T2 | 回放 0 缺陷报告剧本 → 同 C8 断言 | — |
| C14 中英文 | P1 | T1 | bundle 断言 | — |
| C15 连续触发 | P1 | T2 | 写两版报告 → 两次通知各自弹出（或 T1 notifier 队列断言） | — |

#### 跨特性与风险实测（X/R）

| 项 | 级别 | 目标 | 方式 | 残留人工 |
|---|---|---|---|---|
| X3 通知组隔离 | — | T1 | 组件测试断言两组 distinct、事件不串 | — |
| X4 设置树顺序 | — | T1 | 枚举 Agent Behavior 子 Configurable 断言顺序含 …→ Skills → Cloud Hub | — |
| R1 `/review` 落点 | — | 人工/T3 | 真链路一次，T3 logpoint 探 `command` 通道实际命令与产物路径 → 回填 spec | 是 |
| R2 host.file.* 覆盖 | — | T2 | 并入 M14.1（新建子目录刷新）；`.draft-*` 抖动观察保留人工注记 | 注记 |

### 4.2 非功能性（NF）

#### 诊断与容错（连接/服务异常 → 类型化诊断与自愈）

| 项 | 级别 | 目标 | 自动化方式（ConnectionLifecycleTest / CloudHubPanelTest） | 残留人工 |
|---|---|---|---|---|
| M17 daemon 停 | P0 | T2 | mock 停 → 错误文案含诊断码；mock 复启 → 自动 ready | — |
| M18 错 key 401 | P0 | T2 | mock 回 401 → 「未授权」诊断（区别于 M17） | — |
| M19 csc 未就绪 503 | P1 | T2 | mock 先 503 后 200 → 轮询文案 → 自动 ready | — |
| M16.1-3 断连恢复 | P0 | T2 | SSE 中途 close → unavailable → 重启 mock → ready；pending permission 重放可作答；**prompt 不自动重发** | — |
| M21 未知态 | P1 | T2 | prompt 后立即断连 → 未知态文案 | — |
| M25.1-3 server_url 异常 | P1/P2 | T2 | 死端口/空值/局域网 IP/畸形 URL 矩阵 → 类型化诊断；非 loopback 断言 resolver 抛 `NonLoopbackUrl` 诊断且无网络活动（该地址不指向 mock，不能字面断言「mock 零请求」） | — |
| M26 envelope 可读性 | P1 | T2 | `{ok:false,error:{code}}` → detail 显示 code+消息 | — |
| M28 旧 daemon | P2 | T2 | 全 404 剧本 → 可诊断错误而非静默 | — |
| B5 未装 csc | P1 | T2 | CI-only（`assumeTrue`，遮蔽决策见 §2 要点 6）→ 打开面板断言 UNAVAILABLE 文案 + 恢复动作指向 InstallCsc | — |
| B6 daemon 停 | P1 | T2 | server_url 指向死端口 → UNAVAILABLE + StartCsCloud | — |
| B7 未登录 | P1 | T2 | favorites 返回 401 → 错误态指向登录入口、无新登录 UI | — |
| B12 404 自愈 | P1 | T2 | 先返回条目、操作时返回 404 → 行移除 + 通知 | — |
| C11 坏 json 降级 | P1 | T1 | 写坏 fixture → watcher 降级分支已测；T2 可选补 UI 文案 | — |

#### 安全边界（M20）

| 项 | 级别 | 目标 | 自动化方式 | 残留人工 |
|---|---|---|---|---|
| M20a key 不落日志 | P0 | T2 | 用例内正则断言测试产物 idea.log 无凭证 | — |
| M20b loopback | P1 | T2 | 同 M25.2 | — |
| M20c 无全局批准 | P1 | T1+T2 | 设置页断言 + M13.1 场景首操作必弹卡 | — |
| M20d 无写盘旁路 | P1 | T0 | grep 已有 | — |

#### 多窗口隔离、生命周期与长稳/兼容

| 项 | 级别 | 目标 | 自动化方式 | 残留人工 |
|---|---|---|---|---|
| B13 双窗口一致 | P1 | 人工 | —（两 IDE 实例成本高；单窗口渲染已由 B8-B11 F 覆盖） | 是 |
| C13 双窗口隔离 | P1 | 人工 | 过滤逻辑 T1 已覆盖（C3，F） | 是（1 轮） |
| M23.1-2 双窗口 | P0/P1 | 人工 | header 断言单窗口侧已由 M15（F）覆盖 | 是 |
| B15 首拉耗时 | P1 | T2 | 与 B9 同场景：busy 出现且 N 秒内场景完成，无假死 | — |
| M22 关闭语义 | P1 | T2 | IDE 关闭后断言 mock **未收到**任何 stop 类请求（先等待 IDE 进程完全退出——`useDriverAndCloseIde` 返回 ≠ 进程退出） | 真 daemon pid 检查简化为抽验 |
| M24 长会话 | P2 | 人工/nightly | mock 可跑 5min 冒烟流（可选） | 30min 真链路保留 |
| M27 split-mode | P1 | 人工 | Starter 不覆盖 split-mode | 是（4 步冒烟） |

## 5. 新增测试资产清单

```
src/integrationTest/kotlin/ai/kilocode/jetbrains/
  IntegrationTestBase.kt        Starter DI/插件安装/fixture 项目生成（TempDir）/场景段编排 + mock 生命周期 + server_url 重写还原
  mock/FakeCsCloudDaemon.kt     JDK HttpServer + SSE + Scenario 剧本 + 请求记录（零新依赖）
  mock/Scenario.kt
  ConnectionLifecycleTest.kt    [F] M10（连接侧）/ [NF] M16-M19/M20a/M21/M22/M25/M26/M28
  SessionLoopTest.kt            [F] M11-M15、C7-C8/C10/C12（复用回放）/ [NF] M20c
  CloudHubPanelTest.kt          [F] B8-B11/B14 / [NF] B15、B5-B7/B12
  BrandSmokeTest.kt             [F] A7/A8/A12
frontend/（T1 增补，均为 F）
  DisplayNameI18nTest           A10/A11/A13、B14、C14（bundle 对齐 + displayName）
  NotificationGroupIsolationTest X3
  SettingsTreeOrderTest         X4
  ReviewActionUpdateTest        C6
build.gradle.kts                仅一处增补：integrationTest 显式 maxParallelForks = 1（固化串行）；源集与任务已在，mock 用 JDK 内置 HttpServer 故零新增依赖（B5 若选注入点方案，另涉 CsCloudStarter 一行级改动）
```

预估（按 §2 要点 2 的「类 = IDE 会话」编排）：T2 新增 5 文件、约 25-30 个场景段（含 §4.1 用户旅程 J1-J7——复用上述 T2 类，不新增文件），对应 5-8 次 IDE 启动，单轮 `integrationTest` 约 15-25 分钟（含 Settings gate 用例；IDE 安装复用本地缓存）。若改为「每用例独立 IDE」（CI 失败定位更准）则为 25-30 次启动 ≈ 50-70 分钟——两模式的取舍已在 §2 要点 2 论证，默认取前者。

## 6. 改造后执行顺序（替代母方案 §13）

```
全自动（CI 可跑，一条命令链；T2 的 CI 前提：将 `.intellijPlatform/ides` 即 verifyPlugin 缓存纳入
  CI cache——未命中时 Starter 回退在线下载 IU，约 2GB/次且需可达 JetBrains 数据服务，本机网络已证实
  解析不了 2026.1 发布列表，见 integration-test.md）：
  T0 静态 → T1 全部单测（含新增）→ T2 integrationTest（mock 回放全套；先 §4.1 功能批后 §4.2 非功能批）
真链路抽样（T3，Claude 代跑，单轮）：
  runIde + 调试探针：B10/B11 真会话可调用、C7 真实 agent 语义、R1 落点、M22 daemon 存活
残留人工（收敛后约 8 项）：
  视觉：A8/A9/A12/A13（看 T2 产物截图即可，1 轮）
  多窗口：B13、C13、M23（1 轮）
  模式：M27 split-mode（4 步冒烟）
  长稳：M24（可选）
```

## 7. 通过标准增量（补充母方案 §15）

- 原「P0 全过」中所有 T2 可达项改为以 `integrationTest` 全绿为验收依据；T3 抽样项以探针记录回填结论
- 新增前置：`FakeCsCloudDaemon` 场景剧本纳入代码评审（剧本即测试语义，需与设计 spec 协议章节对照）
- B 系列若触发 §4.1 Hub gate 降级路径，以降级后判据为准，并在结果模板标注「UI 层未覆盖」
- **功能批（F）自动化项全绿是非功能批（NF）执行的前提**（F 组残留人工目检项不阻塞 NF）；NF 项失败按母方案 §15 级别规则处理（P1 记缺陷不阻塞、P2 可选降级）
- 残留人工项失败处理不变：视觉项按缺陷记录；多窗口/长稳项不阻塞

## 8. 实施顺序建议

1. **基建**：IntegrationTestBase（fixture 生成/场景段编排/还原双保险）+ FakeCsCloudDaemon（固定端口）+ health/SSE 正常剧本 + **Settings gate 用例**（打开 Settings → 定位 Cloud Hub 子页 → 断言面板存在；不过则启用 §4.1 Hub gate 降级路径）（跑通 M10.1 一条 T2）
2. **功能批（F）**：M10-M15 会话闭环（SSE 事件回放器在此落地，工作量最大）→ Hub 正常态 B8-B11/B14 → Review 闭环 C6-C10/C12/C14-C15（B 组以 gate 通过为前提）→ 用户旅程 J1-J7（复用上述场景基建做横向组合断言）
3. **非功能批·诊断容错（NF）**：M16-M21/M25/M26/M28（纯 mock 协议侧）→ B5-B7/B12、C11 异常态（B5 遮蔽机制在此步落地——先 CI-only，需要本机回归时再补注入点）
4. **非功能批·安全与长稳/多窗口（NF）**：M20a-d → M22/B15/M24/M27 → 双窗口人工 B13/C13/M23
5. **T1 增补批**：4 个单测文件（均为 F，独立于 T2，可随功能批先行）
6. 收尾：更新母方案结果模板的"执行方"列，残留人工清单定稿

> 排序说明：原「先协议侧后 UI 侧」的实施难度递进让位于「功能性优先」的验收价值——SSE 事件回放器因此提前到功能批落地；纯 mock 协议侧（M16-M21/M25/M26/M28）实现成本低且独立，作为 NF 首批顺延其后。
