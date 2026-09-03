# 专项一最简修复设计（Legacy Features Minimal Fix）

- 分支：`feat-cs-plugin`
- 日期：2026-09-03（同日二次修订：**原"代码删除"基线全量收敛为"隐藏 UI 入口"**）
- 输入：`packages/kilo-jetbrains/docs/cs-plugin-legacy-features-and-onboarding-review.md`（2026-09-03 复核版，行号以其为基准；本文行号引用均指该快照）
- 基调：**隐藏 UI 入口优先，不删代码**。凡原方案中的代码/资源/文件删除，一律简化为"对应用户可见入口不可见，代码、路由、文案 key、测试全部保留"；无 UI 入口的代码删除项本轮不做。纯文案/资源替换类子项（拼写、通知组 ID、系统提示词、logo、链接、漏译补齐）不属于删除，维持原方案。

## 修订说明（2026-09-03 二次修订）

用户拍板调整基线，规则三条：

1. 所有"需要删除代码"的子项 → 简化为**隐藏 UI 入口**（代码保留，仅入口不可见）。
2. **无 UI 入口**的删除项（纯后端/逻辑/构建/资源删除）→ 本轮**不做处理**。
3. 纯文案与资源**替换**类子项 → 维持原方案（替换不是删除）。

## 已确认决策（用户拍板）

| # | 决策 | 结论 |
|---|---|---|
| D-1 | 范围 | 收敛为两类：① UI 入口隐藏；② 文案/资源替换。原五组中的纯代码删除项全部不做 |
| D-2 | E1 语言包 | **不裁剪**：18 个语言包全保留（原"删 15 个"取消，回落英文场景不再存在）；仅补 en/zh_CN/zh_TW 已知漏译 3 条（D-8） |
| D-3 | E3 拼写 | 不变：用户可见文案统一官方拼写 **CoStrict**；代码标识、URL 域名、log 保持小写 `costrict` 不动 |
| D-4 | A1 形态 | **隐藏**设置页 User Profile 块入口（原"纯删除"取消）；后端 `fetchProfile`/`startLogin`/`logout`/`setOrganization`、`CsCloudRoute.kt:46` 的 `/kilo/profile` stub、ProfileState 管道全部保留。登录入口维持唯一现状：会话内 LoginRequiredView → `csc auth login` |
| D-5 | D 组深度 | **D1 整组不做**（cli.runtime 翻转与构建任务摘除均为代码/构建删除，无 UI 入口）；D2 仍不做 |
| D-6 | B7 / C1+C2 | B7（遥测透传删除）无 UI 入口 → 不做；C1（autocomplete 死存储删除）无 UI 入口 → 不做；C2 迁移向导承诺行 → **隐藏**该行；plugin.xml 宣传语 → description 文案移除（纯文本编辑）保留 |
| D-7 | E4 图标 | 只换用户可见的 `kilo-content.png`（不变）；`kilo-profile*.svg` **不再删文件**（入口隐藏后失去引用即止）；工具窗图标 `kilo.svg` 不动 |
| D-8 | E1 顺带 | 补齐三语包内已知漏译（csCloud 登录卡片 3 条，原属 P2-5），不变 |

## 非目标（明确不做）

一切代码删除类改动：Kilo provider 特权逻辑删除（B3）、Kilo 付费模型 401 检测删除（B4）、`/telemetry/*` 透传删除（B7）、autocomplete 死存储删除（C1）、kilocodeToken 凭证写入删除（C4）、D1 构建链摘除、15 个语言包文件删除、云历史后端路由删除（A4 后端半边）、`kilo-profile*.svg` 文件删除、`KiloBundle` 孤儿 key 清理（不删 UI 代码即不产生孤儿）。

其余维持原非目标：账号图标面板（A1 增强）、OIDC 轮询平移（A2 中期）、daemon conversations 云历史接入（A4 增强）、`/agents/version` 版本展示（B1 增强）、Task.Backgroundable 安装进度（P2-7）、Auto 置顶与 `x-select-llm` 回显（B3 增强）、vibe/strict/plan/raw 模式体系对齐（C5 增强）、user-indicator 遥测对齐（B7-b）、D2 配置面去 Kilo 化、inline autocomplete 实现（C1-a）、GhBanner 行为改动、Agent Manager 任何改动（见"Agent Manager 评估结论"）。

## A 组：入口隐藏

| 项 | 方案 | 触点 |
|---|---|---|
| A1 | **隐藏**设置页 User Profile 整块入口（不渲染，含登录/登出/组织切换区块）。设备 OAuth 入口（A2）、三条 Kilo 外链与 "Kilo Pass Opened" 遥测调用点（A3）随块隐藏而不可达/不触发，代码保留。后端 `fetchProfile`/`startLogin`/`logout`/`setOrganization`、`CsCloudRoute.kt:46` stub、ProfileState 管道**全部不动** | settings UI 的 Profile 区块渲染条件 |
| A2 | 随 A1 隐藏：设备授权流程位于被隐藏的 Profile 块内；`DeviceOAuthPanel.kt`、`QrCode.kt`（+`QrCodeTest.kt`）、`LoggedOutProfileUi.kt` 等文件与测试**全保留** | 同 A1 |
| A3 | 随 A1 不可达，无独立处理 | — |
| A4 | **隐藏** History 面板 Cloud 标签入口；`KiloBackendSessionManager.kt:224-269` 云历史路由**保留** | `HistoryPanel.kt:76,90` |
| A5 | 维持原方案（本即隐藏向）：恢复菜单按 provider 条件隐藏 Reinstall（cs-cloud 生效时不显示）；`reinstallAsync` 补 catch + 错误通知为健壮性增强（非删除），保留 | `ConnectionPanel.kt:271-273`；`KiloAppService.kt:177-180`；`ReinstallKiloAction.kt:14` |
| A6 | 维持（已提交 86f1633058）：`EmptySessionFeedback` 四链接替换为 CoStrict 官方链接，logo 不外链，URL 收口 `CostrictLinks` | `EmptySessionFeedback.kt:127-130` |
| A7 | 维持原方案：`/help` 目标改 `https://docs.costrict.ai` | `SessionUi.kt:860` |

## B 组

| 项 | 方案 | 触点 |
|---|---|---|
| B1 | "Restart Core" → "Restart cs-cloud" 更名维持（文案替换）；"Core vX" 信息项改为**隐藏**（动作类、xml 注册、bundle key 保留，仅不展示） | `kilo.jetbrains.frontend.xml:171-172,183-184,199-206`；`CoreInfoAction` |
| B2 | 下载进度 UI 改为**隐藏**：入口不渲染；`ConnectionState.Downloading` 分支与 bundle `:11-12` 文案保留 | `ConnectionPanel.kt:160-175` |
| B3 | **不做**（Kilo provider 特权逻辑删除属代码删除，无独立 UI 入口）。在途计费标签改动（`creditConsumption`/`creditDiscount`）不受影响，照常提交 | — |
| B4 | **不做**（401 检测删除为逻辑删除，无 UI 入口；`PaidModelAuthTest` 保留） | — |
| B5 | 组织/账号切换 overlay **隐藏**（与 A1 同批处理，ProfileState 管道保留） | `SessionController.kt:2227-2247`；`session/ui/account/*` |
| B6 | Settings Models 面板 Small Model 设置**隐藏**（不渲染）；bundle `:804-805` 与 `includeSmall` 参数保留 | `ModelsSettingsUi.kt` |
| B7 | **不做**（遥测透传删除无 UI 入口；`KiloBackendTelemetry.kt` 与三处调用点保留） | — |

## C 组

| 项 | 方案 | 触点 |
|---|---|---|
| C1 | **不做**（`KiloAutocompleteSettingsService` 死存储删除无 UI 入口，保留） | — |
| C2 | 迁移向导 "Language & Autocomplete" 承诺行**隐藏**（该行不渲染，向导其余不动）；plugin.xml "Features inline autocomplete" 宣传语从 description 移除（纯文案编辑，非代码删除） | `KiloMigrationService.kt:349`；`plugin.xml:8-14` |
| C3 | ShowProfileAction 入口**隐藏**：摘除菜单注册（不再 add-to-group），动作类与 bundle `:443-444` 保留 | `ShowProfileAction.kt:21-39`；`plugin.xml` 注册处 |
| C4 | **不做**（kilocodeToken → `PUT /auth/kilo` 凭证写入删除为逻辑删除，无 UI 入口；`LegacyProviderMapping` 条目随之保留） | — |
| C5 | 维持原方案：`NATIVE_MODE_DEFAULTS` 系统提示词文本重写为 Costrict 语义（文本替换，不动模式体系） | `LegacyMigrationConverters.kt:578-621,641` |

## D 组：不做

D1 整组不做：`cli.runtime` 默认值翻转与 `writeCliChecksums`/`stageRepoCli`/`stageBundledCli`/`buildRepoCli` 任务摘除均为构建/代码删除，无 UI 入口，本轮保持现状（发行包行为不变）。原勘误结论仍成立并记录备查：`generateOpenApiSpec`/`normalizeOpenApiSpec`/`openApiGenerate`/`fixGeneratedApi` 链是 `ai.kilocode.jetbrains.api` 客户端的编译期依赖，任何后续删除专项都不得摘除该链。D2 仍不做。

## E 组：文案与资源替换（删除类取消）

| 项 | 方案 | 触点 |
|---|---|---|
| E1 | **不裁语言包**：18 个语言包全保留（D-2 取消裁剪）。仅补 en/zh_CN/zh_TW 的 `session.login.required.csCloud.title/description/button` 三条漏译（"登录 CoStrict 以继续" 等，zh_TW 繁体） | `messages/KiloBundle_*.properties` |
| E2 | 维持：通知组 ID `"Kilo Code"` → `"Costrict"`，同步改 xml 注册与 4 处兜底硬编码；顺带核查 `Kilo.CodeReview` 组 ID（xml:21）用户可见性一并改。组 ID 变更使老用户该组通知设置回默认（已接受） | `kilo.jetbrains.frontend.xml:16`；`KiloNotifications.kt:12`；`AdvancedLogActions.kt:79-81`、`AgentEditDialog.kt:346-348`、`PromptAttachmentStrip.kt:84`、`PromptPanel.kt:927` |
| E3 | 维持：三语包与硬编码用户可见字符串统一 **CoStrict**（大小写归一）；代码标识/URL/log 不动；扫描覆盖 `worktree.*`、`csCloud.*`、code review 全套 key | bundle `:214-216,911-913,963-971` 等 |
| E4 | `kilo-content.png` 替换为参考仓库 `F:\ai-coding\costrict\src\assets\costrict\logo.png`：新资源入 `icons/costrict/`，`BrandLogo.kt:43` 引用改；`kilo-profile*.svg` **不删文件**（仅失去引用）；`kilo.svg`/`kilo@20x20` 不动 | `BrandLogo.kt:43`；`icons/` |
| E5 | 不处理：provider 图标兜底维持现状（原依赖 B3 删除的场景消失，兜底逻辑保留无害） | — |

## Agent Manager 评估结论（2026-09-03 spike，本轮不处理）

保留入口，不隐藏。依据：worktree CRUD 全本地 git/gh（`KiloWorktreeRpcApiImpl`，不经 HTTP）；worktree 建于 `<项目>/.kilo/worktrees/` 在 roots 校验内；daemon 以 `X-Workspace-Directory` 一等支持多 workspace 会话隔离（目录不存在自动 mkdir，`cs-bridge-server-usage.md:93`）；参考实现亦有 worktree 功能。唯一缺口 gh/GitHub PR 集成降级优雅（GhBanner 提示，不报错），留后续。遗留验证项（转验证轮，不在本 spec 范围）：daemon 多 workspace 下 worktree 会话端到端实测（建 worktree → 发 prompt → 确认改动落在 worktree 目录）。

## 实施顺序

入口隐藏批（A1+A2+A3、A4、B1 信息项、B2、B5、B6、C2 承诺行、C3）→ 文案与品牌批（B1 更名、C5、E2、E3、E1 漏译、E4 logo；A6 已提交、A7 随批）。不再有删除连带清理环节（无孤儿 key、无失效测试）。

## 验证策略

- 只测相关模块（用户偏好）：frontend/backend 模块测试 + typecheck（需设 `JAVA_HOME`）。
- **不删除任何测试**：`QrCodeTest`、`PaidModelAuthTest`、`ModelItemsTest`/`ModelPickerTest`（含在途计费标签用例）全部保留原样。
- 验证标准为"入口不可见且不可达"：UI 不渲染、菜单/设置无对应项、迁移向导无承诺行、Find Action 找不到 ShowProfileAction；重点核对 **IntelliJ Settings 搜索不会命中被隐藏项**（条件隐藏需同时覆盖搜索索引路径）。
- 不再需要孤儿 key 清理与"删包后引用无残留"核对（无删除）；E1 漏译补齐后三语包 key 集合一致仍需核对；E3 拼写扫描保留。

## 风险与边界

- 死代码留存：被隐藏入口对应的代码（Profile 全链、云历史路由、下载进度、Small Model、ShowProfileAction、autocomplete 死存储等）保留在仓内；后续如需彻底清理，另立删除专项（届时 D1、语言包裁剪可一并重提）。
- 隐藏 ≠ 不可触达：UI 条件隐藏必须覆盖 Settings 搜索索引；ShowProfileAction 必须连 xml 注册一并摘除（仅注释代码不行），否则可经 Find Action/快捷键触达。此为验证策略的核对重点。
- 通知组 ID 与拼写归一的用户可感知变化维持原接受结论（E2/D-3）；语言包不裁剪后，原"回落英文"影响不再存在。
- `CostrictLinks` 的 `{base}/issue/` 等精确 URL 落地时须对照参考实现核对，若与运营口径冲突，以官方最新口径为准并在 PR 中标注。
