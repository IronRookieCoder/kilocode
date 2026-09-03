# 专项审查：原 Kilo 遗留功能与 csc/cs-cloud 新用户引导

- 分支：`feat-cs-plugin`（Kilo JetBrains 插件 → Costrict 品牌重塑 + cs-cloud 接入）
- 审查日期：2026-09-02；解决方案补充：2026-09-03
- 审查范围：`packages/kilo-jetbrains`（shared / frontend / backend / cs-cloud / cs-cloud-mcp）
- 行号对应当前工作区文件
- 解决方案参考实现：`F:\ai-coding\costrict`（Costrict VS Code 插件）的 **cloud 模式**（`src/core/cs-cloud/`、`src/core/costrict/auth/`）；daemon 侧参考 `F:\ai-coding\cs-cloud`（Go）
- 硬约束：JetBrains 插件使用**原生 Swing UI**，不引入 JCEF/webview——所有方案按原生 UI 落地，复用参考实现的契约与行为模式而非其 UI 形态
- **2026-09-03 复核**：全部断言已对照四个仓库（kilo-jetbrains / costrict / cs-cloud / csc）逐条验证，核心问题定性与解决方案方向全部成立；9 处实质勘误已写入正文，明细见文末"附 3：复核勘误明细"

---

## 架构背景（两条连接链路的关系）

后端连接通过 `KiloConnectionProvider` 扩展点选择：`KiloBackendAppService.kt:141-148` 按 id 字典序取第一个 provider。

| 链路 | id | 状态 |
|---|---|---|
| **cs-cloud**（新）：`CsCloudConnectionProvider`（`cs-cloud/.../CsCloudConnectionProvider.kt:13`），读 `~/.costrict/cs-cloud/server_url` 发现 daemon，会话跑云端 | `cs-cloud` | **当前生效**（字典序在前，模块必装） |
| **Kilo CLI**（旧）：`KiloCliConnectionProvider`（`backend/.../KiloConnectionProvider.kt:84`），spawn 本地 Kilo CLI 二进制 | `kilo-cli` | 仅在无扩展且 `cli.runtime=true`（默认 true，`KiloProps.kt:23`）时兜底 |

**结论**：cs-cloud 已实质接管连接与执行面（session/prompt/permission/config/agents/file-find 等，路由重写见 `CsCloudRoute.kt:92-109`）；但账号、云历史、CLI 下载重装、部分 provider OAuth、遥测五块仍是旧 Kilo 语义的入口，其中多项在 Costrict 链路上**用户可达但必然失败**。

---

## 参考基准：costrict cloud 模式的关键契约

以下事实来自参考实现，是后文各解决方案的引用依据（路径相对 `F:\ai-coding\costrict`，daemon 相对 `F:\ai-coding\cs-cloud`）：

1. **UI 形态**：参考实现的 cloud 模式在侧边栏嵌入 Cloud UI（外部 assistant-ui 仓库的 Next.js 静态导出），会话/历史/模型/设置/收藏全部由 Cloud UI 渲染，扩展只做宿主桥。JetBrains 插件受原生 UI 约束不采用该形态，但**数据契约可直接复用**——等价功能面在 Swing 侧自实现。
2. **daemon API 面**（`internal/localserver/server.go:154-256`，统一挂载 `/api/v1` 前缀，`server.go:137`）：会话/历史 `GET /conversations`（= OpenCode session，经 csc driver 的 ProxyRoutes 重写自后端 `/session*`，`internal/agent/csc/driver.go:113-128`；含 `PATCH/DELETE /conversations/{id}`）、模型 `GET /agents/models`、设置 `GET/PATCH /agents/config`、权限 `/permissions`、提问 `/questions`、事件 `GET /events`（有 file/git watcher 时走 SSE，否则退化为普通代理）、版本 `/agents/version`。
3. **daemon 鉴权**：`CS_BRIDGE_API_KEY`/`CS_CLOUD_API_KEY` 在 costrict 参考仓库**零命中**；但 daemon（cs-cloud）侧二者是**可选 API key 鉴权**（`internal/config/load.go:24`，默认空=no-op 中间件，`middleware.go:9-14`）——并非"daemon 无 API key 概念"，P1-5 措辞据此修正。常规鉴权=请求自带 `Authorization: Bearer`（经宿主代理原样透传）+ `~/.costrict/share/auth.json` 共享文件（`internal/platform/paths.go:78-93`；daemon 每请求重读该文件、无缓存，token 外部更新即生效）。
4. **登录**：插件原生 OIDC（`src/core/costrict/auth/authService.ts:87-108`）——拼 login URL 开浏览器 + 复制剪贴板兜底 → 3s 轮询 token → 5s 轮询 status 至 `logged_in`；token 双写 SecretStorage 等价物 + 共享 auth.json（`src/core/costrict/runtime-config/index.ts:222-276`，pickFresher 以 refresh_token `iat` 为主信号、`exp` 决胜，防回滚）。纯 HTTP，无 URI 回调/deep link 依赖。
5. **错误呈现**：错误页而非一行横幅（`sidebarProvider.ts:629-904`）；哨兵 `__IS_UNINSTALL_CSC_ERROR__`（`csCloudService.ts:189`）区分"未安装 csc"与"启动失败"——前者 canRetry=false 隐藏 Restart、只给安装指引与命令文本（`npm install @costrict/csc -g`）；启动失败错误页 10s 倒计时自动重试（`sidebarProvider.ts:824`），崩溃页 getCrashedHtml 5s 倒计时（`html.ts:568`）。崩溃检测=进程 exit 事件（`csCloudService.ts:487-491`）+ 15s×3 心跳（常量 `:55-58`）+ server_url 文件 watch，自有进程 3 次指数退避自动重连（`csCloudService.ts:352-463`）。
6. **错误文案 i18n**：`src/i18n/costrict-i18n/locales/{en,zh-CN,zh-TW}/`，错误码→文案三级降级（远端 `error_codes_{lang}.json` + HTTP 状态静态表 + 本地缓存，`ErrorCodeManager.ts`）。
7. **账号**：无自建账号页的强需求——资料从 JWT claims 本地解码（`useCostrictUserInfo.ts:105-127`），余额 `GET {base}/quota-manager/api/v1/quota`（`fetchers/costrict.ts:58-92`；15s 轮询在 `CostrictAccountView.tsx:331-336`），管理动作跳网页控制台 `{base}/credit/manager?state=<tokenSha256>&tab=usage|subscription`。
8. **恢复命令**：仅 `costrict.reconnectCsCloud` / `costrict.restartCsCloudServer`（`src/package.json:141-149`），无 reinstall。
9. **官方链接**：文档 `https://docs.costrict.ai`（CLI 安装 `docs.costrict.ai/cli/guide/installation`）、手册 `https://costrict.ai`、反馈 `{base}/issue/`（`extension.ts:385`）、bug/feature `github.com/zgsm-ai/costrict/issues`、下载 `https://costrict.ai/download`、控制台 `https://zgsm.sangfor.com/cloud`（精确串仅见于 `cs-cloud-status.md:46` CLI 输出示例，代码内只有基础域 `zgsm.sangfor.com`，`authConfig.ts:29`）。
10. **品牌拼写**：用户可见文案官方拼写为 **"CoStrict"**（`src/package.nls.json` 16 处、html.ts 8 处），代码标识为小写 `costrict`。本文 E3"正确拼写 Costrict"的前提与参考实现相反，需勘误（见 E 组解决方案）。

---

## 专项一：不适用 Costrict 的原 Kilo 功能

按"用户可达且必坏 → 用户可见但空转 → 内部残留"排序。

### A. 用户可达且在 cs-cloud 链路上必然失败

| # | 功能 | 位置 | 现状与判断 |
|---|---|---|---|
| A1 | **User Profile 设置页整体**（登录/登出/余额/组织/Kilo Pass） | `KiloBackendAppService.kt:699-726`（`fetchProfile`）；`CsCloudRoute.kt:46` 把 `/kilo/profile` stub 成 401 | profile 恒为 null → 设置页永远显示"未登录"卡片。页面上的 `startLogin`（`:1027-1037`，`providerOauthAuthorize(providerID="kilo")`）、`logout`（`:1054-1063`）、`setOrganization`（`:1070-1088`，POST `/kilo/organization`）在 cs-cloud 路由表中**均无映射**——未映射路径原样透传 daemon → 404 → `CsCloudRequestException`（`CsCloudHttpClients.kt:26`）→ 设置页错误卡片，点击即失败（注：集成测试 `FakeCsCloudDaemon.kt:212` 对未知路径返回 `200 "{}"`，会掩盖该失败）。真正的 Costrict 登录是会话内 `LoginKind.CsCloud`（`SessionController.kt:1741-1751` 设置登录态）→ `LoginRequiredView` → `loginCsCloud()`（`SessionController.kt:2318`）→ `csc auth login`（`CscLogin.kt`），两条登录体系并存且旧的永不为真 |
| A2 | **"Login with Costrict" 按钮**（设备授权 + QR 面板） | `ProfileUi.kt:201-234`（设备授权流程 `start()`）、`LoggedOutProfileUi.kt:46`、`DeviceOAuthPanel.kt`、`QrCode.kt` | 整套 Kilo 设备 OAuth UI 在 Costrict 链路上失效；按钮文案（key `profile.action.login`）仅英文包改为 "Login with Costrict"（`KiloBundle.properties:836`），其余语言包仍是 "Login with Kilo Code"（并入 E1），用户极易走错门 |
| A3 | **Dashboard / Top up / Kilo Pass 外链** | `ProfileUi.kt:31-33`：`app.kilo.ai/profile`、`app.kilo.ai/credits`、`kilo.ai/pricing/kilo-pass`；遥测事件名 "Kilo Pass Opened"（`:74`） | Kilo 账户 Web 链接直跳 Kilo 官网，品牌与账号体系都不属于 Costrict |
| A4 | **History 的 Cloud 标签** | `KiloBackendSessionManager.kt:224-269`（GET `/kilo/cloud-sessions`、POST `/kilo/cloud/session/import`）；`HistoryPanel.kt:76,90` | cs-cloud 无该路由 → Cloud 页恒报 "Failed to load cloud history"，**必坏** |
| A5 | **恢复菜单里的 "Reinstall"**（XML 定义 `Kilo.Reinstall`，cli.text 为 "Reinstall Core"） | `kilo.jetbrains.frontend.xml:174-175`；`ConnectionPanel.kt:271-273` 无条件加入菜单；`CsCloudConnectionService.kt:111` `reinstall()` 直接抛 `CsCloudUnsupportedOperationException` | cs-cloud 模式下点击后 `KiloAppService.reinstallAsync()`（`KiloAppService.kt:177-180`，调用方 `ReinstallKiloAction.kt:14` 亦无 catch）**无 try/catch、无任何用户反馈**，静默失败 |
| A6 | **反馈与支持弹窗外链** | `EmptySessionFeedback.kt:127-130`：`kilocode.ai`（点 logo）、`github.com/Kilo-Org/kilocode/issues/new/choose`、`kilo.ai/discord`、`kilo.ai/support` | 空会话页 "Feedback and Support" 四个按钮全部指向 Kilo 社区 |
| A7 | **`/help` 斜杠命令** | `SessionUi.kt:860`：`SlashAction.HELP → https://kilo.ai/docs`（文案 key `prompt.slash.help`，bundle `:283`） | 文案已是 "Open Costrict documentation"，打开的却是 Kilo 文档 |

### A 组解决方案

共通思路：Costrict 的账号体系 = 浏览器 OAuth 登录 + JWT 本地解码 + 网页控制台承接管理动作，**没有"设置页 profile 卡片"这个形态**；云端能力经 daemon `/api/v1/*` 与 quota-manager 消费。

| # | 解决方案 | 参考实现 |
|---|---|---|
| A1 | 删除设置页 User Profile 整块（`fetchProfile`/`startLogin`/`logout`/`setOrganization` 及 `/kilo/profile` stub）。账号入口改为工具窗头部的账号图标（未登录=登录图标，已登录=头像/首字母），点开面板仅含：登录态 + 用户名/邮箱（JWT claims 本地解码）、（可选）余额、登录/重新登录/登出、打开账户控制台 | 入口=侧边栏 `$(account)` 图标（`src/package.json:465-469`）；claims 解码 `useCostrictUserInfo.ts:105-127`；额度 `fetchers/costrict.ts:58-92` |
| A2 | 删除 Kilo 设备 OAuth UI（`DeviceOAuthPanel.kt`、`QrCode.kt`、`ProfileUi.kt:201-234`、`LoggedOutProfileUi.kt:46`）。登录统一为 Costrict 登录：短期保留现有 `loginCsCloudAsync`（`csc auth login`，`KiloAppService.kt:252`）；中期把参考实现的插件原生 OIDC 轮询平移进 Kotlin（纯 HTTP + `BrowserUtil.browse` + 剪贴板兜底 + 定时轮询，无需 deep link 注册） | `authService.ts:87-108`（state+machine_code 轮询）、`authApi.ts:12-14`（`/oidc-auth/api/v1/plugin/login|token|status`，`:15` 另有 logoutUrl） |
| A3 | 三条 Kilo 外链替换：Dashboard/Top up → 账户控制台 `{base}/credit/manager?state=<tokenSha256>&tab=usage|subscription`，Kilo Pass → 运营活动 `https://costrict.ai/operation`；删除 "Kilo Pass Opened" 遥测事件。全部 URL 收敛到单一常量/单例（参考 E2） | `CostrictAccountView.tsx:265-284`；docs 统一出口 `webview-ui/src/utils/docLinks.ts` |
| A4 | 删除 Cloud 标签（`HistoryPanel.kt:76,90`）与 `/kilo/cloud-sessions`、`/kilo/cloud/session/import` 路由。如需跨设备历史，原生历史列表改接 daemon `GET /conversations`（重命名/删除用 `PATCH/DELETE /conversations/{id}`） | 参考实现无云历史（Roo CloudService 已注释禁用，`src/extension.ts:25`）；会话即历史=daemon conversations（`server.go:200-219`） |
| A5 | cs-cloud 模式下从恢复菜单移除 "Reinstall Core"（`ConnectionPanel.kt:271-273` 改按 provider 条件显示）；同时给 `KiloAppService.reinstallAsync` 补 catch+通知兜底，消除静默失败 | 参考恢复命令只有 reconnect/restart（`src/package.json:141-149`） |
| A6 | `EmptySessionFeedback.kt:127-130` 四链接替换：问题反馈 → `{base}/issue/`，bug/feature → `github.com/zgsm-ai/costrict/issues/new?template=...`，支持 → `https://docs.costrict.ai`，社区 → 官方下载页 `https://costrict.ai/download`（或微信群二维码/邮箱 `zgsm@sangfor.com.cn`）；logo 点击不再外链 | `About.tsx:99-139`、`extension.ts:385`；参考实现空会话页无任何外链（`ChatView.tsx:1977-2039`） |
| A7 | `/help` 目标改为 `https://docs.costrict.ai`（`SessionUi.kt:860`） | `docLinks.ts` |

### B. 用户可见但空转/仅旧链路有意义

| # | 功能 | 位置 | 现状与判断 |
|---|---|---|---|
| B1 | **"Restart Core" / "Core vX" 动作** | `kilo.jetbrains.frontend.xml:171-172,183-184,199-206`；`CoreInfoAction`（版本 `cli.version=7.4.23`，字面值在 `package.json:11`，`build.gradle.kts:40-68` 读取注入） | cs-cloud 模式下 `restart()` 只是重连（`CsCloudConnectionService.kt:109`），"Core vX.Y" 版本信息与 Costrict 无关 |
| B2 | **"Downloading Costrict Core… x%" 进度 UI** | `ConnectionPanel.kt:160-175` + bundle `:11-12`；`ConnectionState.Downloading` 仅由旧链路 `KiloBackendConnectionService` 产生 | cs-cloud 永不触发，纯死 UI |
| B3 | **Kilo provider 特权逻辑** | `ProviderCatalog.kt:18-29`（kilo 排 popular 第一位）；`ModelItems.kt:12-15`（kilo 模型免连接即展示）；`ModelPicker.kt:277,301,307,309`（kilo 排序/小模型特判；`:309` `providerSort` kilo=0）；`ProviderListRows.kt:95`（不显示 Disconnect）；`KiloBackendProviderSettingsManager.kt:111-113` 错误文案 "Kilo Gateway cannot be disconnected..." | 全套 Kilo 官方 provider/模型的目录、排序、文案残留；cs-cloud 下 `/provider` 数据由 daemon 的 `/api/v1/agents/models` 重写（`CsCloudRoute.kt:74-90`），这些前端特判基本落空但会污染 UI |
| B4 | **Kilo 付费模型 401 检测** | `SessionController.kt:1708-1724` + `PaidModelAuth.kt:8-32` | Kilo Gateway 契约，cs-cloud 链路永不为真（与其并存的 `isCsCloudAuthRequired`（`PaidModelAuth.kt:43-64`）才是有效路径） |
| B5 | **组织/账号切换 overlay** | `SessionController.kt:2227-2247`、`session/ui/account/*` | Kilo 组织概念；cs-cloud 下 profile 无组织故不可见，代码残留 |
| B6 | **Settings 窗口 Models 面板的 Small Model 设置** | `ModelsSettingsUi.kt`（`client/settings/models/`）+ bundle `:804-805` | "小模型用于 prompt 增强/标题生成" 是旧 Kilo CLI 语义；cs-cloud 模型数据只取第一个 provider 作默认（会话头部无此设置） |
| B7 | **遥测上报** | `KiloBackendTelemetry.kt:25-33,39-47`（`/telemetry/capture`、`/telemetry/setEnabled`） | cs-cloud 路由表无此路径（`else -> path` 原样透传 daemon → 404），调用处三处 `runCatching{}.onFailure{ log.info }`（`KiloBackendAppService.kt:629,639,649`），静默失败 |

### B 组解决方案

| # | 解决方案 | 参考实现 |
|---|---|---|
| B1 | "Restart Core" 更名 "Restart cs-cloud"（语义对齐 daemon 重启）；"Core vX" 信息项删除，如需版本展示改读 daemon `/agents/version`。参考实现的版本策略是插件只上报自身 version，不维护 daemon 版本比较 | `src/package.json:141-149`；版本注入 `html.ts:749-751` |
| B2 | 删除 `ConnectionState.Downloading` 分支与进度 UI（`ConnectionPanel.kt:160-175`）。cs-cloud 安装/启动过程反馈改用 `Task.Backgroundable` 进度通知（见 P2-7） | 启动进度 `withProgress`「正在启动 CoStrict Cloud」（`sidebarProvider.ts:512-519`） |
| B3 | Kilo 特权逻辑替换为 costrict/Auto 语义：默认模型（Auto=服务端路由）置顶并作缺省；（可选增强）用 `x-select-llm` 响应头回显"本次实际模型"（该头由云端上游下发、daemon 代理透传——cs-cloud/csc 仓库零命中，costrict 前端读取处 `src/api/providers/costrict.ts:237`）；删除 `ProviderCatalog` 内置 Kilo 目录/popular 排序、`ModelItems.kt` 免连接展示、"Kilo Gateway cannot be disconnected" 文案、`ModelPicker` 小模型特判、`ProviderListRows` 的 Disconnect 禁显 | Auto 默认模型 `packages/types/src/providers/costrict.ts`；选模回显 `ChatRow.tsx:1316-1322`；模型缓存范式 `fetchers/modelCache.ts`（5min 内存+磁盘+zod 校验） |
| B4 | 删除 `PaidModelAuth`（`SessionController.kt:1708-1724`、`PaidModelAuth.kt`）；401 统一收敛为单一登录引导路径（见 P1-5/P2-4） | `ErrorCodeManager.ts:193-206` 将 `*.unauthorized`/`token_invalid`/`voucher_expired` 归并 401 → 原生通知带 Login 按钮 + 去重（`authService.ts:509-533`） |
| B5 | 删除组织/账号切换 overlay（`SessionController.kt:2227-2247`、`session/ui/account/*`）。参考实现无组织切换，组织仅 JWT 只读展示 | `App.tsx:407-414`（Roo OrganizationSwitcher 被注释）、`useCostrictUserInfo.ts:120-121` |
| B6 | 删除会话头部 Small Model 设置与相关 bundle 文案；标题/commit 类辅助生成用会话主模型 raw 模式（可选独立 commit 模型配置）。参考实现无 small model 概念 | `commitService.ts:55-57`（`costrict.commit.commitModelId` 可选覆盖）、`costrict.ts:987`（`prompt_mode:"raw"`） |
| B7 | 二选一：a) 删除 `/telemetry/*` 透传（cs-cloud 链路无消费方）；b) 对齐参考遥测模型——`{base}/user-indicator/api/v1/control` 服务端开关 + `indicators/batch-report` 聚合批量（默认 20min，服务端可调）+ raw-store 会话上报，登录 Bearer 门控、`DISABLE_USER_INDICATOR` 逃生阀。b 含完整对话上报，属产品/合规决策，需单独评审后再做 | `packages/telemetry/src/costrictTelemetry/`、`src/core/costrict/telemetry/index.ts:11-26` |

### C. 死代码与承诺未兑现

| # | 项 | 位置 | 判断 |
|---|---|---|---|
| C1 | **Autocomplete 设置服务是纯死存储** | `KiloAutocompleteSettingsService.kt:10-39`；全仓库唯一写入方是迁移 `KiloMigrationService.kt:349`，**无任何读取方**；插件不实现编辑器 tab/inline 补全（全仓库无 `InlineCompletionProvider`） | 迁移向导却向用户承诺迁移 "Auto-Approval, Language & Autocomplete"（bundle `:954`，key `migration.row.settings`），落地后无消费 |
| C2 | **plugin.xml 描述宣称 inline autocomplete** | `plugin.xml:8-14` "Features inline autocomplete" | 本插件没有该功能（上游 Kilo 的宣传语被原样保留），描述与实现不符 |
| C3 | **ShowProfileAction 死代码** | `ShowProfileAction.kt:21-39` + bundle `:443-444` | 已定义未注册（XML 中已无引用），文件可删 |
| C4 | **迁移把 kilocodeToken 写成 `kilo` provider OAuth 凭证** | `LegacyMigrationConverters.kt:233-242`、`LegacyProviderMapping.kt:62`（"Kilo (Gateway)"） | 目标 `PUT /auth/kilo` 在 cs-cloud 链路上无消费方 |
| C5 | **迁移内建模式系统提示词自称 "You are Kilo Code"** | `LegacyMigrationConverters.kt:578-621` `NATIVE_MODE_DEFAULTS`；`:641` 起 `buildMergedNativeMode` | 迁移后的 Costrict 配置里 agent 系统提示词自称 Kilo Code，属于会随配置长期存活的品牌污染 |

### C 组解决方案

| # | 解决方案 | 参考实现 |
|---|---|---|
| C1+C2 | 二选一，现状（死存储+承诺不兑现）不可保留：a) **实现补全**——JetBrains 原生 `InlineCompletionProvider`，推理后端移植自 Costrict 的 auto-complete（其本身即自 Kilo 移植，语义同源），补全 runtime 二进制有成熟的全自动安装器范式；b) **暂不做**——删除 `KiloAutocompleteSettingsService`、迁移向导的 "Language & Autocomplete" 承诺（bundle `:954`）与 `plugin.xml:8-14` 的 inline autocomplete 宣传语 | a) `src/core/costrict/auto-complete/**`（6 处 Kilo 版权注释可证移植来源）、`src/core/costrict/runtime-config/runtimeInstaller.ts`（版本清单+checksum+SHA256 签名+3 次退避+失败回退旧版本）；b) 参考实现无此类宣传 |
| C3 | 删除 `ShowProfileAction.kt` 与 bundle `:443-444` | — |
| C4 | 迁移不再把 kilocodeToken 写成 kilo provider OAuth 凭证（`LegacyMigrationConverters.kt:233-242`、`LegacyProviderMapping.kt:62`）；账号环节改为迁移完成后引导登录（复用 A2 的登录入口）。跨工具配置迁移参考实现只有 JSON 导入/导出 + 启动自动导入两条通用通道 | `src/core/config/importExport.ts`、`costrict.autoImportSettingsPath`（`src/package.json`） |
| C5 | `NATIVE_MODE_DEFAULTS` 系统提示词重写为 Costrict 语义；模式体系对齐参考实现的 vibe/strict/plan/raw 模式（strict/plan 仅限 costrict provider） | `src/shared/modes.ts:140-149`（`isProviderAllowedForCostrictCodeMode`，:144-145 strict/plan 仅 costrict；`CostrictCodeMode` 为 vibe/strict/plan/raw 四值） |

### D. 旧 Kilo CLI 基础设施（非用户直接可见，但随包发布）

| 项 | 位置 |
|---|---|
| CLI 进程管理 / 下载（**运行时从 `github.com/Kilo-Org/kilocode` release 下载二进制**） | `KiloBackendCliManager.kt:34,154`；`KiloCliDownloader.kt:35-38`；`KiloRepoCli.kt:14,116`（捆绑 `kilo-cli.zip`） |
| 旧配置目录 `~/.config/kilo`、`KILO_CONFIG_DIR` | `KiloCliConfigPath.kt:9,11`；使用点 `KiloWorkspaceRpcApiImpl.kt:356`、`KiloBackendLegacyMigrationStoreService.kt:125`（`KiloBackendCliManager.kt:662` 是 devStorageEnv 开发隔离，非此用途） |
| `cli.runtime=true` 默认开启、`cli.pinned`（默认 true）、`cli.version=7.4.23`（字面值在 `package.json:11`） | `KiloProps.kt:14-23`、`backend/build.gradle.kts:40-68` |
| 构建任务指向 Kilo-Org 仓库 | 默认构建链上：`GenerateOpenApiSpecTask.kt:35,145`（经 compileKotlin 挂载）、`WriteCliChecksumsTask.kt:22`（挂 processResources）；**不在默认链**：`StageBundledCliTask.kt:30,112`（需 `-Pkilo.cli.bundled=true`）、`StageRepoCliTask.kt`（需 `-Pkilo.cli.pinned=false`，且为本地 `../opencode` 打包、无远程 URL） |
| 配置文件名/路径仍是 `kilo.json(c)`、`.kilo` 目录、`$schema: app.kilo.ai/config.json` | `KiloWorkspaceRpcApiImpl.kt:80-92`（Config Files 菜单会显示 "Open: global kilo.json"） |

> 说明：`cli.runtime=true` 意味着**发行包仍内置 Kilo CLI 下载/兜底能力**。cs-cloud 模块在位时不会走到，但这是发行包体与供应链层面的遗留，建议至少把默认值改为 false 或裁剪。

**D 组解决方案**

1. `cli.runtime` 默认值改 false（`KiloProps.kt:23`）；从默认构建链摘除 `GenerateOpenApiSpecTask` 与 `WriteCliChecksumsTask`（`StageBundledCliTask`/`StageRepoCliTask` 需显式 `-P` 参数才挂载、本就不在默认链，直接删除任务定义即可）——发行包不再含 Kilo CLI 下载/兜底能力。
2. 配置面去 Kilo 化：`~/.config/kilo`/`KILO_CONFIG_DIR`/`kilo.json(c)`/`.kilo` 目录（`KiloCliConfigPath.kt:6-14`、`KiloWorkspaceRpcApiImpl.kt:80-92`）随 CLI 裁剪删除，或迁移到 `~/.config/costrict`（daemon 收藏/技能已用该目录）。
3. 若未来需要自带二进制（摆脱 npm 依赖），参考实现给出两种形态：a) 捆绑二进制自动拉起——放 `~/.costrict/bin/`，作为发现链第 3 级（`csCloudService.ts:151-166`）；b) 全自动下载安装器——版本清单 API + checksum/SHA256 签名双校验 + 并发去重 + 失败回退旧版本（`runtimeInstaller.ts`）。

### E. 品牌残留（用户可见文案/资源）

| # | 项 | 位置 |
|---|---|---|
| E1 | **17 个语言包未重塑**（zh_CN 例外：主要 key 已改 Costrict，但新增 key 有漏译，见 P2-5）：`notification.group.kilo=Kilo Code`（通知设置里的组名）、`settings.kilo.displayName=Kilo Code`（设置页名）、欢迎语 `session.empty.welcome`、`session.connection.unsupported.*`、`profile.action.login="Login with Kilo Code"` 等 | `messages/KiloBundle_<ar/bs/da/de/es/fr/ja/ko/nl/no/pl/pt_BR/ru/th/tr/uk/zh_TW>.properties` 各 `:190/:278/:19/:11-16` 附近（英文包与 zh_CN 包已改 Costrict；zh_CN 对应行 `:203/:294`） |
| E2 | 通知组 ID 与兜底标题硬编码 "Kilo Code" | `KiloNotifications.kt:12`；`AdvancedLogActions.kt:79-81`、`AgentEditDialog.kt:346-348`、`PromptAttachmentStrip.kt:84`、`PromptPanel.kt:927`（绕过 bundle 的兜底构造，用户直接看到 "Kilo Code" 标题） |
| E3 | "CoStrict" 大小写混用（官方拼写 CoStrict，见解决方案勘误） | 英文包 `:214-216`（csCloud 登录卡片）、`:911-913`（csc 安装/登录文案）、`:963-971`（code review 全套）等；全包计数 **CoStrict 16 次 / Costrict 61 次** |
| E4 | 图标资源名残留：toolWindow `kilo.svg`、账号页 `kilo-profile*.svg`、空会话 logo `kilo-content.png` | `frontend/.../resources/icons/`；`kilo.jetbrains.frontend.xml:30`；`BrandLogo.kt:43` |
| E5 | `icons/providers/` 目录不存在，provider 图标兜底到 `AllIcons.Nodes.Plugin` | `ProviderCatalog.kt:52-55` |

### E 组解决方案

| # | 解决方案 | 参考实现 |
|---|---|---|
| E1 | 先做范围决策：a) 裁剪到 en/zh_CN/zh_TW 三语（参考实现仅维护三语），其余语言包删除；b) 保留全量语言则一次性完成替换（zh_CN 已完成主要 key，可作非英文包的校对基准），必见项（`notification.group.kilo`、`settings.kilo.displayName`、`session.empty.welcome`、`session.connection.unsupported.*`）人工校对。任一方案都先以英文包为基准再同步其余 | `src/shared/language.ts`（`COSTRICT_LANGUAGES`=3 语）、`package.nls.{zh-CN,zh-TW}.json` |
| E2 | 新建品牌常量单例（如 `CostrictBrand`）收口通知组名/兜底标题，替换 `KiloNotifications.kt:12` 与 `AdvancedLogActions.kt:79-81`、`AgentEditDialog.kt:346-348`、`PromptAttachmentStrip.kt:84`、`PromptPanel.kt:927` 四处硬编码；配合 A3 的 URL 单例 | `src/shared/package.ts` 的 Package 常量模式（publisher/命令前缀/输出通道集中管理） |
| E3 | **勘误**：参考实现用户可见文案的官方拼写是 **"CoStrict"**（复核精确计数：`package.nls.json` CoStrict 16 处 / Costrict 1 处且唯一一处是代码标识 `CostrictCodeStorage`；`html.ts` CoStrict 8 处（`:371,395,550,654,671,754,1102,1254`）/ Costrict 0 处）——本文原判"正确拼写 Costrict"与官方相反。建议：用户可见文案统一为官方 "CoStrict"，代码标识保持小写 `costrict`；按此结论全仓统一（bundle `:214-216,911-913,963-971` 等） | `src/package.nls.json`、`src/core/cs-cloud/extension/html.ts` |
| E4 | 图标资源迁入单一品牌目录（如 `resources/icons/costrict/`：logo.svg、profile 等），toolWindow 图标、账号页、空会话 logo 一次性替换并经常量引用 | `src/assets/costrict/{logo.svg,logo.png,wechat.png}` 单一品牌资源目录 + BASE_URI 注入（`ClineProvider.ts:1531`） |
| E5 | 随 B3 收敛后 provider 目录只剩 costrict/Auto，图标兜底问题自然消失；若保留多 provider 列表，为 costrict 补一个图标即可 | 参考实现无 provider 图标目录（模型来自云列表） |

### F. 有意保留 / 内部标识（不构成问题，列出以示区分）

- `KiloBundle` 资源文件名、`action.Kilo.*` 动作 ID、模块名 `kilo.jetbrains.*`、registry key `kilo.session.*`、VFS 协议名 `kilo`、`KiloPlugin.ID="ai.costrict.jetbrains"` —— 均为内部标识，用户不可见（除 E2 所述通知组）。
- 迁移向导本身（从旧 Kilo 安装导入设置）对 Kilo→Costrict 迁移用户有价值，属有意保留；但其承诺项需与 C1 对齐。

---

## 专项二：未安装 csc / cs-cloud 用户的友好引导

### 现状链路还原（全新用户、未装 csc）

```
IDE 启动 → backend connect() → CsCloudConnectionService.connect()
  → CsCloudEndpointResolver 找不到 ~/.costrict/cs-cloud/server_url
  → fail(MissingUrl) → code=CSC_NOT_INSTALLED（CsCloudConnectionService.kt:261）
  → appState=ERROR（KiloBackendAppService.kt:450-457，detail=connectionDiagnostic 英文文案）
  → 工具窗 ConnectionPanel：红色 "Connection failed" + 详情自动展开（ConnectionPanel.kt:183）
  → "Try again" 链接 → 恢复弹出菜单：Try again / Restart / Reinstall / Start cs-cloud / Install csc CLI（:258-280；菜单文本为 "Restart"/"Reinstall"，"Restart Core"/"Reinstall Core" 是 cli.text 不入此菜单）
  → 点 Install csc CLI → 后端按 npm→pnpm→bun→yarn 探测（CscInstaller.kt:63-74）→ 各用各的安装命令（npm `install -g` / pnpm、bun `add -g` / yarn `global add`），包名 `@costrict/csc`（:76-86）
       ├─ 找到 → 安装 → 自动 `csc cloud start` → connect()（CsCloudConnectionService.kt:120-124）
       └─ 找不到(NPM_NOT_FOUND) → 通知 + "Open csc on npm" 打开 npmjs.com（KiloAppService.kt:227-236）
  → daemon 起来但未登录(401) → code=UNAUTHORIZED → 详情建议 "set CS_BRIDGE_API_KEY / CS_CLOUD_API_KEY"（KiloBackendAppService.kt:1129-1132）
  → 会话内 401/未登录文本 → LoginRequired 卡片（标题 "Sign in to CoStrict to continue"、按钮 "Sign in to CoStrict"）→ `csc auth login` 开浏览器（CscLogin.kt）
  → 后台 poll 循环（≤2s 退避）持续重试，daemon 一旦就绪自动连上（CsCloudConnectionService.kt:241-251）
```

### 已具备的引导（做得好的）

1. **零手动安装设计**：Install csc → 自动 Start cs-cloud → 自动 connect 全链打通；csc 解析覆盖 nvm/bun/volta/pnpm 等 PATH 盲区（`CsCloudStarter.kt:78-92`，类名 `CscCloudStarter`）。
2. CSC_NOT_INSTALLED 时错误详情**默认展开**，无需用户再点（`ConnectionPanel.kt:183`，`expanded = code == CSC_NOT_INSTALLED`；其他错误码默认折叠）。
3. 失败通知带行动按钮的链式引导：Start 失败 → Install 按钮；Install 失败 → npm 页面按钮（`KiloAppService.kt:193-236`）。
4. 自动重连/轮询：装好 csc、daemon 起来后无需手动 Retry。
5. 会话内未登录有专门卡片并直达 `csc auth login`（浏览器 OAuth，token 热更新无需重启 daemon）。
6. `csCloud.*` 关键通知文案 zh_CN/zh_TW 已翻译。

### 问题清单（按影响排序）

| # | 严重度 | 问题 | 证据 |
|---|---|---|---|
| P2-1 | 高 | **无主动前置引导**：首次打开工具窗，用户只看到笼统的 "Connection failed"（summary），"装 csc" 这个唯一正解藏在 "Try again" → 弹出菜单第 4/5 项里；空会话欢迎页（logo+欢迎语+History/Feedback 按钮）完全没有任何 cs-cloud 安装引导 | `SessionController.kt:2506-2511`（summary 固定 `session.connection.error.app`）；`EmptySessionPanel.kt:103-138` |
| P2-2 | 高 | **关键错误文案硬编码英文**：给用户的详情/通知正文全部是代码里拼的英文技术句（"csc is not installed or not on the IDE PATH - install it with `npm install -g @costrict/csc`, then try Start cs-cloud again" 等），对中文用户（Costrict 主力用户群）不友好，且无法本地化 | `CsCloudStarter.kt:35,45`；`CscInstaller.kt:29,43`；`CscLogin.kt:34,64`；`KiloBackendAppService.kt:1121-1150`（connectionDiagnostic 全英文） |
| P2-3 | 高 | **恢复菜单混入旧链路动作**：任何错误码下都先列出 "Restart"/"Reinstall"，未装 csc 的用户面前摆着 5 个选项，其中 2 个与问题无关、1 个（Reinstall）点了静默失败（`reinstallAsync` 无 catch 无通知，异常只在协程日志里） | `ConnectionPanel.kt:271-280`；`KiloAppService.kt:177-180`；`CsCloudConnectionService.kt:111` |
| P2-4 | 中 | **UNAUTHORIZED 引导跑偏**：daemon 起了但没登录时，详情建议用户去设 `CS_BRIDGE_API_KEY`/`CS_CLOUD_API_KEY` 环境变量（开发者向），恢复菜单此时只加 StartCsCloud，**没有 "Sign in to CoStrict" 入口**；正确路径（点登录卡片触发 `csc auth login`）需要先发一条消息才能看到 | `KiloBackendAppService.kt:1129-1132`；`ConnectionPanel.kt:274-276`；`SessionController.kt:1741-1751`（登录态）+ `:2318`（loginCsCloud） |
| P2-5 | 中 | **zh_CN/zh_TW 未翻译登录卡片**：`session.login.required.csCloud.title/description/button` 三条在两个中文包里仍是英文 | `KiloBundle_zh_CN.properties:268-270`、`KiloBundle_zh_TW.properties:268-270` |
| P2-6 | 中 | **npm 缺失的降级引导弱**：只弹通知 + 打开 npmjs.com 英文页；没有指向 Costrict 官方安装文档/国内镜像的链接，也未提示"安装 Node.js 后请重启 IDE/重试" | `CscInstaller.kt:29`；`KiloAppService.kt:227-236`（`InstallCscAction.CSC_NPM_URL`） |
| P2-7 | 低 | **安装过程无进度反馈**：`npm install -g` 最长等 300s，期间只有一条 "Installing csc CLI via npm…" 通知，无进度/可取消；且 `installCscAsync` 有 `csCloudInstalling` 原子锁，重复点击静默忽略（无"正在安装中"提示） | `CscInstaller.kt:20`；`KiloAppService.kt:215-247` |
| P2-8 | 低 | **"Core vX" 信息项在 cs-cloud 模式无意义**：恢复菜单/设置分组里的 Core 版本信息对 Costrict 用户是噪音 | `kilo.jetbrains.frontend.xml:183-184,199-206`；`CoreInfoAction` |
| P2-9 | 低 | **安装成功/失败的因果不透明**：Install 成功通知 "csc installed and cs-cloud started"（`csCloud.install.ok`）在 start 阶段失败时并不成立（installCsc 里 install 成功但 startCsCloud 失败时返回的是 start 的失败 DTO，ok=false → 走 failed 分支，文案却是 "Failed to install csc"，误导排查方向） | `CsCloudConnectionService.kt:120-124`；`KiloAppService.kt:224-239` |

### 解决方案（参考 cloud 模式，按优先级）

**P0（先做，成本低收益高）**

1. **[P2-1] 错误引导卡**：ConnectionPanel 按 `code` 渲染引导区而非一行 summary——`CSC_NOT_INSTALLED` → 标题"未安装 csc 组件" + **Install csc** 主按钮 + 安装文档链接；`DAEMON_DOWN` → "cs-cloud 未启动" + Start 按钮（可加 10s 倒计时自动重试）；`UNAUTHORIZED` → "登录 CoStrict" 主按钮。空会话面板在首次 ERROR 时加同款引导区。
   参考：错误页形态 `sidebarProvider.ts:629-904`；倒计时——启动失败错误页 10s（`sidebarProvider.ts:824`）、崩溃页 5s（`html.ts:568`，getCrashedHtml 自 `:380` 起）。
2. **[P2-2] 错误文案 i18n**：`ConnectionErrorCode`（`shared/.../ConnectionErrorCode.kt`）仅 4 个稳定码，前端展示前从 KiloBundle 映射（新增 `csCloud.error.<code>.title/desc` 键）；英文技术细节保留在折叠详情区。可选增强：文案支持远端下发+缓存（对齐参考实现三级降级）。
   参考：`ErrorCodeManager.ts`（HTTP 静态表 + 远端 `error_codes_{lang}.json` + 本地缓存）。
3. **[P2-5] 补翻译**：`session.login.required.csCloud.*` 三条补 zh_CN/zh_TW——"登录 CoStrict 以继续" / "登录 CoStrict 账号后即可使用 cs-cloud，然后继续本会话。" / "登录 CoStrict"（zh_TW 用繁体；拼写按 E3 勘误统一）。

**P1（体验完善）**

4. **[P2-3] 恢复菜单按 code 过滤**：`recoveryActionIds()`（`ConnectionPanel.kt:271-280`）不再无条件加 `Kilo.Restart`/`Kilo.Reinstall`；cs-cloud 模式下菜单按 code 只保留 Retry / Start cs-cloud / Install csc / Sign in。参考实现恢复命令全集只有 reconnect/restart，且未装 csc 时隐藏 Restart（`canRetry = !isUninstallCsc`）。
5. **[P2-4] 修正 401 引导**：① 删除 `KiloBackendAppService.kt:1129-1132` 的 `CS_BRIDGE_API_KEY`/`CS_CLOUD_API_KEY` 建议——daemon 侧这两个变量是**可选 API key 鉴权且默认关闭**（cs-cloud `internal/config/load.go:24`、`middleware.go:9-14`，空 key 即 no-op），默认部署下 401 的正解是登录，该引导是开发者向的误导（勘误：初版"daemon 无 API key 概念（全仓库零命中）"的表述不准确——零命中仅对 costrict 参考仓库成立）；② 新注册 `Kilo.SignInCsCloud` action（注册方式复用 `Kilo.StartCsCloud`，`kilo.jetbrains.frontend.xml:180-181`）调用现成的 `loginCsCloudAsync`（`KiloAppService.kt:252`），UNAUTHORIZED 时加入恢复菜单与引导卡；③ 详情文案改为"认证已过期/未登录 + 解决步骤"结构。
   参考：`openStatusBarLoginTip`（`authService.ts:509-533`，警告通知 + Login 按钮 + `hasStatusBarLoginTip` 去重）；`apiErrors.json` 的分段式 solution 文案（"1.点击账户图标→重新登录 2.联系管理员"）。
6. **[P2-6] npm 缺失降级**：NPM_NOT_FOUND 通知改双按钮——Costrict 官方安装文档 `https://docs.costrict.ai/cli/guide/installation` + npm 页面；正文提示"安装 Node.js 后重启 IDE 再重试"。注：官方安装文档的 npm 包名写作 `@costrict/cs`（cs TUI），与插件安装的 `@costrict/csc`（csc 仓库 `package.json:2`）口径不一致，链接落地前需与官方确认目标页面；该文档另提供 GitHub Release（`zgsm-ai/opencode`）二进制兜底方案，可一并指给无 Node.js 环境的用户。
   参考：VS Code 不代执行安装，直接在错误页展示命令文本（`csCloudService.ts:185-189`）；官方下载页 `https://costrict.ai/download`。
7. **[P2-7] 安装进度**：install/start 改 `Task.Backgroundable` 带进度与取消（npm 进程 destroy）；`csCloudInstalling`/`csCloudStarting` 锁被占用时提示"正在安装/启动中"而非静默忽略（`KiloAppService.kt:186,219`）。
   参考：`withProgress`「正在启动 CoStrict Cloud」（`sidebarProvider.ts:512-519`）。
8. **[P2-9] 语义修正**：`CsCloudConnectionService.installCsc`（`:120-124`）返回 DTO 增加 `stage`（install/start）或拆分独立错误码，`csCloud.install.failed` 按 stage 分文案——start 阶段失败时报 "csc 已安装，但 cs-cloud 启动失败"。
   参考：哨兵 `__IS_UNINSTALL_CSC_ERROR__`（`csCloudService.ts:189`）把"未安装"与"启动失败"从错误对象层面分开，UI 与文案完全分叉。

**P2（与专项一联动清理）**

9. **[P2-8]** Core 信息项随 B1 删除。
10. 配合 A5/B1 把 Core 相关动作从发行包 UI 中裁剪；配合 E1 完成各语言包品牌重塑（zh_CN 已完成主要 key，其余 17 包待做；`notification.group.kilo`、`settings.kilo.displayName`、欢迎语是用户必见项）。
11. **[P2-1 增强] 首次启动前置引导**：app 进入 ERROR 且 `CSC_NOT_INSTALLED` 时弹一次性 balloon（带 "Don't show again"，状态存 `PropertiesComponent`），或首次打开工具窗时在空会话面板常驻"开始前需安装 csc"引导区 + Install 按钮。
    参考：切 cloud 模式前强制登录校验的产品姿态（`registerCommands.ts:214-236`，未登录弹"请先登录"对话框）——JetBrains 同理可做"未就绪先引导"。

---

## 附：核心证据索引

| 主题 | 位置 |
|---|---|
| 连接 provider 选择（cs-cloud 优先 / kilo-cli 兜底） | `KiloBackendAppService.kt:141-152`、`KiloConnectionProvider.kt:84-93`、`kilo.jetbrains.cs-cloud.xml:12-14` |
| cs-cloud 端点发现与错误码映射 | `CsCloudEndpointResolver.kt:17-40`、`CsCloudConnectionService.kt:253-268` |
| csc 安装/启动/登录 | `CscInstaller.kt`、`CsCloudStarter.kt`、`CscLogin.kt`、`KiloAppService.kt:184-247` |
| 恢复菜单与错误 banner | `ConnectionPanel.kt:177-213,258-280` |
| cs-cloud 路由重写与 stub（profile 401 等） | `CsCloudRoute.kt:46-47,74-109` |
| Kilo 账号体系残留 | `KiloBackendAppService.kt:699-726,1024-1088`、`ProfileUi.kt:31-33,201-281` |
| Kilo CLI 下载/兜底链路 | `KiloBackendCliManager.kt`、`KiloCliDownloader.kt:35-38`、`KiloProps.kt:14-23` |
| 云历史残留 | `KiloBackendSessionManager.kt:224-269`、`HistoryPanel.kt:76-90` |
| 品牌文案残留 | `KiloBundle*.properties`（18 语言包，zh_CN 已重塑）、`KiloNotifications.kt:12`、`EmptySessionFeedback.kt:127-130`、`SessionUi.kt:860` |

## 附 2：参考实现索引（解决方案引用依据）

未注明者相对 `F:\ai-coding\costrict`；daemon 路径相对 `F:\ai-coding\cs-cloud`。

| 主题 | 位置 |
|---|---|
| cloud 模式宿主桥（消息协议/fetch 代理/token 注入/postMessage 全集） | `src/core/cs-cloud/extension/sidebarProvider.ts:191-455`、`html.ts:742-955,1122-1252` |
| daemon 发现链/健康检查/崩溃检测/自动重连 | `src/core/cs-cloud/extension/csCloudService.ts:119-208,352-463,487-491`（exit 监听；心跳/退避常量 `:55-58`） |
| cs-cloud 配置项全表 | `src/core/cs-cloud/extension/config.ts:14-26`、`src/package.json:776-839` |
| OIDC 登录/token 存储/登出/多窗口同步 | `src/core/costrict/auth/{authService,authApi,authStorage,authCommands}.ts`、`src/core/costrict/runtime-config/index.ts:222-276` |
| daemon API 路由表（conversations/agents/permissions/questions/events/terminal） | `internal/localserver/server.go:154-256` |
| 错误页/崩溃页/未装 csc 哨兵/倒计时 | `sidebarProvider.ts:629-904`（启动失败页 10s 倒计时 `:824`）、`html.ts:380-`（崩溃页 5s 倒计时 `:568`；`__IS_UNINSTALL_CSC_ERROR__` 于 `csCloudService.ts:189`） |
| 错误码→文案映射（三级降级） | `src/core/costrict/error-code/ErrorCodeManager.ts`、`src/i18n/costrict-i18n/locales/{en,zh-CN,zh-TW}/{common,apiErrors}.json` |
| 账号中心/额度/邀请码 | `webview-ui/src/components/cloud/CostrictAccountView.tsx`、`src/api/providers/fetchers/costrict.ts:58-92`（15s 轮询在 `CostrictAccountView.tsx:331-336`）、`useCostrictUserInfo.ts:105-127` |
| 模型列表/Auto 默认/缓存 | `packages/types/src/providers/costrict.ts`、`fetchers/modelCache.ts`、`ChatRow.tsx:1316` |
| 官方链接与反馈入口 | `webview-ui/src/utils/docLinks.ts`、`webview-ui/src/components/settings/About.tsx:99-139`、`src/extension.ts:385`、`authConfig.ts` |
| 补全与 runtime 自动安装器 | `src/core/costrict/auto-complete/**`（6 处 Kilo 版权注释）、`src/core/costrict/runtime-config/runtimeInstaller.ts` |
| 品牌常量与资源组织 | `src/shared/package.ts`、`src/assets/costrict/`、`src/package.nls*.json`、`src/shared/language.ts` |
| 遥测（user-indicator 体系） | `packages/telemetry/src/costrictTelemetry/`、`src/core/costrict/telemetry/{index,rawTaskReporter}.ts` |

---

## 附 3：复核勘误明细（2026-09-03）

全部断言已对照四个仓库逐条复核（kilo-jetbrains / costrict / cs-cloud / csc，约 150 条 file:line 引用）。核心问题定性（A1-A7、B3/B4/B7、C1-C5、P2-1~P2-9）与解决方案方向**全部成立**；以下实质修正已写入正文，行号级小偏差（±2~14 行）一并同步。

| # | 原文表述 | 勘误后 | 依据 |
|---|---|---|---|
| 1 | 参考基准#3/P1-5："daemon 无 API key 概念（全仓库零命中）" | daemon（cs-cloud）有**可选** API key 鉴权：`CS_BRIDGE_API_KEY`（新名）/`CS_CLOUD_API_KEY`（旧名回退）→ `authMiddleware`，默认空=no-op；"零命中"仅对 costrict 参考仓库成立（csc 仓库也零命中） | cs-cloud `internal/config/load.go:24`、`internal/platform/env.go:21-30`、`middleware.go:9-14`、`server.go:136` |
| 2 | E1："18 个语言包未重塑" | zh_CN 主要 key 已改 Costrict（`:203/:294/:19/:11-16`），未重塑为 **17** 个；但 zh_CN 的新增 key 仍有漏译（P2-5 的 csCloud 登录卡片） | `KiloBundle_zh_CN.properties` |
| 3 | D 组："构建任务指向 Kilo-Org 仓库"+方案"四个任务从默认构建链摘除" | 默认链上仅 `generateOpenApiSpec` 与 `writeCliChecksums`；`stageBundledCli`（需 `-Pkilo.cli.bundled=true`）/`stageRepoCli`（需 `-Pkilo.cli.pinned=false`）本就不在默认链，且 `StageRepoCliTask` 是本地 `../opencode` 打包、无 Kilo-Org 远程 URL | `backend/build.gradle.kts:178-197`、`gradle.properties:6` |
| 4 | D 组：`KiloBackendCliManager.kt:662` 使用旧配置目录 | 该行是 devStorageEnv 开发隔离（XDG_CONFIG_HOME 映射）；`KiloCliConfigPath` 真实使用点 `KiloWorkspaceRpcApiImpl.kt:356`、`KiloBackendLegacyMigrationStoreService.kt:125` | 同左 |
| 5 | B6："会话头部 Small Model 设置" | `ModelsSettingsUi` 在 Settings 窗口 Models 面板（`client/settings/models/`），会话头部无此设置；删除建议不变，仅定位更正 | bundle `:804-805` 引用处 |
| 6 | 参考基准#5：启动失败/崩溃页倒计时均为 10s（`html.ts:380-389`） | 10s 在启动失败错误页（`sidebarProvider.ts:824`，按钮"重新启动"）；崩溃页 getCrashedHtml 为 **5s**（`html.ts:568`，按钮"重新连接"） | 同左 |
| 7 | 参考基准#4：`src/core/cs-cloud/runtime-config/index.ts`、pickFresher 按 JWT exp | 实际路径 `src/core/costrict/runtime-config/index.ts`；pickFresher 主信号是 refresh_token `iat`、`exp` 仅决胜 | `pickFresher.ts:61-66` |
| 8 | C1+C2：auto-complete "13 处版权注释" | 实际 **6** 处（6 文件×2 行）；runtimeInstaller 在 `src/core/costrict/runtime-config/`（非 cs-cloud 下） | 同左 |
| 9 | 专项二链路：Install 统一执行 `npm install -g @costrict/csc` | 探测 npm→pnpm→bun→yarn 后**各用各的命令**（pnpm/bun `add -g`、yarn `global add`），仅包名统一 `@costrict/csc` | `CscInstaller.kt:63-86` |
| 10 | 各处行号/细节 | 已同步实际值：`SessionUi.kt:860`、`SessionController.kt:2506-2511 / 1741-1751 / 1708-1724 / 2227-2247`、`ModelPicker.kt:277,301,307,309`、bundle `:954 / :443-444 / :836 / :283 / :366`、E3 `:911-913 / :963-971`、github 链接实为 `/issues/new/choose`、恢复菜单文本为 "Restart"/"Reinstall"、`CsCloudStarter.kt`（非 CscCloudStarter）、`authApi.ts:12-14`、`extension.ts:385` 等 | 复核记录 |

补充事实（不改结论，供落地参考）：

- **A1 失败机制与测试盲区**：三个 Kilo 账号 RPC 的失败路径是"未映射路径原样透传 daemon → 404 → `CsCloudRequestException`（`CsCloudHttpClients.kt:26`）"；集成测试 `FakeCsCloudDaemon.kt:212` 对未知路径一律返回 `200 "{}"`，**mock 环境会掩盖这类失败**——排障与回归验证时勿以该 mock 为准。
- **`x-select-llm` 头来源**：cs-cloud/csc 仓库零命中，说明该头由云端上游下发、daemon 代理透传（costrict 前端读取处 `src/api/providers/costrict.ts:237`）；JetBrains 侧复用可行，但依赖 daemon 代理保留响应头。
- **官方文档包名口径**：`docs.costrict.ai/cli/guide/installation` 的 npm 包名写作 `@costrict/cs`（cs TUI，一键安装 `costrict.ai/install.bat|sh`），与插件安装的 `@costrict/csc`（csc 仓库 `package.json:2`，bin `csc`）不一致；P1-6 落地前需与官方确认。
- **`https://costrict.ai/operation` 页面存在**（可达，内容较薄、疑似引导下载页），A3 落地前与运营确认用途。
- **daemon 侧会话/鉴权细节**：conversations 即 OpenCode session（经 csc driver ProxyRoutes 重写自 `/session*`，`internal/agent/csc/driver.go:113-128`），全部路由挂 `/api/v1` 前缀；`csc auth login` 写 `~/.costrict/share/auth.json` 后 daemon **每请求重读**（无缓存无 watch）即生效，但 cs-cloud 自带 `cs-cloud login` 会在 daemon 运行中主动重启 daemon（`internal/cli/login.go:65-68`）。
- **参考实现杂项**：`CostrictCodeMode` 实为 vibe/strict/plan/**raw** 四值（`src/shared/modes.ts:18`）；`extension.ts:25` 注释禁用了 Roo CloudService，但 `src/activate/handleUri.ts:3` 仍残留活跃 import（Clerk 回调分支），对齐清理时注意；401 归并 code 还含 `quota-manager.voucher_expired`。
