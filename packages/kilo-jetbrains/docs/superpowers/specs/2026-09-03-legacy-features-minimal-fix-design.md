# 专项一最简修复设计（Legacy Features Minimal Fix）

- 分支：`feat-cs-plugin`
- 日期：2026-09-03
- 输入：`packages/kilo-jetbrains/docs/cs-plugin-legacy-features-and-onboarding-review.md`（2026-09-03 复核版，行号以其为基准；本文行号引用均指该快照）
- 基调：**每个子项取删除/替换优先的最简变体，不新增功能**。凡评审文档方案中的"中期/可选增强"一律不做。

## 已确认决策（用户拍板）

| # | 决策 | 结论 |
|---|---|---|
| D-1 | 范围 | 全量 A–E 五组，最简变体 |
| D-2 | E1 语言包 | 裁到 en / zh_CN / zh_TW 三语，删除其余 15 个语言包文件（那些语言用户回落英文） |
| D-3 | E3 拼写 | 用户可见文案统一官方拼写 **CoStrict**（驼峰大写 S）；代码标识、URL 域名、log 保持小写 `costrict` 不动 |
| D-4 | A1 形态 | 纯删除，不加账号图标面板（登录入口维持唯一现状：会话内 LoginRequiredView → `csc auth login`） |
| D-5 | D 组深度 | 只做 D1（`cli.runtime=false` + 摘构建任务）；D2（配置路径迁移）不做 |
| D-6 | B7 / C1+C2 | 走删除侧（删 `/telemetry/*` 透传；删 autocomplete 死存储与宣传，不实现补全） |
| D-7 | E4 图标 | 只换用户可见的 `kilo-content.png`；工具窗图标 `kilo.svg`（中性抽象图形）不动 |
| D-8 | E1 顺带 | 补齐三语包内已知漏译（csCloud 登录卡片 3 条，原属 P2-5） |

## 非目标（明确不做）

账号图标面板（A1 增强）、OIDC 轮询平移（A2 中期）、daemon conversations 云历史接入（A4 增强）、`/agents/version` 版本展示（B1 增强）、Task.Backgroundable 安装进度（P2-7）、Auto 置顶与 `x-select-llm` 回显（B3 增强）、vibe/strict/plan/raw 模式体系对齐（C5 增强）、user-indicator 遥测对齐（B7-b，产品/合规决策需单独评审）、D2 配置面去 Kilo 化、inline autocomplete 实现（C1-a）、GhBanner 行为改动、Agent Manager 任何改动（见"Agent Manager 评估结论"）。

## A 组：用户可达且必坏（纯删除为主）

| 项 | 方案 | 触点 |
|---|---|---|
| A1 | 删除设置页 User Profile 整块：`ProfileUi.kt`、`LoggedOutProfileUi.kt`、后端 `fetchProfile`/`startLogin`/`logout`/`setOrganization`、`CsCloudRoute.kt:46` 的 `/kilo/profile` 401 stub、ProfileState 相关管道 | `KiloBackendAppService.kt:699-726,1027-1088`；`CsCloudRoute.kt:46`；settings UI 与 `KiloBundle` 对应 key |
| A2 | 随 A1 删除 Kilo 设备 OAuth UI：`DeviceOAuthPanel.kt`、`QrCode.kt`（+`QrCodeTest.kt`）、`LoggedOutProfileUi.kt`、ProfileUi 内设备授权流程（`:201-234`） | 同上 |
| A3 | 随 A1 自然消失：三条 Kilo 外链（Dashboard/Top up/Kilo Pass）与 "Kilo Pass Opened" 遥测调用点均在被删的 `ProfileUi.kt` 内，删除时确认调用点一并移除 | `ProfileUi.kt:31-33,74` |
| A4 | 删除 History 的 Cloud 标签与后端云历史路由 | `HistoryPanel.kt:76,90`；`KiloBackendSessionManager.kt:224-269` |
| A5 | 恢复菜单按 provider 条件隐藏 Reinstall（cs-cloud 生效时不显示）；`reinstallAsync` 补 catch + 错误通知，消除静默失败 | `ConnectionPanel.kt:271-273`；`KiloAppService.kt:177-180`；`ReinstallKiloAction.kt:14` |
| A6 | `EmptySessionFeedback` 四链接替换为 Costrict 官方链接，logo 点击不再外链。全部 URL 收口到新建 `CostrictLinks` 常量对象（与 A7/E2 共用），精确值落地时对照参考实现 `docLinks.ts`/`authConfig.ts`（`F:\ai-coding\costrict`）核对：反馈 `{base}/issue/`（base=`https://zgsm.sangfor.com`）、bug/feature `https://github.com/zgsm-ai/costrict/issues`、支持 `https://docs.costrict.ai`、社区/下载 `https://costrict.ai/download` | `EmptySessionFeedback.kt:127-130` |
| A7 | `/help` 目标改 `https://docs.costrict.ai` | `SessionUi.kt:860` |

## B 组：用户可见但空转

| 项 | 方案 | 触点 |
|---|---|---|
| B1 | "Restart Core" 语义更名 "Restart cs-cloud"（含 `cli.text`）；删除 "Core vX" 信息项（动作类 + xml 注册） | `kilo.jetbrains.frontend.xml:171-172,183-184,199-206`；`CoreInfoAction` |
| B2 | 删除 `ConnectionState.Downloading` 分支与下载进度 UI 及 bundle 文案 | `ConnectionPanel.kt:160-175`；bundle `:11-12` |
| B3 | 删除全套 Kilo provider 特权逻辑：`ProviderCatalog` 内置 kilo 目录与 popular 排序、`ModelItems` kilo 免连接展示、`ModelPicker` providerSort/小模型特判、`ProviderListRows` Disconnect 禁显、"Kilo Gateway cannot be disconnected" 文案。**必须保留工作区在途的计费标签改动**（`creditConsumption`/`creditDiscount`，未提交 diff） | `ProviderCatalog.kt:18-29`；`ModelItems.kt:12-15`；`ModelPicker.kt:277,301,307,309`；`ProviderListRows.kt:95`；`KiloBackendProviderSettingsManager.kt:111-113` |
| B4 | 删除 Kilo 付费模型 401 检测（保留 `isCsCloudAuthRequired` 有效路径），同步删改 `PaidModelAuthTest` 的 Kilo 用例 | `SessionController.kt:1708-1724`；`PaidModelAuth.kt:8-32` |
| B5 | 删除组织/账号切换 overlay，与 A1 共享 ProfileState 管道，一起拆 | `SessionController.kt:2227-2247`；`session/ui/account/*` |
| B6 | 删除 Settings 窗口 Models 面板的 Small Model 设置与 bundle 文案；`includeSmall` 参数如失去全部调用方一并清理 | `ModelsSettingsUi.kt`；bundle `:804-805` |
| B7 | 删除 `/telemetry/capture`、`/telemetry/setEnabled` 透传与三处 `runCatching` 调用点 | `KiloBackendTelemetry.kt:25-33,39-47`；`KiloBackendAppService.kt:629,639,649` |

## C 组：死代码与承诺未兑现

| 项 | 方案 | 触点 |
|---|---|---|
| C1+C2 | 删除 `KiloAutocompleteSettingsService` 及迁移写入方；删除迁移向导 "Language & Autocomplete" 承诺行；删除 plugin.xml "Features inline autocomplete" 宣传语 | `KiloAutocompleteSettingsService.kt:10-39`；`KiloMigrationService.kt:349`；bundle `:954`；`plugin.xml:8-14` |
| C3 | 删除 `ShowProfileAction.kt` 与 bundle 两条 key | `ShowProfileAction.kt:21-39`；bundle `:443-444` |
| C4 | 删除 kilocodeToken → `PUT /auth/kilo` 的凭证写入逻辑；`LegacyProviderMapping` 的 "Kilo (Gateway)" 条目若仅服务该写入则一并删，若另有消费方（如 provider 设置迁移）则保留并在 PR 中注明。不加迁移后登录引导 | `LegacyMigrationConverters.kt:233-242`；`LegacyProviderMapping.kt:62` |
| C5 | `NATIVE_MODE_DEFAULTS` 系统提示词文本重写为 Costrict 语义（替换自称 "You are Kilo Code" 等），只改文本，不动模式体系 | `LegacyMigrationConverters.kt:578-621,641` |

## D 组：构建链（只做 D1）

- `KiloProps.kt:23`：`cli.runtime` 默认值 true → **false**。
- 从默认构建链摘除 `generateOpenApiSpec`（经 compileKotlin 挂载）与 `writeCliChecksums`（挂 processResources）；`StageBundledCliTask`/`StageRepoCliTask` 需显式 `-P` 参数、本就不在默认链，直接删除任务定义。
- `cli.version`/`cli.pinned` 等内部标识保持不动（F 组豁免）；D2 记为后续项，不在本轮。

## E 组：品牌残留

| 项 | 方案 | 触点 |
|---|---|---|
| E1 | 删除 15 个语言包文件（ar/bs/da/de/es/fr/ja/ko/nl/no/pl/pt_BR/ru/th/tr/uk），保留 en/zh_CN/zh_TW；补 zh_CN/zh_TW 的 `session.login.required.csCloud.title/description/button` 三条漏译（"登录 CoStrict 以继续" 等，zh_TW 繁体） | `messages/KiloBundle_*.properties` |
| E2 | 新建 `CostrictBrand` 常量（与 `CostrictLinks` 同文件或相邻）：通知组 ID `"Kilo Code"` → `"Costrict"`，同步改 xml 注册与 4 处兜底硬编码；顺带核查 `Kilo.CodeReview` 组 ID（xml:21）——若在通知设置中用户可见，一并改 Costrict 前缀。注：组 ID 变更会使老用户该组通知设置回默认（已接受） | `kilo.jetbrains.frontend.xml:16`；`KiloNotifications.kt:12`；`AdvancedLogActions.kt:79-81`、`AgentEditDialog.kt:346-348`、`PromptAttachmentStrip.kt:84`、`PromptPanel.kt:927` |
| E3 | 三语包与硬编码用户可见字符串统一 **CoStrict**（现有 Costrict→CoStrict、CoStrict 大小写归一）；代码标识/URL/log 不动；扫描范围顺带覆盖 `worktree.*`、`csCloud.*`、code review 全套 key | bundle `:214-216,911-913,963-971` 等 |
| E4 | `kilo-content.png`（已目验为旧 Kilo logo）替换为参考仓库 `F:\ai-coding\costrict\src\assets\costrict\logo.png`：新资源入 `icons/costrict/`，`BrandLogo.kt:43` 引用改；`kilo-profile*.svg` 随 A1 失去引用后删文件；`kilo.svg`/`kilo@20x20` 不动 | `BrandLogo.kt:43`；`icons/` |
| E5 | 不处理：B3 删除内置目录后 provider 图标兜底自然失去场景 | — |

## Agent Manager 评估结论（2026-09-03 spike，本轮不处理）

保留入口，不隐藏。依据：worktree CRUD 全本地 git/gh（`KiloWorktreeRpcApiImpl`，不经 HTTP）；worktree 建于 `<项目>/.kilo/worktrees/` 在 roots 校验内；daemon 以 `X-Workspace-Directory` 一等支持多 workspace 会话隔离（目录不存在自动 mkdir，`cs-bridge-server-usage.md:93`）；参考实现亦有 worktree 功能。唯一缺口 gh/GitHub PR 集成降级优雅（GhBanner 提示，不报错），留后续。遗留验证项（转验证轮，不在本 spec 范围）：daemon 多 workspace 下 worktree 会话端到端实测（建 worktree → 发 prompt → 确认改动落在 worktree 目录）。

## 实施顺序

A+C（纯删除先清场）→ B → D → E（品牌文案最后统一扫，避免中途改名反复）。每步删除连带清理 `KiloBundle`（三语包）孤儿 key：`profile.*`、云历史、small model、autocomplete 承诺行、ShowProfile 等。

## 验证策略

- 只测相关模块（用户偏好）：frontend/backend 模块测试 + typecheck（需设 `JAVA_HOME`）。
- 删除失效测试：`QrCodeTest`、`PaidModelAuthTest` 的 Kilo 用例、被删 UI 的相关测试；调整并保留 `ModelItemsTest`/`ModelPickerTest`（含在途计费标签用例）。
- A 组验证标准是"入口不存在"（UI/端点删除 + 编译通过），**不以** `FakeCsCloudDaemon`（未知路径返回 `200 {}`，会掩盖 404）作为通过依据。
- E1/E3 用脚本化核对：删包后资源引用无残留（`KiloBundle.message` 引用的 key 均存在）、三语包 key 集合一致。

## 风险与边界

- 删除量大，依赖编译器与 unused 检查兜底；跨模块引用（shared DTO 如 `ProviderDto`）删除字段前先确认前端在途 diff 未用（credit 字段保留）。
- 通知组 ID 与语言包裁剪均会造成用户可感知变化（通知设置重置、语言回落英文），已由 D-2/D-3 决策接受。
- `CostrictLinks` 的 `{base}/issue/` 等精确 URL 落地时须对照参考实现核对，若与运营口径冲突（参考评审文档"官方文档包名口径不一致"教训），以官方最新口径为准并在 PR 中标注。
