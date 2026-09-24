# JetBrains Outbox 高保真诊断设计

## 目标

让 `~/.costrict/telemetry/outbox/<scope-id>.jsonl` 成为 JetBrains 插件问题的唯一必要诊断输入。仅取得 outbox 时，应能定位异常类型、原始异常文本、完整堆栈、相关路径、失败请求或事件的业务载荷、前后端运行侧及关联操作，同时继续产出可聚合的稳定性指标。

## 已核实的问题

当前 outbox 是指标优先的严格白名单事实流，不是完整诊断日志：

- `error.reported` 和 `error.uncaught` 只允许固定模板、最多 5 个插件方法帧，不接收原始异常文本、完整堆栈、路径或业务载荷。
- RPC 异常统一写为 `cause=unknown`、`error_code=other`。
- `Faults.report` 的生产接入覆盖面很小；现有样本中没有任何 `error.*` 事实，但同一时段 `kilo.log` 有 JSON 解码、HTTP 404、MCP 绑定失败等警告。
- `telemetry.health.drop` 聚合所有丢弃原因，无法判断指标缺失来自校验、争用、容量、策略、超长记录还是文件淘汰。
- 当前单条记录限制为 32 KiB，单 scope 文件限制为 10 MiB，无法承载完整堆栈和必要业务载荷。

## 数据模型

新增事件类型、原始内容和分片语义超出了 v1“minor 只能增加可选字段”的兼容边界，因此使用 `schema_version=2.0`。无控制契约文件（常态）：插件 fail-open 占位策略默认接受 fact schema major {1,2}，高保真诊断默认启用；显式放置的控制文件仍可按其 `accepted_fact_schema_majors` 声明收窄（如 `[1]` 抑制 v2）。一个 scope 文件可以包含历史 v1 行和新 v2 行，消费者必须逐行按 major 解码。每个故障由一个主记录和零到多个分片组成：

- `diagnostic.reported`：WARN/ERROR 或业务失败的主记录。
- `error.reported`、`error.uncaught`：异常计数和异常诊断主记录；继续区分 handled。
- `diagnostic.payload`：大文本或二进制载荷分片。

主记录包含：

- `incident_id`、`severity`、`component`、`code`、`message`；
- `exception_type`、完整 `cause_chain`、`suppressed_count`；
- `thread_name`、`thread_id`、`frames`；
- `method`、`route`、`http_status`、`content_type`、`payload_bytes`；
- `json_path`、`expected_type`、`actual_type`；
- `payload_refs`，指向同一 incident 的分片种类；
- `truncated`，明确指出是否因总预算未保存全部内容。

公共 `context` 继续承载 `operation_id`、`attempt_id`、`fault_id`、`trace_id`、`workspace_id`，并新增 `incident_id`。`run_id`、`side`、`mode` 等仍由 recorder 注入。业务调用点不得自行伪造公共身份字段。

分片记录包含 `incident_id`、`payload_kind`、`chunk_index`、`chunk_count`、`encoding`、`content`、`original_bytes`、`sha256`。单片 UTF-8 wire 大小保持不超过 32 KiB；文本使用 UTF-8，非文本使用 Base64。单 incident 最多保存 1 MiB；超过时保留头尾各 512 KiB，设置 `truncated=true` 并保留完整原文 SHA-256 和原始长度。

## 内容边界

在日志用途许可有效时，允许落盘：

- 原始异常 message、完整 cause/suppressed 链和完整 JVM 堆栈；
- 绝对文件路径、工作区路径、命令路径；
- 与失败直接相关的请求、响应、RPC、SSE 或解析载荷；
- 线程、HTTP、运行状态和组件上下文。

以下凭证无论配置如何都必须替换为 `<redacted:type>`：

- `Authorization`、`Proxy-Authorization`、Cookie、Set-Cookie；
- access/refresh/API token、JWT、密码、client secret；
- PEM 私钥、SSH 私钥、云凭证和已登记敏感环境变量的值。

过滤器必须作用于 message、stack、path、payload 和结构化属性。过滤失败时拒绝该详情记录并生成不含原文的 `diagnostic.redaction_failed` 主记录，不允许 fail open。

## 采集链路

`KiloLog.create()` 返回的 logger 在保留现有 IntelliJ/file 输出的同时，将 WARN/ERROR 非阻塞镜像到进程内 `DiagnosticBridge`。`StabilityService` 激活 run 后安装 bridge sink，停止或撤销时卸载。collector 自身日志不回灌，bridge 使用重入保护避免递归。

需要原始业务载荷或精确关联 ID 的边界使用显式 `Diagnostics.report(...)`，而不是依赖解析自由文本：

- frontend/backend RPC 调用；
- HTTP 请求和响应处理；
- SSE 解码和事件应用；
- CLI 启动、下载、健康检查；
- session 创建、恢复和用户操作；
- MCP 注册；
- 配置、迁移和工作区状态解析。

`Operations.rpc` 在执行 block 时安装包含 operation ID 的协程诊断上下文。业务代码中的日志和显式报告自动继承该上下文，异常主记录和 operation end 可以直接关联。

## 队列、文件与指标质量

wire channel 继续只使用 `critical` 和 `diagnostic`，不改变现有出口语义。内存队列另分为 failure、critical、sample 三档：failure 包含 WARN/ERROR、异常详情、载荷分片和 operation end；critical 包含生命周期及 operation start/progress；sample 包含 EDT、资源和常规 health 采样。容量不足时按 sample → critical 的顺序淘汰，failure 不得被其他档位驱逐。

producer 仍不得在 EDT 上执行文件 IO。队列争用不再直接丢弃 failure；failure 使用独立无锁 MPSC 队列，writer 批量合并三个档位。进程在预算耗尽时允许拒绝新的超大 payload 分片，但必须保留主记录、哈希、原始长度和 `truncated=true`。

单 scope 文件预算从 10 MiB 提升到 50 MiB。容量重写优先保留最近 failure 记录及其完整分片组，再保留 critical，最后保留 sample；不得留下只有部分分片的 incident。

`telemetry.health` 增加 `drop_invalid`、`drop_contention`、`drop_capacity`、`drop_policy`、`drop_oversize`、`drop_evicted`、`drop_failure` 和 `quality`。任一 failure 或 operation end 丢失时 `quality=degraded`；指标消费者不得将该 run 的精确成功率标为可信。成功率以自包含 operation end 为准，不要求 start 作为分母。

## 特殊诊断

- EDT delay 超过 2 秒时，后台 watchdog 保存发生阻塞时的 EDT 完整堆栈，而不是恢复后的堆栈，并以 incident 关联 `edt.stall`。
- `plugin.unclean` 增加前一 run 的末尾 seq、最后事实时间、最后 writer flush 时间和最多 32 个未完成 operation ID；更大的集合以 payload 分片保存。
- JSON 解析失败保存原始异常、完整堆栈、JSON path、expected/actual 类型及失败载荷。
- HTTP 失败保存 method、无查询参数的 URL、status、headers（凭证过滤后）及必要 request/response body。

## 验收

- 单独复制 outbox 到空环境后，可以关联并解释 JSON 类型错误、HTTP 404、MCP bind 失败、EDT stall 和 unclean restart。
- 同一故障的 operation、异常、业务载荷和日志镜像共享 operation/trace/incident ID。
- 合成凭证在 message、stack、path、header 和 payload 中均不会原样落盘。
- 100 个并发 failure 在周期采样压满队列时全部保留；被牺牲的 sample 按原因计数。
- 分片可按 incident 无歧义重组，缺片或截断可检测。
- monolith 与 split mode 的 frontend/backend 都写入其正确 scope/run/side，且无需额外日志文件即可排障。
