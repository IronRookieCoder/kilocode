# Cloud Hub 面板 v1（JetBrains 端）设计

> 日期：2026-08-31
> 状态：已与需求方逐节确认，待实施
> 依据：[csc-hub-migration-evaluation.md](../../csc-hub-migration-evaluation.md)（方案选型）、[hub-and-review-minimal-change-evaluation.md](../../hub-and-review-minimal-change-evaluation.md)（最小化复核，本 spec 的直接基础）
> 关系：本 spec 取代 `docs/superpowers/plans/2026-08-31-jetbrains-hub-panel.md` 作为实施基线——原 plan 的 Task 1（csc 仓扩 REST）与"手动更新/立即同步"UI 已从 v1 裁剪；实施计划将以本 spec 为准另行新建。

---

## 1. 背景与目标

把 csc 的 `/hub`（Costrict 云端收藏：skill / agent / command / mcp 四类条目的启用管理）以最薄的方式带进 kilo-jetbrains 插件：

- **引擎与数据面零改动**：收藏引擎、后台同步、认证全部留在 csc / cs-cloud daemon，IDE 只做展示与操作转发。
- **单仓交付**：全部改动限于 `packages/kilo-jetbrains`，无跨仓版本耦合。
- 复用关键事实（已源码验证）：
  - Go daemon 已有 UI 级 facade：`GET/POST /api/v1/agents/favorites*`（`cs-cloud/internal/localserver/server.go:224-226`），自带云端分页拉取、本地状态合并、token 刷新；
  - `CsCloudRoute.kt:108` 未映射路径原样透传，OkHttp base 即 daemon 根，直调即达；
  - csc 后台同步每轮自动更新 `Active && hasUpdate` 条目并清理孤儿（`csc/src/costrict/favorite/sync.ts:250-261`），手动更新无功能必要性。

## 2. 决策记录

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| D1 | 方案路线 | 引擎留 csc，IDE 薄面板（评估文档方案 A 的极限化） | 避免引擎移植（~7000 行）与 auth.json 双客户端竞争 |
| D2 | v1 功能边界 | **纯零后端**：列表 + 启停 + 状态徽标 | 版本更新由后台同步自动完成；单仓 PR |
| D3 | 面板形态 | Settings 页内嵌（Settings → Tools → Kilo Code → Agent Behavior → Cloud Hub） | 完全套用 `SkillsConfigurable` + `SettingsListPanel` 既有范式，改动最小；hub 是低频管理面 |
| D4 | scope 语义 | 仅全局（`~/.costrict` 世界），无项目级 | 与 csc `/hub` 语义对齐 |
| D5 | 技术栈约束 | 纯 Swing，禁 JCEF/Compose/UI DSL v2 | `packages/kilo-jetbrains/AGENTS.md` 规定（split-mode 兼容） |

## 3. 架构与数据流

```
CloudHubConfigurable（frontend 模块，Swing Settings 页）
        │  KiloAppRpcApi（split-mode RPC，既有链路，+3 方法）
        ▼
KiloAppRpcApiImpl → KiloBackendAppService → CsCloudConnectionService
        │  favoritesCall 包装：异常 → ok=false DTO
        ▼
CsCloudFavoritesApi（cs-cloud 模块，手写 OkHttp，CsCloudHttpClients.favoritesClient）
        │  GET  /api/v1/agents/favorites
        │  POST /api/v1/agents/favorites/{id}/load
        │  POST /api/v1/agents/favorites/{id}/unload
        ▼
cs-cloud Go daemon favorites_handler（现有，零改动）
        ├── 代理 → csc serve（favorite 引擎，现有零改动）
        └── 直连 → cloud-api（分页 + Bearer）
```

- **列表**：页面打开或手动刷新时拉取一次；**不带 `?type=`**（Go handler 会把 type 原样传给 cloud-api，而云端 agent 类型实际为 `subagent`，语义易错），客户端按 `itemType` 分四个 section，一次请求省三次。
- **启停**：以 POST 响应中返回的 `item`（引擎给出的启停后权威状态）更新对应行——规避 csc 列表 30s 共享缓存（`favorite.ts` `FAVORITE_LIST_CACHE_TTL_MS`）的滞后。
- **生效语义**：启停写入全局 `~/.costrict` 世界，daemon 为单点权威；JetBrains 端会话运行时即该世界，条目启用后天然生效，插件不做额外失效通知（引擎侧已处理 config 注册与实例刷新）。

## 4. 数据契约（daemon facade，现有端点）

### GET /api/v1/agents/favorites

成功（200）——裸 JSON 数组：

```json
[{
  "id": "abc123",
  "slug": "my-skill",
  "name": "My Skill",
  "description": "…",
  "itemType": "skill",        // skill | agent | command | mcp（daemon 已归一化）
  "status": "Active",         // Cloud | Downloaded | Active | Unloaded
  "localPath": "C:\\Users\\…\\.costrict\\skills\\my-skill"   // 可空
}]
```

失败：401 未登录（`AUTH_REQUIRED`）、5xx（daemon 标准 envelope）。

### POST /api/v1/agents/favorites/{id}/load 与 /unload

`{id}` 为 slug 或云端 id（引擎两者都接受）。成功：透传 csc 引擎响应 `{"success": true, "item": {…同上形状…}}`；404：条目不存在。

### 客户端 DTO（`ignoreUnknownKeys = true`，前向兼容）

```kotlin
CloudFavoriteItem(id, slug, name, description, itemType, status, localPath?)
CloudFavoritesResult(ok, items = [], errorCode?, errorMessage?)
CloudFavoriteActionResult(ok, item?, errorCode?, errorMessage?)
// errorCode: UNAUTHORIZED | UNAVAILABLE | NOT_FOUND | INTERNAL
```

## 5. 组件与改动清单（全部在 kilo-jetbrains 单仓）

**新建：**

| 文件 | 内容 | 估行 |
|---|---|---|
| `shared/src/main/kotlin/ai/kilocode/rpc/dto/CloudFavoritesDto.kt` | 上述 3 个 DTO | ~40 |
| `cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudFavoritesApi.kt` | list/load/unload；kotlinx.serialization；异常→`ok=false`（401→UNAUTHORIZED，连接态异常→UNAVAILABLE，4xx not found→NOT_FOUND，其余→INTERNAL） | ~100 |
| `frontend/src/main/kotlin/ai/kilocode/client/settings/hub/CloudHubConfigurable.kt` | `AgentBehaviorConfigurableBase` + `SettingsListPanel` 子类（范式参照 `SkillsConfigurable`） | ~160 |

**修改（小增量）：**

| 位置 | 改动 |
|---|---|
| `CsCloudHttpClients.kt` | +第 4 个 `favoritesClient`（120s 超时：daemon 列表内部需分页拉云端） |
| `CsCloudConnectionService.closeTransport()` | +1 行关闭 favoritesClient |
| RPC 穿线 4 文件（`KiloAppRpcApi` / `KiloConnection` 默认实现 / `KiloBackendAppService` / `KiloAppRpcApiImpl`） | 各 +3 方法（cloudFavorites / loadCloudFavorite / unloadCloudFavorite），非 cs-cloud provider 降级 `ok=false` |
| `FakeAppRpcApi`（测试桩） | +3 方法 |
| `kilo.jetbrains.frontend.xml` | 注册 `applicationConfigurable`，置于 Agent Behavior 下 Skills 之后 |
| `AgentBehaviorConfigurable.kt` | 子页链接表 +1 行 |
| `KiloBundle.properties` + `KiloBundle_zh_CN.properties` | ~20 个 key；其余 17 语言按标准资源束回退英文 |

合计约 **420 行实现 + ~150 行测试**，单仓 PR。

## 6. 交互设计

- 四 section：Skills / Agents / Commands / MCP（按 `itemType` 分组；未知类型过滤 + warn log，section 结构保持稳定）。
- 组内排序：状态优先级（Active 最前）→ 名称字母序。
- 状态徽标：Active=Primary 强调色；Downloaded / Cloud / Unloaded 次级样式。
- 行 cell 动作矩阵：
  - 状态 ∈ {Cloud, Downloaded, Unloaded} → 显示 **Enable**（引擎的 load 对三态均可完成安装+激活；Downloaded 为已落盘未激活）；
  - 状态 ∈ {Active} → 显示 **Disable**。
- 顶部工具栏：搜索框（`SettingsListPanel` 自带）、手动刷新按钮。
- 所有拉取/操作走 `SettingsListPanel` 既有 `busy` + 后台线程模式，EDT 只渲染。

## 7. 错误处理与边界

| 场景 | 行为 |
|---|---|
| 未登录（401 `AUTH_REQUIRED`） | `ok=false` + UNAUTHORIZED → 面板错误态，指向既有登录入口（`ConnectionPanel` 的 `Kilo.LoginCsCloud` 恢复语义），**不新建登录 UI** |
| daemon 未运行 / csc 未安装 | fetch 即失败 → UNAVAILABLE 错误态，恢复动作指向既有 `Kilo.StartCsCloud` / `Kilo.InstallCsc` |
| 网络 / 5xx / 超时 | INTERNAL + 通用文案，`KiloNotifications` 既有通知组提示 |
| 云端无收藏且本地无记录 | 空态文案 + 指引（前往 Costrict 云端收藏条目） |
| 启停目标已不存在（404） | 行标记移除 + 通知提示，下次刷新自愈 |
| 多项目窗口并发 | 全局语义，UI 不做跨窗口实时同步，另一窗口刷新自然可见 |
| 未知 `itemType` | 过滤 + warn log |

## 8. 测试策略（只测相关模块）

- `HubRowLogicTest`（纯逻辑）：分组、排序、cell 可用性矩阵、未知类型过滤。
- `CsCloudFavoritesApiTest`（MockWebServer）：正常数组 / 401 / 5xx / 404 四形状 + `ignoreUnknownKeys` 前向兼容（响应多未知字段不崩）。
- RPC 链不建新测试（纯委托），由 `FakeAppRpcApi` 支撑前端测试。
- 手动验收清单：① 未装 csc 错误态；② daemon 停止错误态；③ 未登录指引；④ 四类条目分组渲染；⑤ 启用 skill → Active 徽标 + 新会话可用；⑥ 卸载 → Unloaded + 会话失效；⑦ 双项目窗口一致性；⑧ 中英文文案。

## 9. 非目标（Future Work）

- 版本更新徽标（`hasUpdate`/`localVersion`）：v1.1 候选——cs-cloud 仓 `favorites_handler.go` 的 `favoriteItem` 结构体 +3 字段并在 `mergeFavorites` 透传（~15 行 Go），届时无需动 csc。
- 手动"立即更新 / 立即同步"：需 csc 仓加 REST 路由（原 hub plan Task 1），条件触发再立项。
- store 云端链接、孤儿管理 UI（后台同步已自动处理孤儿）。
- 项目级 scope。
- VSCode 端 Hub（独立产品决策）。

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| daemon 响应形状与 Go 结构体声明不完全一致（load/unload 实际透传 csc 响应） | `CsCloudFavoritesApiTest` 以真实透传形状 pin；`ignoreUnknownKeys` 兜底 |
| 列表首次拉取慢（云端分页 × 本地合并） | 120s 专用超时 + busy 态 + 页面按需拉取（打开才拉，无轮询） |
| csc 列表 30s 缓存导致刷新滞后 | 被操作行以 POST 响应为权威；其余行可接受，手动刷新兜底 |
| 现网 daemon 版本过旧、无 favorites 端点 | 部署验证步骤：curl `GET {daemon}/api/v1/agents/favorites`（带 bearer key）或查 `{daemon}/api/v1/docs` Swagger；404 时提示升级 csc |
