# 专项一·隐藏入口版实施计划（Legacy Features Hide-Entry）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 2026-09-03 二次修订版 spec，把原"删除代码"基线全量收敛为"隐藏 UI 入口"：用户可达的 Kilo 遗留入口一律不可见不可达，代码、路由、文案 key、测试文件全保留；无 UI 入口的删除项不做；文案/资源替换类照常执行。

**Architecture:** 每个入口选一个最小隐藏点：设置页=摘 xml `applicationConfigurable` 注册块；菜单项=摘 group 内 `reference`；标签页=`TabInfo.isHidden`；事件驱动 UI=事件分支不触发或控制器不发 Show 事件；承诺行=默认预选置 false + 文案去掉。全部改动可经单点翻转恢复。品牌/链接常量复用已存在的 `CostrictLinks`，新增 `CostrictBrand`（通知组 ID）。

**Tech Stack:** Kotlin (JetBrains 插件、kotlinx.coroutines/serialization)、Gradle（模块 `:shared` `:frontend` `:backend` `:cs-cloud`）、JUnit（平台测试 `BasePlatformTestCase` / kotlin.test）。

**Spec:** `packages/kilo-jetbrains/docs/superpowers/specs/2026-09-03-legacy-features-minimal-fix-design.md`（2026-09-03 二次修订版；本计划从该 spec 论证，执行者两份都要读）

## Spec → 任务覆盖对照

| Spec 项 | 本计划处置 | 任务 |
|---|---|---|
| A1/A2/A3 Profile 设置页 + 设备 OAuth + 外链 | 隐藏入口（摘 xml 注册块，类/管道/测试全保留） | Task 4 |
| A4 History Cloud 标签 | 隐藏（`cloudInfo.isHidden`，云链路保留） | Task 5 |
| A5 Reinstall 条件隐藏 + 重装失败通知 | 维持原方案（本即隐藏向，非删除） | Task 3 |
| A6 反馈链接 | 已提交（86f1633058 + 在途微调），Task 1 仅提交 | Task 1 |
| A7 /help 指向 docs.costrict.ai | 替换（非删除） | Task 10 |
| B1 Restart 更名 + CoreInfo 菜单项 | 更名（文案）+ 菜单项隐藏（摘 reference，动作类/注册保留） | Task 11 |
| B2 下载进度 UI | 隐藏（事件分支不渲染，分支/文案保留） | Task 7 |
| B3 provider 特权 / B4 401 检测 / B7 遥测透传 | **不做**（无 UI 入口的代码删除） | — |
| B5 组织/账号切换 overlay | 隐藏（控制器不发 Show，管道保留） | Task 6 |
| B6 Small Model 设置行 | 隐藏（不渲染该行，picker/文案/参数保留） | Task 8 |
| C1 autocomplete 死存储 / C4 kilo 凭证写入 | **不做**（无 UI 入口的代码删除） | — |
| C2 迁移向导 autocomplete 承诺 | 隐藏（默认不预选 + 行文案去掉 + plugin.xml 描述移除短语） | Task 9 |
| C3 ShowProfileAction | 复核（无 xml 注册，入口已由 B5 覆盖；类/key 保留） | Task 10 |
| C5 NATIVE_MODE_DEFAULTS 提示词 | 替换（非删除） | Task 12 |
| D1 构建链 / E1 语言包裁剪 | **不做**（D 组整组不做；18 个语言包全保留，仅补漏译） | E1→Task 13 |
| E2 通知组 ID | 替换（`Kilo Code`→`Costrict` 等，新增 CostrictBrand 收口） | Task 2 |
| E3 拼写归一 | 替换 | Task 14 |
| E4 logo 替换 | 替换（`kilo-profile*.svg`/`kilo-content.png` **不删文件**） | Task 15 |
| Agent Manager | 不处理（spike 结论：保留入口不隐藏） | — |

## Global Constraints

- 所有 Gradle 命令在 `packages/kilo-jetbrains/` 目录执行。先设 JDK 21：`export JAVA_HOME='C:\Users\demo\.jdks\ms-21.0.12.1'`（Git Bash；路径已验证存在）。
- **隐藏基线纪律（本计划与旧计划的根本差异）**：
  - 严禁 `git rm`/删除任何 `.kt` 文件、properties 文件、图标文件；
  - 严禁删除 bundle key（三语包只增改不删）；
  - 允许摘除的只有**注册点/挂载点**：xml `applicationConfigurable` 注册块、group 内 `<reference>`/`<separator>`、UI 行的 `rows.row(...)` 挂载、事件分支的调用体；
  - 严禁删除/跳过既有测试文件；允许且应当把"断言入口可见"的用例改写为"断言入口隐藏"；
  - 严禁触碰 `creditConsumption`/`creditDiscount` 在途计费标签代码与测试。
- 用户可见文案品牌拼写统一 **CoStrict**（驼峰大写 S）；通知组 ID、`@costrict` 包名、URL 域名（`docs.costrict.ai` 等小写）、log、代码标识不动。
- 内部标识不清理：`KiloBundle` 资源名、`action.Kilo.*` 动作 ID、模块名 `kilo.jetbrains.*`、VFS 协议 `kilo`、`KiloPlugin.ID`、toolWindow `id="Costrict"`。
- 提交信息用中文、格式 `类型(jetbrains): 描述`，结尾加：
  `Co-Authored-By: Claude Code <noreply@anthropic.com>`。每任务一个干净提交。
- 每任务收尾跑编译门禁：`./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin`（触到 cs-cloud 再加 `:cs-cloud:compileKotlin`）。
- 只跑相关模块测试，不做全量测试；不以 `FakeCsCloudDaemon`/`MockCliServer` 的 200-兜底作为入口隐藏的通过依据（mock 盲区，见 spec 验证策略）。
- **通过标准是"入口不可见且不可达"**：UI 不渲染、菜单/设置/Find Action 无对应项、迁移向导无承诺行；测试同步改写为断言隐藏态。

## 通用验证命令

- 编译门禁：`./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin`
- 前端测试：`./gradlew :frontend:test --tests "<pattern>"`（各任务给出具体 pattern）
- 后端测试：`./gradlew :backend:test --tests "<pattern>"`

---

### Task 1: Pre-flight — 提交工作区在途改动

工作区有三组互不相关的未提交改动（`git status` 已核实），必须先落成干净提交，后续任务才能逐任务提交。

**Files:**（仅提交，不修改）
- cs-cloud 会话路由修复：`backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloSessionRpcApiImpl.kt`、`backend/src/test/kotlin/ai/kilocode/backend/rpc/KiloSessionRpcApiImplTest.kt`、`cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudRoute.kt`、`cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridge.kt`、`cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudRouteTest.kt`、`cs-cloud/src/test/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridgeTest.kt`、`.changeset/fix-cs-cloud-conversation-routes.md`
- A6 微调：`frontend/.../session/ui/empty/EmptySessionFeedback.kt`、`frontend/.../ui/CostrictLinks.kt`、三语 bundle（各删 1 行 `feedback.dialog.issue`）、`frontend/src/test/kotlin/ai/kilocode/client/session/ui/EmptySessionPanelTest.kt`
- 文档：`docs/superpowers/specs/2026-09-03-legacy-features-minimal-fix-design.md`（本次修订）、`docs/cs-plugin-legacy-features-and-onboarding-review.md`（未跟踪）、`.changeset/show-empty-agent-manager.md`（未跟踪）

**Interfaces:** 无（本任务无代码产出）。

- [ ] **Step 1: 先验证再提交** — 在 `packages/kilo-jetbrains/` 跑：

```bash
export JAVA_HOME='C:\Users\demo\.jdks\ms-21.0.12.1'
./gradlew :cs-cloud:test :backend:test --tests "ai.kilocode.backend.rpc.KiloSessionRpcApiImplTest"
```

Expected: PASS。若有失败，**停止并汇报**（在途修复不完整，不得提交），转交用户。

- [ ] **Step 2: 三个提交**（仓库根 `F:\ai-coding\kilocode` 执行）：

```bash
git add packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloSessionRpcApiImpl.kt \
  packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/rpc/KiloSessionRpcApiImplTest.kt \
  packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudRoute.kt \
  packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridge.kt \
  packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudRouteTest.kt \
  packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridgeTest.kt \
  .changeset/fix-cs-cloud-conversation-routes.md
git commit -m "fix(jetbrains): 会话请求经本地 cs-cloud daemon 路由并兼容无 capabilities 的 CSC

Co-Authored-By: Claude Code <noreply@anthropic.com>"

git add packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/ui/empty/EmptySessionFeedback.kt \
  packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/ui/CostrictLinks.kt \
  packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle.properties \
  packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle_zh_CN.properties \
  packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle_zh_TW.properties \
  packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/session/ui/EmptySessionPanelTest.kt
git commit -m "fix(jetbrains): 反馈弹窗移除 zgsm 反馈链接（A6 微调）

Co-Authored-By: Claude Code <noreply@anthropic.com>"

git add packages/kilo-jetbrains/docs/superpowers/specs/2026-09-03-legacy-features-minimal-fix-design.md \
  packages/kilo-jetbrains/docs/cs-plugin-legacy-features-and-onboarding-review.md \
  packages/kilo-jetbrains/docs/superpowers/plans/2026-09-03-legacy-features-hide-entry.md \
  .changeset/show-empty-agent-manager.md
git commit -m "docs(jetbrains): 专项一 spec 修订为隐藏入口基线并收录评审文档与实施计划

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 3: 确认干净** — `git status --short` 应为空（`build/` 等忽略项除外）。

---

### Task 2: E2 — 通知组 ID 收口到 CostrictBrand

`"Kilo Code"`→`"Costrict"`、`"Kilo.CodeReview"`→`"Costrict.CodeReview"`，新增 `CostrictBrand` 常量对象收口 6 处代码引用。组 ID 变更会使老用户该组通知设置回默认（spec 已接受）。注意 bundle key（`notification.group.kilo` 等）与显示值本任务**不动**（显示值大小写归一在 Task 14）。

**Files:**
- Create: `frontend/src/main/kotlin/ai/kilocode/client/ui/CostrictBrand.kt`
- Create: `frontend/src/test/kotlin/ai/kilocode/client/ui/CostrictBrandTest.kt`
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:16-19`（`id="Kilo Code"` → `id="Costrict"`）、`:21-24`（`id="Kilo.CodeReview"` → `id="Costrict.CodeReview"`）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/KiloNotifications.kt:12`（删私有常量，改引用）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/settings/agents/AgentEditDialog.kt:346,348`
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/settings/AdvancedLogActions.kt:79,81`
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/ui/prompt/PromptAttachmentStrip.kt:84`
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/ui/prompt/PromptPanel.kt:927`
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/codereview/CodeReviewNotifier.kt:47`
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/NotificationGroupIsolationTest.kt:27-29,34-35`（ID 字面量）
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/settings/models/ModelsSettingsUiTest.kt:286`（`groupId == "Kilo Code"`）

**Interfaces:**
- Produces: `object CostrictBrand { const val NOTIFICATION_GROUP: String; const val CODE_REVIEW_NOTIFICATION_GROUP: String }`（Task 11 无依赖；后续用户可见品牌统一以它为准）

- [ ] **Step 1: 写失败测试** — 新建 `frontend/src/test/kotlin/ai/kilocode/client/ui/CostrictBrandTest.kt`：

```kotlin
package ai.kilocode.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CostrictBrandTest {

    @Test
    fun `notification group ids match the xml registration`() {
        val xml = javaClass.classLoader.getResourceAsStream("kilo.jetbrains.frontend.xml")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw AssertionError("kilo.jetbrains.frontend.xml not on test classpath")
        // The exact value with closing quote — must not match "Costrict.CodeReview".
        assertTrue(xml.contains("<notificationGroup id=\"Costrict\""), "generic group must be registered as Costrict")
        assertTrue(
            xml.contains("<notificationGroup id=\"Costrict.CodeReview\""),
            "code review group must be registered as Costrict.CodeReview",
        )
        assertEquals("Costrict", CostrictBrand.NOTIFICATION_GROUP)
        assertEquals("Costrict.CodeReview", CostrictBrand.CODE_REVIEW_NOTIFICATION_GROUP)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.ui.CostrictBrandTest"`
Expected: FAIL（unresolved reference: CostrictBrand；xml 里仍是 Kilo Code）

- [ ] **Step 3: 实现常量 + 改 xml + 改 6 处引用**

新建 `frontend/src/main/kotlin/ai/kilocode/client/ui/CostrictBrand.kt`：

```kotlin
package ai.kilocode.client.ui

/**
 * Notification group ids. Must match the <notificationGroup id="..."/> registrations in
 * kilo.jetbrains.frontend.xml — a mismatch silently drops the group and its notifications.
 */
object CostrictBrand {
    const val NOTIFICATION_GROUP = "Costrict"
    const val CODE_REVIEW_NOTIFICATION_GROUP = "Costrict.CodeReview"
}
```

`kilo.jetbrains.frontend.xml:16`：`<notificationGroup id="Kilo Code"` → `<notificationGroup id="Costrict"`；`:21`：`id="Kilo.CodeReview"` → `id="Costrict.CodeReview"`（其余属性行不动）。

`KiloNotifications.kt`：删除第 12 行 `private const val GROUP = "Kilo Code"`，加 `import ai.kilocode.client.ui.CostrictBrand`，文件内全部 `GROUP` 引用改 `CostrictBrand.NOTIFICATION_GROUP`（error/error(project,action)/info/suggestion 四个方法共 8 个使用点）。

4 处兜底硬编码与 code review 常量（各文件加 `import ai.kilocode.client.ui.CostrictBrand`）：
- `AgentEditDialog.kt:346` `.getNotificationGroup("Kilo Code")` → `.getNotificationGroup(CostrictBrand.NOTIFICATION_GROUP)`；`:348` `Notification("Kilo Code", ...)` → `Notification(CostrictBrand.NOTIFICATION_GROUP, ...)`
- `AdvancedLogActions.kt:79`、`:81` 同上两处替换
- `PromptAttachmentStrip.kt:84` `Notification("Kilo Code", ...)` → `Notification(CostrictBrand.NOTIFICATION_GROUP, ...)`
- `PromptPanel.kt:927` `Notification("Kilo Code", ...)` → `Notification(CostrictBrand.NOTIFICATION_GROUP, ...)`
- `CodeReviewNotifier.kt:47` `private const val GROUP = "Kilo.CodeReview"` → `private const val GROUP = CostrictBrand.CODE_REVIEW_NOTIFICATION_GROUP`

同步更新两个既有测试的 ID 字面量：
- `NotificationGroupIsolationTest.kt:27,28,29,34,35`：`"Kilo Code"` → `"Costrict"`、`"Kilo.CodeReview"` → `"Costrict.CodeReview"`（`:38-40,44-50` 的 bundle key/显示值断言不动）
- `ModelsSettingsUiTest.kt:286`：`it.groupId == "Kilo Code"` → `it.groupId == CostrictBrand.NOTIFICATION_GROUP`，文件头加 `import ai.kilocode.client.ui.CostrictBrand`

- [ ] **Step 4: 跑测试确认通过 + 残留核查**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.ui.CostrictBrandTest" \
  --tests "ai.kilocode.client.NotificationGroupIsolationTest" \
  --tests "ai.kilocode.client.settings.models.ModelsSettingsUiTest"
rg -n '"Kilo Code"|"Kilo\.CodeReview"' frontend/src/main
```

Expected: 测试 PASS；rg 无命中。

- [ ] **Step 5: 编译门禁 + Commit**

```bash
git add -A && git commit -m "refactor(jetbrains): 通知组 ID 收口到 CostrictBrand（Kilo Code→Costrict）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: A5 — providerId 下发 + cs-cloud 模式隐藏 Reinstall + 重装失败通知

**Files:**
- Modify: `shared/src/main/kotlin/ai/kilocode/rpc/dto/KiloAppStateDto.kt:215` 附近（`val profile: ProfileDto? = null,` 之后加 `providerId` 字段）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt:141` 之后（新增 `providerId` 属性）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloAppRpcApiImpl.kt:148-149`（`dto()` 附加 providerId）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/ui/ConnectionPanel.kt:271-280`（`recoveryActionIds()` 条件化）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/app/KiloAppService.kt:176-180`（`reinstallAsync` 补 catch + 错误通知）
- Modify: 三语 bundle（新增 `action.Kilo.Reinstall.failed`）
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/session/ui/ConnectionPanelTest.kt`（新增 1 用例；既有 4 处 `recoveryActionIds` 断言**不改**——默认 `providerId=null` 行为不变）

**Interfaces:**
- Consumes: `CsCloudConnectionProvider.id == "cs-cloud"`（`cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionProvider.kt:13`，已核实）
- Produces: `KiloAppStateDto.providerId: String?`；约定 `providerId == "cs-cloud"` 时前端隐藏 `Kilo.Reinstall`

- [ ] **Step 1: 写失败测试** — `ConnectionPanelTest.kt` 新增用例（文件头补 `import ai.kilocode.rpc.dto.KiloAppStateDto` 与 `import ai.kilocode.rpc.dto.KiloAppStatusDto`）：

```kotlin
fun `test recovery menu hides reinstall on cs-cloud provider`() {
    edt {
        controller.model.app = KiloAppStateDto(
            KiloAppStatusDto.ERROR,
            providerId = "cs-cloud",
        )
        panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowError(
            "Connection failed",
            "cs-cloud server URL was not found",
            code = ConnectionErrorCode.CSC_NOT_INSTALLED,
        ))
    }

    assertEquals(listOf("Kilo.Restart", "Kilo.StartCsCloud", "Kilo.InstallCsc"), panel.recoveryActionIds())
}
```

（`controller.model.app` 是 public var，`SessionModel.kt:56` 已核实。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.session.ui.ConnectionPanelTest"`
Expected: FAIL（`providerId` 参数不存在 → 编译错误即失败）

- [ ] **Step 3: 实现**

`shared/.../dto/KiloAppStateDto.kt`：在 `val profile: ProfileDto? = null,`（约 :215）之后加：

```kotlin
    /** Connection provider id (e.g. "cs-cloud"); null for the default CLI provider. */
    val providerId: String? = null,
```

`backend/.../app/KiloBackendAppService.kt`：在 `private val connectionProvider = providers`（:141）之后加：

```kotlin
    /** Connection provider id — lets the frontend tailor recovery actions per provider. */
    val providerId: String get() = connectionProvider.id
```

`backend/.../rpc/KiloAppRpcApiImpl.kt:148-149`：

```kotlin
    private fun dto(state: KiloAppState): KiloAppStateDto =
        appStateDto(state).copy(providerId = app.providerId)
```

`frontend/.../session/ui/ConnectionPanel.kt:271-280`：

```kotlin
    /** Recovery actions offered for the current failure, newest first. */
    internal fun recoveryActionIds(): List<String> = buildList {
        add("Kilo.Restart")
        // Costrict (A5): cs-cloud manages its own lifecycle — Reinstall is a Kilo-CLI action.
        if (controller.model.app.providerId != "cs-cloud") add("Kilo.Reinstall")
        if (code == ConnectionErrorCode.CSC_NOT_INSTALLED || code == ConnectionErrorCode.DAEMON_DOWN || code == ConnectionErrorCode.UNAUTHORIZED) {
            add("Kilo.StartCsCloud")
        }
        if (code == ConnectionErrorCode.CSC_NOT_INSTALLED) {
            add("Kilo.InstallCsc")
        }
    }
```

`frontend/.../app/KiloAppService.kt:176-180`（`KiloNotifications` 已在该文件 import）：

```kotlin
    /** Fire-and-forget reinstall from non-suspend context (e.g. action handlers). */
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

三语 bundle 新增（en 插到 `action.Kilo.Reinstall.description`（:901）之后；zh_CN 插到 `action.Kilo.Reinstall.description`（:180）之后；zh_TW 插到同 key（:178）之后）：

```properties
# KiloBundle.properties
action.Kilo.Reinstall.failed=Reinstall failed: {0}
```
```properties
# KiloBundle_zh_CN.properties
action.Kilo.Reinstall.failed=重装失败：{0}
```
```properties
# KiloBundle_zh_TW.properties
action.Kilo.Reinstall.failed=重裝失敗：{0}
```

- [ ] **Step 4: 跑测试确认通过 + 编译门禁**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.session.ui.ConnectionPanelTest"
./gradlew :shared:compileKotlin :backend:compileKotlin
```

Expected: ConnectionPanelTest 全 PASS（含既有 4 处断言 Reinstall 在列的用例，`providerId=null` 不受影响）；编译通过。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "fix(jetbrains): cs-cloud 模式隐藏 Reinstall 并为重装失败补通知

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: A1/A2/A3 — 设置页 User Profile 入口隐藏

唯一用户入口是 Settings 树里的 `UserProfileConfigurable` 注册块。摘除注册块后：设置页无 Profile 子页、Settings 搜索不索引（未注册的可搜索不到）；Profile 页、设备 OAuth UI（A2）、三条 Kilo 外链与 "Kilo Pass Opened" 遥测点（A3）全部随块不可达。**类文件、bundle key、后端 profile 管道、既有测试全部保留。**

**Files:**
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:45-51`（整块摘除，见 Step 1）

**Interfaces:** 无新增；`UserProfileConfigurable` 类与 `settings.profile.displayName` key 保留在原处。

- [ ] **Step 1: 摘除注册块** — 从 `kilo.jetbrains.frontend.xml` 删除恰好以下 7 行（在 `ai.kilocode.jetbrains.settings` 与 `ai.kilocode.jetbrains.settings.models` 两块之间）：

```xml
        <applicationConfigurable
                parentId="ai.kilocode.jetbrains.settings"
                id="ai.kilocode.jetbrains.settings.profile"
                groupWeight="5"
                instance="ai.kilocode.client.settings.profile.UserProfileConfigurable"
                bundle="messages.KiloBundle"
                key="settings.profile.displayName"/>
```

- [ ] **Step 2: 不可达核查**

```bash
rg -n "UserProfileConfigurable|settings\.profile\.displayName" frontend/src/main
```

Expected: 仅剩 `settings/profile/UserProfileConfigurable.kt` 类文件自身与 bundle key 定义行，**无任何 xml/代码注册或引用**。若出现其他引用（除 `UserProfileConfigurableTest` 直测类外），停止并汇报。

- [ ] **Step 3: 相关测试**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.plugin.DisplayNameI18nTest" \
  --tests "ai.kilocode.client.settings.*" --tests "ai.kilocode.client.session.controller.*"
```

Expected: PASS。`DisplayNameI18nTest` 的 `settings.profile.displayName` critical-key 断言仍绿（key 保留）；`UserProfileConfigurableTest` 仍绿（类保留）。

- [ ] **Step 4: 编译门禁 + Commit**

```bash
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
git add -A && git commit -m "refactor(jetbrains): 隐藏设置页 User Profile 入口（A1-A3，代码与测试保留）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: A4 — History Cloud 标签隐藏

用 `TabInfo.isHidden` 隐藏 Cloud 标签：用户看不到也点不到；`JBTabsImpl.select()` 对隐藏标签**没有 isHidden 守卫**（已反汇编核实），所以 `HistoryControllerTest` 里 12 处 `panel.clickCloud()` 测试钩子照常工作，**全部测试不需要改**。云数据链路（controller/后端路由）原样保留。

**Files:**
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/history/HistoryPanel.kt:98-99`（`addTab(cloudInfo)` 之后加一行）

**Interfaces:** 无新增；`cloudInfo`/`cloudPanel`/云链路全部保留。

- [ ] **Step 1: 隐藏标签** — `HistoryPanel.kt` 的 `tabs` 初始化块（:94-105）改为：

```kotlin
    private val tabs: JBTabs = JBTabsFactory.createTabs(null, this).apply {
        presentation.setSingleRow(true)
        presentation.setTabsPosition(JBTabsPosition.top)
        presentation.showBorder = false
        addTab(localInfo).setPreferredFocusableComponent(localSearch.textEditor)
        addTab(cloudInfo).setPreferredFocusableComponent(cloudSearch.textEditor)
        // Costrict (A4): Kilo cloud history entry hidden — pipeline kept for tests and restore.
        // JBTabs still allows programmatic selection of hidden tabs, so test hooks keep working.
        cloudInfo.isHidden = true
        addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                sync()
            }
        }, this@HistoryPanel)
    }
```

（若 `cloudInfo.isHidden = true` 编译报错，改用等价方法调用 `cloudInfo.setHidden(true)`——`TabInfo.setHidden(boolean)`/`isHidden()` 已 javap 核实存在。）

- [ ] **Step 2: 验证既有 cloud 用例不受影响**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.session.history.*"
```

Expected: 全 PASS（含 12 处 `clickCloud()` 用例——隐藏标签仍可被 `tabs.select(cloudInfo, false)` 内选）。若出现"选不中隐藏标签"类失败，**停止并汇报**，不得擅自改写这批用例。

- [ ] **Step 3: 编译门禁 + Commit**

```bash
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
git add -A && git commit -m "refactor(jetbrains): 隐藏 History Cloud 标签入口（A4，云链路与测试保留）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: B5 — 组织/账号切换 overlay 入口隐藏

单点隐藏：控制器 `showAccountOverlay()` 永不发 Show（初始态即 Hide，`setAccountOverlayState` 去重后为 no-op）。overlay 构造与挂载保留（`SessionUiLayoutTest:88/180` 的布局断言继续成立），`SessionAccountOverlay` 类与其测试原样保留（直驱 `onEvent`，不受影响）。

**Files:**
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt:2369-2372`（`showAccountOverlay()` 方法体）
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/session/SessionUiLayoutTest.kt:765-774,776-791,802-817`（3 个可见性用例改写为隐藏态）

**Interfaces:** 无新增；`refreshAccountOverlay()` 已有 `if (!acctAllowed) return` 守卫，`acctAllowed` 永远为 false 后组织切换路径自然不可达。

- [ ] **Step 1: 改写 3 个用例为隐藏态断言（先红）** — `SessionUiLayoutTest.kt`：

`:765` `test account overlay shows after recents complete` 整个替换为：

```kotlin
    fun `test account overlay stays hidden after recents complete`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = ProfileDto(email = "user@example.com"))
        rpc.recent.add(session("ses_1"))
        ui = newUi(displayMs = 1_000)

        settle()

        val overlay = find<SessionAccountOverlay>(ui)
        assertFalse(overlay.isVisible)
    }
```

`:776` `test account overlay hides after first prompt` 整个替换为：

```kotlin
    fun `test account overlay stays hidden after first prompt`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = ProfileDto(email = "user@example.com"))
        rpc.recent.add(session("ses_1"))
        ui = newUi(displayMs = 1_000)
        settle()

        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            controller().prompt("hello")
        }
        settle()

        val overlay = find<SessionAccountOverlay>(ui)
        assertFalse(overlay.isVisible)
    }
```

`:802` `test account overlay uses prompt panel top and right insets` 整个替换为：

```kotlin
    fun `test account overlay is registered but never visible`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = ProfileDto(email = "user@example.com"))
        rpc.recent.add(session("ses_1"))
        ui = newUi(displayMs = 1_000)
        settle()
        layout()

        val root = find<SessionRootPanel>(ui)
        val overlay = find<SessionAccountOverlay>(ui)

        assertSame(root.overlay, overlay.parent)
        assertFalse(overlay.isVisible)
    }
```

`:752`/`:793` 两个隐藏断言用例与 `:745` 注册断言用例**不改**。

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.session.SessionUiLayoutTest"`
Expected: 恰好 Step 1 的前两个新用例 FAIL（overlay 仍会显示）；第三个可能 PASS（注册断言）。

- [ ] **Step 3: 实现** — `SessionController.kt:2369-2372`：

```kotlin
    private fun showAccountOverlay() {
        // Costrict (B5, 2026-09-03 spec revision): the account/org-switch overlay entry is hidden.
        // Keep the snapshot/event/overlay pipeline intact for restore — never allow Show.
        acctAllowed = false
        setAccountOverlayState(SessionControllerEvent.AccountOverlayChanged.Hide)
    }
```

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.session.SessionUiLayoutTest" \
  --tests "ai.kilocode.client.session.ui.account.*" --tests "ai.kilocode.client.session.controller.*"
```

Expected: 全 PASS（`SessionAccountOverlayTest` 直驱 `onEvent`，不改自绿）。

- [ ] **Step 5: 编译门禁 + Commit**

```bash
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
git add -A && git commit -m "refactor(jetbrains): 隐藏组织/账号切换 overlay 入口（B5，管道与 overlay 类保留）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: B2 — 下载进度 UI 入口隐藏

`ShowDownloading` 事件分支不再渲染横幅；事件类、`showDownloading()` 方法、`session.connection.downloading*` bundle key（:11-12）全部保留。`KiloAppStatusDto.DOWNLOADING` 等状态枚举本就保留。

**Files:**
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/ui/ConnectionPanel.kt:132`（分支体）
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/session/ui/ConnectionPanelTest.kt:40-51`（用例改写）

**Interfaces:** 无新增。

- [ ] **Step 1: 改写用例（先红）** — `ConnectionPanelTest.kt:40-51` 的 `test downloading hides retry and details` 整个替换为：

```kotlin
    fun `test downloading entry is hidden and panel stays invisible`() {
        edt {
            panel.onEvent(SessionControllerEvent.ConnectionChanged.ShowDownloading(42, "1.2.3", "darwin-arm64"))
        }

        assertFalse(panel.isVisible)
        assertFalse(panel.retryVisible())
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.session.ui.ConnectionPanelTest"`
Expected: FAIL（面板当前会显示下载横幅）

- [ ] **Step 3: 实现** — `ConnectionPanel.kt:132`：

```kotlin
            // Costrict (B2): download progress entry hidden — event class, banner method and
            // bundle keys stay for restore.
            is SessionControllerEvent.ConnectionChanged.ShowDownloading -> Unit
```

- [ ] **Step 4: 跑测试确认通过 + 编译门禁**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.session.ui.ConnectionPanelTest"
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
```

Expected: PASS（`showDownloading()` 方法保留但不再被调用，不会触发 unused 编译错误）。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor(jetbrains): 隐藏 CLI 下载进度横幅入口（B2，事件与文案保留）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: B6 — Small Model 设置行隐藏

只摘 `ModelsSettingsContent` 里的 `rows.row(smallModel…)` 挂载；`small` picker、`includeSmall`、`settings.models.smallModel.*` bundle key、`ModelsSettingsState` 的 small 草稿/patch 链全部保留（行不可见 ⇒ 用户改不了 ⇒ patch 永不含 `small_model`）。

**Files:**
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/settings/models/ModelsSettingsUi.kt:270-274`（摘除 rows.row 挂载）
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/settings/models/ModelsSettingsUiTest.kt`（新增 1 用例）

**Interfaces:** 无新增；`ModelsSettingsStateTest`（smallModel 草稿）与 `FakeAppRpcApi` 的 `small_model` patch 处理不动。

- [ ] **Step 1: 写失败测试** — `ModelsSettingsUiTest.kt` 新增用例（文件头补 `import ai.kilocode.client.plugin.KiloBundle`；断言方法继承自 `junit.framework.TestCase`，无需额外 import）：

```kotlin
    fun `test small model row is hidden`() {
        val smallModelTitle = KiloBundle.message("settings.models.smallModel.title")
        assertFalse(text(requireUi()).contains(smallModelTitle))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.settings.models.ModelsSettingsUiTest"`
Expected: FAIL（当前渲染 Small Model 行）

- [ ] **Step 3: 实现** — `ModelsSettingsUi.kt` 的 `ModelsSettingsContent.init` 中删除以下 5 行（`defaults` 行与 `subagent` 行之间的挂载）：

```kotlin
        rows.row(SettingsRow(
            KiloBundle.message("settings.models.smallModel.title"),
            KiloBundle.message("settings.models.smallModel.description"),
            small,
        ))
```

其余一切不动：`val small = ModelSettingPicker()`、`small.picker.*` 接线、`syncContent()` 里的 `smallItems`/`small.setItems(...)`/`listOf(defaults, small, subagent)` 全部保留。

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.settings.models.*"
```

Expected: 全 PASS（含既有 `ModelsSettingsStateTest`——草稿链未动）。

- [ ] **Step 5: 编译门禁 + Commit**

```bash
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
git add -A && git commit -m "refactor(jetbrains): 隐藏 Settings Models 面板 Small Model 设置行（B6）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 9: C2 — 迁移向导 autocomplete 承诺隐藏

单点置 false：`MigrationSelectionBuilder.defaults()` 不再预选 autocomplete ⇒ 向导预选不含它、`currentSelections()`（`settingsRow.selected → defaults.settings`，`MigrationWizardPanel.kt:216` 已核实）传出的 wire selections 恒为 false ⇒ `applyAutocomplete` 早退、进度项 "Autocomplete settings" 不再出现。autocomplete 全链路代码（`KiloAutocompleteSettingsService`、xml 注册、`applyAutocomplete`、toDto 字段）保留。配套去掉行文案与插件描述里的承诺。

**Files:**
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/migration/MigrationSelectionBuilder.kt:24,49`（注释行 + `autocomplete = false`）
- Modify: `src/main/resources/META-INF/plugin.xml:8-14`（description 移除 "Features inline autocomplete, " 短语）
- Modify: 三语 bundle `migration.row.settings`（en `:954`、zh_CN `:402`、zh_TW `:400`，三处值相同）
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/migration/KiloMigrationServiceTest.kt`（新增 1 用例）

**Interfaces:** 无新增；`KiloMigrationServiceTest:277`（显式 selections 直驱 apply）与 `:91`（detectionProps）不受影响，必须保持绿。

- [ ] **Step 1: 写失败测试** — `KiloMigrationServiceTest.kt` 新增（文件头补 `import ai.kilocode.client.migration.MigrationSelectionBuilder`）：

```kotlin
    fun `test defaults never preselect autocomplete`() {
        assertFalse(MigrationSelectionBuilder.defaults(sampleDetection()).settings.autocomplete)
    }
```

（`sampleDetection()` 的 settings 含 autocomplete 数据——现 defaults 会预选为 true，故先红。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.migration.KiloMigrationServiceTest"`
Expected: 恰好新用例 FAIL

- [ ] **Step 3: 实现**

`MigrationSelectionBuilder.kt:49`：

```kotlin
            // Costrict (C2): the autocomplete promise is hidden — never preselected, never applied.
            autocomplete = false,
```

同文件 KDoc（:24）删掉 `- Autocomplete: if present` 一行。

`plugin.xml` description 改为（仅去掉短语，其余原样）：

```
        Costrict is an open-source AI coding agent that generates code from natural language,
        automates tasks, and runs terminal commands. An Agent Manager for parallel worktrees,
        MCP integrations, browser automation, and custom modes for planning, coding, and
        debugging. Supports multiple AI models including GLM.
        Learn more at https://costrict.ai
```

三语 bundle（en `:954`、zh_CN `:402`、zh_TW `:400`）：

```properties
migration.row.settings=Auto-Approval & Language
```

- [ ] **Step 4: 跑测试确认通过 + 残留核查**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.migration.*"
rg -n -i "autocomplete" frontend/src/main --glob "!*.properties"
```

Expected: 测试全 PASS。rg 命中仅允许：`kilo.jetbrains.frontend.xml:13`（applicationService 注册，C1 不做）、`KiloAutocompleteSettingsService.kt`、`KiloMigrationService.kt`（import/构造参数/`applyAutocomplete`/`buildInitialProgress:453`）、`MigrationSelectionBuilder.kt`（KDoc 与 toDto 透传行）、`ModelsSettingsContent` 的 `includeSmall`（模型选择器参数，非本项）。出现其他生产调用点则停下汇报。

- [ ] **Step 5: 编译门禁 + Commit**

```bash
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
git add -A && git commit -m "refactor(jetbrains): 隐藏迁移向导 autocomplete 承诺（C2，链路代码保留）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 10: A7 — /help 指向 CoStrict 文档 + C3 复核

**Files:**
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/SessionUi.kt:860`（HELP 分支目标）

**Interfaces:**
- Consumes: `CostrictLinks.DOCS`（`frontend/.../ui/CostrictLinks.kt`，已存在）

- [ ] **Step 1: 替换 /help 目标** — `SessionUi.kt:860`：

```kotlin
            SlashAction.HELP to { BrowserUtil.browse(CostrictLinks.DOCS) },
```

文件头加 `import ai.kilocode.client.ui.CostrictLinks`（若无）。

- [ ] **Step 2: 核查**

```bash
rg -n "kilo\.ai/docs|kilocode\.ai" frontend/src/main
rg -n "ShowProfileAction" frontend/src/main frontend/src/main/resources
```

Expected: 第一条 0 命中；第二条仅 `actions/ShowProfileAction.kt` 类文件自身与 bundle `action.Kilo.ShowProfile.*` key（`SessionAccountOverlay.kt:65-66` 的 key 引用保留）。**C3 结论落档**：ShowProfileAction 在 plugin xml 无任何注册（已核实 `kilo.jetbrains.frontend.xml` 全文），唯一入口是 SessionAccountOverlay 的 profile 按钮，已由 Task 6 隐藏——类与 bundle key 保留，无需改动。把该结论写进提交说明。

- [ ] **Step 3: 相关测试 + 编译门禁**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.session.*"
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "fix(jetbrains): /help 指向 docs.costrict.ai；复核 ShowProfileAction 无注册入口（A7/C3）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 11: B1 — "Restart Core" 更名 cs-cloud + "Core vX" 菜单项隐藏

更名走 bundle 文案（3 语）+ xml group `text`；"Core vX" 信息项摘除 `Kilo.CliGroup` 内的 `<separator/>` + `<reference ref="Kilo.CoreInfo"/>`（用户菜单不可见）。`CoreInfoAction` 类、`<action id="Kilo.CoreInfo">` 声明、`action.Kilo.CoreInfo.*` bundle key（:915-918）、`KiloAppService.core/fetchCoreInfoAsync/bundled/fetchBundledAsync` 成员**全部保留**（内部标识 F 组豁免；Find Action 可见性为已接受残留——仅弹信息框，无破坏性）。

**Files:**
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:199`（`text="Core"` → `text="cs-cloud"`）、`:204-205`（摘除 separator + reference）
- Modify: 三语 bundle（en `:894-898,901` 区、zh_CN `:175-180,370-371` 区、zh_TW `:173-178,368-369` 区）
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/session/ui/ConnectionPanelTest.kt:160-174`（xml 断言更新）

**Interfaces:** 无新增。

- [ ] **Step 1: 更新测试断言（先红）** — `ConnectionPanelTest.kt` 的 `test retry popup group uses core recovery actions`（:160-174），只改两行：

```kotlin
        assertTrue(xml.contains("<group id=\"Kilo.CliGroup\" text=\"cs-cloud\" popup=\"true\">"))
        assertTrue(xml.contains("<reference ref=\"Kilo.Restart\"/>"))
        assertTrue(xml.contains("<reference ref=\"Kilo.Reinstall\"/>"))
        assertFalse(xml.contains("<reference ref=\"Kilo.CoreInfo\"/>"), "Core info menu entry must stay hidden")
```

（`assertFalse` 来自 `junit.framework.TestCase` 基类，无需 import。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.session.ui.ConnectionPanelTest"`
Expected: 恰好该用例 FAIL

- [ ] **Step 3: 实现 xml 与 bundle**

`kilo.jetbrains.frontend.xml:199`：`<group id="Kilo.CliGroup" text="Core" popup="true">` → `text="cs-cloud"`；删除 :204-205 两行：

```xml
            <separator/>
            <reference ref="Kilo.CoreInfo"/>
```

bundle（保持各 key 原行位，只改值）：

en `KiloBundle.properties`：
```properties
action.Kilo.CliGroup.text=cs-cloud
action.Kilo.CliGroup.description=cs-cloud actions
action.Kilo.Restart.cli.text=Restart cs-cloud
action.Kilo.Restart.description=Restart the cs-cloud daemon connection
```

zh_CN `KiloBundle_zh_CN.properties`（:176,177,370,371）：
```properties
action.Kilo.Restart.cli.text=重启 cs-cloud
action.Kilo.Restart.description=重新连接并重启 cs-cloud 守护进程
action.Kilo.CliGroup.text=cs-cloud
action.Kilo.CliGroup.description=cs-cloud 相关操作
```

zh_TW `KiloBundle_zh_TW.properties`（:174,175,368,369）：
```properties
action.Kilo.Restart.cli.text=重啟 cs-cloud
action.Kilo.Restart.description=重新連線並重新啟動 cs-cloud 常駐程序
action.Kilo.CliGroup.text=cs-cloud
action.Kilo.CliGroup.description=cs-cloud 相關操作
```

注意：`action.Kilo.Restart.text`（重启 Costrict）、`action.Kilo.Reinstall.*`、`action.Kilo.CoreInfo.*` 各行**不动**（大小写归一属 Task 14）。

- [ ] **Step 4: 跑测试确认通过 + 编译门禁**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.session.ui.ConnectionPanelTest" --tests "ai.kilocode.client.actions.*"
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor(jetbrains): Restart 更名 cs-cloud 并隐藏 Core vX 菜单项（B1，动作类与注册保留）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 12: C5 — 迁移内置模式提示词 CoStrict 化

**Files:**
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/migration/LegacyMigrationConverters.kt:581,588,595,602,609,616`（6 处 `roleDefinition` 前缀）

**Interfaces:** 无新增。

- [ ] **Step 1: 替换**（大小写敏感，Git Bash）：

```bash
sed -i 's/You are Kilo Code,/You are CoStrict,/g' \
  backend/src/main/kotlin/ai/kilocode/backend/migration/LegacyMigrationConverters.kt
```

- [ ] **Step 2: 核查**

```bash
rg -n "You are Kilo" backend/src/main
rg -c "You are CoStrict," backend/src/main/kotlin/ai/kilocode/backend/migration/LegacyMigrationConverters.kt
```

Expected: 第一条 0 命中；第二条为 6。

- [ ] **Step 3: 测试 + 编译门禁**

```bash
./gradlew :backend:test --tests "ai.kilocode.backend.migration.*"
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
```

Expected: PASS（已核实 backend 测试无 "You are Kilo" 文案断言；若有用例比对 `NATIVE_MODE_DEFAULTS` 值也是自引用，不受影响）。

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "fix(jetbrains): 迁移内置模式系统提示词改为 CoStrict 自称（C5）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 13: E1 — 补 zh_CN/zh_TW 的 csCloud 登录卡片漏译（3 条 × 2 文件）

只补翻译，**不裁语言包**（18 个全保留）。键已存在但值是英文：zh_CN `:270-272`、zh_TW `:268-270`。

**Files:**
- Modify: `frontend/src/main/resources/messages/KiloBundle_zh_CN.properties:270-272`
- Modify: `frontend/src/main/resources/messages/KiloBundle_zh_TW.properties:268-270`
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/plugin/DisplayNameI18nTest.kt`（新增 1 个 @Test）

**Interfaces:** 无新增；en `:214-216` 为基准值。

- [ ] **Step 1: 写失败测试** — `DisplayNameI18nTest.kt` 新增：

```kotlin
    @Test
    fun `csCloud login card is translated in zh bundles`() {
        for (name in listOf("KiloBundle_zh_CN.properties", "KiloBundle_zh_TW.properties")) {
            val zh = bundle(name)
            val value = zh["session.login.required.csCloud.title"]
                ?: throw AssertionError("$name misses csCloud login title")
            assertTrue(!value.contains("Sign in"), "$name csCloud login title must be translated: $value")
        }
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.plugin.DisplayNameI18nTest"`
Expected: 恰好新用例 FAIL（当前值是英文 "Sign in to CoStrict to continue"）

- [ ] **Step 3: 补译** — zh_CN `:270-272`：

```properties
session.login.required.csCloud.title=登录 CoStrict 以继续
session.login.required.csCloud.description=登录 CoStrict 账号后即可使用 cs-cloud，然后继续本会话。
session.login.required.csCloud.button=登录 CoStrict
```

zh_TW `:268-270`：

```properties
session.login.required.csCloud.title=登入 CoStrict 以繼續
session.login.required.csCloud.description=登入您的 CoStrict 帳號即可使用 cs-cloud，然後繼續本會話。
session.login.required.csCloud.button=登入 CoStrict
```

（`session.login.required.dismiss` 与 `session.login.required.title/description/button` 各行不动。）

- [ ] **Step 4: 跑测试确认通过**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.plugin.DisplayNameI18nTest"
```

Expected: 全 PASS（en/zh_CN critical-key 对齐断言原本就绿）。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "fix(jetbrains): 补齐 zh_CN/zh_TW 的 csCloud 登录卡片翻译（E1，语言包不裁剪）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 14: E3 — 用户可见拼写统一 CoStrict

三语 bundle 内 `Costrict`→`CoStrict` 全量归一（大小写敏感 sed，不会碰 `@costrict`、`docs.costrict.ai` 等小写）；再扫 Kotlin 用户可见串。既有测试中按旧拼写写的期望值一并更新。

**Files:**
- Modify: `KiloBundle.properties`、`KiloBundle_zh_CN.properties`、`KiloBundle_zh_TW.properties`
- Modify: Kotlin 硬编码用户可见串（扫描后逐条判定）
- Test modify: `frontend/src/test/kotlin/ai/kilocode/client/plugin/DisplayNameI18nTest.kt:57-58`、`frontend/src/test/kotlin/ai/kilocode/client/NotificationGroupIsolationTest.kt:48`

**Interfaces:** Consumes `CostrictBrand`（Task 2）。

- [ ] **Step 1: bundle 归一**

```bash
cd packages/kilo-jetbrains/frontend/src/main/resources/messages
sed -i 's/Costrict/CoStrict/g' KiloBundle.properties KiloBundle_zh_CN.properties KiloBundle_zh_TW.properties
rg -c 'Costrict' KiloBundle*.properties          # 期望 0 命中
rg -n 'docs\.costrict\.ai|@costrict|costrict\.ai' KiloBundle.properties | head   # 小写域名必须原样存在
```

- [ ] **Step 2: 更新按旧拼写写的测试期望**

- `DisplayNameI18nTest.kt:57`：`assertEquals("Costrict", KiloBundle.message("settings.kilo.displayName"))` → `"CoStrict"`；`:58` `notification.group.kilo` 同改 `"CoStrict"`（`:59` 已是 "CoStrict Code Review"，不动）。
- `NotificationGroupIsolationTest.kt:48`：`assertEquals("Costrict", generic)` → `"CoStrict"`（`:49` 不动）。

- [ ] **Step 3: Kotlin 用户可见串扫描**

```bash
rg -n '"[^"]*Costrict[^"]*"' packages/kilo-jetbrains/frontend/src/main/kotlin packages/kilo-jetbrains/backend/src/main/kotlin
```

逐条判定：通知组 ID（`CostrictBrand` 常量与 xml `id="Costrict"`）、URL、包名、路径、log 语句**不动**；用户可见的标题/对话框/通知文案改 `CoStrict` 或改走 bundle。Task 2/12 完成后此扫描理论上仅剩 ID/URL 类命中；出现用户可见串则逐条替换并在本任务提交内完成。

- [ ] **Step 4: worktree/csCloud/残留 Kilo 核查**

```bash
cd packages/kilo-jetbrains
rg -n 'worktree\.|csCloud\.' frontend/src/main/resources/messages/KiloBundle.properties | rg -i 'kilo'   # 期望 0
rg -n 'Kilo' frontend/src/main/resources/messages/KiloBundle.properties   # 逐条人工核对，仅允许非用户可见语境（如 key 名）
```

- [ ] **Step 5: 测试 + 编译门禁 + Commit**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.plugin.*" --tests "ai.kilocode.client.NotificationGroupIsolationTest"
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
git add -A && git commit -m "chore(jetbrains): 用户可见文案拼写统一为官方 CoStrict（E3）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 15: E4 — 欢迎页 logo 替换

`kilo-content.png`（旧 Kilo logo）的引用改为参考仓库的 CoStrict logo；**旧文件不删**（`kilo-content.png`、`kilo-profile*.svg` 原地保留，仅失去引用）。`kilo.svg`/`kilo@20x20`（工具窗图标）不动。

**Files:**
- Create: `frontend/src/main/resources/icons/costrict/logo.png`（拷贝）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/session/ui/empty/BrandLogo.kt:43`（LOGO_PATH）

**Interfaces:** 无新增；`BrandLogoIcon` 绘制逻辑不变。

- [ ] **Step 1: 拷贝资源**（源文件已核实存在，5287 字节）：

```bash
mkdir -p packages/kilo-jetbrains/frontend/src/main/resources/icons/costrict
cp /f/ai-coding/costrict/src/assets/costrict/logo.png \
  packages/kilo-jetbrains/frontend/src/main/resources/icons/costrict/logo.png
```

- [ ] **Step 2: 改引用** — `BrandLogo.kt:43`：

```kotlin
        private const val LOGO_PATH = "/icons/costrict/logo.png"
```

- [ ] **Step 3: 核查 + 测试**

```bash
rg -n "kilo-content" packages/kilo-jetbrains/frontend/src/main   # 期望 0（kilo-content.png 失去引用但不删文件）
./gradlew :frontend:test --tests "ai.kilocode.client.session.ui.EmptySessionPanelTest"
```

Expected: rg 0 命中；测试 PASS。若 `kilo-profile` 在 `frontend/src/main` 仍有引用，原样保留（不新增也不删除）。

- [ ] **Step 4: 编译门禁 + Commit**

```bash
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin
git add -A && git commit -m "assets(jetbrains): 欢迎页 logo 替换为 CoStrict 品牌图（E4，旧资源保留）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 16: 收尾验证

- [ ] **Step 1: 编译门禁**

```bash
export JAVA_HOME='C:\Users\demo\.jdks\ms-21.0.12.1'
./gradlew :shared:compileKotlin :frontend:compileKotlin :backend:compileKotlin :cs-cloud:compileKotlin
```

- [ ] **Step 2: 本计划触达的模块测试**

```bash
./gradlew :frontend:test --tests "ai.kilocode.client.session.*" --tests "ai.kilocode.client.settings.*" \
  --tests "ai.kilocode.client.plugin.*" --tests "ai.kilocode.client.migration.*" --tests "ai.kilocode.client.ui.*" \
  --tests "ai.kilocode.client.NotificationGroupIsolationTest" --tests "ai.kilocode.client.actions.*"
./gradlew :backend:test --tests "ai.kilocode.backend.migration.*" --tests "ai.kilocode.backend.rpc.*"
```

Expected: 全 PASS。

- [ ] **Step 3: 入口不可达核查清单**（在 `packages/kilo-jetbrains/` 下，前 5 条期望 0 命中，后 4 条期望存在）：

```bash
rg -n 'id="ai\.kilocode\.jetbrains\.settings\.profile"' frontend/src/main/resources                 # 0
rg -n '"Kilo Code"|"Kilo\.CodeReview"' frontend/src/main                                            # 0
rg -n 'Costrict' frontend/src/main/resources/messages/KiloBundle*.properties                        # 0（大小写敏感）
rg -n "You are Kilo" backend/src/main                                                               # 0
rg -n "kilo\.ai/docs" frontend/src/main                                                             # 0
rg -n 'text="cs-cloud"' frontend/src/main/resources/kilo.jetbrains.frontend.xml                     # 1（CliGroup）
rg -n 'cloudInfo\.isHidden = true' frontend/src/main/kotlin/ai/kilocode/client/session/history/HistoryPanel.kt   # 1
rg -n 'ShowDownloading -> Unit' frontend/src/main/kotlin/ai/kilocode/client/session/ui/ConnectionPanel.kt        # 1
git status --short                                                                                   # 期望空（工作区干净）
```

- [ ] **Step 4: 手动冒烟（可选，环境允许时）** — `./gradlew runIde`：① Settings → Tools → Costrict 无 Profile 子页且搜索 "Profile" 无命中；② History 面板只有本地标签；③ 空会话右上角无账号胶囊；④ 恢复菜单（连接失败时）无 Reinstall（cs-cloud 生效时）；⑤ Settings → Models 无 Small Model 行；⑥ 迁移向导（如可触发）无 "& Autocomplete" 字样。

- [ ] **Step 5: 汇报** — 按 verification-before-completion 输出命令与结果；逐条对照 spec"入口不可见且不可达"标准；遗留事项转验证轮清单（Agent Manager gh 横幅、worktree 会话端到端实测、死代码彻底清理另立专项时 D1/语言包裁剪可重提）。
