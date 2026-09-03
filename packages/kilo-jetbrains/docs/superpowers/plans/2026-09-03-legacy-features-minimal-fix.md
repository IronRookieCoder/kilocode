# 专项一最简修复实施计划（Legacy Features Minimal Fix）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec 删除/替换 24 项 Kilo 遗留功能（A–E 五组最简变体），使插件在 Costrict cs-cloud 链路上不再有必坏入口、空转 UI 与品牌残留。

**Architecture:** 纯删除 + 少量常量收口（`CostrictLinks`/`CostrictBrand`）与一个状态字段（`KiloAppStateDto.providerId`）。不新增功能面。跨层删除（shared DTO / backend RPC / frontend UI）按编译原子性合并为单任务提交。

**Tech Stack:** Kotlin (JetBrains 插件、kotlinx.coroutines/serialization)、Gradle（模块 `:shared` `:frontend` `:backend` `:cs-cloud`）、JUnit5。

**Spec:** `packages/kilo-jetbrains/docs/superpowers/specs/2026-09-03-legacy-features-minimal-fix-design.md`

## Global Constraints

- 所有 Gradle 命令在 `packages/kilo-jetbrains/` 目录执行；`JAVA_HOME` 必须指向 JDK 21（未设置时先 `ls "C:\Program Files\Java"` 找 21 路径再 `export JAVA_HOME=...`）。
- **工作区已有未提交的"模型计费标签"改动**（`ModelPicker.kt`/`ModelItems.kt`/`ProviderDto.kt` 等，creditConsumption/creditDiscount，+139/−99）。执行本计划前先将其单独提交（`git add -A && git commit -m "feat(jetbrains): 模型计费倍率标签"`），此后每个任务一个干净提交。Task 12 中严禁删除 credit 相关代码与测试。
- 只跑相关模块测试，不做全量测试；集成测试不以 `FakeCsCloudDaemon`/`MockCliServer` 的 200-兜底作为删除项的通过依据（mock 盲区，见 spec）。
- 提交信息用中文、格式 `类型(jetbrains): 描述`，结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。
- 每个任务结束必须 `./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin` 通过后再提交。
- 用户可见文案品牌拼写统一 **CoStrict**（驼峰大写 S）；代码标识、URL 域名（`docs.costrict.ai` 等小写）、log 不动。
- 内部标识不清理（F 组豁免）：`KiloBundle` 资源名、`action.Kilo.*` 动作 ID、模块名 `kilo.jetbrains.*`、VFS 协议 `kilo`、`KiloPlugin.ID`。

## 通用验证命令

- 前端测试：`./gradlew :frontend:test --tests "ai.kilocode.client.<包>.*"`
- 后端测试：`./gradlew :backend:test --tests "ai.kilocode.backend.<包>.*"`
- 编译门禁：`./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin`

---

### Task 1: 品牌常量 CostrictLinks / CostrictBrand

**Files:**
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/plugin/CostrictBrand.kt`
- Test: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/plugin/CostrictBrandTest.kt`

**Interfaces:**
- Produces: `object CostrictBrand { val NOTIFICATION_GROUP: String; val DISPLAY_NAME: String }`；`object CostrictLinks { val DOCS_URL; val ISSUE_URL; val GITHUB_ISSUES_URL; val DOWNLOAD_URL }`（全部 `const val String`）。Task 5/6/16 消费。

- [ ] **Step 1: 写失败测试**

```kotlin
package ai.kilocode.client.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CostrictBrandTest {
    @Test
    fun `notification group and display name are CoStrict`() {
        assertEquals("Costrict", CostrictBrand.NOTIFICATION_GROUP)
        assertEquals("CoStrict", CostrictBrand.DISPLAY_NAME)
    }

    @Test
    fun `all links are https`() {
        listOf(
            CostrictLinks.DOCS_URL,
            CostrictLinks.ISSUE_URL,
            CostrictLinks.GITHUB_ISSUES_URL,
            CostrictLinks.DOWNLOAD_URL,
        ).forEach { assertTrue(it.startsWith("https://"), it) }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.plugin.CostrictBrandTest"`
Expected: FAIL（unresolved reference: CostrictBrand）

- [ ] **Step 3: 实现**

```kotlin
package ai.kilocode.client.plugin

/**
 * CoStrict brand constants shared by notifications and any user-visible title
 * that bypasses the resource bundle. Keep in sync with the notificationGroup
 * id declared in kilo.jetbrains.frontend.xml (see Task 16).
 */
object CostrictBrand {
    const val DISPLAY_NAME = "CoStrict"
    const val NOTIFICATION_GROUP = "Costrict"
}

/**
 * Official Costrict destinations. Values follow the reference implementation
 * (F:\ai-coding\costrict: docLinks.ts / authConfig.ts / extension.ts).
 */
object CostrictLinks {
    const val DOCS_URL = "https://docs.costrict.ai"
    const val ISSUE_URL = "https://zgsm.sangfor.com/issue/"
    const val GITHUB_ISSUES_URL = "https://github.com/zgsm-ai/costrict/issues"
    const val DOWNLOAD_URL = "https://costrict.ai/download"
}
```

- [ ] **Step 4: 跑测试确认通过** — 同 Step 2 命令，Expected: PASS
- [ ] **Step 5: Commit** — `git add` 两个文件，`feat(jetbrains): 新增 CostrictBrand/CostrictLinks 品牌常量`

---

### Task 2: A1/A2/A3(前端)/B5/C3 — 前端 Kilo 账号体系删除

删除设置页 User Profile、设备 OAuth UI、组织切换 overlay、ShowProfileAction。后端管道留到 Task 3（本任务后 RPC 接口仍存在但前端无调用方）。

**Files:**
- Delete: `frontend/src/main/kotlin/ai/kilocode/client/settings/profile/`（BalanceFormat.kt、LoggedInProfileUi.kt、LoggedOutProfileUi.kt、LoginState.kt、ProfileUi.kt、UserProfileConfigurable.kt 共 6 个文件）
- Delete: `frontend/src/main/kotlin/ai/kilocode/client/settings/auth/DeviceOAuthPanel.kt`、`frontend/src/main/kotlin/ai/kilocode/client/settings/auth/QrCode.kt`
- Delete: `frontend/src/main/kotlin/ai/kilocode/client/actions/ShowProfileAction.kt`
- Delete: `frontend/src/main/kotlin/ai/kilocode/client/session/ui/account/`（AccountChoice.kt、AccountPickerRenderer.kt、SessionAccountOverlay.kt）
- Delete tests: `frontend/src/test/kotlin/ai/kilocode/client/settings/QrCodeTest.kt`、`frontend/src/test/kotlin/ai/kilocode/client/settings/UserProfileConfigurableTest.kt`、`frontend/src/test/kotlin/ai/kilocode/client/session/ui/account/SessionAccountOverlayTest.kt`
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:45-51`（删 UserProfileConfigurable 注册块）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/app/KiloAppService.kt:472-530 附近`（删 refreshProfileAsync/startLogin/completeLogin/logout/setOrganization/setProfile 与 profile 状态字段）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/app/KiloProviderService.kt:81`（删 `service<KiloAppService>().refreshProfileAsync()` 行）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt`（删 :110 openProfileAction、:116 OrganizationTarget、:190 target、:1039-1041 resumeAfterLogin 的 profile 分支、:1044 refreshAccountOverlay() 调用、:2227-2247 selectOrganization、:2249-2253 openProfile、:2343-2389 accountSnapshot/showAccountOverlay/hideAccountOverlay/refreshAccountOverlay/setAccountOverlayState、lastProfile/acctAllowed/acctState 字段、:2574 附近 replay 中 `listener.onEvent(acctState)`、AccountOverlay 相关事件消费）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/SessionUi.kt:173`（删 `openProfileAction = ::openProfileSettings`；LoginRequiredView 的 openProfile 构造参数保留到 Task 13）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/settings/models/ModelsSettingsUi.kt:126-130`（删 `modelsLoginBannerVisible(...)` 调用，改 `syncModelBanner(state)`；连带 `ModelsSettingsState.kt:82` 的 `modelsLoginBannerVisible` 函数及其测试）
- Modify: `frontend/src/test/kotlin/ai/kilocode/client/plugin/DisplayNameI18nTest.kt`（删 `"settings.profile.displayName"` 两处引用）
- Modify: 三语 bundle（删 `profile.*`、`settings.profile.displayName`、ShowProfile 的 bundle `:443-444` 两条 key——先 `rg -n "^profile\.|^settings\.profile\.|^action\.Kilo\.ShowProfile" frontend/src/main/resources/messages/KiloBundle.properties` 取精确 key 清单，同 key 从 zh_CN/zh_TW 一并删除；`rg -n "KiloBundle.message\(\"profile\." frontend/src/main` 应为 0 后再删）

**Interfaces:**
- Consumes: 无
- Produces: 无（纯删除）；Task 3 依赖"前端已无 `.profile`/`startLogin` 等引用"

- [ ] **Step 1: 删除文件**（上述 Delete 清单，`git rm`）
- [ ] **Step 2: XML/调用点清理** — 按上述 Modify 清单逐处删除；用 `rg -n "ProfileUi|DeviceOAuthPanel|QrCode|ShowProfileAction|UserProfileConfigurable|SessionAccountOverlay|AccountChoice|AccountPickerRenderer|OrganizationTarget|openProfileAction|refreshAccountOverlay|AccountOverlay" frontend/src/main frontend/src/test` 驱动清零（结果仅允许剩 Task 13 将处理的 `LoginRequiredView` openProfile 参数与 `LoginKind.Profile`）
- [ ] **Step 3: bundle 清理** — 按 key 清单删三语包条目
- [ ] **Step 4: 编译 + 相关测试** — `./gradlew :frontend:compileKotlin` 然后 `:frontend:test --tests "ai.kilocode.client.plugin.*" --tests "ai.kilocode.client.session.controller.*" --tests "ai.kilocode.client.settings.models.*"`
- [ ] **Step 5: Commit** — `refactor(jetbrains): 删除前端 Kilo 账号体系（profile 设置页/设备 OAuth/组织 overlay/ShowProfileAction）`

---

### Task 3: A1(后端+shared) — Kilo 账号管道删除

**Files:**
- Modify: `shared/src/main/kotlin/ai/kilocode/rpc/KiloAppRpcApi.kt`（删 `refreshProfile`/`startLogin`/`completeLogin`/`logout`/`setOrganization` 5 方法 + `DeviceAuthDto`/`ProfileDto` import）
- Modify: `shared/src/main/kotlin/ai/kilocode/rpc/dto/KiloAppStateDto.kt`（删 :167-202 的 ProfileOrganizationDto/ProfileBalanceDto/ProfileKiloPassDto/ProfileDto/DeviceAuthDto、:18-22 ProfileStatusDto、LoadProgressDto.profile 字段、KiloAppStateDto.profile 字段）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloAppRpcApiImpl.kt`（删 :130-142 五个 override、:144-146 保留 captureTelemetry（Task 14 处理）、:152-187 appStateDto 中 profile/progress.profile 相关、:189-210 profileDto 映射器）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`（删 :699-726 fetchProfile、:1010-1021 附近 refreshProfile/profile 字段、:1027-1048 startLogin/completeLogin、:1054-1063 logout、:1070-1088 setOrganization、状态组装中的 profile 传递与 fetch 调用点、`setAppReady(current.data.copy(profile = ...))` 改为不带 profile）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/app/KiloAppState.kt`（内部 sealed class 各状态里的 profile 字段——`rg -n "profile" backend/src/main/kotlin/ai/kilocode/backend/app/KiloAppState.kt` 定位后删除）
- Modify: `cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudRoute.kt:46`（删 `"/kilo/profile" -> return@Interceptor local(chain.request(), 401, "{}")` 行；`when` 其余分支不动）
- Modify: `backend/src/test/kotlin/ai/kilocode/backend/testing/MockCliServer.kt` 及引用 profile 端点的后端测试（`rg -n "kilo/profile|providerOauthAuthorize|authRemove|KiloProfile|startLogin|setOrganization" backend/src/test` 逐个删除/调整用例）

**Interfaces:**
- Consumes: Task 2 已清空前端引用
- Produces: 无

- [ ] **Step 1: shared 删 RPC 方法与 DTO** → 编译 `:shared:compileKotlin`（此时 backend 报错是预期，继续）
- [ ] **Step 2: backend 删实现与状态管道**（按清单；用编译错误驱动：`./gradlew :backend:compileKotlin` 直到 0 error）
- [ ] **Step 3: 删 CsCloudRoute 的 /kilo/profile stub**（注意保留同 `when` 中 `"/kilo/notifications", "/config/warnings", "/skill" -> "[]"`）
- [ ] **Step 4: 测试** — `./gradlew :backend:test`（被删功能的用例随删；`MockCliServer` 中 `/kilo/profile` mock 端点一并删除）
- [ ] **Step 5: Commit** — `refactor(jetbrains): 删除后端 Kilo 账号管道（profile/OAuth/org RPC 与 /kilo/profile stub）`

---

### Task 4: A4 — 云历史全链删除

**Files:**
- Modify: `frontend/.../session/history/HistoryPanel.kt`（删 cloudSearch/cloudList/cloudRows/cloudPanel/cloudInfo/repoOnly/more/tabs（改单面板直出 localPanel+localSearch）、:110 loadMoreCloud 绑定、:112 bind(cloud)、:114-116 onRepoOnlyChanged、panel() 的 repoOnly 分支、cloudList()、sync 中 cloud 分支——`rg -n "cloud" frontend/.../session/history/HistoryPanel.kt` 驱动）
- Modify: `frontend/.../session/history/HistoryController.kt`（删 cloud/CLOUD_LIMIT/gitUrl/repoOnly/loadCloud/reloadCloud/loadMoreCloud/applyRepoOnly/resolveUrlIfNeeded/open(CloudHistoryItem)/gitUrlProvider/import CloudSessionDto）
- Delete: `frontend/.../session/history/GitRemoteUrl.kt`（删除前 `rg -n "resolveGitRemoteUrl" frontend/src/main` 确认仅 HistoryController 使用）
- Modify: `frontend/.../session/history/HistoryModel.kt`（删 CloudHistoryModel）、`HistoryItem.kt`（删 CloudHistoryItem）、`HistoryRows.kt`（删 CloudHistoryRow）
- Modify: `frontend/.../session/SessionHost.kt:201`（删 `is SessionRef.Cloud -> root` 分支）、`frontend/.../session/controller/SessionController.kt:997 与 :1187`（删 Cloud 分支与 importCloudSession 调用）、`SessionRef` 定义处删 Cloud 变体（`rg -n "SessionRef" frontend/src/main/kotlin/ai/kilocode/client/session/` 定位）
- Modify: `frontend/.../app/KiloSessionService.kt:163-167`（删 cloudSessions/importCloudSession）
- Modify: `shared/.../rpc/KiloSessionRpcApi.kt:61-64`（删两方法 + import）、`shared/.../rpc/dto/`（删 CloudSessionDto/CloudSessionListDto——`rg -n "CloudSession" shared/src backend/src` 定位文件）
- Modify: `backend/.../app/KiloBackendSessionManager.kt:224-269`（删 cloudSessions/importCloudSession）、`backend/.../rpc/KiloSessionRpcApiImpl.kt`（对应 override）、`backend/.../cli/KiloCliDataParser.kt:437-455`（删 parseCloudSessions）及其 import
- Modify: `backend/src/test/.../MockCliServer.kt:117-123,421-428`（删 cloud mock）、`KiloCliDataParserTest`（删 parseCloudSessions 用例）、`frontend/src/test/.../HistoryControllerTest.kt`（删 cloud 用例）、`HistoryLoadingTest`/`HistorySessionActionsTest` 中 cloud/import 用例
- bundle：删 `history.tab.cloud`、`history.cloud.load.more`、`history.cloud.repo.only`、`history.error.cloud`（三语包；先 rg 确认 key 名）

- [ ] **Step 1: 前端删除并编译**（`:frontend:compileKotlin` 0 error；`rg -n "Cloud" frontend/src/main/kotlin/ai/kilocode/client/session/history/` 应仅剩无关命中）
- [ ] **Step 2: shared+backend 删除并编译**（`:shared:compileKotlin :backend:compileKotlin`）
- [ ] **Step 3: 测试** — `:frontend:test --tests "ai.kilocode.client.session.history.*" --tests "ai.kilocode.client.session.controller.HistoryLoadingTest"`；`:backend:test --tests "ai.kilocode.backend.cli.*"`
- [ ] **Step 4: Commit** — `refactor(jetbrains): 删除 Kilo 云历史（History Cloud 标签与 /kilo/cloud-sessions 链路）`

---

### Task 5: A5 — providerId 字段 + 恢复菜单条件隐藏 + reinstall 兜底

**Files:**
- Modify: `shared/.../rpc/dto/KiloAppStateDto.kt`（`KiloAppStateDto` 增加 `val providerId: String? = null`）
- Modify: `backend/.../app/KiloBackendAppService.kt`（新增 `val providerId: String get() = connectionProvider.id`）
- Modify: `backend/.../rpc/KiloAppRpcApiImpl.kt`（`dto(state)` 改为 `appStateDto(state).copy(providerId = app.providerId)`）
- Modify: `frontend/.../session/ui/ConnectionPanel.kt:271-280`（recoveryActionIds 条件化）
- Modify: `frontend/.../app/KiloAppService.kt:176-180`（reinstallAsync 补 catch）
- Modify: `frontend/src/main/resources/messages/KiloBundle.properties` + zh_CN + zh_TW（新增 `action.Kilo.Reinstall.failed=Reinstall failed: {0}` / 「重装失败：{0}」/「重裝失敗：{0}」）
- Test: `frontend/src/test/kotlin/ai/kilocode/client/session/ui/ConnectionPanelTest.kt`（改/增 recoveryActionIds 用例）

**Interfaces:**
- Consumes: `CostrictBrand`（不需要）；`KiloAppStateDto.providerId`
- Produces: `KiloBackendAppService.providerId: String`；cs-cloud 判定约定 `providerId == "cs-cloud"`（`CsCloudConnectionProvider` 的 id，见 `cs-cloud/.../CsCloudConnectionProvider.kt:13` 注册）

- [ ] **Step 1: 写失败测试** — `ConnectionPanelTest.kt` 增加用例（现有 `test recovery menu offers cs-cloud actions only for cs-cloud failures`（:99-144）在默认 `providerId=null` 下断言不变，保持原样）：

```kotlin
fun `test recovery menu hides reinstall on cs-cloud provider`() {
    controller.model.app = KiloAppStateDto(
        status = KiloAppStatusDto.ERROR,
        providerId = "cs-cloud",
    )
    edt {
        panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
            "Connection failed",
            "cs-cloud server URL was not found",
            code = ConnectionErrorCode.CSC_NOT_INSTALLED,
        ))
    }

    assertEquals(listOf("Kilo.Restart", "Kilo.StartCsCloud", "Kilo.InstallCsc"), panel.recoveryActionIds())
}
```

（import `ai.kilocode.rpc.dto.KiloAppStateDto` 与 `ai.kilocode.rpc.dto.KiloAppStatusDto`；`SessionControllerTestBase` 暴露的 `controller.model.app` 为 var，见 `SessionController.kt:1037` 同用法。）

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 实现** — shared 加字段；backend `providerId` 与 `dto()` copy；ConnectionPanel：

```kotlin
internal fun recoveryActionIds(): List<String> = buildList {
    add("Kilo.Restart")
    if (controller.model.app.providerId != "cs-cloud") add("Kilo.Reinstall")
    if (code == ConnectionErrorCode.CSC_NOT_INSTALLED || code == ConnectionErrorCode.DAEMON_DOWN || code == ConnectionErrorCode.UNAUTHORIZED) {
        add("Kilo.StartCsCloud")
    }
    if (code == ConnectionErrorCode.CSC_NOT_INSTALLED) {
        add("Kilo.InstallCsc")
    }
}
```

reinstallAsync：

```kotlin
fun reinstallAsync() {
    LOG.info("reinstallAsync: launching reinstall")
    cs.launch {
        try {
            reinstall()
        } catch (e: Exception) {
            LOG.warn("reinstallAsync failed", e)
            KiloNotifications.error(KiloBundle.message("action.Kilo.Reinstall.failed", e.message ?: ""))
        }
    }
}
```

- [ ] **Step 4: 编译+测试通过** — `:frontend:test --tests "ai.kilocode.client.session.ui.ConnectionPanelTest"`、`:backend:compileKotlin`
- [ ] **Step 5: Commit** — `fix(jetbrains): cs-cloud 模式隐藏 Reinstall 并为重装失败补通知`

---

### Task 6: A6+A7 — 反馈链接与 /help 指向 Costrict

**Files:**
- Modify: `frontend/.../session/ui/empty/EmptySessionFeedback.kt`（companion 常量与按钮重排）
- Modify: `frontend/.../session/SessionUi.kt:860`（HELP → `CostrictLinks.DOCS_URL`）
- Modify: 三语 bundle：删 `feedback.dialog.discord`；新增 `feedback.dialog.issue`、`feedback.dialog.bug`（值见下）；`feedback.dialog.github`/`feedback.dialog.support` 保留改文案
- Delete: `frontend/src/main/resources/icons/discord.svg`、`discord_dark.svg`（删除前 `rg -n "discord" frontend/src/main` 确认仅此引用）

**Interfaces:**
- Consumes: Task 1 `CostrictLinks`
- Produces: 无

- [ ] **Step 1: 改 EmptySessionFeedback** — 常量替换：

```kotlin
private const val GITHUB_ISSUES_URL = CostrictLinks.GITHUB_ISSUES_URL
private const val ISSUE_URL = CostrictLinks.ISSUE_URL
private const val SUPPORT_URL = CostrictLinks.DOCS_URL
```

按钮组改为三枚（去掉 Discord 按钮）：`feedback.dialog.issue → ISSUE_URL`、`feedback.dialog.github（改 key 为 feedback.dialog.bug）→ GITHUB_ISSUES_URL`、`feedback.dialog.support → SUPPORT_URL`；logo 的 `addMouseListener { open(KILO_URL) }` 整段删除（不再外链）；`urls()` 返回 `listOf(ISSUE_URL, GITHUB_ISSUES_URL, SUPPORT_URL)`；删 `KILO_URL`、`DISCORD_URL`、`DISCORD_ICON`。
bundle 值（en）：`feedback.dialog.issue=Report an issue`、`feedback.dialog.bug=Bug & feature requests (GitHub)`、`feedback.dialog.support=Support & docs`；zh_CN：`反馈问题`、`Bug 与功能建议（GitHub）`、`支持与文档`；zh_TW：`回報問題`、`Bug 與功能建議（GitHub）`、`支援與文件`。
注：`CostrictLinks.DOWNLOAD_URL`（社区/下载页）暂不放按钮——参考实现的空会话页无任何外链，弹窗收敛为三入口；常量保留供后续使用。
- [ ] **Step 2: SessionUi:860** — `SlashAction.HELP to { BrowserUtil.browse(CostrictLinks.DOCS_URL) }`（加 import）
- [ ] **Step 3: bundle + 图标删除**（`git rm` discord*.svg）
- [ ] **Step 4: 编译 + `rg -n "kilo\.ai|kilocode\.ai" frontend/src/main` 应为 0**（若剩 ProfileUi 相关已随 Task 2 删除；如剩其他 kilo.ai 引用逐个替换为 CostrictLinks 对应项）
- [ ] **Step 5: Commit** — `fix(jetbrains): 反馈与支持弹窗及 /help 链接指向 Costrict 官方入口`

---

### Task 7: C1+C2 — autocomplete 死存储与虚假承诺删除

**Files:**
- Delete: `frontend/src/main/kotlin/ai/kilocode/client/autocomplete/KiloAutocompleteSettingsService.kt`
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:13`（删 applicationService 行）
- Modify: `frontend/.../migration/KiloMigrationService.kt`（删 :6/:12 import、:61 autocomplete 构造参数、:121 applyAutocomplete 调用、:343-351 函数、:366 与 :397 与 :453 的 autocomplete 片段）
- Modify: `frontend/.../migration/MigrationUiState.kt:27`（删 autocomplete 字段）
- Modify: `frontend/.../migration/MigrationSelectionBuilder.kt:24,:49,:82`（删 autocomplete）
- Modify: `frontend/.../migration/ui/MigrationWizardPanel.kt:170`（删 defaults.settings.autocomplete）
- Modify: `src/main/resources/META-INF/plugin.xml:9-13`（description 删除 "Features inline autocomplete, " 短语，保留其余）
- Modify: 三语 bundle `migration.row.settings` 值去掉 "& Autocomplete"（en 现 "Auto-Approval, Language & Autocomplete" → "Auto-Approval & Language"；zh_CN/zh_TW 对应行同步）
- Test: `frontend/src/test/.../KiloMigrationServiceTest.kt`、`FakeMigrationRpcApi.kt`、`FakeMigrationUiController.kt`（删 autocomplete 注入/断言用例）

**Interfaces:** 无新增。

- [ ] **Step 1: 删服务与注册**（文件 + xml 行）
- [ ] **Step 2: 迁移链清理**（按行清单；`rg -n -i "autocomplete" frontend/src backend/src/main` 剩余仅允许 `LegacyAutocompleteSettingsDto`（shared/backend detection 保留，描述源数据））
- [ ] **Step 3: plugin.xml 与 bundle 文案**
- [ ] **Step 4: 测试** — `:frontend:test --tests "ai.kilocode.client.migration.*"` + 编译门禁
- [ ] **Step 5: Commit** — `refactor(jetbrains): 删除 autocomplete 死存储与迁移/插件描述虚假承诺`

---

### Task 8: C4 — 迁移不再写 kilocodeToken 为 kilo OAuth 凭证

**Files:**
- Modify: `backend/.../migration/LegacyProviderMapping.kt:62`（删除 `"kilocode" to ProviderMapping(...)` 整行——kilo 网关已不存在，迁移检测将其归入 unsupported provider）
- Modify: `backend/.../migration/LegacyMigrationConverters.kt:233-242`（删 `if (mapping.id == "kilo") { ... }` OAuth 分支，保留 else 通用路径；`rg -n '"kilo"' backend/src/main/kotlin/ai/kilocode/backend/migration` 清零）
- Test: backend 迁移测试中 kilocode/kilo 断言用例（`rg -n "kilocode|Kilo \(Gateway\)" backend/src/test` 定位并删除/改为 unsupported 断言）

- [ ] **Step 1: 删映射与分支** → **Step 2: 测试**（`:backend:test --tests "ai.kilocode.backend.migration.*"`）→ **Step 3: Commit** — `fix(jetbrains): 迁移不再把 kilocodeToken 写成 kilo provider OAuth 凭证`

---

### Task 9: C5 — 迁移内置模式提示词 CoStrict 化

**Files:**
- Modify: `backend/.../migration/LegacyMigrationConverters.kt:578-621`（6 个 roleDefinition 前缀替换）

- [ ] **Step 1: 替换** — 在该文件执行大小写敏感替换：`You are Kilo Code,` → `You are CoStrict,`（6 处；其余句子不动）。命令：`sed -i 's/You are Kilo Code,/You are CoStrict,/g' backend/src/main/kotlin/ai/kilocode/backend/migration/LegacyMigrationConverters.kt`（Git Bash）
- [ ] **Step 2: 核查** — `rg -n "Kilo Code" backend/src/main` 应为 0
- [ ] **Step 3: 测试** — `:backend:test --tests "ai.kilocode.backend.migration.*"`（若用例断言旧文案，同步更新断言）
- [ ] **Step 4: Commit** — `fix(jetbrains): 迁移内置模式系统提示词改为 CoStrict 自称`

---

### Task 10: B1 — Restart 更名 + CoreInfo 删除

**Files:**
- Modify: 三语 bundle：`action.Kilo.Restart.cli.text=Restart Core` → `Restart cs-cloud`（zh_CN「重启 cs-cloud」zh_TW「重新啟動 cs-cloud」）；`action.Kilo.Restart.description=Kill and restart the Core process` → `Restart the cs-cloud daemon connection`（zh_CN「重新连接并重启 cs-cloud 守护进程」zh_TW「重新連線並重新啟動 cs-cloud 常駐程序」）；`action.Kilo.CliGroup.text=Core` → `cs-cloud`、`action.Kilo.CliGroup.description=Costrict Core actions` → `cs-cloud actions`（zh「cs-cloud 相关操作」/「cs-cloud 相關操作」）
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:199`（`<group id="Kilo.CliGroup" text="Core" popup="true">` 的字面 text 属性同步改 `text="cs-cloud"`）
- Delete: `frontend/.../actions/CoreInfoAction.kt`
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:183-184`（删 Kilo.CoreInfo action 注册）、`:204-205`（删 `<separator/>` 与 `<reference ref="Kilo.CoreInfo"/>`）
- Test: `ConnectionPanelTest.kt:160-174`（`test retry popup group uses core recovery actions`：xml 断言 `text="Core"` 改 `text="cs-cloud"`；删 `:173` 的 Kilo.CoreInfo 断言）
- Modify: bundle 删 `action.Kilo.CoreInfo.text/.bundled/.loading/.description`（三语，`rg -n "^action\.Kilo\.CoreInfo" messages/KiloBundle.properties` 取清单）
- Modify: `frontend/.../app/KiloAppService.kt`（删仅被 CoreInfoAction 使用的成员：`core`/`fetchCoreInfoAsync`/`bundled`/`fetchBundledAsync`——删前 `rg -n "fetchCoreInfoAsync|fetchBundledAsync|\.core\b|\.bundled\b" frontend/src/main` 确认无其他调用方；`watch()` 内 :523 `fetchCoreInfoAsync()` 若引用则一并删除；RPC `cliVersion/cliPlatform/cliBundled` 保留）
- Test: 无直接用例；编译门禁 + `:frontend:test --tests "ai.kilocode.client.actions.*"`

- [ ] **Step 1: bundle 更名与删 key** → **Step 2: 删 action + xml 引用 + 孤儿 service 成员** → **Step 3: 编译门禁** → **Step 4: Commit** — `refactor(jetbrains): Restart 更名 cs-cloud 并删除 Core vX 信息项`

---

### Task 11: B2 — Downloading 进度链删除

**Files:**
- Modify: `frontend/.../session/controller/SessionControllerEvent.kt:65`（删 `ShowDownloading` data class）
- Modify: `frontend/.../session/controller/SessionController.kt:2514-2520`（删 DOWNLOADING 分支——DOWNLOADING 状态自然落入末尾 `ShowConnecting` 兜底，行为正确）
- Modify: `frontend/.../session/ui/ConnectionPanel.kt`（删 :132 事件分支与 :160-175 showDownloading()）
- Modify: 三语 bundle 删 `session.connection.downloading`、`session.connection.downloading.version`（:11-12）
- Test: `frontend/src/test/.../ConnectionPanelTest.kt:40-51`（整删 `test downloading hides retry and details` 用例与 :42/:46 的 ShowDownloading/key 引用）
- 注：`KiloAppStatusDto.DOWNLOADING`、`KiloAppStateDto.downloadPercent/Version/Platform`（shared）、`KiloAppState.Downloading`（backend）保留——旧链路状态枚举仍会产生，仅前端不再有专属展示。

- [ ] **Step 1: 删事件与分支** → **Step 2: 删面板方法与 bundle key** → **Step 3: 测试**（`:frontend:test --tests "ai.kilocode.client.session.ui.ConnectionPanelTest"`）→ **Step 4: Commit** — `refactor(jetbrains): 删除 CLI 下载进度 UI（ConnectionState.Downloading 链）`

---

### Task 12: B3+B6 — 模型选择去 Kilo 特权 + Small Model 删除

**Files:**
- Modify: `frontend/.../settings/providers/ProviderCatalog.kt:18-30`（删 `KILO_PROVIDER_ID` 常量与 popularIds 中 `KILO_PROVIDER_ID` 条目；`rg -n "KILO_PROVIDER_ID" frontend/src` 清零——ProviderListRows:95 同步改）
- Modify: `frontend/.../session/ui/model/ModelItems.kt`（删 :5 `KILO_PROVIDER` 常量、:15 过滤改 `it.id in cfg.connected`；删 `includeSmall` 参数与 :44 过滤行；**保留 creditConsumption/creditDiscount 两行**）
- Modify: `frontend/.../session/ui/model/ModelPicker.kt`（删 :73 `includeSmall` 属性、:140/:193 传参、:278 `small` 集合、:302 buttonLabel 的 kilo 分支、:308 `small()`、:310 `providerSort`；**保留 :323-334 creditLabel 及在途 credit 测试**）
- Modify: `frontend/.../session/ui/model/ModelPickerRows.kt`（删 :12 includeSmall 参数、:15 filterNot、:26 providerSort 排序行——排序退化为按组名自然序）
- Modify: `frontend/.../settings/providers/ProviderListRows.kt:95`（删 `if (provider.id == KILO_PROVIDER_ID && ...) return emptyList()` 行——kilo provider 不再有 Disconnect 豁免）
- Modify: `backend/.../provider/KiloBackendProviderSettingsManager.kt:111-113`（`rg -n "cannot be disconnected" backend/src/main` 定位；该串经 RPC 返回、后端无 bundle 环境——保持硬编码英文，仅去 Kilo 字样，改为 `"This provider cannot be disconnected."`）
- Modify: `frontend/.../settings/models/ModelsSettingsUi.kt`（删 :48 small getter、:115 smallItems、:143/:145 small 行、ModelsSettingsContent 的 `small` picker/:252-254/:258 列表项/:270-274 smallModel SettingsRow、:171 `items(includeSmall)` 改 `modelItems(providers)`）
- Modify: `frontend/.../settings/models/ModelsSettingsState.kt`（删 ModelsDraft.small:12、:20、:29 `small_model` patch 行、:86 savedMatches small 行）
- Modify: 三语 bundle 删 `settings.models.smallModel.title/.description`（:807-808）
- Test: `ModelPickerTest.kt`/`ModelItemsTest.kt`（删 kilo 排序/small 用例；**保留/保留 credit 用例**）；`NewWorktreeDialog.kt:179` 调用 `modelItems(ws.providers)` 无参兼容（参数已删，无需改）

- [ ] **Step 1: ModelItems/ModelPicker/ModelPickerRows 删特权与 includeSmall 链** → **Step 2: ProviderCatalog/ProviderListRows/后端文案** → **Step 3: ModelsSettingsUi/State 删 Small Model** → **Step 4: bundle 删 key** → **Step 5: 测试**（`:frontend:test --tests "ai.kilocode.client.session.ui.model.*" --tests "ai.kilocode.client.settings.models.*" --tests "ai.kilocode.client.settings.providers.*"`）→ **Step 6: Commit** — `refactor(jetbrains): 删除 Kilo provider 特权逻辑与 Small Model 设置`

---

### Task 13: B4 — PaidModelAuth 与 LoginKind.Profile 删除

**Files:**
- Modify: `frontend/.../session/controller/PaidModelAuth.kt`（删 :8 常量与 :21-32 `isPaidModelAuthRequired`；保留 isCsCloudAuthRequired/isCsCloudAuthRequiredText）
- Modify: `frontend/.../session/controller/SessionController.kt:1711-1720`（删 isPaidModelAuthRequired 分支）、`:2332`（`val reason = ...` 简化为 `"cs_cloud_auth"` 常量或保留 CsCloud 判断）
- Modify: `frontend/.../session/model/SessionState.kt:4`（`enum class LoginKind { CsCloud }`）、:30（LoginRequired 默认 kind 改 `LoginKind.CsCloud`）
- Modify: `frontend/.../session/views/LoginRequiredView.kt`（删 :22 openProfile 构造参数、:32 ID_OPEN、:42 与 :55-61 Profile 分支——show() 只留 CsCloud 动作组、:81 openProfileButton() helper；`session.login.required.title/description/button` 三个 key 不再使用）
- Modify: `frontend/.../session/SessionUi.kt`（LoginRequiredView 构造调用点去掉 openProfile 实参；删 `openProfileSettings` 私有函数）
- Modify: 三语 bundle 删 `session.login.required.title/.description/.button`（:210-212；`session.login.required.dismiss` 保留）
- Test: `PaidModelAuthTest.kt`（删 isPaidModelAuthRequired 用例，保留 isCsCloudAuthRequired 用例）、`LoginRequiredViewTest.kt`（删 Profile 分支用例，全部走 CsCloud）

- [ ] **Step 1: 删检测函数与分支** → **Step 2: LoginKind/View 简化** → **Step 3: bundle 删 key** → **Step 4: 测试**（`:frontend:test --tests "ai.kilocode.client.session.controller.PaidModelAuthTest" --tests "ai.kilocode.client.session.views.LoginRequiredViewTest" --tests "ai.kilocode.client.session.controller.TurnLifecycleTest"`）→ **Step 5: Commit** — `refactor(jetbrains): 删除 Kilo 付费模型 401 检测，登录引导统一 cs-cloud`

---

### Task 14: B7 — /telemetry 透传删除

**Files:**
- Delete: `backend/src/main/kotlin/ai/kilocode/backend/telemetry/KiloBackendTelemetry.kt`
- Modify: `backend/.../app/KiloBackendAppService.kt`（删 :618-651 三个私有函数 captureLoad/setTelemetry/captureBackend 及调用点 :484、:564、:565-566、:590、:604）
- Modify: `backend/.../rpc/KiloAppRpcApiImpl.kt:144-146`（captureTelemetry 保留 RPC 签名，改为 no-op：`override suspend fun captureTelemetry(capture: TelemetryCaptureDto) { /* cs-cloud 链路无遥测消费方，前端埋点在此终止 */ }`）
- Test: `rg -n "KiloBackendTelemetry" backend/src` 清零；若存在 KiloBackendTelemetryTest 删除

- [ ] **Step 1: 删类与调用** → **Step 2: captureTelemetry no-op** → **Step 3: 测试**（`:backend:test`；前端 31 处 `Telemetry.send` 不动）→ **Step 4: Commit** — `refactor(jetbrains): 删除 /telemetry 透传，RPC 遥测改为 no-op`

---

### Task 15: D1 — 构建链裁剪（runtime=false + 删除 CLI 打包任务）

**Files:**
- Modify: `backend/build.gradle.kts:27`（`orElse(true)` → `orElse(false)`，即 `kilo.cli.runtime` 默认 false）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/cli/KiloProps.kt:23`（`?: true` → `?: false`）
- Modify: `backend/build.gradle.kts`（删 :125-133 writeCliChecksums 注册、:106-123 stageRepoCli/stageBundledCli 注册、:189-192 与 :197-199 的条件 dependsOn 行、:48-49 sourceSets 条件行、:54-59 两条守卫 error 改为仅保留 repoCli&&bundled 冲突守卫）
- Delete: `build-tasks/src/main/kotlin/.../WriteCliChecksumsTask.kt`、`StageRepoCliTask.kt`、`StageBundledCliTask.kt`（`rg -n "class WriteCliChecksumsTask|class StageRepoCliTask|class StageBundledCliTask" build-tasks` 定位路径；`buildRepoCli` Exec 任务 :84-88 一并删除）
- 保留（偏离 spec 说明）：`generateOpenApiSpec`/`normalizeOpenApiSpec`/`openApiGenerate`/`fixGeneratedApi` 链**不动**——`ai.kilocode.jetbrains.api` 客户端是编译期代码生成且未入库，摘除将导致全后端编译失败；spec D1 的目标（发行包不含 CLI 下载/兜底）已由 runtime=false + 删除 staging/checksums 完全达成。
- Test: `backend/src/test/.../KiloPropsTest.kt`（`rg -n "runtime" backend/src/test` 定位；默认值断言 true→false）

- [ ] **Step 1: 改默认值与测试**（先改 KiloPropsTest 断言为 false，跑 `:backend:test --tests "*KiloProps*"` 红→改实现→绿）
- [ ] **Step 2: build.gradle 裁剪** → `./gradlew :backend:compileKotlin :backend:processResources` 通过
- [ ] **Step 3: Commit** — `build(jetbrains): cli.runtime 默认 false 并删除 CLI 打包/校验任务`

---

### Task 16: E2 — 通知组品牌收口

**Files:**
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:16`（`<notificationGroup id="Kilo Code"` → `id="Costrict"`；key 仍指 `notification.group.kilo`，其 en 值已是 Costrict）
- Modify: `frontend/.../client/KiloNotifications.kt:12`（`private const val GROUP = "Kilo Code"` → 引用 `CostrictBrand.NOTIFICATION_GROUP`，删除私有常量）
- Modify: 4 处兜底硬编码（`AdvancedLogActions.kt:79-81`、`AgentEditDialog.kt:346-348`、`PromptAttachmentStrip.kt:84`、`PromptPanel.kt:927`）：`getNotificationGroup("Kilo Code")` → `getNotificationGroup(CostrictBrand.NOTIFICATION_GROUP)`，`Notification("Kilo Code", ...)` → `Notification(CostrictBrand.NOTIFICATION_GROUP, ...)`（`rg -n '"Kilo Code"' frontend/src/main` 清零）
- Modify: `kilo.jetbrains.frontend.xml:21`（`Kilo.CodeReview` → `Costrict.CodeReview`）+ `rg -n '"Kilo\.CodeReview"' frontend/src/main` 的 getNotificationGroup 引用同步改
- 注：组 ID 变更会使老用户该组通知设置回默认（spec 已接受）

- [ ] **Step 1: 改 xml 与常量引用** → **Step 2: 核查** `rg -n '"Kilo Code"' frontend/src/main` = 0 → **Step 3: 编译 + `:frontend:test --tests "ai.kilocode.client.settings.*"`** → **Step 4: Commit** — `refactor(jetbrains): 通知组 ID 与兜底标题收口到 CostrictBrand`

---

### Task 17: E1 — 语言包裁剪 + 三语包补译

**Files:**
- Delete: 15 个语言包（ar/bs/da/de/es/fr/ja/ko/nl/no/pl/pt_BR/ru/th/tr/uk）— `git rm packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle_{ar,bs,da,de,es,fr,ja,ko,nl,no,pl,pt_BR,ru,th,tr,uk}.properties`
- Modify: `KiloBundle_zh_CN.properties:268-270` 补 `session.login.required.csCloud.title=登录 CoStrict 以继续`、`...description=登录 CoStrict 账号后即可使用 cs-cloud，然后继续本会话。`、`...button=登录 CoStrict`
- Modify: `KiloBundle_zh_TW.properties:268-270` 补 `=登入 CoStrict 以繼續`、`=登入您的 CoStrict 帳號即可使用 cs-cloud，然後繼續本會話。`、`=登入 CoStrict`
- Modify: `frontend/src/test/.../plugin/DisplayNameI18nTest.kt`（若遍历语言包文件，收敛到 3 语清单）
- 核查：三包 key 集合一致 — `for f in KiloBundle KiloBundle_zh_CN KiloBundle_zh_TW; do rg -o '^[^#!][^=]+=' frontend/src/main/resources/messages/$f.properties | sort > /tmp/$f.keys; done; diff /tmp/KiloBundle.keys /tmp/KiloBundle_zh_CN.keys; diff /tmp/KiloBundle.keys /tmp/KiloBundle_zh_TW.keys`（差异应为 0；有差异则补齐缺 key）

- [ ] **Step 1: 删 15 包** → **Step 2: 补译** → **Step 3: key 一致性脚本** → **Step 4: 测试**（`:frontend:test --tests "ai.kilocode.client.plugin.*"`）→ **Step 5: Commit** — `refactor(jetbrains): 语言包裁剪到 en/zh_CN/zh_TW 并补齐 csCloud 登录卡片翻译`

---

### Task 18: E3 — 用户可见拼写统一 CoStrict

**Files:**
- Modify: `KiloBundle.properties`、`KiloBundle_zh_CN.properties`、`KiloBundle_zh_TW.properties`
- Modify: Kotlin 硬编码用户可见串（Task 16 已收口通知；本任务扫剩余）

**Interfaces:** Consumes `CostrictBrand.DISPLAY_NAME`（如需拼接处）。

- [ ] **Step 1: bundle 统一**（大小写敏感，不影响小写 URL/代码标识）：

```bash
cd packages/kilo-jetbrains/frontend/src/main/resources/messages
sed -i 's/Costrict/CoStrict/g' KiloBundle.properties KiloBundle_zh_CN.properties KiloBundle_zh_TW.properties
rg -c 'Costrict' *.properties   # 期望 0
rg -c 'CoStrict' *.properties   # 原 16/16/6 + Step1 新增
```

（注意 `@costrict/csc`、`docs.costrict.ai` 均为小写不受影响；若出现 `CoStrict` 误入 URL 的大小写（不应有），逐处还原。）
- [ ] **Step 2: Kotlin 用户可见串** — `rg -n '"[^"]*Costrict[^"]*"' packages/kilo-jetbrains/frontend/src/main/kotlin packages/kilo-jetbrains/backend/src/main/kotlin`：逐条判断——用户可见（通知标题/对话框文案）改 CoStrict 或改用 bundle；URL、包名、路径、log、RPC 字段名不动。
- [ ] **Step 3: worktree/csCloud keys 顺带核查** — `rg -n 'worktree\.|csCloud\.' messages/KiloBundle*.properties | rg -i 'kilo'` 应为 0（品牌残留）；`rg -n 'Kilo' messages/KiloBundle.properties` 输出逐条人工核对，允许剩余仅限非用户可见语境（理论应为 0——E1 已裁三语且前序任务清完；不为 0 则逐条替换）。
- [ ] **Step 4: 测试** — `:frontend:test --tests "ai.kilocode.client.plugin.*"` + 编译门禁
- [ ] **Step 5: Commit** — `chore(jetbrains): 用户可见文案拼写统一为官方 CoStrict`

---

### Task 19: E4 — 欢迎页 logo 替换

**Files:**
- Create: `frontend/src/main/resources/icons/costrict/logo.png`（拷贝自参考实现 `F:\ai-coding\costrict\src\assets\costrict\logo.png`）
- Modify: `frontend/.../session/ui/empty/BrandLogo.kt:43`（`LOGO_PATH = "/icons/costrict/logo.png"`）
- Delete: `frontend/src/main/resources/icons/kilo-content.png`、`kilo-profile.svg`、`kilo-profile_dark.svg`（删前 `rg -n "kilo-content|kilo-profile" frontend/src/main` = 0）
- 注：`kilo.svg`/`kilo@20x20`（工具窗图标，中性抽象图形）不动。

- [ ] **Step 1: 拷贝资源并改路径** → **Step 2: 核查引用清零后 git rm 旧图** → **Step 3: 编译 + 手动冒烟**（如环境可用：开空会话面板看 logo 渲染）→ **Step 4: Commit** — `assets(jetbrains): 欢迎页 logo 替换为 CoStrict 品牌图`

---

### Task 20: 收尾验证

- [ ] **Step 1: 编译门禁** — `./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin :cs-cloud:compileKotlin`
- [ ] **Step 2: 相关模块测试** — `./gradlew :frontend:test :backend:test`（本计划触达的模块全量）
- [ ] **Step 3: 残留核查**（全部期望 0 命中，命令在 `packages/kilo-jetbrains/` 下）：
  - `rg -n "Kilo Code" frontend/src/main backend/src/main cs-cloud/src/main`（用户可见品牌；`KiloNotifications` 已收口）
  - `rg -n "kilo\.ai|kilocode\.ai|Kilo-Org" frontend/src/main cs-cloud/src/main`
  - `rg -n 'Costrict' frontend/src/main/resources/messages`（大小写敏感）
  - `rg -n "You are Kilo" backend/src/main`
  - `rg -n '"Kilo Code"|"Kilo\.CodeReview"' frontend/src/main`
- [ ] **Step 4: 三语 key 一致性**（同 Task 17 Step 3 命令）
- [ ] **Step 5: 汇报** — 按 verification-before-completion 输出命令与结果；遗留事项（Agent Manager gh 横幅、worktree 会话实测、`FakeCsCloudDaemon` 盲区回归注意）转验证轮清单
