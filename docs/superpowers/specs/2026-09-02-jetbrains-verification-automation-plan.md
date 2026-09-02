# Costrict 验证自动化方案（用户场景驱动 · 人工项 → 自动化映射）

> 日期：2026-09-02。
> 依据：`packages/kilo-jetbrains/docs/integration-test.md`（Starter/Driver 集成测试，已端到端跑通）+ JetBrains IDE MCP 工具（IDEA 内置 MCP server）+ 四份设计 spec（MVP / Cloud Hub v1 / Code Review P0-lite / 母方案技术方案）+ 插件入口盘点（`kilo.jetbrains.frontend.xml` / `KiloBundle` / `CsCloudRoute.kt`）。
> 组织方式：以**使用者视角**的主线组织——按「用户装完插件后能做的每一件事」给出完整场景覆盖（§5 地图、§6-§9 四维度），非功能性维度（稳定性/鲁棒性/安全性/兼容性）整体后置（§9）；每个场景标注自动化载体与母方案锚点。
> 编号体系：U（用户旅程场景，本文主索引）、J（用户旅程组合项）、A（品牌）/ B（Hub）/ C（Review）/ M（MVP 链路）/ X（跨特性）/ R（风险实测）为母方案锚点，互通对照。**★ 标记本方案新增场景或基建**。

## 1. 目标与自动化原则

**目标**：用户从安装插件到完成一次 AI 编码、一次 Cloud Hub 管理、一次代码审查的全过程，每一步可见行为都有自动化断言；人工只保留三类不可自动化项——视觉审美、真实云端 agent 语义、多独立 IDE 进程。

原则：

1. **载体选择就低不就高**：能 T0 静态断言不写 T1，能 T1 组件测试不启 T2，能 T2 mock 回放不占 T3/人工。
2. **一个用户场景 = 至少一个断言**：§5 地图中场景步没有载体标注即视为覆盖缺口，须补自动化或显式降级为人工并给理由。
3. **T2 编排复用即覆盖**：IDE 启动成本高的场景（冷启动重连、设置持久化）通过统一基线断言和重启编排「免费」覆盖，不单独立用例。
4. **真实 agent 语义只抽验**：mock 回放证明插件侧协议处理正确，不证明真实 agent 语义；csc 真实语义（含母方案五子棋验收）收敛到 T3 单轮抽样，由 Claude 代跑。

**属性分组（F/NF）**：功能性（F）= 正常输入与正常环境下设计行为的正确性（品牌呈现、正常渲染、CRUD 与闭环）；非功能性（NF）= 异常环境/畸形输入/安全/长时运行/兼容下的行为。§6-§8 三个功能维度 = F；§9 = NF；执行顺序 F 先于 NF（§12）。错误态场景（如首次连接的引导）在旅程内出现一次（用户视角归属 §6/§8），深度异常矩阵统一在 §9。

## 2. 自动化层级定义

| 层级 | 载体 | 运行方式 | 特点 |
|---|---|---|---|
| T0 | 静态检查（grep / 文件断言） | `./gradlew` 外任意脚本 | 秒级 |
| T1 | 单元 / 组件测试（`BasePlatformTestCase`） | `./gradlew :frontend:test :cs-cloud:test :backend:test` | 秒-分钟级，测逻辑不测真 UI |
| T2 | **Starter/Driver 集成测试**（`src/integrationTest`） | `./gradlew integrationTest`（真实 IDE + 插件 ZIP，单次约 2 分钟） | 测真实安装启动、RPC 链路、真实 UI 状态；产物含 IDE 日志 / 截图 |
| T3 | **JetBrains IDE MCP 辅助**（Claude 驱动 dev IDE） | 会话内工具调用，非 CI | 对**真实云端**项做非挂起探针与终端操作，替代人眼盯屏 |
| 人工 | runIde 目检 | — | 仅视觉审美、多窗口、真实云端验收 |

> T0-T3 是**执行载体**维度；§5-§9 是**用户场景/验证属性**维度，两维正交——每个场景按成本选择载体。

## 3. 关键使能：FakeCsCloudDaemon 与增量基建

### 3.1 FakeCsCloudDaemon（脚本化 mock daemon）

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
| `/api/v1/runtime/health` + `/api/v1/events` SSE 正常剧本 | U2.1 就绪、M4 联动 |
| 基础只读端点（`/api/v1/agents/models`、`/agents/commands`、`/agents/config`、`/agents/session-modes`、`/runtime/path` 等） | U2.2 模型目录、J3/J5 目录数据及会话前置数据 |
| 延迟响应（可配 delay） | B9/B15 busy 态 |
| 401 / 404 / 503 / 5xx / `{ok:false}` envelope | B7、M18、M28、M26、B12 |
| 不监听端口 / 畸形 / 非 loopback server_url | B6、M17、M25、M20b |
| favorites 四类混合数据 + Enable/Disable POST 记录 | B8、B10/B11（UI 侧） |
| ★状态化目录（收到 favorites load POST 后，`/agents/commands`、`/agents/session-modes` 等目录端点返回新增条目） | ★U6.6 启用即生效联动 |
| `/conversations*` CRUD（含重命名/删除/abort） + `/prompt/async` + **脚本化 SSE 事件流回放**（assistant delta / tool call / status / permission / question / compact / revert） | U3/U4 会话闭环、C7 阶段渲染 |
| `/api/v1/permissions*`、`/api/v1/questions*` 的 **POST 回执记录** | M13.1-M13.4 批准/拒绝/作答回执、J6 自动回执断言 |
| SSE 中途 close 再恢复 | M16.1-M16.3、M21 |
| IDE 关闭期间未收到 stop 类请求 | M22 关闭语义 |
| 请求记录断言 `X-Workspace-Directory` 恒等项目根 | U2.7/M15、M23.1（单窗口侧） |

> 表中端点均为 daemon 侧真实路径（统一 `/api/v1` 前缀），以 `cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudRoute.kt` 映射表为准——插件侧别名（如 `/global/health`、`/session`）经 interceptor 映射后才到达 mock，FakeCsCloudDaemon 按映射后路径实现。

工程要点：

1. 抽 `IntegrationTestBase`：现有 `PluginTest` 的 DI 覆写（ExistingIdeInstaller / CIServer）上移，补充「**TempDir 临时生成测试项目**（README + 空目录，不依赖母方案 E6 机器路径）+ 启动 mock daemon（固定端口）+ 改写/还原 server_url + key 置空」的 setUp/tearDown；**测试必须串行**（`~/.costrict` 是机器级共享状态），并在 build.gradle.kts 显式固化 `maxParallelForks = 1` 防回归。
2. **用例组织模型**：Starter 模型下 1 个 `@Test` = 1 次完整 IDE 启动关闭（`PluginTest` 现状），故采用**「类 = 一个 IDE 会话，场景段 = 会话内顺序断言单元」**编排（每类 1-2 个长测试方法），否则用例数 ≈ IDE 启动次数（见 §11 预估）。约束：ConnectionService 状态机不回退，已 ready 的会话内重演的是「状态降级路径」而非「首次连接路径」——B6/B7/M17/M18 可等价重演（面板按当前连接状态渲染）；B5（未装 csc）不可重演，依赖遮蔽机制（要点 6）。
3. server_url 改写是本机破坏性操作——tearDown 必还原，另以**原始值记录 + JVM shutdown hook 双保险**（防进程被杀残留 mock 死端口地址）；且运行窗口内同机其他 Costrict 客户端（dev IDE、登录态环境）重连会读到 mock 地址，跑 T2 前应知悉。与母方案 §2 破坏性手册同源。
4. M20a（key 不落日志）顺势自动化：用例内对框架收集的 IDE 日志跑正则断言，不再人工 grep。
5. mock 回放测的是「插件侧对协议的处理」，**不证明真实 agent 语义**——该项留给 T3/人工抽样，通过标准已区分。
6. B5「未装 csc」在本机不可天然模拟（母方案 E1 已装 csc）：优先 **CI-only**（`assumeTrue`——CI runner 无 `@costrict/csc` 全局包，`CsCloudStarter.findCsc` 天然失败）；若需本机可回归，再给 `toolDirs` 加系统属性覆盖注入点（如 `kilo.cscloud.tool.dirs`，默认 `defaultDirs()` 行为不变，仅测试设置），属 main 代码一行级改动。

### 3.2 增量基建（★本方案新增）

| # | 基建 | 说明 | 服务场景 |
|---|---|---|---|
| G1 | 冷启动基线断言 | `IntegrationTestBase` 提供统一首断言：IDE 启动后 N 秒内连接自动 ready（mock 常开）。所有 T2 测试类免费覆盖「重启 IDE 后自动重连」 | U2.6、U4.3、U5.3 |
| G2 | ColdRestart 编排 | 一个测试类内两次 IDE 启动：第一次尾部改设置 + 建会话 → 关闭重启 → 断言 ready + 设置保留 + 历史会话恢复（多付 1 次启动，覆盖 3 个场景） | U4.3、U5.3 |
| G3 | 状态化 mock 目录 | 已并入 §3.1 能力表（POST 副作用驱动目录端点返回变化） | U6.6 |
| G4 | 品牌一致性扫描（T0 脚本） | 规则①：frontend xml + 全部 bundle 的**用户可见字符串**中 `Kilo` 残留 = 0（白名单：内部 ID `Kilo.*`、文件名 `kilo.svg`、包名 `ai.kilocode.*`）；规则②：品牌拼写唯一——现状 16 处 `CoStrict` vs 61 处 `Costrict` 并存，**产品定标后**断言另一种拼写 = 0 | U8.6 |
| G5 | 插件 ZIP 依赖门禁（T0 脚本） | 断言 buildPlugin 产物 `lib/` 不含平台已捆绑的库（kotlinx-serialization 等）——近期 cloudFavorites LinkageError 的根因即重复打包，静态可查 | 回归钉子 R-4、U1.3 |

## 4. T3：JetBrains IDE MCP 辅助（真实云端项）

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

## 5. 用户旅程覆盖地图

完整性以本表为准：**每个场景步在 §6-§9 都有对应行**，无「未评估」格即视为覆盖完整（§13 通过标准的验收维度之一）。

| 旅程 | 用户一句话 | 维度 | 场景步 | 主要载体 |
|---|---|---|---|---|
| U1 安装与启用 | 装上插件就能看到 Costrict | 基础能力 | 3 | T2+T0 |
| U2 首次连接与登录 | 不装 csc / 不启 daemon / 未登录都有引导，就绪后自动可用 | 基础能力 | 7 | T2 |
| U3 日常 AI 编码（核心闭环） | 新建会话 → 提问 → 审批 → 落盘 → diff → 继续多轮 | 插件功能 | 17 | T2+T1 |
| U4 会话管理 | 历史 / 恢复 / 重命名 / 删除 / 停止 / 压缩 / worktree | 插件功能 | 6 | T2+T1 |
| U5 个性化与设置 | 设置树可逛、可改、改完留得住 | 插件功能 | 5 | T2+T1 |
| U6 Cloud Hub 管理 | 浏览四类收藏、搜索、启停、启用了就能用 | Costrict 服务 | 8 | T2 |
| U7 Code Review | 三个入口触发 → 会话运行 → 通知 → 看报告 | Costrict 服务 | 10 | T2+T1+T3 |
| U8 品牌替换与本地化 | 处处 Costrict，中英文正常 | Costrict 服务 | 7 | T0+T1+T2 |
| U9 异常与恢复 | 坏了有诊断，好了能自愈（**优先级后置**） | 非功能性 | 13 | T2+T1 |

> 计数口径：U9 步数仅计 §9.1 诊断容错矩阵（13 步）；§9.2 安全与 §9.3 多窗口/长稳不占场景步计数，按母方案锚点行直接验收，执行记录要求与 §13 相同。

## 6. 维度一：基础能力

### U1 安装与启用

| 场景 | 级别 | 用户可见行为断言（自动化方式） | 载体 | 锚点 |
|---|---|---|---|---|
| U1.1 安装启动 | — | 构建 ZIP 装进真实 IU → IDE 启动无异常、已装插件元数据 name=Costrict、vendor=Costrict（url=costrict.ai）、工具窗按钮文本 = Costrict | T2 | A7/A8（PluginTest 现状扩展） |
| U1.2 图标与空态 logo | — | 工具窗 icon、空态 BrandLogo 加载不抛异常（CIServer 兜底）；截图产物供目检（含 Darcula/zh_CN 变体） | T2+人工1轮 | A8/A9/A12 |
| U1.3 ★发布包健康 | P1 | ZIP `lib/` 无重复捆绑平台库（G5 门禁，回归钉子 R-4）；v5 设置迁移在旧配置 fixture 下行为正确（T1 已有 KiloMigrationServiceTest） | ★T0+T1 | ★ |

> 插件禁用/卸载回归不在本方案范围（P2+，价值低）。

### U2 首次连接与登录

| 场景 | 级别 | 用户可见行为断言（自动化方式） | 载体 | 锚点 |
|---|---|---|---|---|
| U2.1 就绪 | P0 | health + SSE 正常剧本 → ConnectionPanel 隐藏 | T2 | M10.1 |
| U2.2 目录加载 | P0 | 模型/模式选择器非空带前缀（归一化 T1 已有：M2.3 + ModePicker/ModelPicker 系列） | T1+T2 | M10.2/M2.3 |
| U2.3 未装 csc 引导 | P1 | 面板 UNAVAILABLE 文案 + 恢复动作指向 Install csc（CI-only，遮蔽机制见 §3.1 要点 6） | T2 | B5 |
| U2.4 daemon 停引导 | P1 | 类型化诊断 + 菜单含 StartCsCloud 动作、无 Reinstall | T2 | M10.3/B6 |
| U2.5 未登录引导 | P0 | mock 对鉴权接口回 401 → LoginRequiredView 显示「Sign in to CoStrict」登录入口而非裸错误（CscLogin 退出码语义 T1 已有）；**登录真流程（浏览器 OAuth）→ T3 抽验** | T2+T3 | J1/B7 |
| U2.6 ★冷启动自动重连 | P1 | IDE 每次启动 N 秒内自动 ready——所有 T2 类统一基线断言（G1），零额外启动成本 | ★T2 | ★ |
| U2.7 workspace 绑定 | P0 | mock 侧校验器：所有转发请求 `X-Workspace-Directory` 恒等项目根，否则用例失败 | T2+T0 | M15 |

## 7. 维度二：插件功能

### U3 日常 AI 编码核心闭环（P0 主线）

| 场景 | 级别 | 用户可见行为断言（自动化方式） | 载体 | 锚点 |
|---|---|---|---|---|
| U3.1 新建会话 | P0 | 工具栏 New Session / slash `new` → mock 收到 conversations 创建 POST | T2 | M11.1 |
| U3.2 发送 prompt 流式回答 | P0 | 脚本化 SSE 回放：增量文本连续渲染、顺序正确 | T2 | M12.1/J2 |
| U3.3 ReAct 工具卡片 | P1 | edit/read/shell/task 等卡片渲染：标题、状态徽标、diff 徽章；**pending edit/read 卡片不渲染重复文件链接标题**（回归钉子 R-2） | T2+T1 | M12.2 |
| U3.4 权限卡批准 | P0 | 弹卡 → Allow → mock 记录批准 POST 回执 → 会话继续 | T2 | M13.1 |
| U3.5 ★权限卡拒绝 | P0 | Deny → mock 记录拒绝回执 → 会话恢复可交互（「拒绝后不落盘」的真语义归 T3） | ★T2 | M13.1 变体 |
| U3.6 always 规则 | P1 | 卡上勾选 always → 规则保存断言，后续同类不弹卡 | T2 | M13.4 |
| U3.7 问答卡作答 | P0/P1 | 单选/多选/自定义答案提交 → mock 记录回执 | T2 | M13.2/M13.3 |
| U3.8 文件落盘刷新 | P0 | mock 事件 + 测试进程真实写文件 → VFS 刷新、项目树可见（兼新建子目录，即 R2 host.file.* 覆盖） | T2 | M14.1/R2 |
| U3.9 diff 查看 | P0/P1 | 打开生成文件内容断言；diff 视图断言较重可降为 RPC 层断言 | T2 | M14.2-3 |
| U3.10 ★回答复制 | P1 | 消息工具栏 SessionCopyButton 复制回答内容（若无既有覆盖补组件测试） | ★T1 | ★ |
| U3.11 停止按钮 | P1 | 运行中 Stop → 会话收敛 idle；**SSE 断连孤儿 run 后 Stop 也必须落地**（回归钉子 R-1）；abort RPC 404/410 记为静默成功（T1 增补） | T2+T1 | M12.3 |
| U3.12 多轮上下文 | P0 | 第二轮 prompt 请求体携带前轮 messages；两轮完整渲染 | T2 | J2 |
| U3.13 压缩会话 | P2 | compact 后会话可用、状态正常 | T2 | M12.4 |
| U3.14 上下文输入 | P1 | @文件补全与引用组装（T1 已有 PromptMentionParts/EditorContextGatherer）；prompt 请求体含被引用路径；选区/当前文件上下文 | T1+T2 | J4 |
| U3.15 slash 命令 | P1 | 输入 `/` 出列表（new/sessions/models/agents/variant/compact/settings/help）→ 选择后 mock 收到展开内容（`/review` 特例由 U7.2 覆盖） | T2 | J5 |
| U3.16 模型/模式切换 | P1 | 会话中经选择器切换 → 后续请求体携带新值，UI 徽标同步 | T2 | J3 |
| U3.17 外部改动对照 | P2 | 人工可选，非每轮 | 人工 | M14.4 |

### U4 会话管理

| 场景 | 级别 | 用户可见行为断言（自动化方式） | 载体 | 锚点 |
|---|---|---|---|---|
| U4.1 历史与恢复 | P1 | History 面板列表 → 打开历史会话、messages 完整回放 | T2 | M11.2 |
| U4.2 重命名/删除 | P2 | 上下文菜单 Rename/Delete → mock 收到对应请求、UI 同步 | T2 | M11.3 |
| U4.3 ★重启后会话仍在 | P1 | ColdRestart 第二次启动后历史会话可恢复（G2） | ★T2 | ★ |
| U4.4 空态/加载态 | P2 | 空集合剧本（无历史/无收藏/无模型）→ 空态文案非白屏；loading 占位 | T2 | J7 |
| U4.5 ★Worktree 冒烟 | P2 | New Worktree → 列表渲染 → 打开会话编辑器（T1 已有 15 个 worktree 测试类，T2 只补一条真实 UI 链） | ★T2 | ★ |
| U4.6 云会话导入 | P2 | 可选，非目标 | — | M11.3 |

### U5 个性化与设置

| 场景 | 级别 | 用户可见行为断言（自动化方式） | 载体 | 锚点 |
|---|---|---|---|---|
| U5.1 设置树完整可逛 | — | 全部 Configurable 可实例化、displayName 为 Costrict 品牌、顺序含 …→ Skills → Cloud Hub（页面清单：User Profile / Models / Providers / Agent Behavior(六子页) / Auto-Approve / Context / Advanced） | T1 | A11/X4 |
| U5.2 自动批准规则 | P1 | 设置页（AutoApproveConfigurable）增/删规则 → 会话中同类操作不再弹卡 / 恢复弹卡，mock 记录自动回执；**默认无全局批准**（区别于 U3.6 会话内 always） | T2+T1 | J6/M20c |
| U5.3 ★设置改完留得住 | P1 | ColdRestart：改设置 → 重启 → 断言保留（G2） | ★T2 | ★ |
| U5.4 ★cs-cloud 模式遗留页 | P2 | Providers / User Profile 页可打开不崩（cs-cloud 模式下产品去留**待产品确认**，先以「可打开+品牌正确」为底线） | ★T1+产品确认 | ★ |
| U5.5 中英文 | — | zh_CN 与 en bundle key 集合对齐 + 缺失回退行为（资源束标准行为） | T1 | A13/B14/C14 |

## 8. 维度三：Costrict 服务

### U6 Cloud Hub（Settings → Tools → Costrict → Agent Behavior → Cloud Hub）

| 场景 | 级别 | 用户可见行为断言（自动化方式） | 载体 | 锚点 |
|---|---|---|---|---|
| U6.1 面板可达 | 基建 | Driver 驱动 Settings 打开 Cloud Hub 子页（gate 用例，失败走下方降级路径） | T2 | §8 注 |
| U6.2 四类分组 | P0 | favorites 剧本含 4 类 × 4 状态 → 四 section、Active 置顶+徽标、次级样式类 | T2 | B8 |
| U6.3 搜索/刷新/busy | P1 | 延迟 3s 剧本 → busy 可见且 N 秒内场景完成无假死（首拉耗时）；输入过滤断言行数 | T2 | B9/B15 |
| U6.4 Enable | P0 | 行内 Enable → mock 200 → 行变 Active（UI 断言）；mock 断言收到 load POST；**真实会话可调用 → T3 抽验**；LinkageError 由 G5 门禁 + T2 装包启动兜底（回归钉子 R-4） | T2+T3 | B10 |
| U6.5 Disable | P0 | 行内 Disable → Unloaded；unload POST 被记录；真实路径 T3 抽验 | T2+T3 | B11 |
| U6.6 ★启用即生效 | P1 | Enable skill/command 后，状态化 mock（G3）目录更新 → 重开会话/重拉目录后 slash 列表或模式列表出现新条目（hub 手册⑤「启用后新会话可用」的自动化版） | ★T2 | ★ |
| U6.7 空态 | P2 | 空集合 → 空态文案 + 前往云端指引 | T2 | J7/Hub spec §7 |
| U6.8 错误态 | P1 | 未装 csc / daemon 停 → UNAVAILABLE + 恢复动作；401 → 登录指引（无新登录 UI）；404 → 行移除+通知自愈 | T2 | B5/B6/B7/B12 |

> **Settings modal 前置 gate 与降级路径**（适用于全部 U6 行及 U6.8 的 UI 断言）：Cloud Hub 宿主为 Settings 对话框子页（`CloudHubConfigurable`），而 Driver 驱动 modal Settings（打开 → 树导航至子页 → 断言面板渲染 → 点行内按钮）在本仓无验证记录。基建批先跑通 **gate 用例**（§12 步骤 1）作为 U 组准入；若 Driver 无法驱动 Settings，U6 UI 断言统一降级为 ConnectionService/favorites **服务层状态断言**（RPC 层），Settings 渲染退回人工截图 1 轮——降级后分组/行状态以服务层为准，并在结果模板标注「UI 层未覆盖」。权限/问答弹卡（U3.4-U3.7）为工具窗内 inline 视图（`PermissionView`/`QuestionView`，非 modal），不在此降级范围内。

### U7 Code Review（三入口：工具栏「审查当前变更」/ 编辑器右键「审查此文件·选区」/ 项目视图右键「审查此目录」）

| 场景 | 级别 | 用户可见行为断言（自动化方式） | 载体 | 锚点 |
|---|---|---|---|---|
| U7.1 无会话禁用 | P1 | action update() enabled=false + tooltip 提示需先开会话 | T1 | C6 |
| U7.2 三入口触发 | P0/P1 | 三入口各自发送 → mock 断言 prompt/command 内容含对应 `/review <args>`；args 构建（变更集/路径/选区回退）T1 已有 ReviewArgsTest，补 action 级绑定 | T2+T1 | C7/C9 |
| U7.3 运行中 | P0 | 会话 busy；五阶段阶段文本渲染（脚本化 SSE 回放） | T2 | C7 |
| U7.4 完成通知 | P0 | 用例向项目根写 `review-report.{md,json}` → 高/中/低计数 + 质量评分摘要通知，独立通知组「CoStrict Code Review」；计数一致性 T1 已有；通知文案人读 1 轮 | T2+T1 | C8/X3 |
| U7.5 查看报告 | P0 | 通知 [查看报告] → Markdown 预览打开 review-report.md（无 Markdown 插件降级文本编辑器，不阻塞） | T2 | C8 |
| U7.6 空结果 | P1 | 0 缺陷剧本 → 「未发现缺陷」文案，动作不变 | T2 | C12 |
| U7.7 中止无误报 | P1 | 中途 abort → 未落报告文件 → 无完成通知 | T2 | C10 |
| U7.8 连续触发 | P1 | 两版报告 → 两次通知各自弹出（或 T1 notifier 队列断言） | T2 | C15 |
| U7.9 ★降级分支 | P1 | 坏 json / 未知版本标记 → degraded 通知「报告已生成但无法解析/格式已更新」+ 直接打开 md；md 缺失 → 回退打开 json 文件本身（FileEditorManager 无法打开目录；与 Review spec §7「打开目录」表述不一致，**以实现为准**）；watcher 降级 T1 已有，此处补 UI 通知分支 | ★T2 | C11/Review spec §7 |
| U7.10 真实 agent 语义 | — | 真链路：`command` 通道实际命令与产物落点、五阶段真实输出（logpoint 探针，结论回填 spec） | T3 | C7/R1 |

### U8 品牌替换与本地化

| 场景 | 级别 | 用户可见行为断言（自动化方式） | 载体 | 锚点 |
|---|---|---|---|---|
| U8.1 插件名/vendor | — | 已装插件元数据 name=Costrict、vendor=Costrict（url=costrict.ai）；xml 静态兜底（A2/A3） | T2+T0 | A7 |
| U8.2 工具窗 | — | 按钮文本=Costrict、icon 加载正常 | T2+人工 | A8 |
| U8.3 通知组显示名 | — | bundle `notification.group.kilo`="Costrict"、`notification.group.codereview`="CoStrict Code Review"（显示名即 bundle 渲染）；两组 distinct、事件不串 | T1 | A10/X3 |
| U8.4 设置树品牌与顺序 | — | 根节点 "Costrict" + 子页顺序（与 U5.1 同断言） | T1 | A11/X4 |
| U8.5 空态 logo | — | 资源加载不抛 + 组件存在 + 截图产物（回归钉子 R-3：128px bilinear 渲染）；T1 EmptySessionPanelTest 变体 | T2+T1+人工 | A12 |
| U8.6 ★品牌一致性扫描 | P1 | G4 脚本：用户可见字符串 Kilo 残留=0（现状：frontend xml 两处硬编码 "Kilo prompt"/"Kilo session" description 待修）；CoStrict/Costrict 拼写定标后唯一化 | ★T0 | ★ |
| U8.7 深色主题与中文 | — | Darcula + zh_CN 环境截图产物，人工 1 轮快速目检；中文渲染错位抽查 | T2 产物+人工 | A9/A13 |

## 9. 维度四：非功能性（优先级后置）

> 整体排在 §6-§8 功能维度之后执行；组内按「诊断容错 → 安全 → 兼容/长稳」递进。U2.3-U2.5/U6.8 的错误态已给用户视角断言，本节为**深度异常矩阵**（实现细节层）。

### 9.1 诊断与容错（连接/服务异常 → 类型化诊断与自愈）

| 项 | 级别 | 自动化方式（ConnectionLifecycleTest / CloudHubPanelTest） |
|---|---|---|
| M17 daemon 停 | P0 | mock 停 → 错误文案含诊断码；mock 复启 → 自动 ready |
| M18 错 key 401 | P0 | mock 回 401 → 「未授权」诊断（区别于 M17） |
| M19 csc 未就绪 503 | P1 | mock 先 503 后 200 → 轮询文案 → 自动 ready |
| M16.1-3 断连恢复 | P0 | SSE 中途 close → unavailable → 重启 mock → ready；pending permission 重放可作答；**prompt 不自动重发** |
| M21 未知态 | P1 | prompt 后立即断连 → 未知态文案 |
| M25.1-3 server_url 异常 | P1/P2 | 死端口/空值/局域网 IP/畸形 URL 矩阵 → 类型化诊断；非 loopback 断言 resolver 抛 `NonLoopbackUrl` 诊断且无网络活动（该地址不指向 mock，不能字面断言「mock 零请求」） |
| M26 envelope 可读性 | P1 | `{ok:false,error:{code}}` → detail 显示 code+消息 |
| M28 旧 daemon | P2 | 全 404 剧本 → 可诊断错误而非静默 |
| B5 未装 csc | P1 | CI-only（`assumeTrue`，遮蔽决策见 §3.1 要点 6）→ 打开面板断言 UNAVAILABLE 文案 + 恢复动作指向 InstallCsc |
| B6 daemon 停 | P1 | server_url 指向死端口 → UNAVAILABLE + StartCsCloud |
| B7 未登录 | P1 | favorites 返回 401 → 错误态指向登录入口、无新登录 UI |
| B12 404 自愈 | P1 | 先返回条目、操作时返回 404 → 行移除 + 通知 |
| C11 坏 json 降级 | P1 | 写坏 fixture → watcher 降级分支已测；UI 通知分支见 ★U7.9 |

### 9.2 安全边界（M20）

| 项 | 级别 | 自动化方式 |
|---|---|---|
| M20a key 不落日志 | P0 | 用例内正则断言测试产物 idea.log 无凭证 |
| M20b loopback | P1 | 同 M25.2 |
| M20c 无全局批准 | P1 | 设置页断言 + U3.4 场景首操作必弹卡 |
| M20d 无写盘旁路 | P1 | grep 已有 |
| ★workspace 越界拒绝 | P1 | T1 已有 WorkspacePathScopingTest / RPC scoping（与 U2.7 mock 校验器互补） |

### 9.3 多窗口隔离、生命周期与长稳/兼容

| 项 | 级别 | 自动化方式 | 残留人工 |
|---|---|---|---|
| B13 双窗口一致 | P1 | ★双项目同进程探索（见下）吸收语义主体 | gate 失败时 1 轮 |
| C13 双窗口隔离 | P1 | 过滤逻辑 T1 已覆盖（C3）；★双项目同进程补 RPC 侧 | gate 失败时 1 轮 |
| M23.1-2 双窗口 | P0/P1 | header 断言单窗口侧已由 U2.7/M15 覆盖；★双项目同进程补双根校验 | gate 失败时 1 轮 |
| B15 首拉耗时 | P1 | 与 U6.3 同场景：busy 出现且 N 秒内场景完成，无假死 | — |
| M22 关闭语义 | P1 | IDE 关闭后断言 mock **未收到**任何 stop 类请求（先等待 IDE 进程完全退出——`useDriverAndCloseIde` 返回 ≠ 进程退出） | 真 daemon pid 检查简化为 T3 抽验 |
| M24 长会话 | P2 | mock 可跑 5min 冒烟流（nightly 可选） | 30min 真链路保留 |
| M27 split-mode | P1 | Starter 不覆盖 split-mode | 是（4 步冒烟） |

**★ 双项目同进程探索（替代双 IDE 实例的高成本方案）**：单个 IDE 进程内打开项目 A + 项目 B（split-mode 下各自 backend）→ 断言两会话 workspace header 各归各根、Hub/Review 通知按项目过滤（B13/M23/C13 的语义主体）。可行性未验证，按 Settings gate 同范式处理：先跑 gate 用例（`MultiProjectSmokeTest`），Driver 不支持则降级为「T1 服务层过滤断言（C3/NotifierTest 已有）+ 人工双窗口 1 轮」，并在结果模板标注覆盖降级。

## 10. 回归钉子（近期修复 → 常驻用例）

近期缺陷修复点必须 pin 为固定用例，防止回归（来源：commit f48843c4bc）。**R-1~R-4 为本方案回归钉子编号，区别于母方案风险实测锚点 R1/R2**：

| # | 缺陷 | 钉子用例 | 归属场景 |
|---|---|---|---|
| R-1 | stop 按钮不落地（cs-cloud 重启孤儿 run） | T2：SSE 断连后按 Stop → 会话收敛 idle；T1：abort RPC 404/410 = 静默成功 | U3.11 |
| R-2 | 工具卡片标题重复（"编辑 edit"） | T1：pending edit/read 卡片不渲染文件链接回退标题 | U3.3 |
| R-3 | 空态 logo 分数 DPI 楼梯 | T2：组件存在+截图产物；T1 EmptySessionPanelTest 变体 | U8.5 |
| R-4 | cloudFavorites RPC LinkageError（split classloader 重复打包） | T0：G5 ZIP 依赖门禁；T2 装真实 ZIP 启动即天然暴露 | U6.4/U1.3 |

## 11. 测试资产清单与规模预估

```
packages/kilo-jetbrains/
  src/integrationTest/kotlin/ai/kilocode/jetbrains/
    IntegrationTestBase.kt        Starter DI/插件安装/fixture 项目生成（TempDir）/场景段编排 + mock 生命周期 + server_url 重写还原 + ★G1 冷启动基线断言
    mock/FakeCsCloudDaemon.kt     JDK HttpServer + SSE + Scenario 剧本 + 请求记录（零新依赖）+ ★G3 状态化目录
    mock/Scenario.kt
    ConnectionLifecycleTest.kt    [F] U1/U2（M10 连接侧）/ [NF] M16-M19/M20a/M21/M22/M25/M26/M28
    SessionLoopTest.kt            [F] U3/U4（M11-M15）、U7.2-U7.9（C7-C8/C10/C12/C15 + ★降级段）/ [NF] M20c
    CloudHubPanelTest.kt          [F] U6（B8-B11 + ★U6.6 联动段；B14 断言由 T1 DisplayNameI18nTest 承载）/ [NF] B15、B5-B7/B12
    BrandSmokeTest.kt             [F] U1.1/U1.2、U8.1/U8.2/U8.5（A7/A8/A12）
    ★ColdRestartTest.kt           G2：两次 IDE 启动（U2.6 收口断言/U4.3/U5.3）
    ★MultiProjectSmokeTest.kt     双项目探索 gate（§9.3；不可行则不建）
  frontend/src/test 增补（T1，均为 F，独立于 T2 可先行）
    DisplayNameI18nTest           U5.5/U8.3（A10/A11/A13、B14、C14：bundle 对齐 + displayName）
    NotificationGroupIsolationTest X3/U8.3
    SettingsTreeOrderTest         X4/U5.1
    ReviewActionUpdateTest        C6/U7.1
    ★MessageToolbarActionsTest    U3.10 复制按钮
  scripts/（T0，★新增）
    brand-consistency-scan        G4（U8.6）
    plugin-zip-dependency-check   G5（U1.3/回归钉子 R-4）
  build.gradle.kts                仅一处增补（与原方案一致）：integrationTest 显式 maxParallelForks = 1（固化串行；源集与任务已在）；mock 用 JDK 内置 HttpServer 故零新增依赖（B5 若选注入点方案，另涉 CsCloudStarter 一行级改动）
```

预估（按 §3.1 要点 2 的「类 = IDE 会话」编排）：T2 用例类 4→6（+ColdRestart；+MultiProject 探索，不可行则不建；另有 IntegrationTestBase 基类与 mock 两个支撑文件），场景段约 32-38 段（覆盖 §5 地图中 T2 载体承载的场景步，其余由 T0/T1/T3/人工承载，见 §12），对应约 8-11 次 IDE 启动（含 ColdRestart 双启动），单轮 `integrationTest` 约 20-30 分钟（含 Settings gate 用例；IDE 安装复用本地缓存）。若改为「每用例独立 IDE」（CI 失败定位更准）则为 32-38 次启动 ≈ 65-80 分钟——两模式取舍见 §3.1 要点 2，默认取前者。

## 12. 执行顺序

```
全自动（CI 可跑，一条命令链；T2 的 CI 前提：将 `.intellijPlatform/ides` 即 verifyPlugin 缓存纳入
  CI cache——未命中时 Starter 回退在线下载 IU，约 2GB/次且需可达 JetBrains 数据服务，本机网络已证实
  解析不了 2026.1 发布列表，见 integration-test.md）：
  T0 静态（含 ★G4/G5 脚本）→ T1 全部单测（含新增）→ T2 integrationTest（mock 回放全套；先 §6-§8 功能批后 §9 非功能批）
真链路抽样（T3，Claude 代跑，单轮）：
  runIde + 调试探针：登录真流程（U2.5）、B10/B11 真会话可调用、C7 真实 agent 语义、R1 落点、
  母方案五子棋端到端（真实生成→落盘→浏览器自动化验收，母方案 §6.1）、M22 daemon 存活
残留人工（收敛后约 4-5 项）：
  视觉：U1.2/U8.7（A8/A9/A12/A13 + U7.4 通知文案，看 T2 产物截图即可，1 轮）
  多窗口：B13、C13、M23（仅当 ★双项目 gate 失败，1 轮）
  模式：M27 split-mode（4 步冒烟）
  长稳：M24（可选）；U3.17 外部改动对照（P2 可选）
```

实施顺序建议：

1. **基建**：IntegrationTestBase（fixture 生成/场景段编排/还原双保险 + ★G1 基线断言）+ FakeCsCloudDaemon（固定端口 + ★G3 状态化目录）+ health/SSE 正常剧本 + **Settings gate 用例**（打开 Settings → 定位 Cloud Hub 子页 → 断言面板存在；不过则启用 §8 降级路径）+ **★G4/G5 两个 T0 脚本先行**（G4 会立刻暴露 xml 硬编码残留，属低垂果实）（跑通 M10.1 一条 T2）
2. **维度一（F）**：ConnectionLifecycleTest 功能段（U1/U2）
3. **维度二（F）**：SessionLoopTest（**SSE 事件回放器在此落地，工作量最大**）→ ★ColdRestartTest → Worktree 冒烟段
4. **维度三（F）**：Hub gate 通过为前提 → CloudHubPanelTest（含 ★U6.6 联动段）→ Review 场景段（U7.2-U7.9）→ BrandSmokeTest → 用户旅程横向组合收口（J1-J7 均已落位 U2-U6，无独立用例）
5. **非功能批·诊断容错（NF）**：M16-M19/M21/M25/M26/M28（纯 mock 协议侧）→ B5-B7/B12、C11 异常态（B5 遮蔽机制在此步落地——先 CI-only，需要本机回归时再补注入点）
6. **非功能批·安全与长稳/多窗口（NF）**：M20a-d + ★workspace 越界 → M22/B15/M24/M27 → ★双项目 gate → 双窗口人工兜底
7. **T1 增补批**：5 个单测文件（均为 F，独立于 T2，可随功能批先行）
8. **T3 真链路抽样单轮**（清单见上）+ 收尾：更新母方案结果模板的「执行方」列，残留人工清单定稿

> 排序说明：「先协议侧后 UI 侧」的实施难度递进让位于「功能性优先」的验收价值——SSE 事件回放器因此提前到维度二落地；纯 mock 协议侧（M16-M19/M21/M25/M26/M28）实现成本低且独立，作为 NF 首批顺延其后。

## 13. 通过标准增量（补充母方案 §15）

- 原「P0 全过」中所有 T2 可达项改为以 `integrationTest` 全绿为验收依据；T3 抽样项以探针记录回填结论。
- **★覆盖完整性**：§5 地图 76 个场景步（U9 仅计 §9.1 诊断容错矩阵 13 步）全部有执行记录（自动或显式降级），无「未评估」格；§9.2/§9.3 锚点行执行记录要求相同，不占场景步计数——本方案相对母方案新增的验收维度。
- 新增前置：`FakeCsCloudDaemon` 场景剧本纳入代码评审（剧本即测试语义，需与设计 spec 协议章节对照）。
- U6 若触发 Settings gate 降级路径、§9.3 若触发双项目降级路径，以降级后判据为准，并在结果模板标注「UI 层未覆盖」。
- **功能批（§6-§8）自动化项全绿是非功能批（§9）执行的前提**（F 组残留人工目检项不阻塞 NF）；NF 项失败按母方案 §15 级别规则处理（P1 记缺陷不阻塞、P2 可选降级）。
- ★G4 品牌扫描在产品完成 CoStrict/Costrict 拼写定标前，仅报告不判失败（规则① Kilo 残留 = 0 仍为硬门禁）。
- 残留人工项失败处理不变：视觉项按缺陷记录；多窗口/长稳项不阻塞。

## 14. 残留人工清单（收敛后）与不做项

| # | 项 | 成本 | 理由 |
|---|---|---|---|
| 1 | 视觉目检（A8/A9/A12/A13 + zh_CN/Darcula + U7.4 通知文案） | 1 轮，看 T2 截图产物 | 审美与渲染观感不可自动判 |
| 2 | M27 split-mode 冒烟 | 4 步 | Starter 框架不覆盖 split-mode |
| 3 | 双窗口 B13/C13/M23 | 1 轮，**仅当双项目探索 gate 失败** | 两个独立 IDE 进程成本高，语义主体已由 T2+T1 覆盖 |
| 4 | M24 长稳真链路 30min + U3.17 外部改动对照 | 可选 nightly / P2 | 时长成本，非每轮 |
| 5 | 真链路语义抽样（登录/真 agent/五子棋/R1 落点/B10/B11/M22，清单见 §12） | T3 Claude 代跑，非人工盯屏 | 真实 csc 语义不可 mock |

（对照原方案约 8-9 项人工 = 视觉 4 + 多窗口 3 + 模式 1（M27）+ 长稳 1（M24）：多窗口 B13/C13/M23 由 ★双项目探索大部分吸收，收敛为 gate 失败时的 1 轮；另补齐原方案未显式覆盖的重启类场景——冷启动重连/设置持久化/历史会话恢复，全部由 ★G1/G2 自动化。）

**明确不做**：插件禁用/卸载回归、云会话导入（M11.3 P2 可选）、Review 工具窗口跳转（future spec）、Hub 版本更新徽标（v1.1）、split-mode 深测（M27 冒烟之外）、bug/ 目录临时反馈的专项用例（不入方案）。
