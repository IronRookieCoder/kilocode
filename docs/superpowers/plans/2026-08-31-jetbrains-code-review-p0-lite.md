# CoStrict 代码审查 P0-lite（JetBrains 端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 kilo-jetbrains 插件里以零后端改动接入 csc 的 `/review`：三个触发入口 → 当前会话直发命令 → SSE 感知报告落盘 → 完成通知（缺陷计数）→ 打开内置 Markdown 预览。

**Architecture:** 审查引擎与报告落盘全部留在 csc/cs-cloud（不动）。backend 在既有全局 SSE 监听（`host.file.*`）上加一个报告 watcher（路径过滤 + size/mtime 稳定性确认 + 解析计数），经新增的 1 个 app 级 RPC Flow 推给 frontend；frontend 三入口 Action 通过 `SessionController.command` 复用既有会话派发链路（无会话自动创建），project 级 Notifier 按目录过滤后弹通知并打开 `review-report.md`。

**Tech Stack:** Kotlin、kotlinx.coroutines（SharedFlow/Flow）、kotlinx.serialization、IntelliJ Platform（fleet.rpc `@Rpc`、AnAction、NotificationGroupManager、ProjectActivity）、kotlin.test（JUnit Platform）。

**Spec:** `docs/superpowers/specs/2026-08-31-jetbrains-code-review-p0-lite-design.md`（本计划以其为基准；spec 的取舍理由、数据契约、风险表都在那边，执行前先读）。

## Global Constraints

- 单仓交付：只改 `packages/kilo-jetbrains/` 下文件；**禁止**改 csc / cs-cloud / 仓库其他 packages。
- 全部改动在 `packages/kilo-jetbrains` 目录下执行 gradle（该目录自带 `gradlew`）：`./gradlew <task>`。
- 文件 I/O（读报告、stat 稳定性）只在 backend 模块；frontend 只经 RPC 拿 DTO（split-mode 兼容）。
- 解析本地报告 JSON 一律 `ignoreUnknownKeys = true`（skill 版本前向兼容）。
- UI 命名统一「CoStrict 代码审查」；新增 bundle key 必须**同时**写 `KiloBundle.properties` 和 `KiloBundle_zh_CN.properties`（其余 17 语言按标准资源束回退英文，不写）。
- 纯 Swing 语义：不引入 JCEF/Compose/Webview（本计划只有 Action/通知，天然满足）。
- 只跑本计划列出的测试类与编译任务，**不做全量测试**（用户既定偏好）。
- 每个任务独立提交；commit message 用 `feat(jetbrains): …` / `test(jetbrains): …` 风格，结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。当前分支 `feat-cs-plugin`，只 `git add` 各任务 Files 列表里的路径。
- 手动验收前置：本机已装 csc（`~/.costrict/skills/review/SKILL.md` 存在）、能启动 cs-cloud daemon；调试开关参考 `docs/development-debugging-guide.md`。

## 与 spec 的实现差异（已核准的落地取舍，执行时不要"纠正"回 spec 原文）

1. **Action 可用性**：spec D3 写"无活动会话时禁用"；实现改为 **"Kilo 工具窗口未打开时禁用"**。理由：`SessionController.command` 的 `dispatch` 在 `sid == null` 时会自动创建会话（`SessionController.kt:320`），工具窗口打开即有 blank 会话，更严的判定是多余代码。
2. **RPC 穿线文件数**：spec 沿用 hub spec 的"4 文件"说法；实际 app 级 RPC 只有 3 处（`KiloAppRpcApi` 接口 / backend `KiloAppRpcApiImpl` / frontend 测试 `FakeAppRpcApi`）+ backend service 持有 flow，**不存在**第 4 个默认实现文件（已源码核实）。
3. **未知 severity**：spec §7 要求"不计入 + warn log"；实现为纯静态 `parse` 里静默跳过（不加日志），deviation 无行为影响。
4. **md 缺失降级**：spec §7 写"打开 `code-review_result/` 目录"；实现改为**降级打开 `review-report.json`（文本）**——`FileEditorManager.openFile` 不能打开目录，打开 json 同样给出落点。
5. **手动输入 `/review`**（不经 Action，直接在聊天框敲）同样会收到完成通知——spec 未禁止，是 SSE 全局监听的预期增益。
6. **D4 的 `command` vs `prompt` 回退**：实现固定走 `command`（经 `SessionController.command` → `KiloSessionRpcApi.command`）。若手动验收 ⑨（风险 #1）发现 skill 语义不符，回退方案是把 Action 的发送改成 `prompt` 直发 `/review <args>` 文本——只动 `SessionUi.sendCommand` 一处，架构不变；届时在 spec §10 附注记录结论后再改。

## File Structure（改动全景）

```
packages/kilo-jetbrains/
├── shared/src/main/kotlin/ai/kilocode/rpc/
│   ├── KiloAppRpcApi.kt                                  [改] +1 方法 cloudReviewReports()
│   └── dto/CodeReviewReportDto.kt                        [新] RPC DTO
├── backend/src/main/kotlin/ai/kilocode/backend/
│   ├── app/KiloBackendAppService.kt                      [改] +flow 字段、SSE when 分支接 watcher
│   ├── app/KiloBackendWorkspaceRefresh.kt                [复用] paths() 事件路径解析（不改）
│   └── app/codereview/CodeReviewReportWatcher.kt         [新] 检测+稳定性+解析
├── backend/src/test/kotlin/ai/kilocode/backend/app/codereview/
│   └── CodeReviewReportWatcherTest.kt                    [新]
├── frontend/src/main/kotlin/ai/kilocode/client/
│   ├── app/KiloChatAccess.kt                             [新] 工具窗口 manager/workspace 暴露
│   ├── KiloToolWindowFactory.kt                          [改] setup() 写入 KiloChatAccess
│   ├── session/SessionManager.kt                         [改] +sendCommand 默认空实现
│   ├── session/SessionHost.kt                            [改] +sendCommand 覆写
│   ├── session/SessionUi.kt                              [改] +sendCommand 转发 controller
│   ├── actions/CodeReviewActions.kt                      [新] 基类 + 三个 Action
│   ├── codereview/ReviewTarget.kt                        [新] 目标→args 纯逻辑
│   ├── codereview/CodeReviewNotifier.kt                  [新] 订阅+通知+打开报告
│   ├── codereview/CodeReviewStartupActivity.kt           [新] 项目启动挂载
│   └── testing（test 源集）/…/FakeAppRpcApi.kt            [改] +fake flow
├── frontend/src/test/kotlin/ai/kilocode/client/codereview/
│   ├── ReviewArgsTest.kt                                 [新]
│   └── CodeReviewNotifierTest.kt                         [新]
└── frontend/src/main/resources/
    ├── kilo.jetbrains.frontend.xml                       [改] 3 action、popup 锚点、通知组、startup activity
    └── messages/KiloBundle.properties + KiloBundle_zh_CN.properties [改] +16 key×2
```

依赖顺序：Task 1（RPC 面）→ Task 2（watcher 核心）→ Task 3（backend 接线）；Task 4、5 独立；Task 6 依赖 4+5；Task 7 依赖 1（收尾）。

---

### Task 1: RPC 面——DTO + 接口方法 + backend flow + Fake

纯穿线任务，无可单测的逻辑，验证方式是编译 + 既有测试不破。

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/rpc/dto/CodeReviewReportDto.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/rpc/KiloAppRpcApi.kt`（`captureTelemetry` 之后，约 122 行处）
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt:173`（`workspaces` val 之后加 flow 字段）
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloAppRpcApiImpl.kt:59-61`（`state()` 覆写之后）
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/testing/FakeAppRpcApi.kt:80-83`（`state()` 覆写之后）

**Interfaces:**
- Consumes: 无（首个任务）。
- Produces（后续任务按此签名使用）:
  - `@Serializable data class CodeReviewReportDto(directory, reportJsonPath, reportMdPath, marker: String, highCount/middleCount/lowCount: Int, qualitySummary: String?, degraded: Boolean)`（包 `ai.kilocode.rpc.dto`）
  - `KiloAppRpcApi.cloudReviewReports(): Flow<CodeReviewReportDto>`
  - `KiloBackendAppService.codeReviewReports: SharedFlow<CodeReviewReportDto>`
  - `FakeAppRpcApi.cloudReviewReports: MutableSharedFlow<CodeReviewReportDto>`（测试可发射）

- [ ] **Step 1: 新建 DTO**

```kotlin
package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/**
 * A completed CoStrict code-review report detected in a workspace.
 *
 * Emitted by the backend once `<workspace>/code-review_result/review-report.json`
 * appears and its size/mtime stays stable. [degraded] means the report could not
 * be parsed with the known schema (unknown `report` marker or malformed JSON) —
 * the Markdown twin is still worth opening.
 */
@Serializable
data class CodeReviewReportDto(
    val directory: String,
    val reportJsonPath: String,
    val reportMdPath: String,
    val marker: String,
    val highCount: Int,
    val middleCount: Int,
    val lowCount: Int,
    val qualitySummary: String? = null,
    val degraded: Boolean = false,
)
```

- [ ] **Step 2: 接口加方法**（`KiloAppRpcApi.kt`，`captureTelemetry` 声明后、接口收尾 `}` 前；同时文件头 import 区加 `import ai.kilocode.rpc.dto.CodeReviewReportDto`）

```kotlin
    /** Observe completed CoStrict code-review reports written under open workspaces. */
    suspend fun cloudReviewReports(): Flow<CodeReviewReportDto>
```

- [ ] **Step 3: backend service 持有 flow**（`KiloBackendAppService.kt`，在 `val workspaces = …`（约 173 行）之后；确认 import 区有 `kotlinx.coroutines.flow.MutableSharedFlow` 与 `kotlinx.coroutines.flow.asSharedFlow`，缺则补；`CodeReviewReportDto` 的 import 也补）

```kotlin
    private val _codeReviewReports = MutableSharedFlow<CodeReviewReportDto>(extraBufferCapacity = 32)
    val codeReviewReports: SharedFlow<CodeReviewReportDto> get() = _codeReviewReports.asSharedFlow()
```

（Task 3 才会有发射方；本任务只建通道。）

- [ ] **Step 4: backend RPC 实现委托**（`KiloAppRpcApiImpl.kt`，`state()` 覆写之后；import `ai.kilocode.rpc.dto.CodeReviewReportDto`）

```kotlin
    override suspend fun cloudReviewReports(): Flow<CodeReviewReportDto> = app.codeReviewReports
```

- [ ] **Step 5: Fake 补齐**（`FakeAppRpcApi.kt`，`state()` 覆写之后；import `ai.kilocode.rpc.dto.CodeReviewReportDto`，`MutableSharedFlow` 已有 import）

```kotlin
    val cloudReviewReports = MutableSharedFlow<CodeReviewReportDto>(extraBufferCapacity = 32)

    override suspend fun cloudReviewReports(): Flow<CodeReviewReportDto> {
        assertNotEdt("cloudReviewReports")
        return cloudReviewReports
    }
```

- [ ] **Step 6: 编译验证**（接口改动会波及接口的全部实现者，编译即验证完整性）

Run: `cd packages/kilo-jetbrains && ./gradlew :shared:compileKotlin :backend:compileKotlin :frontend:compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/rpc/dto/CodeReviewReportDto.kt \
        packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/rpc/KiloAppRpcApi.kt \
        packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt \
        packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloAppRpcApiImpl.kt \
        packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/testing/FakeAppRpcApi.kt
git commit -m "feat(jetbrains): add code review report RPC surface

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: backend watcher 核心逻辑（TDD）

可测的纯逻辑：事件路径匹配、报告解析计数、防伪标记降级。稳定性循环（suspend）不在单测范围，由 Task 7 手动验收覆盖。

**Files:**
- Test: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/codereview/CodeReviewReportWatcherTest.kt`
- Create: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/codereview/CodeReviewReportWatcher.kt`

**Interfaces:**
- Consumes: `KiloBackendWorkspaceRefresh.paths(root: Path, event: SseEvent): List<Path>`（companion `internal`，同模块可见）、`SseEvent(type: String, data: String)`（`ai.kilocode.backend.app`）、Task 1 的 `CodeReviewReportDto`。
- Produces:
  - `CodeReviewReportWatcher.reportPath(root: Path, event: SseEvent): Path?`（companion internal）
  - `CodeReviewReportWatcher.parse(body: String, directory: String, jsonPath: Path): CodeReviewReportDto`（companion internal）
  - `CodeReviewReportWatcher.qualityLine(conclusion: String?): String?`（companion internal）
  - `CodeReviewReportWatcher(cs, roots: () -> List<String>, emit: (CodeReviewReportDto) -> Unit, log).handle(event: SseEvent)`（实例，Task 3 用）

- [ ] **Step 1: 写失败测试**

```kotlin
// kilocode_change - new file
package ai.kilocode.backend.app.codereview

import ai.kilocode.backend.app.SseEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeReviewReportWatcherTest {
    @Test
    fun `report path matches only the report json under code-review_result`() {
        val root = Files.createTempDirectory("kilo-review-root").toRealPath()
        val dir = root.resolve("code-review_result").also { Files.createDirectories(it) }
        val report = dir.resolve("review-report.json")
        Files.writeString(report, "{}")

        val hit = CodeReviewReportWatcher.reportPath(
            root,
            SseEvent("host.file.created", """{"type":"host.file.created","properties":{"path":"${json(report.toString())}"}}"""),
        )
        val miss = CodeReviewReportWatcher.reportPath(
            root,
            SseEvent("host.file.updated", """{"type":"host.file.updated","properties":{"path":"${json(root.resolve("src/Main.kt").toString())}"}}"""),
        )
        val outside = CodeReviewReportWatcher.reportPath(
            root,
            SseEvent("host.file.created", """{"type":"host.file.created","properties":{"path":"${json(root.resolveSibling("other").resolve("code-review_result").resolve("review-report.json").toString())}"}}"""),
        )

        assertEquals(report, hit)
        assertNull(miss)
        assertNull(outside)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `parse counts chinese severities and extracts quality line`() {
        val root = Files.createTempDirectory("kilo-review-root").toRealPath()
        val report = root.resolve("review-report.json")
        val body = """
            {
              "report": "I-AM-CODE-REVIEW-REPORT-V1",
              "issues": [
                {"severity": "高", "type": "逻辑缺陷", "location": "src/a.ts:1-2", "title": "a"},
                {"severity": "中", "type": "静态缺陷", "location": "src/b.ts:3", "title": "b"},
                {"severity": "低", "type": "内存问题", "location": "src/c.ts:5-9", "title": "c"},
                {"severity": "未知", "type": "静态缺陷", "location": "src/d.ts:1", "title": "d"}
              ],
              "conclusion": "### CoStrict评审摘要\n**质量评分**：良好\n**评分详情**：\n- 安全漏洞：高 0 / 中 0 / 低 0",
              "extra": {"future": "field"}
            }
        """.trimIndent()

        val dto = CodeReviewReportWatcher.parse(body, root.toString(), report)

        assertEquals("I-AM-CODE-REVIEW-REPORT-V1", dto.marker)
        assertEquals(1, dto.highCount)
        assertEquals(1, dto.middleCount)
        assertEquals(1, dto.lowCount)
        assertEquals("质量评分：良好", dto.qualitySummary)
        assertEquals(report.resolveSibling("review-report.md").toString(), dto.reportMdPath)
        assertTrue(!dto.degraded)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `parse degrades on unknown marker and malformed json`() {
        val root = Files.createTempDirectory("kilo-review-root").toRealPath()
        val report = root.resolve("review-report.json")

        val unknownMarker = CodeReviewReportWatcher.parse(
            """{"report": "V2-OTHER", "issues": []}""",
            root.toString(),
            report,
        )
        val malformed = CodeReviewReportWatcher.parse("not json", root.toString(), report)

        assertTrue(unknownMarker.degraded)
        assertTrue(malformed.degraded)
        assertEquals("", unknownMarker.marker)
        root.toFile().deleteRecursively()
    }

    private fun json(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd packages/kilo-jetbrains && ./gradlew :backend:test --tests "ai.kilocode.backend.app.codereview.CodeReviewReportWatcherTest"`
Expected: 编译 FAIL（`CodeReviewReportWatcher` 未定义）

- [ ] **Step 3: 实现 watcher**

```kotlin
// kilocode_change - new file
package ai.kilocode.backend.app.codereview

import ai.kilocode.backend.app.KiloBackendWorkspaceRefresh
import ai.kilocode.backend.app.SseEvent
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.CodeReviewReportDto
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Detects completed CoStrict code-review reports from `host.file.*` SSE events.
 *
 * The review skill writes `<workspace>/code-review_result/review-report.json`
 * (plus a human-readable `review-report.md` twin). Completion is judged by the
 * file, not by session state: once the JSON's size and mtime stay unchanged for
 * [STABLE_WINDOW_MS] it is parsed once and emitted. Repeated events for the
 * same (size, mtime) pair are deduplicated.
 */
class CodeReviewReportWatcher(
    private val cs: CoroutineScope,
    private val roots: () -> List<String>,
    private val emit: (CodeReviewReportDto) -> Unit,
    private val log: KiloLog,
) {
    companion object {
        const val REPORT_MARKER = "I-AM-CODE-REVIEW-REPORT-V1"
        const val REPORT_SUFFIX = "code-review_result/review-report.json"
        const val REPORT_JSON = "review-report.json"
        const val REPORT_MD = "review-report.md"
        const val STABLE_WINDOW_MS = 1_000L
        const val MAX_STABLE_TRIES = 30

        internal val json = Json { ignoreUnknownKeys = true }

        /** The report JSON path inside [root] this event refers to, or null. */
        internal fun reportPath(root: Path, event: SseEvent): Path? =
            KiloBackendWorkspaceRefresh.paths(root, event).firstOrNull { path ->
                path.fileName?.toString() == REPORT_JSON &&
                    path.toString().replace('\\', '/').endsWith(REPORT_SUFFIX)
            }

        /** Parse the report body into a DTO; degraded when marker/schema unknown. */
        internal fun parse(body: String, directory: String, jsonPath: Path): CodeReviewReportDto {
            val md = jsonPath.resolveSibling(REPORT_MD)
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return degraded(directory, jsonPath, md)
            val marker = obj["report"]?.jsonPrimitive?.contentOrNull ?: ""
            val issues = runCatching { obj["issues"]?.jsonArray }.getOrNull()
            if (marker != REPORT_MARKER || issues == null || !issues.all { it is JsonObject }) {
                return degraded(directory, jsonPath, md)
            }
            var high = 0
            var middle = 0
            var low = 0
            for (issue in issues) {
                when (issue.jsonObject["severity"]?.jsonPrimitive?.contentOrNull) {
                    "高" -> high += 1
                    "中" -> middle += 1
                    "低" -> low += 1
                }
            }
            return CodeReviewReportDto(
                directory = directory,
                reportJsonPath = jsonPath.toString(),
                reportMdPath = md.toString(),
                marker = marker,
                highCount = high,
                middleCount = middle,
                lowCount = low,
                qualitySummary = qualityLine(obj["conclusion"]?.jsonPrimitive?.contentOrNull),
                degraded = false,
            )
        }

        /** Extract the `**质量评分**：X` line from the conclusion Markdown, if present. */
        internal fun qualityLine(conclusion: String?): String? =
            conclusion
                ?.lineSequence()
                ?.firstOrNull { it.contains("质量评分") }
                ?.replace(Regex("[*#]"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun degraded(directory: String, jsonPath: Path, md: Path) = CodeReviewReportDto(
            directory = directory,
            reportJsonPath = jsonPath.toString(),
            reportMdPath = md.toString(),
            marker = "",
            highCount = 0,
            middleCount = 0,
            lowCount = 0,
            qualitySummary = null,
            degraded = true,
        )
    }

    private data class Stat(val size: Long, val mtime: Long)

    private val lastEmitted = ConcurrentHashMap<String, Stat>()

    /** Handle one `host.file.*` event; schedules stability confirmation on the app scope. */
    fun handle(event: SseEvent) {
        if (event.type != "host.file.created" && event.type != "host.file.updated") return
        for (rootPath in roots()) {
            val root = runCatching { Path.of(rootPath).toAbsolutePath().normalize() }.getOrNull() ?: continue
            val report = reportPath(root, event) ?: continue
            cs.launch {
                try {
                    val stat = awaitStable(report) ?: return@launch
                    val key = report.toString()
                    if (lastEmitted[key] == stat) return@launch
                    val body = runCatching { Files.readString(report) }.getOrNull()
                    if (body == null) {
                        log.warn("kind=codereview-report read=false path=$key")
                        return@launch
                    }
                    val dto = parse(body, root.toString(), report)
                    lastEmitted[key] = stat
                    log.info(
                        "kind=codereview-report ok=true degraded=${dto.degraded} " +
                            "high=${dto.highCount} middle=${dto.middleCount} low=${dto.lowCount} path=$key",
                    )
                    emit(dto)
                } catch (e: Exception) {
                    log.warn("kind=codereview-report failed path=$report", e)
                }
            }
        }
    }

    /** Poll size+mtime until stable across one window; null when absent or never settles. */
    private suspend fun awaitStable(path: Path): Stat? {
        repeat(MAX_STABLE_TRIES) {
            val first = stat(path) ?: return null
            delay(STABLE_WINDOW_MS)
            val second = stat(path) ?: return null
            if (first == second) return first
        }
        return null
    }

    private fun stat(path: Path): Stat? = runCatching {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        Stat(attrs.size(), attrs.lastModifiedTime().toMillis())
    }.getOrNull()
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd packages/kilo-jetbrains && ./gradlew :backend:test --tests "ai.kilocode.backend.app.codereview.CodeReviewReportWatcherTest"`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/codereview/CodeReviewReportWatcher.kt \
        packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/codereview/CodeReviewReportWatcherTest.kt
git commit -m "feat(jetbrains): add code review report watcher core

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: backend 接线——SSE when 分支接 watcher

**Files:**
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt:904-908`（when 分支）、`:173` 附近（watcher 字段）

**Interfaces:**
- Consumes: Task 2 的 `CodeReviewReportWatcher(cs, roots, emit, log)`、Task 1 的 `_codeReviewReports`。
- Produces: backend 侧链路闭环——`host.file.created/updated` 事件 → 解析报告 → `_codeReviewReports.tryEmit`。

- [ ] **Step 1: 加 watcher 字段**（`KiloBackendAppService.kt`，紧接 Task 1 加的 `_codeReviewReports`/`codeReviewReports` 之后；import 区补 `ai.kilocode.backend.app.codereview.CodeReviewReportWatcher` 与 `com.intellij.openapi.project.ProjectManager`——后者文件已用，确认即可）

```kotlin
    private val codeReviewWatcher = CodeReviewReportWatcher(
        cs = cs,
        roots = {
            ProjectManager.getInstance().openProjects
                .filterNot { it.isDefault }
                .mapNotNull { it.basePath }
        },
        emit = { report ->
            if (!_codeReviewReports.tryEmit(report)) {
                log.warn("kind=codereview-report emit=false directory=${report.directory}")
            }
        },
        log = log,
    )
```

- [ ] **Step 2: 改 when 分支**（`startWatchingGlobalSseEvents` 内，原 904-908 行）

原代码：

```kotlin
                        "host.file.created",
                        "host.file.updated",
                        "host.file.deleted",
                        "host.file.renamed",
                        "session.idle" -> refreshWorkspaces(event)
```

改为：

```kotlin
                        "host.file.created",
                        "host.file.updated" -> {
                            refreshWorkspaces(event)
                            codeReviewWatcher.handle(event)
                        }
                        "host.file.deleted",
                        "host.file.renamed",
                        "session.idle" -> refreshWorkspaces(event)
```

（`host.file.deleted/renamed` 不喂 watcher：报告只增不改名；VFS 刷新行为保持原样。）

- [ ] **Step 3: 编译 + 既有回归**（确认没碰坏现有 VFS 刷新链路）

Run: `cd packages/kilo-jetbrains && ./gradlew :backend:compileKotlin :backend:test --tests "ai.kilocode.backend.app.KiloBackendWorkspaceRefreshTest"`
Expected: BUILD SUCCESSFUL，既有测试 PASS

- [ ] **Step 4: Commit**

```bash
git add packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt
git commit -m "feat(jetbrains): wire report watcher into global SSE events

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: frontend ReviewTarget——目标 → `/review` args 纯逻辑（TDD）

**Files:**
- Test: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/codereview/ReviewArgsTest.kt`
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/codereview/ReviewTarget.kt`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `sealed interface ReviewTarget` + `Changes` / `File(relativePath)` / `Directory(relativePath)` / `Selection(relativePath, startLine, endLine)`
  - `ReviewTarget.args(target): String`——`""` / `@/path` / `@/path:start-end`
  - `ReviewTarget.relative(path: String, root: String): String?`
  - `ReviewTarget.fromEditor(path: String, root: String, selectionLines: IntRange?): ReviewTarget?`
  - `ReviewTarget.fromView(path: String, isDirectory: Boolean, root: String): ReviewTarget?`

- [ ] **Step 1: 写失败测试**

```kotlin
// kilocode_change - new file
package ai.kilocode.client.codereview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewArgsTest {
    @Test
    fun `args mirror costrict reviewContext syntax`() {
        assertEquals("", ReviewTarget.args(ReviewTarget.Changes))
        assertEquals("@/src/app.ts", ReviewTarget.args(ReviewTarget.File("src/app.ts")))
        assertEquals("@/packages/api", ReviewTarget.args(ReviewTarget.Directory("packages/api")))
        assertEquals("@/src/app.ts:15-18", ReviewTarget.args(ReviewTarget.Selection("src/app.ts", 15, 18)))
    }

    @Test
    fun `relative handles windows separators and rejects outside paths`() {
        assertEquals("src/Main.kt", ReviewTarget.relative("""F:\repo\src\Main.kt""", """F:\repo"""))
        assertEquals("src/Main.kt", ReviewTarget.relative("F:/repo/src/Main.kt", "F:/repo/"))
        assertNull(ReviewTarget.relative("F:/other/Main.kt", "F:/repo"))
        assertNull(ReviewTarget.relative("F:/repo", "F:/repo"))
    }

    @Test
    fun `editor target prefers selection and falls back to file`() {
        assertEquals(
            ReviewTarget.Selection("src/app.ts", 15, 18),
            ReviewTarget.fromEditor("F:/repo/src/app.ts", "F:/repo", 15..18),
        )
        assertEquals(
            ReviewTarget.File("src/app.ts"),
            ReviewTarget.fromEditor("F:/repo/src/app.ts", "F:/repo", null),
        )
        assertNull(ReviewTarget.fromEditor("F:/other/app.ts", "F:/repo", null))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd packages/kilo-jetbrains && ./gradlew :frontend:test --tests "ai.kilocode.client.codereview.ReviewArgsTest"`
Expected: 编译 FAIL（`ReviewTarget` 未定义）

- [ ] **Step 3: 实现**

```kotlin
// kilocode_change - new file
package ai.kilocode.client.codereview

/** Target of a `/review` invocation; args syntax mirrors costrict `reviewContext.ts`. */
sealed interface ReviewTarget {
    /** Bare `/review` — the skill reviews the current working-tree changes. */
    data object Changes : ReviewTarget
    data class File(val relativePath: String) : ReviewTarget
    data class Directory(val relativePath: String) : ReviewTarget
    data class Selection(val relativePath: String, val startLine: Int, val endLine: Int) : ReviewTarget

    companion object {
        /** `@/`-prefixed posix relative path, `@/path:start-end` for selections, empty for changes. */
        fun args(target: ReviewTarget): String = when (target) {
            Changes -> ""
            is File -> "@/${target.relativePath}"
            is Directory -> "@/${target.relativePath}"
            is Selection -> "@/${target.relativePath}:${target.startLine}-${target.endLine}"
        }

        /**
         * Relativize [path] against [root] as a posix path, or null when [path] is not
         * strictly inside [root]. Both inputs may use platform separators.
         */
        fun relative(path: String, root: String): String? {
            val p = path.replace('\\', '/').trimEnd('/')
            val r = root.replace('\\', '/').trimEnd('/')
            if (p == r || !p.startsWith("$r/")) return null
            return p.removePrefix("$r/")
        }

        /** Build a target from editor context; selection wins when non-empty. */
        fun fromEditor(path: String, root: String, selectionLines: IntRange?): ReviewTarget? {
            val rel = relative(path, root) ?: return null
            val lines = selectionLines?.takeIf { !it.isEmpty() } ?: return File(rel)
            return Selection(rel, lines.first, lines.last)
        }

        /** Build a file-or-directory target, or null when [path] is not inside [root]. */
        fun fromView(path: String, isDirectory: Boolean, root: String): ReviewTarget? {
            val rel = relative(path, root) ?: return null
            return if (isDirectory) Directory(rel) else File(rel)
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd packages/kilo-jetbrains && ./gradlew :frontend:test --tests "ai.kilocode.client.codereview.ReviewArgsTest"`
Expected: 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/codereview/ReviewTarget.kt \
        packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/codereview/ReviewArgsTest.kt
git commit -m "feat(jetbrains): add review target arg builder

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: 会话命令通道——工具窗口外可向当前会话发命令

**Files:**
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/SessionManager.kt:28`（`focusPrompt()` 后）
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/SessionHost.kt:91-93`（`focusPrompt` 覆写后）
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/SessionUi.kt:744`（`sendPrompt` 前）
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/app/KiloChatAccess.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/KiloToolWindowFactory.kt:149`（`manager.newSession()` 后）

**Interfaces:**
- Consumes: `SessionController.command(command: String, args: String, files: List<PromptPartDto>)`（`SessionController.kt:297`，EDT only，自动建会话）。
- Produces:
  - `SessionManager.sendCommand(command: String, args: String)`（默认空实现）
  - `KiloChatAccess`（`@Service(PROJECT)`）：`manager: SessionManager?`、`workspaceDirectory: String?`——Task 6/7 消费

- [ ] **Step 1: SessionManager 接口加默认方法**（`focusPrompt()` 声明之后）

```kotlin
    /** Send a slash command with arguments into the current session. No-op when unsupported. */
    fun sendCommand(command: String, args: String) {}
```

- [ ] **Step 2: SessionHost 覆写**（`focusPrompt` 覆写之后；`currentUi()` 与 `@RequiresEdt` 均为本文件既有）

```kotlin
    @RequiresEdt
    override fun sendCommand(command: String, args: String) {
        currentUi()?.sendCommand(command, args)
    }
```

- [ ] **Step 3: SessionUi 转发**（`sendPrompt`（744 行）之前；`controller` 是本文件 157 行的私有字段，`readonly` 是既有属性）

```kotlin
    /** Send a slash command into this session's controller (used by review actions). */
    fun sendCommand(command: String, args: String) {
        if (readonly) return
        controller.command(command, args)
    }
```

- [ ] **Step 4: 新建 KiloChatAccess**

```kotlin
// kilocode_change - new file
package ai.kilocode.client.app

import ai.kilocode.client.session.SessionManager
import com.intellij.openapi.components.Service

/**
 * Project-level access to the chat [SessionManager] created by the Kilo tool window,
 * so actions outside the tool window (editor/project-view popups) can send commands.
 */
@Service(Service.Level.PROJECT)
class KiloChatAccess {
    @Volatile
    var manager: SessionManager? = null

    /** Backend-resolved workspace directory (split-mode safe). */
    @Volatile
    var workspaceDirectory: String? = null
}
```

- [ ] **Step 5: 工具窗口 setup 写入与清理**（`KiloToolWindowFactory.kt` `setup()` 内，`manager.newSession()`（149 行）之后、`val actions = …` 之前；`service`/`Disposer` 均已 import）

```kotlin
            val access = project.service<KiloChatAccess>()
            access.manager = manager
            access.workspaceDirectory = workspace.directory
            Disposer.register(manager) {
                if (access.manager === manager) {
                    access.manager = null
                    access.workspaceDirectory = null
                }
            }
```

（import 区补 `ai.kilocode.client.app.KiloChatAccess`。）

- [ ] **Step 6: 编译 + 既有回归**（SessionUi 动了，跑既有 UI 测试确认无破坏）

Run: `cd packages/kilo-jetbrains && ./gradlew :frontend:compileKotlin :frontend:test --tests "ai.kilocode.client.session.SessionUiLayoutTest"`
Expected: BUILD SUCCESSFUL，既有测试 PASS

- [ ] **Step 7: Commit**

```bash
git add packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/SessionManager.kt \
        packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/SessionHost.kt \
        packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/SessionUi.kt \
        packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/app/KiloChatAccess.kt \
        packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/KiloToolWindowFactory.kt
git commit -m "feat(jetbrains): expose chat sendCommand outside tool window

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: 三个触发 Action + xml 注册 + i18n

**Files:**
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/actions/CodeReviewActions.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/resources/kilo.jetbrains.frontend.xml`（actions 区 + extensions 区）
- Modify: `packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle.properties`（文件末尾追加）
- Modify: `packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle_zh_CN.properties`（文件末尾追加）

**Interfaces:**
- Consumes: Task 4 `ReviewTarget`（`args`/`fromEditor`/`fromView`/`Changes`）、Task 5 `KiloChatAccess`、`SessionManager.sendCommand`。
- Produces: action id `Kilo.CodeReview.Changes` / `Kilo.CodeReview.File` / `Kilo.CodeReview.Directory`（Task 7 不依赖，纯 UI 层）。

- [ ] **Step 1: 实现 Action 类**

```kotlin
// kilocode_change - new file
package ai.kilocode.client.actions

import ai.kilocode.client.app.KiloChatAccess
import ai.kilocode.client.codereview.ReviewTarget
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.session.SessionManager
import ai.kilocode.client.telemetry.Telemetry
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

/** Base action sending `/review <args>` into the current Kilo session. */
abstract class CodeReviewAction(private val surface: String) : AnAction(), DumbAware {
    final override fun actionPerformed(e: AnActionEvent) {
        val access = service<KiloChatAccess>()
        val manager = e.getData(SessionManager.KEY) ?: access.manager ?: return
        val root = access.workspaceDirectory ?: e.project?.basePath ?: return
        val target = target(e, root) ?: ReviewTarget.Changes
        Telemetry.send("Code Review Triggered", mapOf("surface" to surface, "target" to target::class.simpleName))
        manager.sendCommand("review", ReviewTarget.args(target))
    }

    final override fun update(e: AnActionEvent) {
        val available = e.getData(SessionManager.KEY) != null || service<KiloChatAccess>().manager != null
        e.presentation.isEnabled = available
        if (!available) e.presentation.description = KiloBundle.message("codereview.disabled.tooltip")
        customize(e)
    }

    /** Hook for subclasses to adjust text (e.g. file vs selection). */
    protected open fun customize(e: AnActionEvent) {}

    /** Resolve the review target from the action context; null falls back to current changes. */
    protected open fun target(e: AnActionEvent, root: String): ReviewTarget? = null
}

/** Toolbar button: review the current working-tree changes (`/review` with no args). */
class ReviewChangesAction : CodeReviewAction("tool_window") {
    init {
        templatePresentation.text = KiloBundle.message("action.Kilo.CodeReview.Changes.text")
        templatePresentation.description = KiloBundle.message("action.Kilo.CodeReview.Changes.description")
    }
}

/** Editor popup: review this file, or the selection when one exists. */
class ReviewEditorAction : CodeReviewAction("editor_popup") {
    init {
        templatePresentation.text = KiloBundle.message("action.Kilo.CodeReview.File.text")
        templatePresentation.description = KiloBundle.message("action.Kilo.CodeReview.File.description")
    }

    override fun customize(e: AnActionEvent) {
        val hasSelection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.hasSelection() == true
        e.presentation.text = KiloBundle.message(
            if (hasSelection) "action.Kilo.CodeReview.Selection.text" else "action.Kilo.CodeReview.File.text",
        )
    }

    override fun target(e: AnActionEvent, root: String): ReviewTarget? {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.path ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return ReviewTarget.fromEditor(file, root, null)
        val model = editor.selectionModel
        val lines = if (model.hasSelection()) {
            (editor.document.getLineNumber(model.selectionStart) + 1)..(editor.document.getLineNumber(model.selectionEnd) + 1)
        } else {
            null
        }
        return ReviewTarget.fromEditor(file, root, lines)
    }
}

/** Project view popup: review the selected directory. */
class ReviewDirectoryAction : CodeReviewAction("project_view") {
    init {
        templatePresentation.text = KiloBundle.message("action.Kilo.CodeReview.Directory.text")
        templatePresentation.description = KiloBundle.message("action.Kilo.CodeReview.Directory.description")
    }

    override fun target(e: AnActionEvent, root: String): ReviewTarget? {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (!file.isDirectory) return null
        return ReviewTarget.fromView(file.path, isDirectory = true, root = root)
    }
}
```

- [ ] **Step 2: bundle key**（`KiloBundle.properties` 末尾追加）

```properties
# CoStrict code review (P0-lite)
notification.group.codereview=CoStrict Code Review
action.Kilo.CodeReview.Changes.text=CoStrict: Review Current Changes
action.Kilo.CodeReview.Changes.description=Run a CoStrict code review of your current working-tree changes
action.Kilo.CodeReview.File.text=CoStrict: Review This File
action.Kilo.CodeReview.Selection.text=CoStrict: Review Selection
action.Kilo.CodeReview.File.description=Run a CoStrict code review of this file
action.Kilo.CodeReview.Directory.text=CoStrict: Review This Directory
action.Kilo.CodeReview.Directory.description=Run a CoStrict code review of this directory
codereview.completed.title=CoStrict code review completed
codereview.completed.content=High {0} · Medium {1} · Low {2} — {3}
codereview.noissues=No issues found — {0}
codereview.degraded.content=Report generated, but its format was not recognized. Open the Markdown report directly.
codereview.open=View Report
codereview.disabled.tooltip=Open the Kilo Code tool window to start a review
```

- [ ] **Step 3: bundle key（中文）**（`KiloBundle_zh_CN.properties` 末尾追加，键一一对应）

```properties
# CoStrict 代码审查（P0-lite）
notification.group.codereview=CoStrict 代码审查
action.Kilo.CodeReview.Changes.text=CoStrict：审查当前变更
action.Kilo.CodeReview.Changes.description=对当前工作区变更运行 CoStrict 代码审查
action.Kilo.CodeReview.File.text=CoStrict：审查此文件
action.Kilo.CodeReview.Selection.text=CoStrict：审查选区
action.Kilo.CodeReview.File.description=对此文件运行 CoStrict 代码审查
action.Kilo.CodeReview.Directory.text=CoStrict：审查此目录
action.Kilo.CodeReview.Directory.description=对此目录运行 CoStrict 代码审查
codereview.completed.title=CoStrict 代码审查完成
codereview.completed.content=高 {0} · 中 {1} · 低 {2} — {3}
codereview.noissues=未发现缺陷 — {0}
codereview.degraded.content=报告已生成，但格式无法识别，请直接打开 Markdown 报告。
codereview.open=查看报告
codereview.disabled.tooltip=请先打开 Kilo Code 工具窗口再发起审查
```

- [ ] **Step 4: xml 注册**（`kilo.jetbrains.frontend.xml`）

`<extensions>` 区、既有 `<notificationGroup id="Kilo Code" …/>`（16-19 行）之后加：

```xml
        <notificationGroup id="Kilo.CodeReview"
                           displayType="BALLOON"
                           bundle="messages.KiloBundle"
                           key="notification.group.codereview"/>
```

`<actions>` 区、`Kilo.WorktreeSession.Delete`（278-279 行）之后加：

```xml
        <action id="Kilo.CodeReview.Changes"
                class="ai.kilocode.client.actions.ReviewChangesAction"/>

        <action id="Kilo.CodeReview.File"
                class="ai.kilocode.client.actions.ReviewEditorAction">
            <add-to-group group-id="EditorPopupMenu"/>
        </action>

        <action id="Kilo.CodeReview.Directory"
                class="ai.kilocode.client.actions.ReviewDirectoryAction">
            <add-to-group group-id="ProjectViewPopupMenu"/>
        </action>
```

`Kilo.ToolWindowToolbar` 组（223-228 行）内、`<reference ref="Kilo.History"/>` 之后加：

```xml
            <reference ref="Kilo.CodeReview.Changes"/>
```

- [ ] **Step 5: 编译**

Run: `cd packages/kilo-jetbrains && ./gradlew :frontend:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/actions/CodeReviewActions.kt \
        packages/kilo-jetbrains/frontend/src/main/resources/kilo.jetbrains.frontend.xml \
        packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle.properties \
        packages/kilo-jetbrains/frontend/src/main/resources/messages/KiloBundle_zh_CN.properties
git commit -m "feat(jetbrains): add CoStrict code review trigger actions

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: 完成通知 + 打开报告 + 启动挂载 + 手动验收

**Files:**
- Test: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/codereview/CodeReviewNotifierTest.kt`
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/codereview/CodeReviewNotifier.kt`
- Create: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/codereview/CodeReviewStartupActivity.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/main/resources/kilo.jetbrains.frontend.xml`（extensions 区 +1 行）

**Interfaces:**
- Consumes: Task 1 `KiloAppRpcApi.cloudReviewReports()` / `FakeAppRpcApi.cloudReviewReports`、Task 5 `KiloChatAccess.workspaceDirectory`。
- Produces: 交付闭环。无后续任务消费。

- [ ] **Step 1: 写失败测试**（只测纯函数 `matches`——通知/UI 部分依赖 IDE 运行时，走手动验收）

```kotlin
// kilocode_change - new file
package ai.kilocode.client.codereview

import ai.kilocode.rpc.dto.CodeReviewReportDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeReviewNotifierTest {
    @Test
    fun `reports match only the owning workspace directory`() {
        val report = report("C:/work/demo")
        assertTrue(CodeReviewNotifier.matches(report, "C:\\work\\demo"))
        assertTrue(CodeReviewNotifier.matches(report, "C:/work/demo/"))
        assertFalse(CodeReviewNotifier.matches(report, "C:/work/other"))
        assertFalse(CodeReviewNotifier.matches(report, null))
    }

    private fun report(directory: String) = CodeReviewReportDto(
        directory = directory,
        reportJsonPath = "$directory/code-review_result/review-report.json",
        reportMdPath = "$directory/code-review_result/review-report.md",
        marker = "I-AM-CODE-REVIEW-REPORT-V1",
        highCount = 1,
        middleCount = 2,
        lowCount = 3,
    )
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd packages/kilo-jetbrains && ./gradlew :frontend:test --tests "ai.kilocode.client.codereview.CodeReviewNotifierTest"`
Expected: 编译 FAIL（`CodeReviewNotifier` 未定义）

- [ ] **Step 3: 实现 Notifier**

```kotlin
// kilocode_change - new file
package ai.kilocode.client.codereview

import ai.kilocode.client.app.KiloChatAccess
import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.KiloAppRpcApi
import ai.kilocode.rpc.dto.CodeReviewReportDto
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VfsUtilCore
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level collector for completed CoStrict code-review reports.
 *
 * Subscribes to the backend report flow, keeps only reports written under this
 * project's workspace, and shows a balloon with severity counts plus a
 * "View Report" action opening `review-report.md` in the IDE editor
 * (built-in Markdown preview when the Markdown plugin is present).
 */
@Service(Service.Level.PROJECT)
class CodeReviewNotifier internal constructor(
    private val project: Project,
    private val cs: CoroutineScope,
    private val rpc: KiloAppRpcApi? = null,
    private val sink: ((CodeReviewReportDto) -> Unit)? = null,
) {
    /** Platform constructor — resolves RPC from the service container (matches KiloSessionService pattern). */
    constructor(project: Project, cs: CoroutineScope) : this(project, cs, null, null)

    companion object {
        private val LOG = KiloLog.create(CodeReviewNotifier::class.java)
        private const val GROUP = "Kilo.CodeReview"

        /** True when [report] belongs to this project's [directory] (normalized comparison). */
        internal fun matches(report: CodeReviewReportDto, directory: String?): Boolean {
            if (directory == null) return false
            return report.directory.replace('\\', '/').trimEnd('/') ==
                directory.replace('\\', '/').trimEnd('/')
        }

        internal fun content(report: CodeReviewReportDto): String = when {
            report.degraded -> KiloBundle.message("codereview.degraded.content")
            report.highCount + report.middleCount + report.lowCount == 0 ->
                KiloBundle.message("codereview.noissues", report.qualitySummary ?: "")
            else -> KiloBundle.message(
                "codereview.completed.content",
                report.highCount,
                report.middleCount,
                report.lowCount,
                report.qualitySummary ?: "",
            )
        }

        private fun notifyReport(project: Project, report: CodeReviewReportDto) {
            val title = KiloBundle.message("codereview.completed.title")
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP)
                ?.createNotification(title, content(report), NotificationType.INFORMATION)
                ?: Notification(GROUP, title, content(report), NotificationType.INFORMATION)
            notification.addAction(NotificationAction.createSimpleExpiring(KiloBundle.message("codereview.open")) {
                openReport(project, report)
            })
            notification.notify(project)
        }

        private fun openReport(project: Project, report: CodeReviewReportDto) {
            // Prefer the human-readable Markdown twin; fall back to the JSON itself
            // (FileEditorManager cannot open directories).
            val urls = listOf(
                VfsUtilCore.pathToUrl(report.reportMdPath.replace('\\', '/')),
                VfsUtilCore.pathToUrl(report.reportJsonPath.replace('\\', '/')),
            )
            val file = urls.firstNotNullOfOrNull { VirtualFileManager.getInstance().refreshAndFindFileByUrl(it) }
            if (file == null) {
                LOG.warn("kind=codereview-open ok=false md=${report.reportMdPath}")
                return
            }
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private val started = AtomicBoolean(false)
    private val defaultSink: (CodeReviewReportDto) -> Unit = { report -> notifyReport(project, report) }
    private val handler: (CodeReviewReportDto) -> Unit = sink ?: defaultSink

    fun start() {
        if (!started.compareAndSet(false, true)) return
        cs.launch {
            try {
                val api = rpc
                if (api != null) api.cloudReviewReports().collect { onReport(it) }
                else durable { KiloAppRpcApi.getInstance().cloudReviewReports().collect { onReport(it) } }
            } catch (e: Exception) {
                LOG.warn("kind=codereview-subscription failed message=${e.message}", e)
            }
        }
    }

    private fun onReport(report: CodeReviewReportDto) {
        val directory = service<KiloChatAccess>().workspaceDirectory ?: project.basePath
        if (!matches(report, directory)) return
        ApplicationManager.getApplication().invokeLater({ handler(report) }, ModalityState.nonModal())
    }
}
```

（注意：测试构造用 `CodeReviewNotifier(project, cs, fakeRpc, testSink)`；平台构造走双参版本 + `defaultSink`。）

- [ ] **Step 4: 实现启动挂载**

```kotlin
// kilocode_change - new file
package ai.kilocode.client.codereview

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the code-review report notifier when the project opens. */
class CodeReviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CodeReviewNotifier>().start()
    }
}
```

- [ ] **Step 5: xml 注册**（extensions 区，Task 6 加的 `notificationGroup` 之后）

```xml
        <postStartupActivity implementation="ai.kilocode.client.codereview.CodeReviewStartupActivity"/>
```

- [ ] **Step 6: 跑测试确认通过 + 模块内相关测试全绿**

Run: `cd packages/kilo-jetbrains && ./gradlew :frontend:test --tests "ai.kilocode.client.codereview.*" :backend:test --tests "ai.kilocode.backend.app.codereview.*" :backend:test --tests "ai.kilocode.backend.app.KiloBackendWorkspaceRefreshTest"`
Expected: 全部 PASS

- [ ] **Step 7: Commit**

```bash
git add packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/codereview/CodeReviewNotifier.kt \
        packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/codereview/CodeReviewStartupActivity.kt \
        packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/codereview/CodeReviewNotifierTest.kt \
        packages/kilo-jetbrains/frontend/src/main/resources/kilo.jetbrains.frontend.xml
git commit -m "feat(jetbrains): notify on completed code review reports

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 8: 手动验收（spec §8 清单，沙箱 IDE）**

启动：`cd packages/kilo-jetbrains && ./gradlew :frontend:runIde`（split-mode 场景另跑 `runIdeSplitMode`）。逐项核对：

- [ ] ① 工具窗口按钮「CoStrict: 审查当前变更」→ 会话运行可见（busy）；
- [ ] ② 报告写盘后约 1-2s 出 balloon，计数与 `code-review_result/review-report.json` 一致；
- [ ] ③ [查看报告] 打开 `review-report.md` 且渲染 Markdown 预览；
- [ ] ④ 编辑器选区右键 / 文件右键 / Project 视图目录右键，各自 args 正确（会话里回显 `/review @/src/app.ts:15-18` 等）；
- [ ] ⑤ 审查中点会话 abort → 无误报通知；报告已写出再 abort → 通知照常；
- [ ] ⑥ 手工把 `review-report.json` 改成坏 JSON 再触发 → degraded 文案 + 仍能打开 md；
- [ ] ⑦ 双项目窗口各跑一次 → 只有归属窗口弹通知；
- [ ] ⑧ 中英文界面各验一遍文案；
- [ ] ⑨ **风险 #1 实测**：会话内 `/review` 的输出目录 = 项目根 `code-review_result/`（不是 home/其他）——结论回填 spec §10 附注；
- [ ] ⑩ **风险 #2 实测**：确认 `host.file.*` 事件覆盖新建子目录与 `.draft-*` 中间产物（backend 日志 `kind=codereview-report` 是否出现）——结论回填 spec §10 附注，供工具窗口 spec 引用；
- [ ] ⑪ split-mode（`runIdeSplitMode`）下重复 ①③，确认 RPC flow 与打开文件均正常。

---

## 规模对账

新增/修改实现行数（估）：DTO ~25、watcher ~160、KiloBackendAppService ~30、ReviewTarget ~65、Action ~105、KiloChatAccess ~20、Notifier ~150、StartupActivity ~13、xml ~20、i18n 16×2、接口/Fake/穿线 ~25 ≈ **620 行**；测试 ~190 行。与 spec §5 的 560-620（实现）估算一致（测试略超，可接受）。
