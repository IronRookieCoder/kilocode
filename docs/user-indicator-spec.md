# user-indicator 指标上报接口规范（user-indicator-spec.md）

> **文档定位**：本文件是 **user-indicator（统一上报采集服务 / 写侧）** 的接口契约规范，面向 **cs-bridge 上报方**（以及后端服务 / 网关 / 拨测）。阅读本规范即可完成**接口对接**（拉取 Spec、构造请求体、上报与错误处理）；上报方内部的采集 / 富化 / 聚合等实现不在本规范范围内。
>
> 内容提炼自 [metrics-implement.md](./metrics-implement.md) 的 §2.3（接口）、§3.1（事件模型）、§4.1（存储 Mapping）、§5.1（Spec）、§5.2（错误码）、§6.1（采集协作）、§7（通讯约定），并与 [metrics.md](./metrics.md) 3.14（通用 Label）对齐。口径以 metrics.md 为准，本文只描述"如何上报"。

---

## 1. 概述

### 1.1 定位与边界

| 项 | 说明 |
| -- | ---- |
| 模块 | **user-indicator** — 统一指标接收与落库 |
| 角色 | **只做采集（写）**；查询由 `costrict-admin` 承担，二者互不依赖 |
| 上报链路 | 客户端（含 cs-bridge）→ user-indicator **外部上报接口 `/events`**；<br/>服务端采集点 → **内部上报接口 `/ingest`**；<br/>user-indicator →（写）→ ES / PG |

> **单一事实源**：指标名、值类型、单位、直方图桶边界、标签白名单均来自 **Spec（主文件 + Drop-in 目录组合）**，user-indicator 对外提供 `GET .../spec`，上报方据此对齐指标口径与前端渲染（见 §5、§6）。Spec 采用**运行时配置**方式管理——由**主文件 + Drop-in 目录多文件组合**而成，支持的指标种类与指标清单可**热更新**、**无需重启**（见 §3.4）。

### 1.2 上报链路

```
客户端（Web / VSCode / CLI —— cs-bridge 运行于客户端；拨测为特殊客户端）
   │ ① POST /user-indicator/api/v2/metrics/events  （外部接口 + 用户 JWT）
   ▼
user-indicator（JWT 校验 → Spec 校验 → 落库）
   │ ② 写
   ▼
ES（costrict_metrics_unified_v1）/ PG

后端服务 / 网关
   │ POST /internal/indicator/api/v2/metrics/ingest  （内部服务凭据）
   ▼
user-indicator
```

> 上报方**只负责按接口契约提交事件**，落库前的校验与处理由 user-indicator 承担（见 §3）。**cs-bridge 与拨测走外部接口**；后端服务 / 网关走**内部接口**（见 §3.3）。

| 环节 | 责任方 | 说明 |
| ---- | ------ | ---- |
| 提交事件 | 上报方（客户端 / 后端服务 / 网关 / 拨测） | 按接口契约构造并提交 `MetricEvent`（见 §3、§4） |
| 身份校验 | user-indicator | 校验 JWT 并绑定 `user_id`（外部接口，见 §2.3） |
| Spec 校验 | user-indicator | 按 Spec 校验指标名 / 值类型 / 单位 / 标签白名单 |
| 幂等去重 | user-indicator | 按 `event_id` 去重，重传不重复计数（见 §6） |
| 落库 | user-indicator | 写入统一索引 / 库表 |

> **幂等可靠**：服务端以 `event_id` 去重，保证重传**不重复计数**（见 §6）。

---

## 2. 接入与鉴权

### 2.1 请求头

| 头 | 取值 | 适用 | 说明 |
| -- | ---- | ---- | ---- |
| `Content-Type` | `application/json` | 全部 | 上报体为 JSON；OTLP 接入为 `application/json`（OTLP/JSON） |
| `Content-Encoding` | `gzip`（可选） | 全部 | 批量体可 gzip 压缩 |
| `Authorization` | `Bearer <JWT>` | 外部**上报**接口 | 客户端 / 第三方插件 / SDK **必带**用户 JWT；服务端**校验 JWT 并据此绑定 `user_id`**，保证每个用户只上报属于自己的指标（见 §2.3） |
| `X-User-Id` | 用户 ID | 全部 | **仅用于链路日志 / trace 标注与冗余校验**；权威 `user_id` 取自 JWT 主体（外部接口）或受信服务端上下文（内部接口），见 §2.3 |
| `X-Client-Id` | 客户端 ID | 全部 | 富化 `client_id` |
| `X-App` | 应用名（如 `csc`） | 全部 | 富化 `application` |
| `X-Trace-Id` | 追踪 ID | 全部 | 富化 `trace_id`；缺省时由后端生成并回写响应头 |
| `X-Internal-Token` / mTLS | 内部凭据 | 内部接口 | 后端服务 / 网关等**服务端**调用内部接口时必带（**cs-bridge / 拨测等客户端走外部接口 + 用户 JWT，不适用此项**） |

> 请求头与事件字段的对应关系见 §4.5 的"日志 / 请求头约定"；`X-Trace-Id` 与 W3C `traceparent` 语义对齐（见 metrics.md 10.3）。

### 2.2 鉴权与多租户

| 项 | 约定 |
| -- | ---- |
| 外部上报 | **必带**用户 JWT；服务端**校验 JWT 并将 `user_id` 绑定为 JWT 主体**，用户只能上报属于自己的指标（越权拒绝，见 §2.3） |
| cs-bridge 上报 | cs-bridge **运行于客户端**，走**外部上报接口**（`/user-indicator/api/v2/metrics/events`），携带**用户 JWT**，与普通客户端一致（见 §2.3） |
| 拨测上报 | 拨测为**特殊客户端**，走**外部上报接口**（`/user-indicator/api/v2/metrics/events`）；`client_type=probe` 并附加 `traffic_source=probe` |
| 内部上报 | 后端服务 / 网关等**服务端**采集点使用**内部服务凭据**（mTLS / internal token） |
| 多租户 | 按 `tenant` 隔离配额，防止单租户刷爆后端；`tenant` 缺省取鉴权上下文 |

### 2.3 JWT 校验与用户隔离（安全约束）

> **安全要求**：user-indicator 对**外部上报接口**请求头中的 `Authorization: Bearer <JWT>` 做**强校验**，确保**每个用户只上报属于自己的指标**，杜绝冒用 / 越权写入。

| 环节 | 行为 |
| ---- | ---- |
| JWT 校验 | 校验签名、有效期、签发方；缺失 / 非法 / 过期 → **401** |
| 身份绑定 | 从 JWT 主体（`sub` / 用户声明）解析 `user_id`，**该值为权威值** |
| 越权判定 | 事件内 `user_id`（或 `labels.user_id`）与 JWT 主体**不一致** → 判为越权，**逐条**计入 `data.errors[]`（HTTP 200）并返回 `metric_ingest.identity_mismatch`，**不落库**；同一请求内**全部**事件均越权时可整批返回 **403** |
| 强制覆盖 | 事件未带 `user_id` 时，服务端**以 JWT 主体补齐**，不依赖客户端自报 |
| 组织 / 环境维度 | `tenant` / `dept.path` / `env` / `traffic_source` 由鉴权上下文**自动富化**，客户端**无需也不应自报**，自报值**不作权威来源** |

> **适用范围**：外部上报接口（`/events`）**必须**执行上述校验——**cs-bridge 与拨测（特殊客户端）同样走此路径**；外部只读接口（`/spec`、`/labels`）可匿名访问；内部接口（`/internal/...`）由内部服务凭据保护，仅**服务端**采集点（后端服务 / 网关）使用，其 `user_id` 由受信的服务端上下文提供。

---

## 3. API 定义

### 3.1 接口总览

| 分组 | 方法 | 完整路径 | 说明 | 主要调用方 |
| ---- | ---- | -------- | ---- | ---------- |
| **外部** | POST | `/user-indicator/api/v2/metrics/events` | 统一指标事件批量上报 | 客户端 / 第三方插件 / SDK / **拨测** |
| **外部** | GET | `/user-indicator/api/v2/metrics/spec` | 拉取指标规范（支持按分类 / 指标名 / 状态筛选） | SDK / 前端 |
| **外部** | GET | `/user-indicator/api/v2/metrics/labels` | 可用标签白名单 | SDK / 前端 |
| **内部** | POST | `/internal/indicator/api/v2/metrics/ingest` | 统一指标事件批量投递（**服务端**采集点：后端服务 / 网关） | 后端服务 / 网关 |
| **内部** | POST | `/internal/indicator/api/v2/metrics/ingest/otlp` | OTLP/JSON 兼容接入（可选，对齐 metrics.md 10.3） | 已接入 OTel 的服务 |
| **内部** | GET | `/internal/indicator/api/v2/metrics/spec` | 内部读取 Spec | 后端服务 / 网关 |
| **内部** | POST | `/internal/indicator/api/v2/metrics/spec/reload` | 运行时**热更新** Spec（指标种类与指标清单） | 运维 / 配置中心回调 |

#### 3.1.1 通用规格（所有接口共享）

**认证模型**

| 接口组 | 认证方式 | 说明 |
| ------ | -------- | ---- |
| 外部**上报**接口（`/events`） | `Authorization: Bearer <JWT>`（**必填**） | 服务端**强校验 JWT** 并据此绑定 `user_id`；用户只能上报**属于自己**的指标，越权返回 **403**（见 §2.3） |
| 外部**只读**接口（`/spec`、`/labels`） | 可选 | 只读指标规范 / 标签白名单，允许匿名访问 |
| 内部接口 | 内部服务凭据（mTLS / `X-Internal-Token`） | 服务间调用，**禁止对外暴露** |

**内容协商与压缩**

| 项 | 约定 |
| -- | ---- |
| 请求 / 响应媒体类型 | `application/json; charset=utf-8` |
| 请求压缩 | 可选 `Content-Encoding: gzip` |
| 字符集 | UTF-8 |

**批量与体积上限**

| 项 | 外部 `/events` | 内部 `/ingest` |
| -- | ------------------------ | -------------- |
| 单批事件数 | ≤ 1000（建议 200~500） | ≤ 5000（建议 500~2000） |
| 请求体（压缩后） | ≤ 5 MiB | ≤ 20 MiB |
| 单事件体积 | ≤ 32 KiB | ≤ 32 KiB |
| 单个 label / metadata 值 | ≤ 1 KiB | ≤ 1 KiB |

> 超过批量 / 体积上限返回 **413** `metric_ingest.payload_too_large`，上报方须**拆分批次**后重试；单个 label 值超过 **1 KiB** 返回 `metric_ingest.invalid_label`（**拒绝该事件**，**不做静默截断**），见 §7。

**超时（建议值）**

| 方向 | 连接 | 读取 | 服务端处理 |
| ---- | ---- | ---- | ---------- |
| 上报方 → 服务端 | 2s | 5s | — |
| 服务端处理 | — | — | 10s（超时按失败处理，未落库可靠重试） |

**HTTP 状态码约定**

| HTTP | 语义 | 上报方动作 |
| ---- | ---- | ---------- |
| 200 | 整批受理成功 / 部分成功 / 全幂等重复 | 解析 `data.errors[]`；无错则从本地缓冲移除 |
| 400 | **整批级**结构非法（JSON 不可解析 / `events` 缺失或非数组） | **不重试**，修正请求体后重发 |
| 401 | 鉴权失败（JWT 缺失 / 非法 / 过期） | 刷新凭据后重试 |
| 403 | 请求级身份越权（整批 `user_id` 与 JWT 主体不一致） | **不重试**，按 JWT 主体修正 `user_id` 后重发 |
| 413 | 批次 / 体积超限 | **拆分**后重试 |
| 429 | 超出限流（按 `tenant` 配额） | 依据 `Retry-After` 退避重试 |
| 500 | 服务端 / 存储失败 | **指数退避**重试 |

> **整批级 vs 逐条级**：仅 **JSON 不可解析 / `events` 缺失或非数组** 属整批级失败，返回 **400**（`metric_ingest.malformed_request`）；**逐条级**错误（单条字段缺失 / 指标未登记 / 标签越权 / 身份越权）**一律返回 HTTP 200**，在 `data.errors[]` 中逐条定位（见 §4.4、§7），以保证批量**部分成功**语义。

**限流与配额**：按 `tenant` 隔离配额，防止单租户刷爆后端；超限返回 **429**（携带 `Retry-After`）。

| 维度 | 默认值（**可配置**） | 说明 |
| ---- | -------------------- | ---- |
| 每 `tenant` 速率 | 1000 事件/秒（突发 ×2） | 令牌桶限流；服务端按 `tenant` 令牌桶计量 |
| 每 `tenant` 单批事件数 | ≤ 1000（外部）/ ≤ 5000（内部） | 见上「批量与体积上限」 |
| 每 `tenant` 并发请求 | 200 | 超出排队 / 快速失败 |
| 单请求体（压缩后） | ≤ 5 MiB（外部）/ ≤ 20 MiB（内部） | 超限 413 |
| 单事件体积 | ≤ 32 KiB | 超限 413 / `payload_too_large` |
| 单 label / metadata 值 | ≤ 1 KiB | 超限 `invalid_label`（逐条拒绝） |

> 以上为**默认建议值**，实际配额由部署配置下发（**可按租户 / 环境覆盖**）；上报方应实现 **429 退避重试**（读 `Retry-After`）与**本地缓冲**，避免限流期丢数据。

**响应头**

| 头 | 说明 |
| -- | ---- |
| `X-Trace-Id` | 请求未带时由服务端生成并回写，保证全链路可串联 |
| `Retry-After` | 429 时可携带，指明建议退避秒数 |

> 业务级错误码统一取 `metric_ingest.*`（见 §7）；鉴权 / 限流等网关层错误码由接入网关返回。

---

### 3.2 外部上报接口

#### `POST /user-indicator/api/v2/metrics/events` · 统一事件上报

**功能**：接收批量指标事件，按 Spec 校验 → 富化（补 `tenant/dept/user`）→ 5min 聚合 → 落库。

**请求规格**

| 项 | 值 |
| -- | -- |
| 方法 | `POST` |
| 路径 | `/user-indicator/api/v2/metrics/events` |
| 认证 | 用户 JWT（**必填**，服务端强校验并绑定 `user_id`，见 §2.3 / 3.1.1） |
| 内容类型 / 编码 | `application/json`；可选 `gzip` |
| 幂等键 | `event_id`（重传不重复计数） |
| 批量与体积上限 | ≤ 1000 事件 / ≤ 5 MiB（见 3.1.1） |
| 超时 | 见 3.1.1 |

**请求头**

| 头 | 必填 | 说明 |
| -- | ---- | ---- |
| `Authorization` | **是** | `Bearer <JWT>`；服务端校验并绑定 `user_id`（见 §2.3） |
| `Content-Type` | 是 | `application/json` |
| `Content-Encoding` | 否 | `gzip`（压缩请求体） |
| `X-User-Id` | 否 | 链路日志 / trace 标注与冗余校验；权威 `user_id` 取 JWT 主体（见 §2.3） |
| `X-Client-Id` | 否 | 富化 `client_id` |
| `X-App` | 否 | 富化 `application` |
| `X-Trace-Id` | 否 | 富化 `trace_id`；缺省服务端生成并回写 |
| `User-Agent` | 否 | 推断 `client_type` / `client_version` |

**请求体**：`ReportRequest`（见 §4.3）

| 字段 | 类型 | 必填 | 约束 / 说明 |
| ---- | ---- | ---- | ----------- |
| `events` | `MetricEvent[]` | 是 | 非空数组；逐事件字段约束见 §4.6 |

**响应体**：`ReportResponse`（见 §4.4）

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `success` | bool | 请求是否被成功受理（**允许部分成功且 `success=true`**） |
| `code` | string | 整体错误码；成功为空，全幂等重复为 `metric_ingest.duplicate_event` |
| `message` | string | 可读信息 |
| `data.accepted` | int | 成功接收计数 |
| `data.rejected` | int | 被拒计数（幂等重复**不计入**） |
| `data.errors[]` | array | 逐条被拒定位：`{index, event_id, code, message}` |

**状态码**：见 3.1.1（200 / 400 / 401 / 413 / 429 / 500）。

**幂等语义**：相同 `event_id` 重复投递 → 服务端忽略，`data.rejected` 不计，返回 `metric_ingest.duplicate_event`（HTTP 200）。幂等窗口默认 **7 天**（可配置）；超过窗口的同 `event_id` 视为新事件。

**错误码**：见 §7。

**示例**：

```bash
curl -X POST "https://<host>/user-indicator/api/v2/metrics/events" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736" \
  -d '{"events":[{"event_id":"e1a7c2f0-0001","client_type":"cli","timestamp":1735689600000,"indicator":"tool_call_count","value_type":"counter","value":3,"labels":{"tool_type":"mcp","tool_name":"read_file","status":"success"}}]}'
```

```json
{"success":true,"code":"","message":"","data":{"accepted":1,"rejected":0,"errors":[]}}
```

**用法**：客户端 / 第三方插件 / SDK / **拨测**主动上报的统一入口；以 `event_id` 保证幂等（见 §6）。

#### `GET /user-indicator/api/v2/metrics/spec` · 拉取指标规范

**功能**：返回指标规范（`metrics.spec.yaml`）供上报方渲染与自校验。

**请求规格**

| 项 | 值 |
| -- | -- |
| 方法 | `GET` |
| 路径 | `/user-indicator/api/v2/metrics/spec` |
| 认证 | 可选（见 3.1.1） |

**查询参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
| ---- | ---- | ---- | ---- | ---- |
| `version` | query | string | 否 | 指定 schema 版本；缺省返回**当前生效版本** |
| `category` | query | string | 否 | 按指标分类筛选，取值须为 Spec 已登记分类；可重复出现，多个取**并集** |
| `indicator` | query | string | 否 | 按指标名筛选，取值须为 Spec 已登记指标；支持**逗号分隔**或**重复参数**，多个取**并集** |
| `status` | query | string | 否 | 按指标状态筛选：`active` / `deprecated`；缺省返回全部 |

> **筛选语义**：`category` / `indicator` / `status` 仅作用于 `indicators[]`；`categories` 始终返回**完整分类枚举**；不同筛选条件之间为**逻辑与（AND）**，同一参数多值之间为**并集（OR）**；无命中时返回 `indicators: []`（仍为 HTTP 200）。筛选组合参与 `ETag` 计算，不同组合对应不同 `ETag`。

**响应体**（`data` 为 Spec 对象）

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `version` | string | Spec 版本 |
| `categories` | string[] | 指标分类枚举（完整枚举，不随筛选变化） |
| `indicators[]` | object[] | 指标定义（**经筛选后**，无筛选时返回全部）：`name` / `displayName` / `category` / `valueType` / `unit` / `bucketBounds[]`（`valueType=histogram` 时的桶边界 `le` 数组，升序、**不含 `+Inf`**） / `labels[]` / `owners[]` / `status` |

```json
{
  "success": true,
  "code": "",
  "data": {
    "version": "1.1.0",
    "categories": ["efficiency", "quality", "service", "agent", "workflow", "client", "gateway", "llm", "chat", "review"],
    "indicators": [
      {"name": "tool_call_count", "displayName": "工具调用次数", "category": "client", "valueType": "counter", "unit": "times", "labels": ["client_type", "tool_type", "tool_name", "status"], "owners": ["ide-team"], "status": "active"}
    ]
  }
}
```

**状态码**：200（成功，含筛选无命中时 `indicators: []`）；404（指定 `version` 不存在）；304（配合 `ETag` / `If-None-Match` 命中，响应体为空）。

**缓存**：建议上报方按 `version` + 筛选参数组合缓存并刷新；`ETag` 已纳入筛选组合，支持 `ETag` / `If-None-Match` 条件请求（可选）。

**用法**：SDK 生成 / 上报前校验；前端下拉项来源（对齐 §1.1、§6.1）。

#### `GET /user-indicator/api/v2/metrics/labels` · 标签白名单

**功能**：返回对外可用标签及其取值白名单。

**请求规格**

| 项 | 值 |
| -- | -- |
| 方法 | `GET` |
| 路径 | `/user-indicator/api/v2/metrics/labels` |
| 认证 | 可选（见 3.1.1） |

**查询参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
| ---- | ---- | ---- | ---- | ---- |
| `category` | query | string | 否 | 按指标分类过滤标签 |

**响应体**

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `data.labels` | map<string, string[]> | 标签名 → 允许取值集合（受控词表） |

```json
{
  "success": true,
  "code": "",
  "data": {
    "labels": {
      "client_type": ["web", "vscode", "cli", "server", "probe"],
      "env": ["prod", "staging"],
      "severity": ["high", "medium", "low"]
    }
  }
}
```

**状态码**：200。

**用法**：上报方校验标签合法性；与 costrict-admin `/metrics/labels` **同源**（对齐 metrics.md 3.14.5）；指标专属标签以 `/spec` 中该指标的 `labels` 声明为准。

---

### 3.3 内部上报接口

#### `POST /internal/indicator/api/v2/metrics/ingest` · 内部统一上报

**功能**：接收后端服务 / 网关等**服务端**采集点的指标事件，处理链路同 `/events`（Spec 校验 → 富化 → 5min 聚合 → 落库）。**cs-bridge 与拨测走外部 `/events`，不由此接口接入。**

**请求规格**

| 项 | 值 |
| -- | -- |
| 方法 | `POST` |
| 路径 | `/internal/indicator/api/v2/metrics/ingest` |
| 认证 | **内部服务凭据**（mTLS / `X-Internal-Token`，见 3.1.1） |
| 内容类型 / 编码 | `application/json`；可选 `gzip` |
| 幂等键 | `event_id` |
| 批量与体积上限 | ≤ 5000 事件 / ≤ 20 MiB（见 3.1.1） |

**请求头**

| 头 | 必填 | 说明 |
| -- | ---- | ---- |
| `X-Internal-Token` | 是¹ | 内部服务凭据（或使用 mTLS） |
| `Content-Type` | 是 | `application/json` |
| `Content-Encoding` | 否 | `gzip` |
| `X-User-Id` / `X-Client-Id` / `X-App` / `X-Trace-Id` | 否 | 富化对应字段 |

> ¹ 与 mTLS 二选一。

**请求体**：`ReportRequest`（见 §4.3，字段约束同 `/events`）

**响应体**：`ReportResponse`（见 §4.4，结构同 `/events`）

**状态码 / 幂等 / 错误码**：同 `/events`（见 3.1.1、§7）。

**示例**：

```bash
curl -X POST "https://<host>/internal/indicator/api/v2/metrics/ingest" \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: <token>" \
  -d '{"events":[{"event_id":"b7c1f3a2-...","client_type":"server","application":"cospower","scenario":"chat","model_id":"gpt-4o","timestamp":1735689600000,"indicator":"chat_request_count","value_type":"counter","value":3,"labels":{"status":"success"}}]}'
```

**用法**：**服务端**采集点（Multica / cospower / 网关等）的主动上报入口。**cs-bridge 与拨测使用外部接口 `/user-indicator/api/v2/metrics/events`（携带用户 JWT）上报，见 §6。**

#### `POST /internal/indicator/api/v2/metrics/ingest/otlp` · OTLP/JSON 兼容接入（可选）

**功能**：兼容 OpenTelemetry OTLP/JSON 指标格式接入，内部转换为统一事件模型后落库（对齐 metrics.md 10.3）。

**请求规格**

| 项 | 值 |
| -- | -- |
| 方法 | `POST` |
| 路径 | `/internal/indicator/api/v2/metrics/ingest/otlp` |
| 认证 | 内部服务凭据（同 `/ingest`） |
| 内容类型 | `application/json`（OTLP/JSON） |

**请求体**：OTLP `resourceMetrics`（符合 OTel 指标数据模型）。

**OTLP → `MetricEvent` 映射**

| OTLP 字段 | 映射到 `MetricEvent` |
| --------- | -------------------- |
| `resourceMetrics[].resource.attributes["service.name"]` | `application`（或 `service` 维度） |
| `scopeMetrics[].metrics[].name` | `indicator` |
| `metrics[].sum.dataPoints[].asInt` / `asDouble` | `value`（`value_type=counter`） |
| `metrics[].gauge.dataPoints[].asDouble` | `value`（`value_type=gauge`） |
| `metrics[].histogram.dataPoints[].bucketCounts` + `explicitBounds` | `buckets[]`（`value_type=histogram`） |
| `dataPoints[].timeUnixNano` | `timestamp`（转 unix millis） |
| `dataPoints[].attributes` | `labels`（按 Spec 白名单过滤） |
| `resourceMetrics[].resource.attributes` 中的客户端 / 用户属性 | `client_type` / `user_id` 等（如可解析） |

**响应体 / 状态码 / 错误码**：同 `/events`（`accepted` / `rejected`）。

**用法**：已接入 OTel SDK 的服务**零改造**上报。

#### `GET /internal/indicator/api/v2/metrics/spec` · 内部读取 Spec

**功能**：内部读取指标规范。

**请求规格**

| 项 | 值 |
| -- | -- |
| 方法 | `GET` |
| 路径 | `/internal/indicator/api/v2/metrics/spec` |
| 认证 | 内部服务凭据 |

**查询参数**

| 参数 | 位置 | 类型 | 必填 | 说明 |
| ---- | ---- | ---- | ---- | ---- |
| `version` | query | string | 否 | 指定 schema 版本；缺省返回当前生效版本 |
| `category` | query | string | 否 | 按指标分类筛选；可重复出现，多个取并集 |
| `indicator` | query | string | 否 | 按指标名筛选；支持逗号分隔或重复参数，多个取并集 |
| `status` | query | string | 否 | 按指标状态筛选：`active` / `deprecated`；缺省返回全部 |

> 筛选语义同外部 `/spec`：仅作用于 `indicators[]`，`categories` 返回完整分类枚举，不同条件为逻辑与、同参数多值为并集。

**响应体 / 状态码**：同外部 `/spec`（200 / 404 / 304）。

**用法**：后端服务 / 网关校验口径；与外部 `/spec` **同源**。

#### `POST /internal/indicator/api/v2/metrics/spec/reload` · 运行时重载 Spec

**功能**：在**不重启** user-indicator 的前提下，**热更新**当前生效的 Spec（指标种类 / 指标清单 / 值类型 / 单位 / 直方图桶边界 / 标签白名单）。

**请求规格**

| 项 | 值 |
| -- | -- |
| 方法 | `POST` |
| 路径 | `/internal/indicator/api/v2/metrics/spec/reload` |
| 认证 | **内部服务凭据**（mTLS / `X-Internal-Token`） |
| 请求体 | 可选；缺省从**配置来源**（主文件 + Drop-in 目录 / 配置中心）重新**合并**加载，也可直接提交新的 Spec 内容 |

**响应体**

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| `data.version` | string | 重载后生效的 Spec 版本 |
| `data.reloaded` | bool | 是否成功切换（`true` 生效 / `false` 保留旧版本） |
| `data.errors[]` | object[] | 校验失败明细（`field` / `message`） |

**状态码**：200（重载成功）；400（Spec 校验失败，**保留旧版本**）；401 / 403（凭据非法）。

**用法**：运维或配置中心回调触发；与文件监听 / `SIGHUP` / 配置中心订阅等自动热更新机制互为补充（见 §3.4）。

---

### 3.4 运行时配置（指标种类与 Spec）

user-indicator **支持的指标种类（`categories`）与指标清单（`indicators`：指标名 / 值类型 / 单位 / 直方图桶边界 / 标签白名单 / `owners` / `status`）** 由 **Spec** 定义，采用**运行时配置**方式管理：**新增 / 修改指标种类与指标无需重新发布或重启 user-indicator**，加载生效后由 `GET .../spec`、`GET .../labels` 对外反映，上报方按 `version` / `ETag` 感知变化。

Spec **不由单个集中大文件承载**，而是由**主文件 + Drop-in 目录**多文件**组合**而成，支持**按团队分散定义 / 独立维护**：无论是**新增指标分类**、**新增指标**，还是**为已有分类追加新指标**，只需**新增或修改一个文件**即可，无需触碰集中配置。

#### 3.4.1 配置组织：主文件 + Drop-in 目录

| 组成 | 路径（默认） | 职责 |
| ---- | ------------ | ---- |
| **主文件** | `./config/metrics.spec.yaml` | 全局 `version` 与基线 `categories`（分类枚举基线） |
| **Drop-in 目录** | `./config/metrics.spec.d/*.yaml` | 每个文件为一个**片段**，分散声明**分类 / 指标**（`categories` + `indicators` 任意组合） |

**三类分散定义场景**：

| 场景 | 做法 | 示例文件 |
| ---- | ---- | -------- |
| **新增指标分类** | Drop-in 文件同时声明 `categories`（新分类名）与其下 `indicators` | `metrics.spec.d/050-security.yaml` |
| **为已有分类新增指标** | Drop-in 文件仅声明 `indicators`，`category` 指向**已存在**分类 | `metrics.spec.d/060-client.yaml` |
| **新增指标（归属已有分类）** | 同上；不同团队可各自维护独立文件 | `metrics.spec.d/070-client-ide.yaml` |

- 每个文件可声明 `owners`，实现**谁定义谁维护**；
- 加载顺序：按**文件名升序**或文件内 `priority` 升序；`priority` 越大越后加载。

#### 3.4.2 合并（组合）规则

- **`categories`**：各文件**取并集**去重；主文件声明的基线分类始终保留；
- **`indicators`**：按 `name` **取并集**；
  - **同名跨文件冲突**默认**视为配置错误**（拒绝生效），避免多团队误覆盖；
  - 确需覆盖时在文件内对该指标显式声明 `override: true`，由**后加载**文件覆盖先加载文件；
- **组合结果即生效 Spec**：`GET .../spec` 返回主文件 + 所有 drop-in 文件**合并后的完整 Spec**（含合并后的 `categories` 与 `indicators`）；
- **文件级版本**：每个文件可携带 `version`，合并后整体 `version` 取各文件最大值并单调递增（或由主文件统一声明）。

#### 3.4.3 配置项与来源

| 配置项 | 说明 | 来源 |
| ------ | ---- | ---- |
| Spec 主文件 | 全局 `version` + 基线 `categories` | `metrics.spec.yaml`（本地文件 / 配置中心） |
| Drop-in 目录 | 分散定义分类 / 指标的片段目录 | 环境变量 `METRICS_SPEC_DIR`（默认 `./config/metrics.spec.d`） |
| 主文件路径 | 主文件挂载路径 | 环境变量 `METRICS_SPEC_PATH` |
| 加载顺序 / 优先级 | 文件名升序，或文件内 `priority` | 文件命名 / `priority` 字段 |
| 配置中心 | 以 Nacos / Apollo 等托管主文件与 drop-in 文件，按 dataId / 分组拉取与订阅 | 环境变量 `METRICS_CONFIG_CENTER`（可选） |
| 热更新开关 | 开启文件监听 / 订阅自动热更新 | `METRICS_SPEC_WATCH=true` |
| 校验失败策略 | 保留旧版本 / 回退内置默认 Spec | `METRICS_SPEC_FALLBACK` |

#### 3.4.4 加载与热更新机制

1. **启动加载**：读取主文件 + 扫描 drop-in 目录 → **合并** → 对**合并结果**做 schema 校验；校验失败时按 `METRICS_SPEC_FALLBACK` **回退内置默认 Spec** 或 **拒绝启动**（fail-fast）。
2. **运行时热更新**（**任一文件新增 / 修改 / 删除**均触发，且**均无需重启**）：文件变更监听（`fsnotify` / K8s ConfigMap 挂载更新，覆盖 drop-in 目录**增删文件**）；`SIGHUP` 信号重载；配置中心（Nacos 等）变更推送；或调用 §3.3 的 `POST .../spec/reload` 手动触发。
3. **重新合并 + 原子切换**：重新合并全部文件并整体校验，通过后**原子替换**内存中生效的 Spec 快照，`version` 递增、`/spec` 的 `ETag` 同步变化；读取采用无锁快照，保证上报校验与 `/spec` 读取始终看到**一致版本**。
4. **下发与共识**：热更新后对外 `/spec` / `/labels` **立即反映新版本**；上报方与前端按 `version` / `ETag` 刷新缓存（见 §3.2、§6.1）。

#### 3.4.5 校验与回滚

> 校验对象为**合并后的结果**，而非单个文件——例如某 drop-in 文件的 `indicators[].category` 只需在**合并后的 `categories`** 中存在即可。

| 校验项 | 规则 | 失败处置 |
| ------ | ---- | -------- |
| 语法 / 结构 | 每个文件 YAML 可解析、字段类型正确 | 拒绝生效，**保留旧版本**并告警 |
| 指标唯一性 | 合并后 `indicators[].name` 全局唯一（除显式 `override: true`） | 同上 |
| 分类引用 | `indicators[].category` 必须存在于**合并后的 `categories`** | 同上 |
| 枚举合法性 | `valueType ∈ {counter,gauge,histogram}`；`status ∈ {active,deprecated}` | 同上 |
| 直方图 | `valueType=histogram` 必须携带 `bucketBounds`（升序非空、**不含 `+Inf`**） | 同上 |
| 标签白名单 | 指标 `labels` 引用合法受控标签 | 同上 |
| 版本 | 合并后整体 `version` 严格递增 | 同上 |

- **原子切换 + 保留旧版本**：任一项失败均**不生效**，继续使用上一份有效 Spec，返回 **400** 并记录告警；
- **灰度回退**：配置中心场景可按 `version` 回滚到指定历史版本；
- **平滑下线**：下线指标先置 `status: deprecated` 过渡，订阅方据 Spec 自动隐藏，避免硬删除导致上报方校验失败。

#### 3.4.6 配置示例（多文件组合）

**主文件** `metrics.spec.yaml`（全局版本 + 基线分类）：

```yaml
version: 1.2.0
categories: [efficiency, quality, service, agent, workflow, client, gateway, llm, chat, review]
```

**Drop-in：新增指标分类** `metrics.spec.d/050-security.yaml`：

```yaml
version: 1.2.1
owners: [sec-team]
categories: [security]                     # 新增分类
indicators:
  - name: security_scan_count              # 新增分类下的新指标
    displayName: 安全扫描次数
    category: security
    valueType: counter                     # counter | gauge | histogram
    unit: times
    labels: [client_type, severity]
    status: active
```

**Drop-in：为已有分类追加新指标** `metrics.spec.d/060-client.yaml`：

```yaml
version: 1.2.2
owners: [ide-team]
indicators:
  - name: tool_call_retry_count            # 复用已有分类 client
    displayName: 工具调用重试次数
    category: client
    valueType: counter
    unit: times
    labels: [client_type, tool_type, tool_name]
    status: active
```

> 上述文件**合并**后即为生效 Spec（`categories` 含 `security`，`indicators` 含 `security_scan_count` 与 `tool_call_retry_count`），经 `GET .../spec` 对外发布——**新增分类 / 指标只需新增一个文件**。

触发运行时重载（文件新增 / 修改 / 删除由监听或配置中心订阅**自动生效**；或手动触发）：

```bash
curl -X POST "https://<host>/internal/indicator/api/v2/metrics/spec/reload" \
  -H "X-Internal-Token: <token>" \
  -H "Content-Type: application/json; charset=utf-8"
```

---

## 4. 数据模型

### 4.1 `MetricEvent`（统一指标事件）

**所有采集点（前端 SDK / 后端服务 / 网关 / 拨测）统一上报同一事件模型**（schema 与 metrics.md 8.5 对齐）：

```go
// MetricEvent 是统一上报的最小指标单元（schema 与 metrics.md 8.5 / 8.2 对齐）。
// 值语义（对齐 Prometheus）：counter 为【累计值】（进程启动起单调递增，重启归零，查询侧做 delta/rate 并识别归零重置）；
// gauge 为瞬时值；histogram 由【上报端分桶】，buckets 为累计计数（含 +Inf，见 §4.2），统计时间窗由查询侧确定。
type MetricEvent struct {
    EventID       string            `json:"event_id" binding:"required"`   // 幂等去重
    SchemaVersion string            `json:"schema_version"`
    ClientType    string            `json:"client_type"`                   // web | vscode | cli | server | probe
    ClientVersion string            `json:"client_version"`
    Application   string            `json:"application"`                   // csc / costrict-plugin / cli / multica / cospower ...（见 §4.6）
    Scenario      string            `json:"scenario"`                      // chat | review | agent-exec | tool-call
    UserID        string            `json:"user_id,omitempty"`             // 外部上报：服务端按 JWT 校验并强制绑定（越权拒绝）
    ClientID      string            `json:"client_id,omitempty"`
    SessionID     string            `json:"session_id,omitempty"`
    TraceID       string            `json:"trace_id,omitempty"`
    ModelID       string            `json:"model_id,omitempty"`            // 模型维度（卡慢归因核心，顶层字段）
    ToolType      string            `json:"tool_type,omitempty"`           // 工具类型 workspace | mcp（卡慢归因核心，顶层字段）
    ToolName      string            `json:"tool_name,omitempty"`           // 工具名（卡慢归因核心，顶层字段）
    Timestamp     int64             `json:"timestamp" binding:"required"`  // unix millis
    Indicator     string            `json:"indicator" binding:"required"`
    ValueType     string            `json:"value_type"`                    // counter | gauge | histogram
    Value         float64           `json:"value"`                         // counter（累计值）/ gauge（瞬时值）时使用
    Buckets       []HistogramBucket `json:"buckets,omitempty"`             // value_type=histogram 时使用（累计桶）
    Count         float64           `json:"count,omitempty"`               // histogram 总观测数（= +Inf 桶累计值）
    Sum           float64           `json:"sum,omitempty"`                 // histogram 观测值总和（用于均值）
    Labels        map[string]string `json:"labels,omitempty"`
    Metadata      map[string]string `json:"metadata,omitempty"`
}
```

### 4.2 `HistogramBucket`（直方图桶）

```go
// HistogramBucket 直方图桶（累计语义，对齐 Prometheus histogram 的 le 桶）。
// count 为【累计计数】——观测值 ≤ UpperBound 的数量（从最小桶起累加，严格单调不减）；
// 桶按 UpperBound 升序；+Inf 桶不显式上报，其累计值由 MetricEvent.Count 表示。
type HistogramBucket struct {
    UpperBound float64 `json:"upper_bound"` // 桶上界（le），升序，不含 +Inf
    Count      float64 `json:"count"`       // 累计计数：value ≤ upper_bound 的观测数
}
```

### 4.3 `ReportRequest`（批量上报体）

```go
// ReportRequest 批量上报体。
type ReportRequest struct {
    Events []MetricEvent `json:"events" binding:"required,dive"`
}
```

**请求示例**：

```json
{
  "events": [
    {
      "event_id": "5f2c8a1e-...",
      "schema_version": "1.0.0",
      "client_type": "cli",
      "client_version": "0.9.3",
      "application": "cli",
      "scenario": "tool-call",
      "timestamp": 1735689600000,
      "indicator": "tool_call_count",
      "value_type": "counter",
      "value": 3,
      "tool_type": "mcp",
      "tool_name": "read_file",
      "labels": {"status": "success"}
    }
  ]
}
```

### 4.4 响应包络

**成功响应（逐事件可寻址）**：

```json
{
  "success": true,
  "code": "",
  "message": "",
  "data": {"accepted": 1, "rejected": 0, "errors": []}
}
```

```go
// 统一响应结构（对齐 metrics-implement.md 5.2）。
type ReportResponse struct {
    Success bool          `json:"success"`
    Code    string        `json:"code"`
    Message string        `json:"message"`
    Data    ReportResult  `json:"data"`
}

type ReportResult struct {
    Accepted int            `json:"accepted"` // 成功接收计数
    Rejected int            `json:"rejected"` // 被拒计数（幂等重复项不计入）
    Errors   []ReportError  `json:"errors"`   // 逐条被拒事件的定位与原因
}

type ReportError struct {
    Index     int    `json:"index"`     // 事件在 events[] 中的下标
    EventID   string `json:"event_id"`  // 被拒事件的 event_id
    Code      string `json:"code"`      // metric_ingest.*
    Message   string `json:"message"`
}
```

- **逐事件可寻址**：`data.accepted` / `data.rejected` 为成功 / 拒绝计数；`data.errors[]` 逐条给出被拒事件的定位（下标 / `event_id`）与原因；重复 `event_id` 命中幂等，`rejected` **不计数**（`metric_ingest.duplicate_event`）。
- **部分成功语义**：批量上报**允许部分成功**，上报方应依据 `data.errors[]` 仅对可修复 / 可重试项处理，勿因个别非法事件重传整批。

### 4.5 字段分层：顶层字段 vs `Labels` vs `Metadata`

`MetricEvent` 中的维度分三类承载，目的是**兼顾公共维度的统一对齐与指标专属维度的灵活扩展**：

| 承载方式 | 定位 | 示例 | 存储 |
| -------- | ---- | ---- | ---- |
| **顶层字段** | 契约化、强类型的**公共维度** + **卡慢归因核心维度**（默认必带） | `client_type`、`application`、`scenario`、`user_id`、`trace_id`、`session_id`、`model_id`、`tool_type`、`tool_name`、`timestamp` | ES 独立 `keyword` 字段，可高效聚合 |
| **`Labels`** | 指标专属 / 可扩展的**弹性维度**（按 Spec 白名单） | `language`、`severity`、`agent_role`、`error_type`、`status` | ES `labels` 为 `flattened` |
| **`Metadata`** | **非维度**上下文，不参与聚合 / 筛选 | 错误堆栈、扩展属性 | ES `metadata` 为 `flattened` |

| 对比项 | 顶层字段 | `Labels` |
| ------ | -------- | -------- |
| 契约强度 | 强类型、固定 schema、全指标统一 | 松散 key-value，按指标白名单校验 |
| 值类型 | 明确类型（`string` / `int64`） | 一律 `string` |
| 来源 | 采集点提交 + 服务端按鉴权上下文补全（`user_id` 取 JWT 主体、组织维度取鉴权上下文） | 采集点 / 业务自带 |
| 默认必带 | 是（公共 Label，对齐 metrics.md 3.14.1 / 3.14.5） | 否，由指标在 Spec 声明 |
| ES 存储 | 独立 `keyword` 字段，可 `terms` 聚合 | `flattened`，支持**叶子键** `terms` 聚合（**不支持数值范围聚合**） |
| Prometheus | 低基数公共维度进 label | 仅白名单内低基数项进 label，高基数不入 |
| 校验 | 结构级（`binding:"required"` / 类型） | `metric_ingest.invalid_label` 白名单校验 |
| 用途 / 变更成本 | 跨域筛选 / 下钻 / join 基础；改 schema 成本高 | 域内细分维度；可动态新增、向后兼容 |

> - **Spec 中的 `labels` 是逻辑标签名**——公共维度在 Spec 中同样登记为 label（如 `labels: [application, client_type, model_id, user_id, dept_path]`），到事件模型时其中一部分被"提升"为顶层字段以获得强类型，其余才落入 `Labels` map；二者是同一 Label 体系的两种承载形态。
> - **卡慢归因核心维度提升**：为支撑看板按"**工具 vs 模型**"定位请求卡慢，`model_id` / `tool_type` / `tool_name` 从 `Labels` **提升为顶层 `keyword` 字段**（ES 独立字段、可高效 `terms` 聚合）；三者的逻辑标签名在 Spec 中仍保留，便于前端下拉与应用层对齐（见 §8）。
> - **命名口径**：**客户端 / 模型维度全链路统一为下划线**——事件模型 json tag、查询参数与 ES mapping 均为 `client_type` / `client_version` / `model_id`（**不再使用点号 `client.type` / `client.version` / `model.id`**，对齐 metrics.md 3.14.5.3「同名同义」）；其余历史点号名（`user.id` / `dept.path`）保留，完整对应关系见 §5.1 映射表。
> - **`Application` 统一命名**：`application` 为**唯一的应用 / 产品线维度**（`csc` / `costrict-plugin` / `cli`，以及**服务端侧应用** `multica` / `cospower` 等）；原 `app` 已收敛到 `application`，请求头 `X-App` 保留为外部契约，统一富化到 `application`。

### 4.6 字段规范与约束

| 字段 | JSON | 类型 | 必填 | 取值 / 说明 | 提交方 |
| ---- | ---- | ---- | ---- | ----------- | ------ |
| `EventID` | `event_id` | string | **是** | 幂等去重键，全局唯一（建议 UUID），长度 ≤ 64 | 调用方 |
| `SchemaVersion` | `schema_version` | string | 否 | 事件模型版本，默认 `1.0.0` | 调用方 |
| `ClientType` | `client_type` | string | 否 | `web` / `vscode` / `cli` / `server` / `probe` | 调用方（可据请求头） |
| `ClientVersion` | `client_version` | string | 否 | 客户端版本 | 调用方 |
| `Application` | `application` | string | 否 | `csc` / `costrict-plugin` / `cli` / `multica` / `cospower` 等（**含服务端侧应用**，受控枚举） | 调用方 |
| `Scenario` | `scenario` | string | 否 | `chat` / `review` / `agent-exec` / `tool-call` | 调用方 |
| `UserID` | `user_id` | string | 否 | 可空；**外部上报以 JWT 主体为权威值**，与 JWT 不一致即越权（403） | user-indicator（按 JWT 绑定） |
| `ClientID` | `client_id` | string | 否 | 客户端 ID | 调用方（`X-Client-Id`） |
| `SessionID` | `session_id` | string | 否 | 会话 ID（join key，不聚合） | 调用方 |
| `TraceID` | `trace_id` | string | 否 | 追踪 ID（join key，不聚合） | 调用方（`X-Trace-Id`） |
| `ModelID` | `model_id` | string | 否 | 模型标识（**卡慢归因核心维度，顶层字段**，如 `gpt-4o`） | 调用方 |
| `ToolType` | `tool_type` | string | 否 | 工具类型 `workspace` / `mcp`（**卡慢归因核心维度，顶层字段**） | 调用方 |
| `ToolName` | `tool_name` | string | 否 | 工具名（**卡慢归因核心维度，顶层字段**，如 `read_file`） | 调用方 |
| `Timestamp` | `timestamp` | int64 | **是** | 事件发生时间，**unix millis** | 调用方 |
| `Indicator` | `indicator` | string | **是** | 指标名，**必须为 Spec 已登记** | 调用方 |
| `ValueType` | `value_type` | string | 条件 | `counter`（**累计值**） / `gauge`（**瞬时值**） / `histogram`（**上报端累计分桶**）；缺省按 Spec 该指标的 `valueType` 推断 | 调用方 |
| `Value` | `value` | float64 | 条件 | `counter`（**累计值**）/ `gauge`（瞬时值）时使用 | 调用方 |
| `Buckets` | `buckets` | []HistogramBucket | 条件 | `value_type=histogram` 时使用；**累计桶**，按 `upper_bound` 升序、不含 `+Inf` | 调用方 |
| `Count` | `count` | float64 | 条件 | `value_type=histogram` 时使用；**总观测数**（= `+Inf` 桶累计值） | 调用方 |
| `Sum` | `sum` | float64 | 条件 | `value_type=histogram` 时使用；**观测值总和**（用于均值 `sum/count`） | 调用方 |
| `Labels` | `labels` | map<string,string> | 否 | 指标专属维度，**必须在 Spec 白名单内**；单值 ≤ 1 KiB | 调用方 |
| `Metadata` | `metadata` | map<string,string> | 否 | 非维度上下文，不参与聚合；单值 ≤ 1 KiB | 调用方 |

> **必填判定**：`event_id` / `timestamp` / `indicator` 为硬必填；`value_type` 缺省按 Spec 推断；`counter`/`gauge` 用 `value`，`histogram` 用 `buckets` + `count`（`sum` 可选，缺失则无法计算均值）。
>
> **值语义（对齐 Prometheus）**：
> - `counter`：**累计值**——自进程（或采集会话）启动起单调递增，重启归零；查询侧以 `delta` / `rate` 差分并**识别归零重置**（见 §11 读侧对接）。
> - `gauge`：瞬时值，直接取用。
> - `histogram`：**上报端分桶**；`buckets[].count` 为**累计计数**（观测值 ≤ `upper_bound` 的数量，从最小桶起累加，严格单调不减），`upper_bound` 升序、**不含 `+Inf`**，`+Inf` 桶累计值即顶层 `count`；**统计时间窗由查询侧确定**（上报端不设窗口）。

---

## 5. 通用 Label（维度）约定

公共 Label 为**指标默认维度**，可随事件提交或由服务端按鉴权上下文补全（对齐 metrics.md 3.14）：

| 分组 | Label | 说明 | 取值示例 |
| ---- | ----- | ---- | -------- |
| 时间 | `@timestamp` | 事件发生时间（另存 `ingest_time` 采集时间） | ISO8601 |
| 组织 | `tenant` | 租户（**服务端按鉴权上下文自动富化**） | default |
| 环境 | `env` | 运行环境（**服务端自动富化**） | prod / staging |
| 客户端 | `client_type` | 前端类型 | web / vscode / cli / server / probe |
| 客户端 | `client_version` | 客户端版本 | 0.9.3 |
| 流量源 | `traffic_source` | 区分真实 / 拨测（**服务端自动富化**） | user / probe |
| 链路 | `trace_id` | 链路追踪 ID（join key，**不聚合**） | hex |
| 链路 | `session_id` | 会话 ID（join key，**不聚合**） | uuid |
| 用户 | `user.id` / `user.name` | 用户标识 / 名称（**外部上报取 JWT 主体**） | u_1001 / 张三 |
| 组织 | `dept.path` | 部门路径（层级聚合，**服务端自动富化**） | /研发/平台组 |
| 模型 | `model_id` | 模型标识（**顶层字段承载**） | gpt-4o |
| 应用 | `application` | 应用 / 产品线（**含服务端侧应用**） | csc / multica |
| 场景 | `scenario` | 业务场景 | chat / review |
| 语言 | `language` | 编程语言（**`labels` 承载**） | go / java / ts |

**命名规范**（对齐 metrics.md 3.14.2）：

- 分层点号、**小写**：`域.字段`（如 `user.id`、`dept.path`），禁止大写与空格；**客户端维度例外**，统一为下划线 `client_type` / `client_version`；
- 枚举型 Label（`client_type` / `env` / `severity` 等）取值来自**受控词表**，禁止自由文本；
- 单位后缀（`_seconds` / `_bytes`），计数指标名带 `_total`；
- 同名同义：同一含义全链路用**同一 Label 名**。

**高基数治理**（对齐 metrics.md 3.14.4）：`trace_id` / `session_id` 极高基数，**禁止进入 Prometheus label**，ES 中仅作检索 / join，不参与 `terms` 聚合；`user.id` 聚合限 TopN；`path` 需路由归一化。

### 5.1 标签命名映射表（逻辑标签 ↔ 事件字段 ↔ ES 字段 ↔ 查询参数）

> **上报方只需使用"事件 JSON 字段"列**（一律**下划线**）；ES 点号字段由服务端落库阶段映射；查询参数与事件字段同名（下划线），供看板筛选传入（见 §11）。

| 逻辑标签 | 事件 JSON 字段 | ES 字段 | 查询参数 | 承载方式 |
| -------- | -------------- | ------- | -------- | -------- |
| `@timestamp` | `timestamp` | `@timestamp` | `start` / `end` | 顶层 |
| `tenant` | （**服务端富化**） | `tenant` | `tenant` | 顶层（富化） |
| `env` | （**服务端富化**） | `env` | — | 顶层（富化） |
| `client_type` | `client_type` | `client_type` | `client_type` | 顶层 |
| `client_version` | `client_version` | `client_version` | `client_version` | 顶层 |
| `traffic_source` | （**服务端富化**） | `traffic_source` | `traffic_source` | 顶层（富化） |
| `trace_id` | `trace_id` | `trace_id` | — | 顶层（join，不聚合） |
| `session_id` | `session_id` | `session_id` | — | 顶层（join，不聚合） |
| `user.id` | `user_id` | `user.id` | `user_id` | 顶层 |
| `dept.path` | （**服务端富化**） | `dept.path` | `dept_path` | 顶层（富化） |
| `model_id` | `model_id` | `model_id` | `model_id` | 顶层 |
| `application` | `application` | `application` | `application` | 顶层 |
| `scenario` | `scenario` | `scenario` | `scenario` | 顶层 |
| `tool_type` | `tool_type` | `tool_type` | `tool_type` | 顶层 |
| `tool_name` | `tool_name` | `tool_name` | `tool_name` | 顶层 |
| `language` | `labels.language` | `labels.language` | `language` | Labels（flattened） |
| `severity` | `labels.severity` | `labels.severity` | `severity` | Labels（flattened） |
| `status` | `labels.status` | `labels.status` | `status` | Labels（flattened） |

> - `labels`（`flattened`）支持**叶子键** `terms` 聚合，故 `language` / `severity` / `status` 等可下钻；但**不支持数值范围聚合**，需数值维度的场景应落在顶层字段。
> - `tenant` / `env` / `traffic_source` / `dept.path` 由服务端富化，**上报方无需也不应自报**（见 §2.3）。

---

## 6. 接口调用说明

> 本节仅说明**如何调用 user-indicator 接口**；上报方内部的采集 / 富化 / 聚合等实现不在本规范范围内。

### 6.1 拉取 Spec 与标签白名单

1. 调用 `GET /user-indicator/api/v2/metrics/spec` 获取指标规范（名称 / 值类型 / 单位 / 直方图桶边界 / 标签），可按 `category` / `indicator` / `status` 筛选以减小响应体；
2. 调用 `GET /user-indicator/api/v2/metrics/labels` 获取可用标签与取值白名单；
3. 按 Spec `version` 缓存并刷新（支持 `ETag` 条件请求，见 §3.2）。

### 6.2 构造请求体

按 §4 数据模型构造 `ReportRequest.events[]`：

- 必填：`event_id`、`timestamp`、`indicator`；`value_type` 缺省按 Spec 该指标推断；`counter`/`gauge` 用 `value`，`histogram` 用 `buckets[]` + `count`（`sum` 可选）；
- `labels` **必须**落在该指标的 Spec 白名单内，单值 ≤ 1 KiB；
- 公共维度字段（`client_type` / `application` / `scenario` / `client_id` / `trace_id` / `session_id`）与**卡慢归因核心维度**（`model_id` / `tool_type` / `tool_name`）按 §4.6 填写；
- **用户 / 组织 / 环境维度**（`user_id` / `tenant` / `dept.path` / `env` / `traffic_source`）**以服务端鉴权上下文为准**，上报方**无需也不应自报**（见 §2.3、§5.1）；
- 值语义（对齐 Prometheus）：`counter` 上报**累计值**、`histogram` 上报**累计桶**，统计时间窗在查询侧确定（见 §4.6、§11）。

### 6.3 时间与幂等

- `timestamp` 一律 **unix millis**；服务端另存 `ingest_time`（入库时间）；
- `event_id` 为幂等键，**重传须保持稳定**；相同 `event_id` 再次投递，服务端忽略并返回 `metric_ingest.duplicate_event`（HTTP 200）；幂等窗口默认 **7 天**（可配置）；
- **事件时效**：默认接受 `timestamp` 距服务端当前时间**不超过 7 天**的事件，超出返回 `metric_ingest.stale_event`（**不重试**，丢弃并告警）；客户端时钟偏差场景请预留容差。

### 6.4 错误码处理

依据接口返回（见 §7）逐事件读取 `data.errors[]`：

| 返回 | 处理 |
| ---- | ---- |
| `200` + `errors[]`（`invalid_event` / `unsupported_indicator` / `invalid_label` / `identity_mismatch` / `stale_event`） | **逐条**修正或丢弃该项，**不重试原样** |
| `400` `malformed_request` | 修正请求体（JSON / `events`）后重发 |
| `401` 鉴权失败 | 刷新凭据后重试 |
| `413` `payload_too_large` | **拆分**为更小批次重发 |
| `429` 限流 | 依据 `Retry-After` 退避重试 |
| `500` `store_failed` | **重试**（保留本地缓冲） |
| `200` `duplicate_event` | **视为成功** |

### 6.5 上报示例（curl）

```bash
curl -X POST "https://<host>/user-indicator/api/v2/metrics/events" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <JWT>" \
  -d '{"events":[{"event_id":"5f2c8a1e-...","client_type":"cli","application":"cli","tool_type":"mcp","tool_name":"read_file","timestamp":1735689600000,"indicator":"tool_call_count","value_type":"counter","value":3,"labels":{"status":"success"}}]}'
```

---

## 7. 错误码与处理策略

**命名空间 `metric_ingest.*`**（对齐 metrics-implement.md 5.2）：

| code | HTTP | 说明 | 上报方处理 |
| ---- | ---- | ---- | ---------- |
| `metric_ingest.malformed_request` | 400 | **整批级**非法（JSON 不可解析 / `events` 缺失或非数组） | **不重试**；修正请求体后重发 |
| `metric_ingest.invalid_event` | 200（逐条） | 单条事件结构非法（必填缺失 / 类型错误） | **不重试**；修正事件后重发 |
| `metric_ingest.unsupported_indicator` | 200（逐条） | Spec 未登记指标 | **不重试**；剔除并告警（校核 Spec 版本） |
| `metric_ingest.invalid_label` | 200（逐条） | 标签不在白名单 / 取值非法 / 单值超 1 KiB | **不重试**；修正 label 后重发 |
| `metric_ingest.identity_mismatch` | 200（逐条）/ 403（整批） | 事件 `user_id` 与 JWT 主体不一致（越权上报） | **不重试**；以 JWT 主体修正 `user_id` 后重发 |
| `metric_ingest.stale_event` | 200（逐条） | `timestamp` 超出可接受时效（默认 > 7 天） | **不重试**；丢弃并告警（校核客户端时钟） |
| `metric_ingest.payload_too_large` | 413 | 超过批量 / 请求体上限 | **拆分批次**后重试 |
| `metric_ingest.duplicate_event` | 200（忽略） | `event_id` 命中幂等，丢弃不重复计数 | **视为成功** |
| `metric_ingest.store_failed` | 500 | 存储写入失败 | **指数退避重试**（保留本地缓冲） |

**统一响应结构**：`{ "success": bool, "code": string, "message": string, "data": ... }`。

> 处理总则：**逐事件**读取 `data.errors[]`——逐条数据问题（HTTP 200 + `errors[]`）本地修复 / 丢弃，413 属"批次问题"（拆分），5xx 属"服务问题"（重试）；命中幂等统一按成功处理；仅 **400**（`malformed_request`）为**整批级**、需修正请求体后重发。

---

## 8. 落点（存储 Mapping 参考）

`v2` 新链路目标索引为 **`costrict_metrics_unified_v1`**（另含 5min 预聚合 `costrict_metrics_unified_5m_v1`）。上报方**无需直接操作 ES**，但理解下列 Mapping 有助于构造正确字段（点号命名对应 ES 字段）：

```json
{
  "mappings": {
    "properties": {
      "@timestamp":     {"type": "date", "format": "strict_date_optional_time||epoch_millis"},
      "ingest_time":    {"type": "date"},
      "event_id":       {"type": "keyword", "ignore_above": 64},
      "indicator":      {"type": "keyword"},
      "value_type":     {"type": "keyword"},
      "value":          {"type": "double"},
      "buckets":        {"type": "nested", "properties": {"upper_bound": {"type": "double"}, "count": {"type": "double"}}},
      "count":          {"type": "double"},
      "sum":            {"type": "double"},
      "client_type":    {"type": "keyword"},
      "client_version": {"type": "keyword"},
      "application":    {"type": "keyword"},
      "scenario":       {"type": "keyword"},
      "tenant":         {"type": "keyword"},
      "env":            {"type": "keyword"},
      "traffic_source": {"type": "keyword"},
      "user":     {"properties": {"id": {"type": "keyword"}, "name": {"type": "keyword"}}},
      "dept":     {"properties": {"path": {"type": "keyword", "ignore_above": 512}}},
      "trace_id":   {"type": "keyword", "ignore_above": 64},
      "session_id": {"type": "keyword", "ignore_above": 64},
      "model_id":   {"type": "keyword"},
      "tool_type":  {"type": "keyword"},
      "tool_name":  {"type": "keyword"},
      "labels":     {"type": "flattened"},
      "metadata":   {"type": "flattened"}
    }
  }
}
```

> 新增维度必须**向后兼容**（旧文档缺字段不影响聚合）。Spec 变更即同步影响：ES mapping 模板、前端下拉项、后端校验与查询默认值。
>
> `counter` 的**累计值**与 `histogram` 的**累计桶**（含 `count` / `sum`）按原样落库；`delta` / `rate`、分位数（P95/P99）与比率等派生计算均在**查询侧**完成（见 §11）。

---

## 9. 示例集（Examples）

> 以下示例供 cs-bridge/cli 作者对照实现；请求 / 响应字段均以 §4 数据模型与 §7 错误码为准。

### 9.1 单指标上报（三种 `value_type`）

**① counter（累计计数）——`tool_call_count`**

```json
{
  "events": [
    {
      "event_id": "e1a7c2f0-0001",
      "schema_version": "1.0.0",
      "client_type": "cli",
      "client_version": "0.9.3",
      "application": "cli",
      "scenario": "tool-call",
      "user_id": "u_1001",
      "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
      "tool_type": "mcp",
      "tool_name": "read_file",
      "timestamp": 1735689600000,
      "indicator": "tool_call_count",
      "value_type": "counter",
      "value": 3,
      "labels": {"status": "success"}
    }
  ]
}
```

**② gauge（瞬时值）——`probe_success`（拨测）**

```json
{
  "events": [
    {
      "event_id": "e1a7c2f0-0002",
      "client_type": "probe",
      "application": "csc",
      "timestamp": 1735689600000,
      "indicator": "probe_success",
      "value_type": "gauge",
      "value": 1,
      "labels": {"target": "backend.healthz", "layer": "l1_liveness", "type": "liveness", "region": "cn-east-1", "traffic_source": "probe"}
    }
  ]
}
```

**③ histogram（分布）——`chat_first_token_duration`**

```json
{
  "events": [
    {
      "event_id": "e1a7c2f0-0003",
      "client_type": "vscode",
      "client_version": "0.9.3",
      "application": "csc",
      "scenario": "chat",
      "model_id": "gpt-4o",
      "timestamp": 1735689600000,
      "indicator": "chat_first_token_duration",
      "value_type": "histogram",
      "buckets": [
        {"upper_bound": 50,  "count": 8},
        {"upper_bound": 100, "count": 20},
        {"upper_bound": 200, "count": 25}
      ],
      "count": 25,
      "sum": 2120
    }
  ]
}
```

> 规则：`value_type=histogram` 用 `buckets`（**累计桶**，按 `upper_bound` 升序、**不含 `+Inf`**）+ `count`（总观测数，即 `+Inf` 累计）+ `sum`（观测值总和）；`counter` / `gauge` 用 `value`。`counter` 为**累计值**、`histogram` 桶为**累计计数**（对齐 Prometheus，见 §4.6）。

### 9.2 完整字段请求体示例

一条包含公共维度与顶层字段的完整 `ReportRequest`（提交至外部接口 `/user-indicator/api/v2/metrics/events`，携带用户 JWT；`user_id` / `tenant` / `env` / `dept.path` 由服务端按鉴权上下文**自动富化**，**无需自报**）：

```json
{
  "events": [
    {
      "event_id": "b7c1f3a2-9d4e-4c11-8f20-7a6b5c4d3e2f",
      "client_type": "vscode",
      "client_version": "0.9.3",
      "application": "csc",
      "scenario": "chat",
      "client_id": "c_8891",
      "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
      "model_id": "gpt-4o",
      "timestamp": 1735689600000,
      "indicator": "chat_request_count",
      "value_type": "counter",
      "value": 1
    }
  ]
}
```

> 字段语义见 §4.6；`user_id` / `tenant` / `env` / `dept.path` 等维度由服务端按鉴权上下文**自动富化**，上报方**无需也不应自报**（见 §2.3、§5.1）。

### 9.3 各采集点上报示例

> 下列采集点示例均标注**目标接口**：`client_type=server` 的服务端采集点走**内部接口**（内部凭据），`client_type=probe` 走**外部接口**（用户 JWT）。

**C4 multica（Agent 任务耗时；走内部接口 `POST /internal/indicator/api/v2/metrics/ingest`）**

```json
{"event_id":"m-1001","client_type":"server","application":"multica","timestamp":1735689600000,"indicator":"agent_task_duration","value_type":"histogram","buckets":[{"upper_bound":1000,"count":3},{"upper_bound":5000,"count":6}],"count":6,"labels":{"agent_id":"coder-1","agent_role":"coder","agent_version":"1.2.0"}}
```

**C3 cospower（工作流端到端周期；走内部接口 `POST /internal/indicator/api/v2/metrics/ingest`）**

```json
{"event_id":"w-2001","client_type":"server","application":"cospower","timestamp":1735689600000,"indicator":"workflow_lead_time","value_type":"histogram","buckets":[{"upper_bound":3600000,"count":2}],"count":2,"labels":{"workflow_id":"REQ-flow","workflow_type":"requirement"}}
```

**C5 拨测（分层探测成功标记；走外部接口 `POST /user-indicator/api/v2/metrics/events` + 用户 JWT）**

```json
{"event_id":"p-3001","client_type":"probe","application":"csc","timestamp":1735689600000,"indicator":"probe_success","value_type":"gauge","value":0,"labels":{"target":"api.chat","layer":"l2_api","type":"api","region":"cn-east-1","traffic_source":"probe"}}
```

**C8 codereview（审查发现问题数；走内部接口 `POST /internal/indicator/api/v2/metrics/ingest`）**

```json
{"event_id":"r-4001","client_type":"server","application":"costrict-plugin","timestamp":1735689600000,"indicator":"review_issue_count","value_type":"counter","value":5,"labels":{"severity":"high","review_type":"code","language":"go"}}
```

### 9.4 批量上报示例（一次请求多条事件）

```json
{"events":[
  {"event_id":"e1a7c2f0-0001","client_type":"vscode","application":"csc","scenario":"chat","model_id":"gpt-4o","timestamp":1735689600000,"indicator":"chat_request_count","value_type":"counter","value":1},
  {"event_id":"e1a7c2f0-0002","client_type":"vscode","application":"csc","scenario":"chat","model_id":"gpt-4o","timestamp":1735689600120,"indicator":"chat_request_count","value_type":"counter","value":2},
  {"event_id":"e1a7c2f0-0003","client_type":"vscode","application":"csc","scenario":"chat","model_id":"gpt-4o","timestamp":1735689600300,"indicator":"chat_tokens_total","value_type":"counter","value":128}
]}
```

> 单请求事件数与体积上限见 §3.1.1；`event_id` 需全局唯一、重传稳定。`counter` 为**累计值**——上例同一计数器 `chat_request_count` 的两次快照依次为 `1`、`2`（递增）；查询侧以 `delta` 差分得到增量。

### 9.5 请求示例（curl）

**外部上报**

```bash
curl -X POST "https://<host>/user-indicator/api/v2/metrics/events" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <JWT>" \
  -H "X-User-Id: u_1001" \
  -H "X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736" \
  -d '{"events":[{"event_id":"e1a7c2f0-0001","client_type":"cli","tool_type":"mcp","tool_name":"read_file","timestamp":1735689600000,"indicator":"tool_call_count","value_type":"counter","value":3,"labels":{"status":"success"}}]}'
```

**内部上报（后端服务 / 网关 / 采集点；内部凭据）**

```bash
curl -X POST "https://<host>/internal/indicator/api/v2/metrics/ingest" \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: <token>" \
  -d '{"events":[{"event_id":"m-1001","client_type":"server","application":"multica","timestamp":1735689600000,"indicator":"agent_task_duration","value_type":"histogram","buckets":[{"upper_bound":1000,"count":3},{"upper_bound":5000,"count":6}],"count":6,"labels":{"agent_id":"coder-1","agent_role":"coder"}}]}'
```

**cs-bridge 上报（运行于客户端，走外部接口 + 用户 JWT，gzip 压缩）**

```bash
curl -X POST "https://<host>/user-indicator/api/v2/metrics/events" \
  -H "Content-Type: application/json" \
  -H "Content-Encoding: gzip" \
  -H "Authorization: Bearer <JWT>" \
  --data-binary @batch.json.gz
```

**拉取 Spec / 标签白名单**

```bash
# 拉取全量 Spec
curl "https://<host>/user-indicator/api/v2/metrics/spec"
# 按分类 / 状态筛选
curl "https://<host>/user-indicator/api/v2/metrics/spec?category=client&status=active"
# 按指标名筛选（逗号分隔，多个取并集）
curl "https://<host>/user-indicator/api/v2/metrics/spec?indicator=tool_call_count,chat_request_count"
curl "https://<host>/user-indicator/api/v2/metrics/labels"
```

### 9.6 响应示例

**全部成功**

```json
{"success":true,"code":"","message":"","data":{"accepted":3,"rejected":0,"errors":[]}}
```

**部分失败（逐事件可寻址）**

```json
{
  "success": true,
  "code": "",
  "message": "",
  "data": {
    "accepted": 2,
    "rejected": 1,
    "errors": [
      {"index": 2, "event_id": "e1a7c2f0-0003", "code": "metric_ingest.unsupported_indicator", "message": "indicator not registered in spec"}
    ]
  }
}
```

**命中幂等（重复 `event_id`，HTTP 200 忽略）**

```json
{"success":true,"code":"metric_ingest.duplicate_event","message":"duplicate event ignored","data":{"accepted":0,"rejected":0,"errors":[]}}
```

**请求体超限（需拆分重试）**

```json
{"success":false,"code":"metric_ingest.payload_too_large","message":"batch exceed limit","data":{"accepted":0,"rejected":0,"errors":[]}}
```

### 9.7 请求 / 响应成对示例

**请求（2 条事件，其中 1 条指标未登记）**

```json
{"events":[
  {"event_id":"e1a7c2f0-0001","client_type":"cli","application":"cli","tool_type":"mcp","tool_name":"read_file","timestamp":1735689600000,"indicator":"tool_call_count","value_type":"counter","value":3,"labels":{"status":"success"}},
  {"event_id":"e1a7c2f0-0002","client_type":"cli","application":"cli","timestamp":1735689600000,"indicator":"unknown_metric","value_type":"counter","value":1}
]}
```

**响应（部分成功）**

```json
{"success":true,"code":"","message":"","data":{"accepted":1,"rejected":1,"errors":[{"index":1,"event_id":"e1a7c2f0-0002","code":"metric_ingest.unsupported_indicator","message":"indicator not registered in spec"}]}}
```

### 9.8 错误处理决策示例

| 场景 | 服务端返回 | 调用方处理 |
| ---- | ---------- | -------------- |
| 指标名未登记（Spec 版本旧） | `200 metric_ingest.unsupported_indicator`（逐条） | 丢弃该项 + 告警 + 刷新 Spec，**不重试** |
| 标签越权 | `200 metric_ingest.invalid_label`（逐条） | 剔除非法标签后重发，**不重试原样** |
| 冒用他人身份上报 | `200 metric_ingest.identity_mismatch`（逐条）/ `403`（整批） | **不重试**；以 JWT 主体修正 `user_id` |
| 批量过大 | `413 metric_ingest.payload_too_large` | **拆分**为更小批次重发 |
| 重传同一窗口 | `200 metric_ingest.duplicate_event` | **视为成功**，从缓冲移除 |
| 存储抖动 | `500 metric_ingest.store_failed` | **指数退避重试**，保留本地缓冲 |

---

## 10. 接口对接自检清单

- [ ] 调用 `/spec` 与 `/labels` 获取并缓存指标规范与标签白名单，按 `version` 刷新
- [ ] 客户端走**外部接口** `/user-indicator/api/v2/metrics/events`（或其别名 `/report`）+ **用户 JWT**；**服务端**采集点走**内部接口** `/internal/indicator/api/v2/metrics/ingest` + 内部凭据
- [ ] `user_id` 以 JWT 主体为准（外部）或由受信服务端上下文提供；避免越权（`identity_mismatch`）
- [ ] 公共维度（`client_type` / `application` / `scenario`）与**卡慢归因维度**（`model_id` / `tool_type` / `tool_name`）按 §4.6 填写；`user_id` / `tenant` / `env` / `dept.path` 由服务端富化，**不自报**
- [ ] `event_id` 全局唯一、重传稳定；`timestamp` 为 unix millis 且在时效窗口内（默认 7 天）
- [ ] `counter` 上报**累计值**、`histogram` 上报**累计桶** + `count`（`sum` 可选）；`labels` 落在 Spec 白名单内、单值 ≤ 1 KiB
- [ ] 事件字段符合 §4 数据模型；请求满足 §3.1.1 的批量 / 体积上限
- [ ] 依据响应 `data.errors[]` 处理：逐条（HTTP 200 + `errors[]`）修正 / 丢弃、整批 `400` 修正请求体、413 拆分、429 退避、5xx 重试、重复视为成功
- [ ] **上报后回读验证**：经 costrict-admin 查询接口确认指标可见、维度 / 单位 / 聚合正确（见 §11）

---

## 11. 读侧对接指引（上报后如何观测）

> user-indicator **只负责采集（写）**，**查询由 `costrict-admin` 承担**。本节给出"上报后如何看到数据"的最小对接指引，帮助上报方与看板开发者形成"**上报 → 查询 → 看板**"闭环（对齐 metrics-implement.md §2.1）。

### 11.1 数据可见性（SLA 参考）

| 数据形态 | 可见延迟（参考） | 说明 |
| -------- | ---------------- | ---- |
| 原始事件（`costrict_metrics_unified_v1`） | 近实时，**≤ 30s** | ES `refresh_interval` 默认 30s |
| 5min 预聚合（`costrict_metrics_unified_5m_v1`） | **约 5~6min** | 滚动窗口完成后可见 |
| 迟到 / 乱序事件 | 按事件 `timestamp` 归窗 | 窗口内补入 |

> **上报成功（`accepted`）≠ 立即可查**；看板若依赖 5min 预聚合，请预留约 6min 延迟。

### 11.2 查询接口（costrict-admin）

上报方**不直接访问 ES**，统一经 `costrict-admin`（`/costrict-admin/api/v1` 前缀，**只读 GET**）查询：

| 方法 | 路径 | 用途 |
| ---- | ---- | ---- |
| GET | `/metrics/{indicator}` | 单指标趋势（`start` / `end` / `interval` + 通用过滤参数） |
| GET | `/metrics/query` | 通用查询（指标 + 聚合 + 过滤） |
| GET | `/metrics/efficiency/breakdown` | 按维度分组下钻（`group_by`：`dept` / `user` / `model` / `language` / `client_type`） |
| GET | `/metrics/labels` | 查询侧标签白名单（与上报侧 `/labels` **同源**） |

- **通用过滤参数**（下划线命名，与事件字段一致）：`start`、`end`、`interval`、`dept_path`、`user_id`、`model_id`、`client_type`、`application`、`scenario`、`language`、`tenant`；映射关系见 §5.1。
- 时间参数：RFC3339 或 unix millis，区间 `[start, end)` **左闭右开**。

### 11.3 派生指标口径（后端计算，前端只展示）

| 派生指标 | 由上报数据计算 | 口径 |
| -------- | -------------- | ---- |
| 增量 / 速率（QPS、调用量） | `counter`（累计值） | 对累计值做 `delta`，**同一序列归零即视为进程重启**并修正；`rate = delta / 时间窗` |
| 分位数 P50 / P95 / P99（时延） | `histogram`（累计桶） | 基于 `buckets[]`（le 桶）**插值**估算分位；时间窗由查询 `interval` / `start`~`end` 确定 |
| 均值（平均时延等） | `histogram` | `sum / count`（`sum` 缺失则该指标无法计算均值） |
| 成功率 / 比率 | 计数类 `counter` | 成功计数 / 总计数（同一时间窗差分后相除） |

> **上报端不做窗口聚合**：`counter` 与 `histogram` 均为累计语义，统计时间窗、分位数与比率一律在**查询侧**完成（见 §4.6、§8）。

### 11.4 标签与下钻

- **顶层维度**（`client_type` / `application` / `scenario` / `model_id` / `tool_type` / `tool_name` / `user_id` / `dept_path`）可直接作为 `group_by` / 过滤，聚合高效；
- **`labels`**（`flattened`）支持**叶子键** `terms` 聚合（如 `language` / `severity` / `status`），但**不支持数值范围聚合**；
- **"工具 vs 模型"卡慢归因**：固定 `client_type` / `tool_type` / `tool_name` / `model_id` 维度，对照 `tool_call_duration` / `model_duration` 的分位数下钻，定位是工具卡还是模型卡。

### 11.5 端到端自检

1. 上报 1 条已知 `indicator` 与维度的事件，记录 `event_id` 与 `timestamp`；
2. 约 30s 后经 `costrict-admin` `/metrics/{indicator}` 或 `/metrics/query` 按该维度查询，确认**可见且数值正确**；
3. 若依赖 5min 预聚合，等待约 6min 后再验；
4. 未见数据时排查：是否落在**时效窗口**内 / 指标是否 Spec **已登记** / 维度是否为**服务端富化项**（不应自报）/ 是否命中**幂等**未落库。
