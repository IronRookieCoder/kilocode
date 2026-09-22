# JetBrains 插件稳定性指标：业务价值与统计定义

本文是指标口径与使用优先级（P0/P1）的唯一维护位置，定义指标的业务含义、衡量起止、统计规则、等级与登记约定。总体决策、事件字典、文件协议、源码接入点与交付分期见[稳定性设计与采集协议](./jetbrains-stability-design.md)。

本文仅定义指标，不定义日志等级、日志目录或日志上传规则。指标链路见[设计第10章](./jetbrains-stability-design.md#10-指标链路)，日志链路单独见[设计第11章](./jetbrains-stability-design.md#11-日志链路)；两者分别控制、转换、上报和验收。

与cs-cloud指标的边界：Agent Core的任务、模型回答、工具调用、Token和费用归cs-cloud侧指标设计负责，本文不重复统计。这是职责划分，不代表统一指标上报已实现；cs-cloud源码及Draft提案的核验结果见[设计2.1](./jetbrains-stability-design.md#21-cs-cloud源码核验)。插件“发送成功”只表示请求被接收且界面正确更新，不表示模型回答正确或任务完成。

## 指标总览

共24组业务指标：P0 11组、P1 13组，等级由本文维护。指标名词与第2章各组标题一致；主要技术指标省略`jetbrains_plugin_`前缀，操作类指标另有配套`_duration_seconds`直方图（个别命名见各组定义），完整清单见第2章各组定义与第3章登记约定。

### P0（11组）

| 分组 | 编号 | 指标名词 | 业务含义 | 主要技术指标 |
|---|---|---|---|---|
| [打开与可用](#21-打开与可用) | M01 | 面板能否打开 | 点击插件后是否空白、初始化失败 | toolwindow_setup_total |
| | M02 | 所需配置和资料能否加载 | 面板出现但一直加载，分辨配置、资料等依赖问题 | backend_load_total |
| | M03 | 打开后能否真正使用 | 打开后多久能开始工作，前置步骤的整体结果 | readiness_total |
| | M04 | 连接本地服务是否可靠 | 与本地服务通信是否可靠，区分一次成功与多次重试后成功 | connection_total（另有attempt诊断层） |
| | M05 | 工作会不会中断，能否自动恢复 | 断线后能否自愈，用户是否需要手动重试或重开IDE | connection_disconnect_total、connection_recovery_total |
| [会话与关键操作](#23-会话与关键操作) | M11 | 返回会话后能否继续工作 | 回来后历史、任务、权限状态是否都能继续 | session_open_total、session_restore_total |
| | M12 | 常用按钮操作能否完成 | 发送、停止、权限、保存等最常用交互的可靠性 | action_total |
| | M13 | 正在使用时有多少时间不可用 | 用时长衡量一次故障损失的工作时间 | active_seconds_total、unavailable_seconds_total |
| [异常与兼容](#24-异常与兼容) | M14 | 有多少次运行受到异常影响 | 版本异常影响面，防止重复打印虚增故障数 | error_total |
| [运行完整性与数据质量](#25-运行完整性与数据质量) | M16 | 监控是否漏采或积压 | “没有错误”是健康，还是记录没写下来、没上报 | observation_total等 |
| | M17 | 数据何时进入上报服务 | 看板是当前情况还是积压的旧数据；由cs-cloud登记 | 接入计划中的cs-cloud自观测 |

### P1（13组）

| 分组 | 编号 | 指标名词 | 业务含义 | 主要技术指标 |
|---|---|---|---|---|
| [首次使用前置](#22-首次使用前置) | M06 | 首次安装是否顺利 | 首次使用是否被组件安装挡住（无包管理器、网络、权限等） | csc_install_total |
| | M07 | 装好后能否启动服务 | 分清“装上但启动不了”和“启动后连不上” | csc_start_total |
| | M08 | cs-cloud凭据是否就绪 | 凭据复用cs-cloud，插件只观测凭据可用性与等待结果 | credentials_ready_total |
| | M09 | 备用CLI能否获取 | kilo-cli模式下是否被下载、解压、校验或权限阻断 | cli_download_total |
| | M10 | 升级是否要求额外迁移 | 升级后是否需要额外迁移操作才能使用 | migration_required_total |
| [异常与兼容](#24-异常与兼容) | M15 | 是否读不懂服务返回的数据 | 插件与服务升级不同步的协议兼容问题 | protocol_error_total |
| | M18 | 活跃会话是否遇到服务释放 | 工作中服务被清理的中断风险 | session_dispose_risk_total |
| | M19 | 前后端通信是否慢或失败 | 前端RPC通信的分层定位依据 | rpc_request_total |
| | M20 | 使用期间界面是否卡顿 | 用户感觉“点击没有反应”的响应问题 | edt_delay_duration_seconds等 |
| | M21 | 内容到达后界面更新是否及时 | 区分模型回复慢与内容到达后插件处理显示慢 | render_apply_duration_seconds、render_failure_total |
| | M23 | 插件提供的IDE能力是否可靠 | 应用编辑、打开差异、刷新文件等能力是否可靠 | ide_operation_total |
| | M24 | 长时间使用是否出现资源增长 | 越用越慢、会话关闭后资源不释放的风险 | owned_resources |
| [运行完整性与数据质量](#25-运行完整性与数据质量) | M22 | 运行是否正常结束 | 突然消失、强杀或关机问题的未知终态线索 | run_started_total、run_end_total |

**目录**

0. [指标总览](#指标总览)：24组指标的P0/P1分组速查
1. [公共统计规则](#1-公共统计规则)：操作与尝试分离、六种结果及分母、时间归因和窗口
2. [指标目录](#2-指标目录)：24组指标，按业务域分五组
3. [技术登记和累计转换](#3-技术登记和累计转换)

## 1. 公共统计规则

### 1.1 用户操作与自动重试分开

一次用户操作有一个operation_id，每次真实尝试有attempt_id。用户点一次连接，后台失败两次、第三次成功：用户层是1次成功；尝试层是3次尝试、2次失败。两层不能混为同一个成功率。

一次操作只有一个终态；手动再次发起是新操作，自动重试仍属于原操作。多个项目根目录的事件流同时失败，流级诊断可分别记录，但整体连接恢复只开启一个区间。

### 1.2 六种结果及分母

| result | 中文含义 | 例子 | 统计方式 |
|---|---|---|---|
| success | 达到明确完成条件 | 请求被接收且界面更新 | 技术成功 |
| failure | 明确失败 | 配置写入失败、界面初始化异常 | 技术失败 |
| timeout | 观测截止前未完成 | 连接超过30秒仍未就绪 | 技术失败，但不因此中断业务 |
| blocked | 缺少前置条件 | 凭据未就绪、未安装服务、必须迁移 | 单独展示，不归为插件异常 |
| cancelled | 预期取消 | 用户取消、关闭项目、正常卸载 | 单独展示，不归为技术失败 |
| unknown | 无法获得可信结果 | 终态证据不足（如end_kind无法判定），由插件侧事实显式携带 | 降低完整率，不算成功或崩溃 |

技术成功率=`success/(success+failure+timeout)`；成功到达比例=`success/全部成熟操作`。同时显示blocked/cancelled/unknown占比，两个比率不能都叫“成功率”。

例如100次打开：90成功、3失败、2超时、3凭据未就绪、1主动关闭、1未知。技术成功率90/95≈94.7%，成功到达比例90%，未知1%。只展示94.7%会隐藏部分用户进不去的问题。

“成熟操作”指已到业务观测截止时间的同一批操作，不代表相关文件已经交接完成。分子分母必须属于同一批；缺少开始事件的孤立终态单列为采集缺口。完整率=`有可信终态的成熟操作/全部成熟操作`，unknown不在分子。终态由插件单调时钟结算，cs-cloud不配对、不补造unknown；丢失的终态体现为覆盖缺口，由plugin.unclean与health损失增量观测，迟到的可信终态按事件时间归入原批次。终态与归窗规则见[设计10.2](./jetbrains-stability-design.md#102-终态与归窗)。

默认观测截止：界面初始化30秒、后端加载30秒、打开到可用60秒、逻辑连接30秒、恢复和普通交互30秒。安装/启动/凭据/下载按实际业务deadline随开始事件记录。插件用单调时钟在业务完成与截止之间结算唯一终态；业务超时后才完成只补诊断，不第二次结算，遥测不新增业务取消。截止前已完成但因缓冲或离线晚收到的end仍是有效终态，consumer不能把未收到end直接当timeout。

### 1.3 时间、归因和窗口

- P95表示95%的观测耗时不超过该值，不是平均值；成功与失败耗时分开，不能平均客户端P95。
- 同进程用单调时钟计时；跨机器通过ID关联，不相减两端的单调时钟。
- cause独立于result：plugin/ide/cs_cloud/agent_core/network/environment/user/unknown。连接失败不自动证明插件有缺陷。
- 按事件发生时间看5分钟、1小时、24小时；最近24小时允许离线补报，标记暂定，不能把补报算作当前突发。
- 比较版本时控制IDE、系统和场景差异，展示样本数；分母为0显示“无数据”，dev/test与prod隔离。

## 2. 指标目录

共24组业务指标（P0 11组、P1 13组），分组、等级与指标名词总览见文首[指标总览](#指标总览)；正文按业务域分五组，每组可以包含次数、耗时等多个技术指标。名称是统一候选名，尚未登记上线，不是已发布指标重命名。

### 2.1 打开与可用

面板初始化（M01）、资料加载（M02）、整体可用（M03）、逻辑连接（M04）与中断恢复（M05）构成从打开到可用的完整链路；M01/M02/M04是M03/M05失败时的阶段定位视图。

#### M01 面板能否打开

> 找出点击插件后空白、初始化失败的问题，回答“界面能不能打开”，不包含凭据或连接。

**如何衡量：** 从工具窗口创建入口到根视图安装和基础控制器订阅完成，每个项目的一次初始化只结算一次。create/setup是失败阶段，不是两次用户操作。

**技术指标：** `jetbrains_plugin_toolwindow_setup_total`、`jetbrains_plugin_toolwindow_setup_duration_seconds`；维度result、stage=create/setup。

**如何解读：** 现有setup内部捕获失败后外层仍可能发Opened，不能直接将Opened当成功。

#### M02 所需配置和资料能否加载

> 解释“面板出现了，但一直在加载”，分辨配置、账户资料等依赖问题。

**如何衡量：** 后端一次load开始到所需资料和服务加载完成，initial/recovery分开。它不是整个插件启动；重连load也不是重新安装或启动。

**技术指标：** `jetbrains_plugin_backend_load_total`、`jetbrains_plugin_backend_load_duration_seconds`；维度result、trigger、reason，原因timeout/profile_error/config_error/notifications_error/other。

**如何解读：** 旧load_failure单独计数并入本指标result=failure；现有Backend Load Completed只能作为该阶段接入点。

#### M03 打开后能否真正使用

> 回答用户最关心的“打开后多久能开始工作”，体现前置步骤的整体结果。

**如何衡量：** 从首次激活到界面、app/workspace、必要事件订阅和输入均就绪。每个前端激活上下文计一次；凭据未就绪、缺少服务等记blocked。

**技术指标：** `jetbrains_plugin_readiness_total`、`jetbrains_plugin_readiness_duration_seconds`；维度result、reason。

**如何解读：** blocked单独展示，不并入技术失败；展示口径为成功到达比例、技术成功率及成功耗时P95。失败时按M01/M02/M04阶段定位。

#### M04 连接本地服务是否可靠

> 判断插件能否与运行任务的本地服务通信，区分用户连接失败与“重试很多但最终成功”。

**如何衡量：** 逻辑连接从请求到发现endpoint、健康检查、全部必需流建立；真实尝试另计。后台poll不自动等于一次用户操作。

**技术指标：** `jetbrains_plugin_connection_total`、`jetbrains_plugin_connection_duration_seconds`；诊断层`jetbrains_plugin_connection_attempt_total`、`jetbrains_plugin_connection_attempt_duration_seconds`。维度按需取result、trigger、stage=resolve/health/streams、error_code。

**如何解读：** attempt失败率不能代替用户失败率。

#### M05 工作会不会中断，能否自动恢复

> 衡量工作的连续性，以及用户是否需要手动重试或重开IDE。

**如何衡量：** 已就绪的必要连接意外丢失开启恢复区间，全部恢复后结束；多个流同时失败只开一个区间，正常关闭排除。

**技术指标：** `jetbrains_plugin_connection_disconnect_total`、`jetbrains_plugin_connection_recovery_total`、`jetbrains_plugin_connection_recovery_duration_seconds`；维度reason（断开），result与intervention=automatic/manual（恢复两指标）。

**如何解读：** 自动恢复比例=截止前自动恢复区间/全部成熟恢复区间，并展示手动、超时、未知。connectionEpoch变化只代表新连接，不能证明daemon重启。连接诊断见M04。

### 2.2 首次使用前置

本组观测插件在首次使用旅程中的供给动作及其可感知结果：插件发起组件安装、发起服务启动、检测凭据就绪；插件自身无登录流程，凭据复用cs-cloud，M08观测的是凭据就绪而非认证过程。csc组件与daemon内部的过程细分由csc/cs-cloud侧遥测负责，本文只定义插件侧口径。

#### M06 首次安装是否顺利

> 找出首次使用被组件安装挡住的原因，例如没有包管理器、网络失败或目录无权限。

**如何衡量：** 从插件发起组件安装，到插件确认命令结果及组件可发现性，不包含后续启动；使用实际安装deadline。安装命令的内部过程（下载、解压、权限）由csc/cs-cloud侧遥测细分，插件只记录命令结果的错误分类。

**技术指标：** `jetbrains_plugin_csc_install_total`、`jetbrains_plugin_csc_install_duration_seconds`；维度result、package_manager、error_code。

**如何解读：** 包管理器只登记实际支持值，不因文档枚举而宣称支持。

#### M07 装好后能否启动服务

> 分清“装上但启动不了”和“启动后连不上”，提高支持排障效率。

**如何衡量：** 从插件发起服务启动，到插件确认启动命令成功且daemon可发现/健康；命令返回与健康确认分阶段，完整插件连接归M04。daemon自身运行稳定性由cs-cloud侧遥测负责。

**技术指标：** `jetbrains_plugin_csc_start_total`、`jetbrains_plugin_csc_start_duration_seconds`；维度result、stage=spawn/exit/health、error_code。

**如何解读：** 命令退出正常但健康超时，不能算服务可用。安装、启动、连接失败次数不能相加成受影响用户数。

#### M08 cs-cloud凭据是否就绪

> 找出“面板能打开、服务已连接，但凭据未就绪不能用”的问题。插件自身无登录流程，凭据由cs-cloud管理并复用；认证过程归cs-cloud侧观测，插件只观测凭据可用性与等待结果。

**如何衡量：** 从插件需要凭据的时刻（面板就绪检查、发起需认证请求）到凭据确认可用、明确不可用或截止；凭据获取与刷新由cs-cloud负责，插件不发起自己的认证流程。观测超时不杀掉等待中的凭据检测。

**技术指标：** `jetbrains_plugin_credentials_ready_total`、`jetbrains_plugin_credentials_ready_duration_seconds`；维度result、stage=probe/wait、error_code。

**如何解读：** 插件若引导触发cs-cloud登录（如CscLogin等待），其返回ok=true只表示流程还在继续，不能映射为凭据就绪。迟到就绪只补诊断；等待耗时可能包含用户完成cs-cloud登录的时间，不全归插件性能。

#### M09 备用CLI能否获取

> 判断kilo-cli模式的用户是否被下载、解压、校验或权限问题阻断。

**如何衡量：** 一次获取到可用文件校验完成，缓存命中记stage=cache，不混入下载耗时。

**技术指标：** `jetbrains_plugin_cli_download_total`、`jetbrains_plugin_cli_download_duration_seconds`；维度result、stage=download/extract/verify/cache、error_code，仅connection_provider=kilo-cli。

**如何解读：** 本组不能代表cs-cloud模式稳定性。

#### M10 升级是否要求额外迁移

> 解释“升级后不能直接使用”，衡量升级给用户增加的操作负担。

**如何衡量：** 每次激活首次进入MigrationRequired计一次，重复状态通知不重复计；M03同时记blocked。

**技术指标：** `jetbrains_plugin_migration_required_total`；维度migration_kind受控值。

**如何解读：** 迁移提示变多不等于故障变多，也不证明迁移成功。

### 2.3 会话与关键操作

#### M11 返回会话后能否继续工作

> 防止历史可见但任务、权限或问答状态丢失，用户无法继续。

**如何衡量：** 已有会话打开/重连恢复，到历史、订阅、pending状态和UI都恢复。新建会话单列create，不混入恢复分母。

**技术指标：** `jetbrains_plugin_session_open_total`、`jetbrains_plugin_session_open_duration_seconds`（mode=create）；`jetbrains_plugin_session_restore_total`、`jetbrains_plugin_session_restore_duration_seconds`（mode=open/reconnect）；维度mode、result、stage=history/subscription/pending/ui。

**如何解读：** 历史HTTP 200但权限卡片没恢复仍不是成功。本组比“历史请求成功率”更接近用户能否继续工作。

#### M12 常用按钮操作能否完成

> 衡量最常用交互的可靠性，按失败影响面排序修复。

**如何衡量：** 按下表起点和成功终点结算，每次用户操作一个终态。

**技术指标：** `jetbrains_plugin_action_total`、`jetbrains_plugin_action_duration_seconds`；维度action、result、cause。

| action | 起点 | 成功终点 | 不包含 |
|---|---|---|---|
| prompt_submit | 输入校验通过并接收发送意图 | 服务端接收且本地状态更新 | 模型首字、回答质量、任务完成 |
| stop | 点击停止 | 请求成功且对应状态更新 | 不将停止本身视为故障 |
| permission_reply | 提交权限决策 | 接收回复且等待卡片更新 | 用户思考时间 |
| question_reply | 提交答案 | 接收回复且问答卡片更新 | 用户阅读和输入时间 |
| settings_save | 校验通过并保存 | 持久化且重读到有效设置 | 未修改保存、校验提示 |

**如何解读：** 成功率按action分别统计，不能合并成单一成功率解读。

#### M13 正在使用时有多少时间不可用

> 次数会忽略“一次故障卡了半小时”；时长衡量工作时间损失。

**如何衡量：** 面板可见且IDE前台的项目观察区间，每30秒及切换时记录不重叠区间；连接、工作区或输入不可用累计unavailable，同项目多面板去重。

**技术指标：** `jetbrains_plugin_active_seconds_total`、`jetbrains_plugin_unavailable_seconds_total`；维度state=ready/connecting/blocked/error。

**如何解读：** 单位是项目活跃时间，不是人数或整机在线时间；睡眠、后台和失联时间不盲目累计。

### 2.4 异常与兼容

异常、协议兼容、服务释放、通信、UI线程、渲染、IDE桥接与自有资源的分层定位；单层计数都不能直接归因插件。

#### M14 有多少次运行受到异常影响

> 比较版本异常影响面，防止一个异常反复打印被误当大量独立故障。

**如何衡量：** 插件自有入口、异步任务、回调和UI边界的非预期异常，同fault_id计一次，预期取消排除。影响运行占比按run_id明细去重。异常计数不因日志详情限频而减少，限频仅作用于日志详情出口（采集约束见设计11.1）。

**技术指标：** `jetbrains_plugin_error_total`；维度component、error_class、handled。

**如何解读：** 占比不是IDE崩溃率。需识别Throwable中的类加载Error，但不吞致命错误；一个CoroutineExceptionHandler无法覆盖所有线程。运行分母依赖M22的run start/end事实（2.5节），随采集器基础能力提供。

#### M15 是否读不懂服务返回的数据

> 发现插件与服务升级不同步的兼容问题，解释“有返回但界面不更新”。

**如何衡量：** 已识别协议解码失败，或应用事件违反已知状态约束；正常忽略可选字段不计错误。

**技术指标：** `jetbrains_plugin_protocol_error_total`；维度transport、stage=decode/apply、error_code=unsupported_schema/decode_failed/apply_violation/other。

**如何解读：** 保留版本和安全错误码，不保存原始响应正文。

#### M18 活跃会话是否遇到服务释放

> 提前发现“工作中服务被清理”的中断风险。

**如何衡量：** 活跃会话收到global_disposed/server_instance_disposed，同来源事件去重，正常退出排除。

**技术指标：** `jetbrains_plugin_session_dispose_risk_total`；维度source。

**如何解读：** 不能直接等同会话或数据已丢失，需结合M05断线与恢复指标确认影响。

#### M19 前后端通信是否慢或失败

> 为关键操作变慢提供分层定位依据。

**如何衡量：** 一次前端RPC尝试开始到返回，按方法组而非任意方法名聚合，重试单列；排除采集器自身控制通信。

**技术指标：** `jetbrains_plugin_rpc_request_total`、`jetbrains_plugin_rpc_duration_seconds`；维度api_group=profile/config/session/workspace/mcp/other、result。

**如何解读：** RPC和daemon HTTP次数不能求和，二者也不保证一对一。

#### M20 使用期间界面是否卡顿

> 发现用户感觉“点击没有反应”的响应问题。

**如何衡量：** 面板可见且IDE前台，同JVM共用一个UI线程探针，每秒至多投递一次且最多一个未完成探针。插件在本机合并有效样本的阻塞区间：单个区间持续≥2秒即形成观测到的卡顿区间，仅合并明确相交或首尾相接的区间，不跨缺口推断连续性，逐条落盘为edt.stall事实；cs-cloud只计数与分布，不推导合并。探针字段与合并规则见[设计10.3](./jetbrains-stability-design.md#103-edt探针事实与卡顿推导)。已确认插件线程违规另计，不能由延迟推断违规。

**技术指标：** `jetbrains_plugin_edt_delay_duration_seconds`、`jetbrains_plugin_edt_stall_total`、`jetbrains_plugin_edt_violation_total`；operation仅用于已知违规。

**如何解读：** UI线程由整个IDE共享，不能自动归因插件；睡眠/探针调度中断归unknown。不新增invokeAndWait来检测卡顿，不宣称覆盖平台级freeze/crash。

#### M21 内容到达后界面更新是否及时

> 区分模型回复慢与“内容到了，但插件处理显示慢”。

**如何衡量：** 合并后的更新批次进入前端到模型状态和组件更新完成，不每Token落盘。

**技术指标：** `jetbrains_plugin_render_apply_duration_seconds`、`jetbrains_plugin_render_failure_total`；维度component、batch_size_bucket=1/2-5/6-20/21-100/100+。

**如何解读：** apply慢指向UI处理，但不证明屏幕像素已绘制，不是模型首Token耗时。

#### M23 插件提供的IDE能力是否可靠

> 判断应用编辑、打开差异、刷新文件等能力是否妨碍用户使用生成结果。

**如何衡量：** 插件本地处理器进入到返回，操作白名单apply_edit/open_diff/vfs_refresh/mcp_register。

**技术指标：** `jetbrains_plugin_ide_operation_total`、`jetbrains_plugin_ide_operation_duration_seconds`；维度operation、result。

**如何解读：** 与Agent Core侧的工具计数不能相加。

#### M24 长时间使用是否出现资源增长

> 提前发现越用越慢、会话关闭后资源不释放的风险。

**如何衡量：** 插件拥有的订阅、控制器、编辑器数量，每30秒快照，观察关闭会话后是否回落。

**技术指标：** `jetbrains_plugin_owned_resources`；维度resource=subscription/controller/editor。

**如何解读：** 单次高值不证明泄漏；JVM总内存/CPU不是插件独占消耗。

### 2.5 运行完整性与数据质量

运行身份与监控自身的数据质量：数据不完整或积压时，其余指标的结论都不可信。

#### M16 监控是否漏采或积压

> 判断“没有错误”是健康，还是记录没写下来、没交给cs-cloud。

**如何衡量：** 开始/结束完整性、写入失败、丢弃增量、待交接大小及最老年龄，每30秒和异常变化时记录。observation_total不新增采集点，由插件侧终态事实直接构成：run级复用M22的start/end/unclean，操作级复用各operation的end/timeout终态；cs-cloud不配对结算、不推断丢失终态，覆盖缺口由health损失增量与unclean表达。

**技术指标：** `jetbrains_plugin_observation_total`（phase=started/terminal）、`jetbrains_plugin_telemetry_dropped_total`、`jetbrains_plugin_telemetry_write_error_total`、`jetbrains_plugin_outbox_depth_bytes`、`jetbrains_plugin_outbox_oldest_age_seconds`；按需选phase、reason、channel。

**如何解读：** 完整率=有可信终态的成熟操作/全部成熟操作，unknown不在分子。磁盘满且进程崩溃时，丢失计数也可能丢失，不能承诺知道所有损失。

#### M17 数据何时进入上报服务（cs-cloud负责）

> 判断看板是现在还是十分钟前的情况，避免把积压误判为恢复。

**如何衡量：** 原事件产生到接口受理；分别记录唯一输出受理数、发送尝试数、未确认数、上报时延、拒绝/丢弃原因、解析错误和已接入端，日志出口保留X-Request-ID用于排障关联。

**技术指标（由cs-cloud登记）：** 接入计划中的cs-cloud自观测，维度source=jetbrains-plugin、sink=metrics/logs，分别统计两出口。已核验cs-cloud提案仍为Draft、任务为planning；当前源码未接入统一指标Sender及日志Sender，不能把提案中的cs_bridge_*候选名当已发布指标。M17需在cs-cloud中补齐定义、登记和实现，插件不生成云端上报成功指标。

**如何解读：** 日志接口受理不等于逐条持久化确认。

#### M22 运行是否正常结束

> 给突然消失、强杀或关机问题提供线索，定位未知终态来源。

**如何衡量：** 每个run记录start/end；下一插件实例按scope-id前缀检查前任文件，最后一条plugin.started后无plugin.shutdown即记unclean。不用全局last_clean_shutdown混合多个进程。基础run身份及start/end事件随采集器基础能力提供，支撑M14分母。

**技术指标：** `jetbrains_plugin_run_started_total`、`jetbrains_plugin_run_end_total`；维度end_kind=app_close/unload/unclean/unknown。

**如何解读：** unclean也可能是断电、IDE崩溃或记录丢失，不能叫插件崩溃率；采集初始化之前的失败不可见。

## 3. 技术登记和累计转换

### 3.1 字段、单位与维度

插件采集事实；cs-cloud按本文章节执行单位转换、维护累计状态并在其配置仓库完成登记，指标名与桶的口径以本文为准。新增观测点仍可能要升级插件，调整已有事实的统计策略不应要求插件升级。

| 项目 | 约定 |
|---|---|
| 前缀 | jetbrains_plugin_；daemon自身健康使用自有指标族 |
| 类型 | _total为累计counter，_duration_seconds为累计histogram；M13的_seconds_total为累计时长counter；depth/age/resources为gauge |
| 单位 | 秒seconds，字节bytes，次数times，丢弃条数records，资源count；内部duration_ms由cs-cloud除1000 |
| 客户端枚举 | 指标使用jetbrains，需在指标服务登记 |
| application | costrict-plugin，复用现有枚举并确认适用范围 |
| 版本和环境 | 采集时固定plugin_version/IDE版本/env，不用发送时版本覆盖历史 |
| 身份 | 用户/租户由认证富化；本地account_epoch与业务身份同步，切换永久退役旧epoch并丢弃未发数据，见设计8.1 |
| device_id | 随机安装标识，用于指标日志关联（指标事件无顶层device_id，需登记为受控label方可查询，否则仅日志侧可检索）；不假设卸载一定清除IDE持久设置 |
| producer_id/run_id | 区分采集进程和重置周期，不以设备ID代替 |

公共聚合维度按需选ide_product、ide_build_major、os_family、connection_provider、mode、side、result；不强制全部指标带所有维度。完整IDE构建号和关联ID作为明细字段保留，不默认变成聚合标签。契约公共维度env由服务端按鉴权上下文富化（词表为prod/staging），本地env=dev/test不得写入该维度；dev/test与prod的隔离改用受控标签plugin_env并在Spec登记。

### 3.2 受控值

| 字段 | 建议值域 |
|---|---|
| connection_provider | cs-cloud/kilo-cli/unknown |
| mode、side | monolith/split；monolith/frontend/backend |
| trigger | initial/manual/recovery |
| 连接error_code | none/csc_not_installed/daemon_down/unauthorized/non_loopback_url/timeout/sse_failed/health_failed/other |
| 安装error_code | none/npm_not_found/network/disk/permission/other |
| 启动error_code | none/csc_not_installed/timeout/spawn_failed/health_failed/other |
| 凭据error_code | none/csc_not_installed/credentials_missing/credentials_expired/timeout/other |
| 下载error_code | none/github_ratelimit/digest_mismatch/lock_timeout/readonly_fs/network/other |
| 操作error_code | none/deadline_exceeded/network/other |
| 断连reason | sse_closed/heartbeat_timeout/health_failed/process_exit/daemon_restart/partial_sse_failed/unknown；shutdown排除 |
| component | frontend/backend/cscloud/shared |
| error_class | linkage_error/no_class_def_found/io_error/timeout_exception/json_parse/npe/illegal_state/other；预期取消排除 |
| 丢弃reason | buffer_full/disk_full/expired/corrupt/oversize/quota/disabled |

daemon_restart必须有daemon实例身份变化等证据；只使用provider实际可观测的心跳和退出事件。未知归other，不以自由文本或null作为标签。具体stage/reason按每组定义登记。

### 3.3 累计与桶

counter/histogram在同一源序列内累计，不能每60秒清零上传。60秒可以是发送周期，不是统计重置周期。cs-cloud对事实先去重、再累计和持久化输出。

同一事实派生的次数、耗时等输出分别生成确定性ID，不能都复用输入event_id；生成方案由cs-cloud内部自定，约束为确定性、区分度与长度合规，累计键还须隔离account_epoch、映射版本及登记标签，详见[设计9.1](./jetbrains-stability-design.md#91-派生输出身份)。同源迟到事实的排序和最终批次归窗必须在阶段0验证；不能只验证多源求和而忽略乱序产生的非单调快照。

插件的producer/run与cs-cloud的process_epoch不同：consumer重启但插件run未变时，恢复该源已提交的去重及累计状态，不按daemon启动时间重新归零。cs-cloud提案按进程重置的默认规则不能直接套用到外部插件源；EventBuilder也必须保留插件采集时的版本、客户端类型和业务时间，具体适配边界见[设计2.2](./jetbrains-stability-design.md#22-与cs-cloud指标提案的对齐边界)。

| 耗时组 | 建议桶边界（秒，升序，不含+Inf） |
|---|---|
| 初始化/加载/可用/连接/恢复/会话/交互 | 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60, 180 |
| 安装/启动/凭据/下载 | 0.25, 1, 2, 5, 10, 30, 60, 120, 180, 300, 600 |
| RPC/IDE操作 | 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30 |
| EDT/渲染 | 0.001, 0.004, 0.008, 0.016, 0.032, 0.05, 0.1, 0.25, 0.5, 1, 2, 5 |

桶以登记Spec为准；同序列不能热换桶，新桶启用新的映射/序列版本，旧输出保持内容。count包含全部观测，sum为总耗时。

多源验收：A累计3→5、B累计8→9，增量是3；A新run从0→2另计2。先逐源识别重置和取增量再汇总。event_id去重不能代替序列隔离；现有metadata不参与聚合，只把run_id放入metadata不能证明查询正确，平台必须明确序列键支持。

### 3.4 Spec候选

第2章技术指标名、类型、单位、专属维度与本章共同组成登记清单（M17由cs-cloud在其配置仓库登记）。category复用client，owners按实际团队。histogram类指标（`_duration_seconds`等）登记时必须按3.3桶表携带bucketBounds（升序、不含+Inf），缺失会被Spec校验拒绝。以下为一项格式模板；完整登记文件在cs-cloud配置仓库按清单生成、校验并联调，不将旧26项模板原样部署。

```yaml
version: 1.0.0
owners: [ide-team]
indicators:
  - name: jetbrains_plugin_readiness_total
    displayName: 打开插件后到达可用状态的操作数
    category: client
    valueType: counter
    unit: times
    labels: [ide_product, ide_build_major, os_family, connection_provider, mode, side, result, reason]
    status: active
```

version是示意，不覆盖实际主Spec版本；同步登记jetbrains及/labels取值，costrict-plugin无需重复新增。
