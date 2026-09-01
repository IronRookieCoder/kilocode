# CoStrict 代码审查 P0-lite（JetBrains 端）设计

> 日期：2026-08-31
> 状态：范围已确认（仅 P0-lite：完成通知 + 内置 Markdown 预览），待实施
> 依据：[code-review-jetbrains-reuse-plan.md](../../code-review-jetbrains-reuse-plan.md)（R1-R7 链路源码验证）、[hub-and-review-minimal-change-evaluation.md](../../hub-and-review-minimal-change-evaluation.md) §3（P0-lite 拆层，本 spec 的直接基础）
> 关系：本 spec 只覆盖 P0-lite；Review 工具窗口（issue 列表 / 过滤 / 点击跳转行号，原复用计划 P0 的 ~600 行窗口与 P1 交互项）为后续独立 spec，不顺延进本文件。实施计划以本 spec 为准另行新建。

---

## 1. 背景与目标

把 csc 的 `/review`（五阶段审查 skill：目标筛选 → 缺陷检测 → 对抗验证 → Wave 管理 → 生成报告）以最薄方式接进 kilo-jetbrains：

- **零后端改动**：审查引擎、prompt 通道、报告落盘全部骑现有链路；daemon、csc、issue-manager 均不动。
- **单仓交付**：全部改动限于 `packages/kilo-jetbrains`。
- 复用关键事实（均已源码/本机验证）：
  - `/review` skill 由 csc skill 同步机制安装并持续更新（`~/.costrict/skills/review/SKILL.md`，带 `.version`）——R1，插件只需发一条命令；
  - slash 命令通道已有：`KiloSessionRpcApi.command` / `prompt`（`shared/src/main/kotlin/ai/kilocode/rpc/KiloSessionRpcApi.kt:84-87`，直通 `KiloBackendChatManager`）——R2；
  - daemon 对 agent 写盘推 `host.file.*` SSE；backend 已有全局监听并按 workspace 过滤刷新 VFS（`backend/.../KiloBackendAppService.kt:878-920` `startWatchingGlobalSseEvents` → `KiloBackendWorkspaceRefresh`）——R3/R5；
  - 报告产物本机实存且双格式：`<项目根>/code-review_result/review-report.json`（防伪标记 `I-AM-CODE-REVIEW-REPORT-V1`）+ `review-report.md`（完整人类可读报告，本仓根目录 2026-08-26 样例已验证）；
  - costrict 先例即"发 prompt + 监听本地报告"（5s 轮询 + 稳定性判断，`cloudReviewReportWatcher.ts`）；kilo 用既有 SSE 替代新建 watcher，优于先例（复核文档 F6）。

**P0-lite 定位**（复核文档 §3 取舍建议）：更早可交付的冒烟里程碑——交付"审查完成"闭环感知，同时在开发期实测两项开工前风险（会话内 `/review` 行为、`host.file.*` 覆盖面），为后续工具窗口 spec 排雷。

## 2. 决策记录

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| D1 | v1 形态 | P0-lite：触发 → 完成通知 → 内置 Markdown 预览；**不做工具窗口** | 需方 2026-08-31 选定；issue 列表/跳转是核心价值但随工具窗口另行立项 |
| D2 | 展示载体 | 完成通知（各级缺陷计数 + 质量评分摘要）+ IDE 内置 Markdown 预览打开 `review-report.md` | md 产物本就是完整报告（本机验证）；复用计划 P2 本就计划用内置 Markdown |
| D3 | 触发会话 | 当前会话直发，不新建专用会话；无活动会话时 Action 禁用 | 复用计划 P0 决策维持；"进行中"状态由会话 busy 自身呈现 |
| D4 | 命令通道 | `KiloSessionRpcApi.command`（command=`review`）；实测语义不符则回退 `prompt` 直发 `/review <args>` 文本 | 两者均已在 `CsCloudRoute` 映射（R2）；回退不改架构 |
| D5 | 报告感知 | 既有 SSE `host.file.*` 过滤 + 稳定性确认（size+mtime 1s 窗口）；文件 I/O 全留 backend | R3/R5 + costrict watcher 思路（F6）；split-mode 约束（AGENTS.md） |
| D6 | UI 命名 | 「CoStrict 代码审查」，独立通知组 | 避开既有 CLI `/review` 与 Kilo Cloud Code Reviews 语义冲突（风险 #6） |

## 3. 架构与数据流

```
触发 Action（工具窗口按钮 / 编辑器右键 / Project 视图右键）
        │  ReviewTarget → /review <args>
        ▼
KiloSessionRpcApi.command（既有链路，当前会话直发）
        ▼
cs-cloud daemon → csc review skill（五阶段，零改动）
        │  写 <项目根>/code-review_result/review-report.{json,md}
        ▼  SSE host.file.created / host.file.updated
KiloBackendAppService.startWatchingGlobalSseEvents（既有全局监听，+1 分支）
        ▼
CodeReviewReportWatcher（backend 新建：路径过滤 → 稳定性确认 → 解析计数）
        ▼  SharedFlow（新增 RPC 方法 cloudReviewReports()）
CodeReviewNotifier（frontend 新建：按项目根过滤）
        ├─ 完成通知：高 N / 中 N / 低 N + 质量评分摘要
        └─ [查看报告] → FileEditorManager 打开 review-report.md（内置 Markdown 预览）
```

- 既有 VFS 刷新链路（`KiloBackendWorkspaceRefresh`）**不动**：报告文件落盘后自然出现在项目视图。
- 完成判定以**文件**为准，不依赖会话状态：文件何时稳定何时通知（应对"agent 先回复总结后写盘"的次序差异）。
- 通知去重：以 `(path, size, mtime)` 为键，同一报告版本的重复事件只通知一次。
- 事件流不可用时（极端情况）接受"无通知"降级——报告文件与项目视图仍然可见，用户可手动打开；不新建轮询基础设施（兜底轮询属工具窗口 spec 的决策范围）。

## 4. 数据契约

### 输入：`<项目根>/code-review_result/review-report.json`（skill 模板，已本机验证）

```json
{
  "report": "I-AM-CODE-REVIEW-REPORT-V1",
  "issues": [{
    "severity": "高|中|低",
    "type": "静态缺陷|安全漏洞|逻辑缺陷|内存问题",
    "location": "src/app.ts:15-18",
    "title": "…", "analysis": "…", "impact": "…",
    "issue_code": "…", "fix_code": "…"
  }],
  "conclusion": "### CoStrict评审摘要\n**质量评分**：优秀|良好|需改进\n…"
}
```

- v1 只消费 `severity`（计数）与 `conclusion`（摘要）；**不解析 `location`**（无跳转需求，风险 #3 天然免疫），其余字段 `ignoreUnknownKeys` 忽略。
- 版本判据：`report` 标记 ≠ `I-AM-CODE-REVIEW-REPORT-V1` → 未知版本降级（见 §7）。
- 完成判据：`host.file.*` 事件命中 `code-review_result/review-report.json` + size+mtime 在 1s 窗口内稳定。

### RPC DTO（新增，kotlinx.serialization，`ignoreUnknownKeys = true`）

```kotlin
CodeReviewReport(
    directory,            // 项目根（frontend 按此过滤多窗口）
    reportJsonPath, reportMdPath,
    marker,               // 防伪标记原值（未知版本时用于降级判断）
    highCount, middleCount, lowCount,
    qualitySummary,       // 从 conclusion 提取的质量评分行
    degraded              // true = 未知版本/解析失败，通知走降级文案
)
```

## 5. 组件与改动清单（全部在 kilo-jetbrains 单仓）

**新建：**

| 文件 | 内容 | 估行 |
|---|---|---|
| `shared/src/main/kotlin/ai/kilocode/rpc/dto/CodeReviewReportDto.kt` | 上述 DTO | ~40 |
| `backend/src/main/kotlin/ai/kilocode/backend/app/codereview/CodeReviewReportWatcher.kt` | 路径过滤（命中 `code-review_result/review-report.json`，限 workspace 根内）→ 稳定性确认 → 解析计数 → emit SharedFlow；纯逻辑部分 companion 静态可测 | ~150 |
| `frontend/src/main/kotlin/ai/kilocode/client/codereview/ReviewTarget.kt` | 变更集 / 文件 / 目录 / 选区 → `/review <args>`（语法对齐 costrict `common/reviewContext.ts`） | ~110 |
| `frontend/src/main/kotlin/ai/kilocode/client/codereview/CodeReviewAction.kt` | 三入口共用的 Action 基类 + enabled 判定（须有活动会话） | ~140 |
| `frontend/src/main/kotlin/ai/kilocode/client/codereview/CodeReviewNotifier.kt` | 订阅 flow、按项目根过滤、通知构建与 [查看报告] 打开预览 | ~90 |

**修改（小增量）：**

| 位置 | 改动 |
|---|---|
| `KiloBackendAppService.startWatchingGlobalSseEvents` | +~5 行：`host.file.*` 事件在送 VFS 刷新的同时交给 watcher（刷新分支不动） |
| RPC 穿线 4 文件（`KiloAppRpcApi` / `KiloConnection` 默认实现 / `KiloBackendAppService` / `KiloAppRpcApiImpl`）+ `FakeAppRpcApi` | 各 +1 方法 `cloudReviewReports(): Flow<CodeReviewReportDto>`（纯委托，范式同 hub spec） |
| `kilo.jetbrains.frontend.xml` | 3 个 action 注册 + 独立通知组 + projectActivity（挂载 Notifier） |
| `KiloBundle.properties` + `KiloBundle_zh_CN.properties` | ~15 个 key；其余语言按标准资源束回退英文 |

合计约 **560-620 行实现 + ~130 行测试**，单仓 PR（与复核文档 §3 的 550-650 估算对齐）。

## 6. 交互设计

- 三入口（同一 Action 基类，args 由 `ReviewTarget` 构建）：
  - Kilo 工具窗口按钮「审查当前变更」→ 默认目标 = 当前变更集；
  - 编辑器右键「CoStrict 审查此文件 / 选区」→ 文件 / `文件:起-止`；
  - Project 视图右键「CoStrict 审查此目录」→ 目录。
- 无活动会话：Action 禁用，tooltip 说明需先打开 Kilo 会话。
- 触发后：会话 busy 即"进行中"，v1 无独立进度 UI。
- 完成通知（独立通知组，避开既有通知渠道）：标题「CoStrict 代码审查完成」，正文「高 N · 中 N · 低 N — <质量评分>」；动作 **[查看报告]**。
- [查看报告] → `FileEditorManager` 打开 `review-report.md`，走 IDE 内置 Markdown 预览；无 Markdown 插件时降级为普通文本编辑器打开（不阻塞）。
- 空结果（`issues = []`，本机样例即此形状）：正文显示「未发现缺陷 — <质量评分>」，动作不变。

## 7. 错误处理与边界

| 场景 | 行为 |
|---|---|
| daemon 未运行 / csc 未安装 | `command` RPC 失败走会话既有错误呈现，不发成功通知 |
| 无活动会话 | Action 禁用 + tooltip |
| 用户中止（abort） | 无完成通知；若报告已写出则照常通知（产物有效） |
| json 损坏 / 解析失败 | `degraded` 通知「报告已生成但无法解析」+ 直接打开 md |
| `report` 标记未知（skill 版本演进） | `degraded` 通知「报告格式已更新」+ 打开 md（风险 #4 缓解） |
| md 缺失（仅 json） | 通知动作降级为打开 `code-review_result/` 目录 |
| 同文件重复 / 多次写盘事件 | `(path, size, mtime)` 去重 + 1s 稳定性窗口 |
| 未知 `severity` 枚举 | 不计入三级计数，warn log（与 hub spec 未知 itemType 同范式） |
| 连续两次触发 | 直接发送（用户意图明确）；通知按报告版本各自弹出 |
| 多项目窗口 | DTO 带 `directory`，Notifier 按当前项目根过滤（与 hub spec 同语义） |
| 审查超长运行 | 无插件侧超时；进行中 = 会话 busy；用户可随时 abort |

## 8. 测试策略（只测相关模块）

- `CodeReviewReportWatcherTest`（纯逻辑 + 临时目录）：路径命中（workspace 根内/外、子目录、`security-review_result/` 不命中）、稳定性判定（size/mtime 变化窗口）、防伪标记校验与未知版本降级、severity 中文枚举计数、md/json 配对缺失、去重键。
- `ReviewTargetTest`：变更集 / 文件 / 目录 / 选区 → args 字符串；边界（未保存文档取 VirtualFile 路径、选区跨行）。
- RPC 链不建新测试（纯委托），由 `FakeAppRpcApi` 支撑前端测试（Notifier 目录过滤可选用 Fake flow 测）。
- 手动验收清单：① 触发后会话运行可见；② 完成通知计数与报告一致；③ 打开 md 预览；④ abort 无误报；⑤ 损坏 json 降级；⑥ 双窗口隔离；⑦ 中英文文案；⑧ **风险 #1/#2 实测记录**（会话内 `/review` 输出目录 = workspace；`host.file.*` 覆盖新建子目录与 `.draft-*` 中间产物）——结论回填本 spec 附注，供工具窗口 spec 引用。

## 9. 非目标（Future Work）

- **Review 工具窗口**：任务态面板、issue 列表（严重度/类型过滤、按文件分组）、点击定位行号——核心价值所在，独立 spec（原复用计划 P0 的 ~600 行窗口 + P1 交互项整体归入）。
- AI 修复（`fix_code` 送会话执行）、编辑器标注（`RangeHighlighter`）、issue 本地处置（忽略/已修）。
- `/security-review` 模式（`security-review_result/` 目录，其余同链路）。
- commit 后自动触发（SSE `host.git.commit` 事件 → 通知询问）。
- 专用 review 会话选项、进行中精细进度。
- issue-manager 集成（跨设备历史 / 云端持久化；新增远端依赖，单独立项）。
- daemon 侧 review 完成事件（`conversation.review.completed` 类）。

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| #1 会话内 `/review` 行为与 csc 独立运行不一致（输出目录 ≠ workspace） | 开发首日实测（CLI-free 调试开关见 `docs/development-debugging-guide.md`）；实测同时决定 D4 的 `command` vs `prompt` 回退 |
| #2 `host.file.*` 未覆盖新建子目录 / 新文件 | 实测确认；不足则 backend 加轻量 NIO watch 兜底（split-mode 兼容）——此项同时为工具窗口 spec 排雷 |
| #3 `location` 格式不合规（LLM 生成） | v1 不解析 location（无跳转），天然免疫；风险随工具窗口 spec 处理 |
| #4 skill 版本演进改 schema | `report` 标记做版本判断 + 降级打开 md |
| #5 长时间运行与复用会话相互干扰 | 当前会话直发 + 通知明示来源；专用会话为后续选项 |
| #6 命名冲突（CLI `/review`、Kilo Cloud Code Reviews） | UI 统一「CoStrict 代码审查」 |
| 报告晚于会话回复写盘 | 完成判定以文件稳定性为准、不挂会话状态；`host.file.updated` 重复事件再次触发判定 |
