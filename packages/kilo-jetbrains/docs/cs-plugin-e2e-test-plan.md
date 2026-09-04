# feat-cs-plugin 端到端功能测试方案

- 基线：分支 `feat-cs-plugin`，范围 `6320e8122d..HEAD`（99 commits，223 文件，+13873 行）
- 原则：**自动化优先**——每条主线先跑存量 T0/T1/T2 自动化，缺口补自动化；只有"真实云端、浏览器 OAuth、桌面取消链路、视觉/搜索索引"四类留手工
- 配套文档：`docs/integration-test.md`（T2 基建用法）、`docs/cs-plugin-p2-onboarding-manual-verification.md`（取消链路手工清单）、`docs/superpowers/specs/2026-09-02-jetbrains-verification-automation-plan.md`（U1-U9 旅程编号来源）、`docs/superpowers/specs/2026-09-03-jetbrains-verification-report.md`（上轮执行报告）

## 1. 主线 → 测试域映射与存量覆盖总览

| 主线 | 测试域前缀 | 存量自动化 | 主要缺口 |
|---|---|---|---|
| csc+cs-cloud 适配：插件基础能力 | A | T1 厚（连接/路由/进程/倍率解析）+ T2 厚（ConnectionLifecycle/SessionLoop/ColdRestart） | 倍率 UI 的 T2 断言 |
| JetBrains IDE MCP 能力桥 | M | 仅 6 个 T1（ensureCapability 容错、lease、EP 名） | **全链路零 T2**：FakeCsCloudDaemon 未实现 capability 端点，Scenario 默认 capabilities 不含 `conversation_ide_capability_v1` |
| CoStrict 品牌替换 | BR | T0 两脚本 + T1（CostrictBrand/DisplayNameI18n/EmptySessionPanel）+ T2 BrandSmokeTest | T2 既往通过记录执行于隐藏批（2e366dd636..HEAD）**之前**，需整批重跑 |
| CoStrict cloud 服务（hub + code review） | CH | T1（HubRowLogic/CsCloudFavoritesApi/ReviewArgs/Watcher/Notifier）+ T2（CloudHubPanelTest、SessionLoopTest review 流） | hub 错误态、abort/连续触发、编辑器与项目视图入口、View Report 打开编辑器 |
| 原 Kilo 功能隐藏入口 | HD | T1 全覆盖（隐藏态断言 10+ 处） | T2/人工层：Settings 搜索索引、runIde 目检、Find Action 残留 |
| 未装 csc/cs-cloud 友好引导 | OB | T1 很厚（ConnectionPanel 19 例/KiloAppService 8 例/CsCloudSetupNotifier/EmptySessionPanel/i18n） | T2 按钮级点击链路；providerId 经 updateConfig 注入的 T1（e18fc84fea 真实回归点） |

## 2. 自动化分层与环境前置

### 2.1 分层定义（沿用上轮验证方案）

| 层 | 内容 | 运行方式 | 机器要求 |
|---|---|---|---|
| T0 | 静态扫描（品牌残留、ZIP 依赖） | `node packages/kilo-jetbrains/scripts/brand-consistency-scan.mjs`；`node packages/kilo-jetbrains/scripts/plugin-zip-dependency-check.mjs` | 无 |
| T1 | JVM 单测（frontend/backend/cs-cloud/shared） | `packages/kilo-jetbrains/` 下 `./gradlew :frontend:test :backend:test :cs-cloud:test :shared:test`（可 `--tests` 过滤） | JAVA_HOME=JDK 21 |
| T2 | Starter+Driver 驱动真实 IDE + 插件 ZIP + FakeCsCloudDaemon | `./gradlew integrationTest --tests "ai.kilocode.jetbrains.XxxTest"`（自动依赖 buildPlugin；未挂 `check`） | 本地 IDE 缓存 `.intellijPlatform/ides/`；**桌面空闲**（driver 走真实输入）；`maxParallelForks=1` 串行 |
| T3 | 真实 csc + cs-cloud + 云端 | 手工按 §6 清单 | 真机 |

### 2.2 环境自检（每轮开始执行一次）

```bash
export JAVA_HOME='C:\Users\demo\.jdks\ms-21.0.12.1'   # turbo strict 模式依赖 turbo.json globalEnv（已修）
cd packages/kilo-jetbrains
./gradlew buildPlugin            # T2 前置，同时产出 plugin-zip-dependency-check 的输入
ls .intellijPlatform/ides/       # 需含与 libs.versions.toml 同版本的 IU（缺失则先跑 verifyPlugin）
```

注意（`docs/integration-test.md`）：T2 测试不要调用 `withVersion()/useRelease()/useEAP()`（会绕过本地 IDE 的 DI 绑定）；T2 运行期间同机不要开 dev IDE（sandbox home 的 `server_url` 指向 mock 会引来外来流量，断言已是"越界检测"语义但仍会污染请求流水）。

## 3. 测试项矩阵

标记：✅ 存量已覆盖（列出覆盖处）｜🟡 缺口待补（P0/P1/P2 见 §5）｜🔴 仅手工。

### 3.1 主线 A：csc+cs-cloud 连接与会话基础能力

| # | 行为点 | 触发 → 期望 | 状态 |
|---|---|---|---|
| A-1 | 冷启动自动连接 | 装 ZIP 启动 IDE 开 Costrict 工具窗 → 无需手点即 ready（health+SSE 打开，无 "Try again"） | ✅ `ConnectionLifecycleTest`（G1 基线） |
| A-2 | daemon 掉线→类型化诊断→自愈 | 停 mock 再同端口重启 → 先显错误+Try again，后退避重连回 ready | ✅ `ConnectionLifecycleTest` M17 段 |
| A-3 | 401 未登录引导 | `scenario.failHealth(401,…)` → 帧文本 "unauthorized"；T1 断言恢复菜单/引导卡映射为 Sign in | ✅ T2 Launch A + `ConnectionPanelTest`/`KiloRecoveryActionsTest` |
| A-4 | csc 未安装观察面 | 删 sandbox `server_url` → "was not found"（= `csc_not_installed` 可观测等价物，无需真卸载） | ✅ T2 Launch C |
| A-5 | 会话闭环 | prompt → `POST /api/v1/conversations` + `/prompt/async`（头 `X-Workspace-Directory`=项目根、`X-Session-Client: kilo-jetbrains`）→ SSE delta 增量渲染 → permission Allow/Deny、question 回执 → 第二轮独立 → busy+Stop → abort | ✅ `SessionLoopTest`（含 workspace 头越界检测、M22 关停零增量、M20a 日志无凭据） |
| A-6 | 模型目录 | ready 后 `/api/v1/agents/models` 到达且归一化 | ✅ T2；倍率解析 ✅ T1（`KiloCliDataParserTest`/`ModelPickerTest`） |
| A-7 | **计费倍率 UI 呈现** | mock models 带 `creditConsumption/creditDiscount` → 模型行显示 "Nx credit"、Auto 显示 "N% discount"、详情面板 Credit rate 行；无数据不显示 | 🟡 P1：在 `SessionLoopTest` 补帧文本断言（mock 数据已在 `Scenario.modelsBody`） |
| A-8 | 无 capabilities CSC 兼容 | health 不含 `conversation_ide_capability_v1`（mock 默认即此）→ capability 失败仅 warn，prompt 照发 | ✅ T1 `KiloSessionRpcApiImplTest`/`CsCloudMcpBridgeTest`（真实 daemon 往返归 T3/M 域） |
| A-9 | 历史与冷重启 | 播种会话 → 重启 IDE → auto-ready + History 列出会话 | ✅ `ColdRestartTest` |
| A-10 | 安装/启动/登录进程语义 | install→start 的 stage 标注、npm 缺失 `NPM_NOT_FOUND`、超时杀进程、取消杀进程、login 超时保留进程 | ✅ T1 `CscInstallerTest`/`CscCloudStarterTest`/`CscLoginTest`/`CsCloudConnectionServiceTest`；**取消/超时真进程用例 POSIX-only，Windows 跳过 → 归 OB-6 手工** |

### 3.2 主线 M：JetBrains IDE MCP 能力桥（当前零 T2，最大缺口）

链路：prompt 前 `CsCloudMcpBridge.ensure()` → `JetBrainsMcpSessionFactory.enabled/open`（平台 `com.intellij.mcpServer` 私有 listener，Streamable HTTP `/stream`）→ `PUT /api/v1/conversations/{id}/capabilities/ide`（transport url + headers + 17 工具白名单）→ idle/abort/delete 时 `DELETE`。

| # | 行为点 | 触发 → 期望 | 状态 |
|---|---|---|---|
| M-1 | 能力协商门禁 | health 无该 capability（现状 mock 即可）→ 发 prompt：**无 PUT、prompt 成功**、日志降级 warn | 🟡 P0：T2 断言 `awaitNoRequest(PUT .../capabilities)` + prompt 正常 |
| M-2 | 绑定成功路径与协议形状 | mock health 加 capability + FakeCsCloudDaemon 记录 `PUT .../capabilities/ide` → PUT 先于 prompt 到达；body 含 `transport.url=http://127.0.0.1:<port>/stream`、`IJ_MCP_SERVER_PROJECT_PATH`、`tools ⊆ COSTRICT_IDE_TOOLS(17)` | 🟡 P0：扩展 `FakeCsCloudDaemon`（PUT/DELETE 端点 + 录制）与 `Scenario`（capabilities 开关） |
| M-3 | 租约撤销链 | SSE `session.status` idle / abort / 删除会话 → 各触发一次 `DELETE ...?generation=<uuid>` | 🟡 P0：T2 录制断言（旧 token 失效需真 MCP Server → 手工项 M-9） |
| M-4 | 恢复期重绑 | 会话 busy/retry 时断 SSE → 重连 → 新 generation PUT；只读历史会话不触发 | 🟡 P1：`ConnectionLifecycleTest` 模式扩展 |
| M-5 | 可选模块降级 | 沙箱禁用 bundled `com.intellij.mcpServer` → 插件加载/连接/prompt 全正常，仅 `mcp_plugin_unavailable` warn | 🟡 P1：T2（安装时排除依赖插件） |
| M-6 | 能力失败全集不阻塞 prompt | `project_not_open`/bind 404/异常 → 仅 warn；CancellationException 仍传播 | 🟡 P1：T1 现有 2 例参数化为 reason 全集 |
| M-7 | 白名单 ∩ Exposed Tools | IDE 设置禁用部分工具 → 列表排除且 generation 轮换；全禁 → 无 PUT、prompt 仍成功 | 🔴 手工（平台设置 UI；cs-cloud-mcp 反射两分支为回归风险点，见 §5 P2） |
| M-8 | 凭证不泄漏 | 绑定+撤销后 idea.log 无 token/完整 headers（仅哈希/端口/错误码） | 🟡 P0：M-2 场景内复用 `assertIdeLogHasNoCredentials` |
| M-9 | 真实 MCP 往返 | 真 daemon + 真 `com.intellij.mcpServer`：csc 侧连 `/stream` 调 IDE 工具；旧 token 401 | 🔴 T3 手工（split-mode 可用 `.run/run-costrict-ide-split-mode.run.xml`） |

### 3.3 主线 BR：CoStrict 品牌替换

| # | 行为点 | 期望 | 状态 |
|---|---|---|---|
| BR-1 | 插件身份 | ID=`ai.costrict.jetbrains`、name=Costrict、vendor=costrict.ai；描述为 CoStrict 文案且无 "inline autocomplete" | ✅ T2 `BrandSmokeTest`（descriptor）+ T0；vendor/描述页人工 1 轮 |
| BR-2 | 工具窗与欢迎页 | 工具窗 id=Costrict；空态欢迎语 "CoStrict is an AI coding assistant…"；logo=`/icons/costrict/logo.png` | ✅ T2 BrandSmokeTest |
| BR-3 | Kilo 残留硬门禁 | en/zh_CN/zh_TW 用户可见串 Kilo=0（内部 ID 白名单）；ZIP 无平台库重复打包 | ✅ T0 两脚本（建议纳入 CI） |
| BR-4 | 通知组 | 组 id `Costrict`/`Costrict.CodeReview` 与常量一致，显示名 CoStrict/CoStrict Code Review | ✅ T1 `CostrictBrandTest`/`NotificationGroupIsolationTest` |
| BR-5 | 官方链接 | `/help`→docs.costrict.ai；反馈弹窗 3 按钮（GitHub zgsm-ai/costrict issues / Docs / Download）；`CostrictLinks.kt` 收口；`rg "kilo\.ai|kilocode\.ai|discord" frontend/src/main` 0 命中 | ✅ T0/T1（按钮文案）；🔴 点开跳转人工 |
| BR-6 | 多语言文案 | 三语 csCloud/登录卡/欢迎语为中文且拼写 CoStrict；16 个未重塑语言包为已知欠账（report-only，不计失败） | ✅ T1 `DisplayNameI18nTest`；🔴 zh IDE 截图人工（U8.7） |
| BR-7 | 深色/高分屏 logo | Darcula + 高 DPI 下 logo 清晰不糊（R-3 钉子） | 🔴 人工截图目检 |
| BR-8 | **隐藏批后 T2 重跑** | BrandSmoke/ConnectionLifecycle/SessionLoop/CloudHub/ColdRestart 全批在 `2e366dd636..HEAD` 上重跑并出报告 | 🟡 P0（纯执行，无新代码） |

### 3.4 主线 CH：cloud hub + code review

| # | 行为点 | 触发 → 期望 | 状态 |
|---|---|---|---|
| CH-1 | Cloud Hub 渲染/搜索/启用闭环 | Settings→Tools→Costrict→Agent Behavior→Cloud Hub：4 类型混合状态分组渲染、搜索过滤、Enable→load POST→Active、G3 启用即生效（catalog 即时出现）、Disable→unload→Unloaded | ✅ T2 `CloudHubPanelTest` |
| CH-2 | Cloud Hub 错误态 | daemon 停→UNAVAILABLE 文案；mock 401→UNAUTHORIZED 指引登录；load 404→对话框存活不崩 | 🟡 P1：404 已覆盖；补 UNAVAILABLE/UNAUTHORIZED 两个 T2 场景 |
| CH-3 | Review 三入口 | 工具窗标题栏/编辑器右键（选区→`@/path:起-止`）/项目视图右键目录→ daemon 收到 `POST /api/v1/conversations/{id}/command`（command=review） | ✅ toolbar 入口 T2 `SessionLoopTest`；args 语法 ✅ T1 `ReviewArgsTest`；🟡 P1：editor/project-view 两入口 T2 |
| CH-4 | 无会话时 Action 禁用 | 未开工具窗 → 三 action enabled=false + tooltip | 🟡 P1：规划中的 `ReviewActionUpdateTest` 未建（T1） |
| CH-5 | 报告落盘→完成通知 | 测试进程写 `code-review_result/review-report.json` + `host.file.updated` SSE → balloon "High N · Medium N · Low N — 评分"；同 (size,mtime) 去重；0-issue 文案 | ✅ T2 `SessionLoopTest`（解析/去重 ✅ T1 `CodeReviewReportWatcherTest`） |
| CH-6 | 降级与 View Report | 坏 JSON/未知 marker → degraded 文案仍可打开 md；md 缺失回退打开 json | ✅ 降级通知 T2；🟡 P2：打开编辑器的断言；🔴 预览渲染人工 |
| CH-7 | abort 无误报/连续触发 | 中途 Stop 未落盘→无通知；连续两次→两次通知 | 🟡 P1：规划 U7.7/U7.8，`SessionLoopTest` 可扩 |
| CH-8 | 多项目窗口隔离 | 双项目各自报告 → 仅匹配窗口弹通知 | ✅ T1 `CodeReviewNotifierTest.matches`；🔴 双窗口手工（成本高，可服务层降级） |
| CH-9 | 真实云端全链路 | 真登录+真 review skill 五阶段+产物落点+通知计数一致 | 🔴 T3 手工（§6） |

### 3.5 主线 HD：原 Kilo 功能隐藏入口（隐藏不删代码基线）

| # | 入口 | 验证点 | 状态 |
|---|---|---|---|
| HD-1 | Settings User Profile 页 | 设置树无 User Profile 子页；**Settings 搜索 "Profile"/"User" 无命中**（spec 指定核对重点） | ✅ T1 `SettingsTreeOrderTest`；🔴 搜索索引人工 |
| HD-2 | History Cloud 标签 | 只有本地标签，Cloud 标签不可见不可点 | ✅ T1（isHidden + 12 用例保留自绿）；🔴 runIde 目检 |
| HD-3 | 恢复菜单 | cs-cloud 模式恒无 Reinstall；无 Core vX 菜单；按错误码只给 Retry+对应修复动作 | ✅ T1 `ConnectionPanelTest`/`KiloRecoveryActionsTest`（xml 断言）；🟡 P1：T2 造错剧本断言菜单文本 |
| HD-4 | CLI 下载进度横幅 | 全生命周期不出现 "Downloading" | ✅ T1；🟡 P1：T2 frameText 断言（零成本顺带） |
| HD-5 | 组织/账号 overlay | 注册但恒不可见，事件流无 Show | ✅ T1 `SessionUiLayoutTest` 3 例 + ViewSwitchingTest 契约改写 |
| HD-6 | Models 页 Small Model 行 | 渲染不含该行 | ✅ T1 `ModelsSettingsUiTest`；🔴 目检 |
| HD-7 | Core→cs-cloud 更名 | 菜单组 "cs-cloud"、动作 "Restart cs-cloud"；`Kilo.CoreInfo` 声明保留（Find Action 可见属已接受残留） | ✅ T1；🔴 Find Action 人工核对 |
| HD-8 | 迁移向导 autocomplete | 设置行 "Auto-Approval & Language"（无 & Autocomplete）、默认不预选、进度无该项 | ✅ T1 `KiloMigrationServiceTest`；🔴 真 fixture 触发人工（`Kilo.ForceMigration`） |
| HD-9 | ShowProfileAction | 无任何 xml 注册（Find Action 搜不到） | ✅ T1 xml 断言 |
| HD-10 | 反馈链接 zgsm 移除 | 弹窗 3 按钮、logo 不可点 | ✅ T1 + T0 rg |

### 3.6 主线 OB：未装 csc/cs-cloud 友好引导

| # | 行为点 | 期望 | 状态 |
|---|---|---|---|
| OB-1 | 三码引导卡 + 恢复菜单映射 | CSC_NOT_INSTALLED→Install csc（+docs 链接、详情默认展开）；DAEMON_DOWN→Start cs-cloud；UNAUTHORIZED→Sign in to CoStrict；菜单只含 Retry+对应动作 | ✅ T1 `ConnectionPanelTest` 19 例 |
| OB-2 | **T2 按钮级点击链** | missing-url launch：`awaitFrameText("Install csc")` → `clickFrameText` → 后台进度任务/安装请求出现 | 🟡 P0：现有基建直接可写，成本最低 |
| OB-3 | 一次性安装气球 | 首次 CSC_NOT_INSTALLED 弹一次（含 npm 命令文本+Install+不再提示）；勾选后重启不再弹 | ✅ T1 `CsCloudSetupNotifierTest` 5 例；🟡 P1：T2 断言通知出现（`getNotifications`）；重启不弹并入 ColdRestart 双 launch 结构（P2） |
| OB-4 | 空会话引导 | 连接失败时欢迎语与 History 之间出现引导卡，恢复后消失 | ✅ T1 `EmptySessionPanelTest`；🟡 P1：missing-url launch 顺带 `awaitFrameText`（零额外成本） |
| OB-5 | npm 缺失双按钮降级 | 通知双按钮（docs + npm 页）、stage 区分"装失败"与"装成功起不来" | ✅ T1 `KiloAppServiceTest` 8 例；🔴 真点击开浏览器人工 |
| OB-6 | **取消链路（桌面）** | Install/Start 进度条 Cancel → npm/csc 进程无残留、无错误通知、锁释放可重发；login 不杀进程 | 🔴 手工（`docs/cs-plugin-p2-onboarding-manual-verification.md` 核心 5 分钟）；T1 已覆盖锁释放逻辑（含 cancel 先于派发回归用例） |
| OB-7 | providerId 注入（Reinstall 隐藏的数据根） | state 与 **updateConfig 两路径**都注入 `providerId="cs-cloud"`（e18fc84fea 真实回归点） | 🟡 P0：补 1 个 T1 dto 映射测试 |
| OB-8 | 浏览器 OAuth 登录 | `csc auth login` 全流程、daemon 每请求重读 auth.json、登录后免重启恢复 | 🔴 T3 手工 |
| OB-9 | zh 界面渲染 | 中文 IDE 下错误标题/登录卡/进度标题中文、拼写 CoStrict | 🔴 人工抽查（T1 静态已够） |

## 4. 用户旅程串联（回归主路径）

自动化执行顺序即旅程顺序（对应上轮方案 U1-U9）：

1. **U1 安装启用**：`PluginTest` → T0 ZIP 依赖检查
2. **U8 品牌**：`BrandSmokeTest`（含隐藏批后重跑要求）
3. **U2 首连与引导**：`ConnectionLifecycleTest`（健康/401/missing-url/loopback 四剧本）+ OB-2/OB-4 补测
4. **U3/U4 编码闭环与会话管理**：`SessionLoopTest`
5. **U5 设置**：Cloud Hub（`CloudHubPanelTest`）+ HD 各 T1 断言
6. **U7 Code Review**：`SessionLoopTest` review 流 + CH 补测
7. **U2.6/U4.3 冷重启**：`ColdRestartTest`
8. **多项目**：`MultiProjectSmokeTest`（gate：`-Dkilo.integrationTest.gate.multiProject=true`，桌面空闲时跑）

## 5. 缺口补测清单（开发任务，按优先级）

> **实施状态（2026-09-04）**：P0-1/2/3 与 P1-1~P1-7 已全部落地（未提交），合并编译门禁 + 目标 T1 复跑绿。其中 P0-2 的 T2 试跑判定为环境受阻（桌面共租输入失效，无 conversations POST），M-2/M-3 的 PUT/DELETE 录制断言待空闲桌面首跑实证。P0-4（隐藏批后 T2 全批重跑）与本表所有 T2 用例的串行执行并入同一批，命令见 §4。P1-1 的点击链路确认无法自动化（IntegrationTestBase 无 PATH 注入机制，真点会触发全局 npm 安装），维持手工。P2 项维持暂缓。

| 优先级 | 任务 | 落点 | 验收 |
|---|---|---|---|
| **P0-1** | MCP 桥 mock 基建：FakeCsCloudDaemon 补 `PUT/DELETE /api/v1/conversations/{id}/capabilities/ide`（录制 body/generation），Scenario 补 `withIdeCapability()` 开关 | `src/integrationTest/.../mock/` | M-1/M-2/M-3/M-8 四条 T2 用例绿 |
| **P0-2** | 新集成测试 `McpBridgeLifecycleTest`：门禁（无 PUT）、绑定形状（PUT 先于 prompt、body 断言）、idle/abort 触发 DELETE、日志无凭据 | `src/integrationTest/kotlin/ai/kilocode/jetbrains/` | 同上 |
| **P0-3** | providerId 双路径注入 T1（防 Reinstall 复发回归） | `backend/src/test`（`KiloAppRpcApiImpl` dto/updateConfig 映射） | 两路径断言 `providerId="cs-cloud"` |
| **P0-4** | 隐藏批后 T2 全批重跑 + 报告（纯执行） | §4 顺序 | 报告落 `docs/superpowers/specs/`，更新上轮 2/6 状态 |
| P1-1 | OB-2/OB-4 T2 按钮级（Install csc 点击 + 空会话引导帧文本） | `ConnectionLifecycleTest` 扩展 | missing-url launch 断言通过 |
| P1-2 | 倍率 UI T2 断言（"Nx credit"/"N% discount"/Credit rate 行） | `SessionLoopTest` 扩展 | 帧文本断言通过 |
| P1-3 | CH-7 abort 无误报/连续触发（U7.7/U7.8） | `SessionLoopTest` 扩展 | 两用例绿 |
| P1-4 | CH-3 editor/project-view 入口 T2 | `SessionLoopTest` 或新 `ReviewEntryTest` | daemon 收到带 `@/…` args 的 command POST |
| P1-5 | `ReviewActionUpdateTest`（CH-4）+ `ensureCapability` reason 全集参数化（M-6） | frontend/backend T1 | 新 T1 绿 |
| P1-6 | Cloud Hub UNAVAILABLE/UNAUTHORIZED 错误态 T2（CH-2）；HD-3/HD-4 菜单与横幅帧文本断言 | `CloudHubPanelTest`/`ConnectionLifecycleTest` 扩展 | 用例绿 |
| P1-7 | OB-3 T2 通知出现断言（`getNotifications`） | missing-url launch | 通知计数=1 |
| P2 | M-4 恢复期重绑、M-5 可选模块降级、CH-6 打开编辑器断言、OB-3 跨重启、cs-cloud-mcp `filter()` 平台级测试（当前该模块无测试源集，反射两分支为回归风险）、气球跨重启双 launch | 各对应模块 | 用例绿或显式降级记录 |

## 6. 手工清单（自动化边界外的必做项）

保留并执行 `docs/cs-plugin-p2-onboarding-manual-verification.md`（取消链路 5 步 + 引导面抽查 6 项，结果贴 PR），另加本轮新增项：

| # | 项 | 步骤要点 |
|---|---|---|
| H-1 | 真实云端 T3（OB-8 + CH-9 + M-9） | 真装 csc→`csc auth login` 浏览器 OAuth→真 prompt（验倍率标签真实值）→真 `/review` 全五阶段→核对 `code-review_result/` 产物与通知计数→（可选 split-mode）验证 MCP `/stream` 真实工具调用与旧 token 401 |
| H-2 | Settings 搜索索引 | Settings 搜 "Profile"、"User" 无 User Profile 命中（HD-1 重点）；顺带搜 "Small Model"（HD-6） |
| H-3 | Find Action 残留 | 搜 "Core Info"（Kilo.CoreInfo 可见=已接受残留，确认无破坏性）；搜 "Show Profile" 应无 |
| H-4 | 视觉抽查 | Darcula + 高 DPI logo（BR-7）；zh_CN 界面引导卡/登录卡/进度标题（OB-9）；通知组显示名（BR-4） |
| H-5 | 引导面 runIde 目检 | History 无 Cloud 标签（HD-2）、Models 无 Small Model 行（HD-6）、迁移向导 autocomplete（HD-8，用 `Kilo.ForceMigration`）、反馈弹窗三按钮跳转（BR-5） |

## 7. 执行编排（subagent 分工）

| 阶段 | 内容 | 并行方式 |
|---|---|---|
| 0 环境自检 | §2.2 命令 | 单 agent |
| 1 静态+单测回归 | T0 两脚本 + T1 四模块 | **3 个 subagent 并行**：①frontend T1 ②backend+shared T1 ③cs-cloud T1 + T0；统一伪失败判定规则（§8） |
| 2 集成批 | §4 顺序跑存量 T2 + gate 类 | **单 subagent 串行**（`maxParallelForks=1`、桌面空闲、勿开 dev IDE），产出报告 |
| 3 补测开发 | §5 P0 → P1 逐项 | 每 1-2 个任务派 1 个 subagent（实现+跑绿+汇报），P0-1 完成后 P0-2 才能启动 |
| 4 手工与真云 | §6 清单 | 人工执行，结果记录 PR |
| 5 汇总 | 各阶段报告合并为最终验证报告 | 单 agent |

## 8. 已知伪失败与判定规则

T1 失败先做 **base 对照**（同命令跑改动前基线）：基线同败 = 环境伪失败，登记跳过；基线绿而分支败 = 缺陷。已备案伪失败（上轮报告 20 个，Windows 环境）：

1. 路径分隔符族（期望 `/` 实得 `\`）：`EditorContextGathererTest`×2、`SessionMessageListPanelTest`、`RulesSettingsUiTest`、`WorkspacePathScopingTest`、`KiloWorktreeRpcApiImplTest`×2、`KiloCliDownloaderTest`、`PromptPanelTest`
2. git objects `AccessDeniedException`（Windows 文件锁竞态）：`KiloWorktreeRpcApiImplTest`×11、`WorkspacePathScopingTest`×1
3. POSIX-only 真进程取消/超时用例在本机跳过（`CscInstallerTest`/`CscCloudStarterTest`）——对应 OB-6 手工
4. T2 专属：桌面共租导致输入注入失效（sendPrompt/Settings 导航不可靠——**安排空闲时段重跑**，纯 RPC 断言不受影响）；dev IDE 同机连接污染（运行期间关闭 dev IDE）；mock 未知路径回 `200 "{}"` 掩盖未映射路由（**不得以 mock 通过作为隐藏/路由类断言的唯一依据**）
5. 其他：JAVA_HOME 未设（已由 turbo.json globalEnv 修复，仍需 export）、T2 首次跑需本地 IDE 缓存或长时间在线下载

## 9. 通过标准（Exit Criteria）

1. T0 两脚本 exit 0（brand scan Rule① 硬门禁 + ZIP 依赖检查）
2. T1 四模块全绿；扣除 §8 备案伪失败后**无新增失败**（base 对照留痕）
3. T2 全部用例绿（gate 类允许"显式降级"记录，不得静默跳过）；BrandSmoke/ConnectionLifecycle 必须出自隐藏批之后的代码
4. §5 P0 四项补测落地并绿（MCP 桥至少覆盖 M-1/M-2/M-3/M-8）
5. §6 手工清单全部执行且结果记录（H-1 真云链路必须含一次真实 code review 全程）
6. 最终验证报告落 `docs/superpowers/specs/`（沿用 `2026-09-03-jetbrains-verification-report.md` 格式），覆盖矩阵更新到本轮新增用例
