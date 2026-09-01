# /hub 与 Code Review 迁移方案复核：能否更薄

> 日期：2026-08-31
> 复核对象：[csc-hub-migration-evaluation.md](csc-hub-migration-evaluation.md)、[code-review-jetbrains-reuse-plan.md](code-review-jetbrains-reuse-plan.md)，以及后续计划 `docs/superpowers/plans/2026-08-31-jetbrains-hub-panel.md`
> 复核方法：对 `F:\ai-coding\csc`、`F:\ai-coding\cs-cloud`（Go daemon）、`F:\ai-coding\costrict`（VSCode 扩展参考）、`F:\ai-coding\kilocode` 四仓源码逐一验证两份文档的前提假设
> 结论：**两个方案都还能更小。** 共同的关键是：cs-cloud Go daemon 已经自带一套面向 UI 的 favorites facade，且 csc 后台同步已自动完成版本更新与孤儿清理——`/hub` 的 v1 可以做到**零 csc / 零 cs-cloud 改动**。

---

## 0. 结论速览

| 方案 | 原方案规模 | 更薄的方案 | 新规模（估） | 后端改动 |
|---|---|---|---|---|
| `/hub` JetBrains 面板 | 评估文档：csc 扩 REST + Swing 面板 1500-2500 行；hub plan 已收敛到 ~450 行（含 csc 仓 Task 1） | **v1 直连 daemon 现有 facade，砍掉全部 csc 侧改动** | ~350-420 行 Kotlin + 测试 | **零** |
| Code Review P0 | ~1200-1500 行 / 1.5 人周（含 ~600 行 Review 工具窗口） | **P0-lite：通知 + 内置 Markdown 预览做首版展示**，工具窗口顺延 | ~550-650 行 | 零（原本就是零） |

方向性判断不变：评估文档的方案 A（引擎留 csc、IDE 只做薄 UI）是对的，本次复核只是把它推到极限——**连"扩展 daemon REST"这一步都可以推迟**。

---

## 1. 本次复核新确立的八个事实

| # | 事实 | 证据 | 对方案的影响 |
|---|---|---|---|
| F1 | **cs-cloud Go daemon 已内置 favorites facade**：`GET /api/v1/agents/favorites`（daemon 自己分页拉云端 + 代理 csc serve 取本地状态 + 按 slug 合并，返回四类条目带 `status`/`localPath`）、`POST /api/v1/agents/favorites/{id}/load\|unload` | `cs-cloud/internal/localserver/server.go:224-226`、`favorites_handler.go:281-328` | 列表/启停**今天就可用**，v1 无需 csc 新路由 |
| F2 | **`CsCloudRoute` 未映射路径原样透传**（`else -> path`），OkHttp base 即 daemon 根地址 | `kilo-jetbrains/cs-cloud/.../CsCloudRoute.kt:108` | 插件直调 `/api/v1/agents/favorites` 即命中，**零路由改动、零 OpenAPI 重新生成** |
| F3 | **csc 后台同步已自动更新版本并清理孤儿**：每轮同步对 `Active && hasUpdate` 的条目自动执行 `updateFavoriteItem`；墓碑/孤儿自动卸载 | `csc/src/costrict/favorite/sync.ts:250-261`、`setup.ts:586-594` | 手动"更新 / 立即同步"按钮是 UX 增强，不是功能必需，v1 可砍 |
| F4 | **kilo-jetbrains 明确禁用 JCEF**（split-mode/远程开发不可用），必须纯 Swing | `packages/kilo-jetbrains/AGENTS.md:275` | 复用 costrict webview（React）的路线在 JetBrains 端不成立 |
| F5 | **costrict 的 hub UI 是预编译 cs-cloud-ui bundle，源码不在 costrict 仓**；webview 通过 `proxyFetch` 桥直调 daemon REST（含 `/agents/favorites`）；可复用资产只有"daemon 发现/生命周期/fetch 桥"这套宿主机制 | `costrict/src/scripts/build-cs-cloud-ui-static.mjs`、`src/core/cs-cloud/extension/{csCloudService,html,sidebarProvider}.ts` | 参考价值 = **验证 daemon-first 是既定架构**（连云端 UI 都跑在这套 facade 上）；对应宿主机制 kilo-jetbrains 已有（`CsCloudConnectionService` 等），无可搬运组件 |
| F6 | **costrict 的 code review 就是"发 prompt + 监听本地报告"**：FileSystemWatcher + 5s 轮询 `code-review_result/review-report.json`（含稳定性判断）；issue-manager REST 仅用于状态回写与历史 | `costrict/src/core/costrict/code-review/cloudReviewReportWatcher.ts:13-119`、`api.ts:95-126` | 佐证复用计划的本地报告契约；kilo 用已有 SSE `host.file.*` 替代新建 watcher，**优于先例** |
| F7 | **csc serve 无 OpenAPI / 无鉴权**（有意为之）；**daemon 有**：`/api/v1/openapi.json` + `/api/v1/docs`（Swagger），favorites 端点带 swag 注解 | `csc/src/server/server.ts:32-33`；`cs-cloud/internal/localserver/server.go:181-182`、`favorites_handler.go:272-280` | 评估文档 §6"csc 侧补 OpenAPI schema、IDE 用生成客户端"的建议应改为：**消费 daemon 已有 facade**（且开发期可直接看 daemon Swagger） |
| F8 | **daemon `/api/v1/*` 挂 `apiAuth`**（bearer key），插件 OkHttp 已统一带此凭证；未登录时 favorites 返回 401 `AUTH_REQUIRED` | `cs-cloud/internal/localserver/server.go:135`、`favorites_handler.go:288`；`CsCloudEndpointResolver.kt` + `CsCloudHttpClients.kt` | 走 daemon facade 比直连 csc serve（auth 禁用）**更安全**；401 可直接映射现有 `ConnectionErrorCode.UNAUTHORIZED` → 复用 ConnectionPanel 的登录恢复动作 |

---

## 2. `/hub`：v1 零后端改动的具体形态

### 2.1 数据面（全部是现有端点）

```
CloudHubConfigurable（Swing，SettingsListPanel 子类）
        │  KiloAppRpcApi（split-mode RPC，既有链路）
        ▼
CsCloudFavoritesApi（手写 OkHttp，~100 行）
        │  GET /api/v1/agents/favorites        ← 一次拉全四类，客户端按 itemType 分组
        │  POST /api/v1/agents/favorites/{id}/load
        │  POST /api/v1/agents/favorites/{id}/unload
        ▼
cs-cloud Go daemon（favorites_handler：云端分页拉取 + 本地状态合并 + token 自动刷新）
        ├── 代理 → csc serve `/global/favorite/skills*`（引擎 + 状态文件）
        └── 直连 → cloud-api（列表分页）
```

### 2.2 与 hub plan（5 个 Task）的差异

| hub plan Task | 处置 | 理由 |
|---|---|---|
| Task 1：csc 仓扩 REST（update / sync / storeUrl，62→~110 行 + 4 测试） | **整体删除（v1）** | F1/F3：列表启停已有 facade；版本更新与同步由后台同步自动完成；storeUrl 是展示性增强 |
| Task 2：DTO + 手写 client | 保留，改调 daemon facade 路径 | F2：透传即达；响应是裸数组 `{id,slug,name,description,itemType,status,localPath}`，DTO 更简单（3 个即可） |
| Task 3：RPC 穿线 | 保留但缩为 **3 个方法**（list/load/unload） | split-mode 架构要求，无法省；update/sync 方法随 Task 1 删除 |
| Task 4：Settings 页 UI | 保留，去掉"立即更新 / 立即同步"，保留状态徽标（Cloud/Downloaded/Active/Unloaded）与启停 cell | `SettingsListPanel` + `ActiveListView` 是既有单文件成页范式（参考 `SkillsConfigurable`） |
| Task 5：回归 + 手册 | 保留，去掉跨仓联动项 | **单仓 PR，无 csc/cs-cloud 版本耦合** |

新增规模估算：DTO ~40 + client ~100 + client 挂载 ~10 + RPC 穿线 ~80 + Configurable ~160 + XML/i18n ~35 ≈ **420 行**（+~150 行测试）。

### 2.3 v1 明确放弃、何时补回

| 放弃项 | 丢失的体验 | 补回成本（远期） |
|---|---|---|
| `hasUpdate` / `localVersion` 徽标 | 看不到"有新版本"提示；但 F3 保证 5 分钟级自动更新 | **~15 行 Go**：`favorites_handler.go` 的 `favoriteItem` 结构体加 3 字段并在 `mergeFavorites` 透传（csc serve 本来就返回这些字段，是 Go 结构体丢弃了）——远小于在 csc 加新路由 |
| 手动"立即更新/同步" | 无手动触发 | 需要 csc 仓加 2 条路由（hub plan Task 1 原案） |
| store 链接 | 无"去云端收藏更多"入口 | csc list 响应加字段或 Go 侧拼 URL；或直接链到 Web Console（daemon `/favorites` 命令所属的 Web UI） |

### 2.4 陷阱与注意

1. **不要带 `?type=` 调列表**：Go handler 把 type 原样传给 cloud-api，而云端 itemType 用 `subagent`（不是 `agent`，见 `favorites_handler.go:25-30` 的映射表），语义易错。v1 一次拉全量、客户端按 `itemType`（已是 skill/agent/command/mcp 方言）分组，还省 3 次请求。
2. **csc 列表有 30s 共享缓存**（`favorite.ts` FAVORITE_LIST_CACHE_TTL_MS）：load/unload 后立即刷新列表可能读到旧状态；启停动作本身会 `notifyCloudFavoritesChanged` 重置同步退避，UI 侧对刚操作的条目做本地乐观更新即可。
3. **未登录场景**：daemon 返回 401 `AUTH_REQUIRED` → 映射现有 `UNAUTHORIZED` 恢复链（ConnectionPanel 已有登录入口），不要新做登录 UI。
4. `costrict-for-jetbrains`（F:\ai-coding 下另一仓）是"整个嵌 VSCode 扩展宿主 + JCEF"的重方案（即评估文档所指 classic 模式），无 favorites 代码且与 kilo-jetbrains 禁 JCEF 约束冲突，**不构成参考路线**。

---

## 3. Code Review：P0-lite（更薄的可选首版）

复用计划的 R1-R7 链路经源码验证**全部属实**（prompt RPC、SSE `host.file.*` 按 workspace 过滤、VFS 刷新、报告契约本机实存）。但 P0 中 ~600 行的 Review 工具窗口可以再拆一层：

### P0-lite（~550-650 行）：触发 → 通知 → 预览

| 交付项 | 复用点 | 新增（估） |
|---|---|---|
| 触发 Action（按钮/右键） | R7 Action 范式 | ~200 行 |
| `/review <args>` 参数构建 | R1 | ~120 行（v0 可只做"审查当前变更"一种，再砍 ~60） |
| prompt 注入 | R2 `KiloSessionRpcApi.prompt` | ~80 行 |
| 报告监听 + 完成判定 | R3/R5：在既有 `host.file.*` 处理里加 `code-review_result/` 过滤 + 稳定性确认 | ~180 行 |
| **完成通知**：解析 JSON 数出各级缺陷数 + 展示 `conclusion`（质量评分摘要） | 现有通知组 | ~50 行 |
| **打开报告**：`review-report.md` 交给 IDE 内置 Markdown 预览（该产物本就是完整人类可读报告，本机已验证） | JetBrains 内置 Markdown（复用计划 P2 本就计划用它） | ~20 行 |

**P0-lite 与 P0 的差异本质**：展示从"自建结构化 issue 列表（过滤/分组/跳转行号）"降级为"Markdown 全文 + 摘要通知"。触发与感知链路（最有复用价值、也最有风险的部分）完全一致。

**取舍建议**：issue 列表 + 点击跳转是这套功能的核心价值，不建议长期停留在 lite 版。P0-lite 的合理定位是：
- 更早可交付的冒烟里程碑（1 周内验证 §5 风险表 #1/#2 两个"开工前实测"项）；
- 或团队希望先拿到"审查进行中/完成"的闭环感知、把交互面板排到下迭代时的选择。

若人力允许直接做 P0，则维持复用计划原案——其本身已经是薄方案（costrict 同等功能是 ~1200 行 webview + ~4900 行 service）。

---

## 4. 两份文档需要修正/更新的表述

| 文档位置 | 原表述 | 修正 |
|---|---|---|
| 评估文档 §3 方案 A："csc 侧改造：扩 REST……约 150-300 行" | 前提是 daemon REST 不够用 | daemon facade 已够 v1 用（F1）；"扩 REST"推迟到需要手动更新/同步时 |
| 评估文档 §3/§4："现有 3 条路由（csc serve）" | 视角停在 csc serve 层 | IDE 实际应消费 **daemon 层** `/api/v1/agents/favorites`（F1），三层关系：IDE → Go daemon（facade，带鉴权/合并）→ csc serve（引擎） |
| 评估文档 §4 Phase 1 估算 "JetBrains 面板 1500-2500 行 / 1-2 周" | 按全新 Swing 面板估 | SettingsListPanel 范式下 ~400 行级（hub plan 已体现，本复核再砍 Task 1） |
| 评估文档 §6 "建议 csc 侧给 favorites 路由补 OpenAPI schema，IDE 用生成客户端" | 前提：csc 有 OpenAPI 机制 | csc 无 OpenAPI（F7）；daemon 才有（`/api/v1/docs`）。手写薄 client + `ignoreUnknownKeys` 是当前合理选择 |
| hub plan Task 1 | csc 仓加 3 条路由 + storeUrl | v1 删除（见 §2.2），Task 3 同步缩为 3 方法 |
| 复用计划 §4 P0 | ~1450 行含 600 行工具窗口 | 增补 P0-lite 拆层选项（§3）；其余判断（issue-manager 降级为可选、SSE 替代 watcher、`host.git.commit` 做 P2）经 costrict 先例与源码双重验证，**维持不变** |

---

## 5. 建议执行顺序

1. **Hub v1（零后端改动）**：按 §2.2 裁剪 hub plan 执行，单仓 PR；先在真实 daemon 上 curl 验证 `GET /api/v1/agents/favorites` 响应形状（带 bearer key，或直接开 `{daemon}/api/v1/docs` Swagger 看）。
2. **Code Review**：按复用计划 P0 开工；若希望更早见效或先排雷，先交付 P0-lite（§3），把两个"开工前实测"风险项（会话内 `/review` 行为、`host.file.*` 覆盖面）跑掉。
3. **Hub v1.1（条件触发）**：需要版本徽标时先做 Go 侧 ~15 行字段透传；需要手动更新/同步时再启动 csc 仓 Task 1（跨仓协作，参照 cs-cloud-mcp 的三仓先例走变更流程）。
4. VSCode 端（评估文档 Phase 2）结论不变：仍是产品决策（是否接受 csc daemon 为运行时）；costrict 的先例（整套 UI 跑在 daemon facade 上、扩展侧只做宿主）说明该路线在 VSCode 端同样成立，但 kilo-vscode 现为零 cs-cloud 代码，属独立立项。
