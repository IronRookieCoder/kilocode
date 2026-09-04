# Costrict JetBrains 插件验证执行报告（最终结论）

> 轮次：2026-09-02 ~ 2026-09-04（T3 复现于 09-04 官方构建收口）。
> 依据方案：`docs/superpowers/specs/2026-09-02-jetbrains-verification-automation-plan.md`。
> 范围：U1–U8 功能性场景（U9 非功能维度本轮整体未执行）。
> 本文为精简版，只保留最终结论；执行明细（T1 失败清单、T2 受阻根因、8 项基建修复、T3 复现步骤与证据、场景逐项对照）见 git 历史版（提交 `666f744372`）。

## 1. 最终结论

| 层级 | 最终结果 | 结论 |
|---|---|---|
| T0 品牌扫描 + ZIP 门禁 | **PASS** | 用户可见 Kilo 残留 = 0（硬门禁）；21 个捆绑 jar 无平台库重复（R-4） |
| T1 单测批 | **3684 / 3704 PASS** | 20 个失败均为 Windows 环境断言问题（路径分隔符 / git objects 文件锁），base 对照证实非本分支引入 |
| T2 集成测试功能批 | **2 / 6 PASS，4 环境受阻** | BrandSmokeTest、ConnectionLifecycleTest 全绿；SessionLoop×2 / CloudHub / ColdRestart 受阻于「桌面共租输入失效 + dev IDE 连接污染」（环境而非产品）；过程中定位的 8 项测试基建缺陷已全部修复 |
| T3 真链路抽样 | **4 / 4 PASS** | 真实 agent 会话/流式/落盘/多轮 ✓；真实 Enable→目录联动→Disable 闭环 ✓；真实问答卡+回执 ✓；**真实代码审查 + R1 报告落盘 ✓（09-04 官方构建）** |

## 2. 缺陷与澄清项（最终状态）

- **A.（P1 → 已关闭）问答回执后工具注册表失效**：仅在 09-03 脏构建 daemon（v1.2.51-6-g67da670-dirty）复现。09-04 在官方构建 cs-cloud v1.2.55 + csc 4.2.28 完整复现同场景：问答回执后 bash/read/edit/write 全链路正常，`review-report.md`（5426 字节）五阶段完整落盘，R1 报告点达成，U7.10 升级为通过。判定为脏构建特有问题，官方构建无此缺陷。
- **B.（待产品确认）冷启动不自动连接**：实现为工具窗内容首次打开才发起连接（懒连接），与方案 G1「启动即 ready」前提不符。测试基线已按实现修正；是否需要"启动即连"待产品定标。
- **C.（P2）沙箱配置导入副作用**：中文 locale / Trial 提示干扰已用 `user.language=en` 等缓解；建议 CI 固定干净配置。
- **D.（既有，非本分支）**：T1 的 20 个 Windows 断言失败，建议统一 `File.separator` 归一化 + worktree 测试 git objects 清理容错。

## 3. 覆盖汇总

功能 76 步：✓ 34、◐ 12（均带真实链路或降级证据）、✗ 30（T2 输入依赖受阻 21、NF 法未跑 3、P2/人工可选 5、OAuth 人工 1）。

## 4. 待办（按优先级）

1. **T2 受阻批补跑**：空闲桌面时串行重跑 SessionLoop×2 / CloudHub / ColdRestart，连同 09-04 新增用例（McpBridgeLifecycleTest 等）——mock 固定端口要求串行；预计单轮 15–30 分钟。CloudHub 的 Settings gate 可尝试 `invokeAction("ShowSettings")` 替代 UI 菜单导航。
2. **产品定标两项**：CoStrict/Costrict 拼写唯一化（当前 154/36 并存）后启用 G4 Rule② 硬门禁；确认冷启动是否需要自动连接（缺陷 B）。
3. T1 Windows 断言治理（缺陷 D）。
4. NF 批与残留人工项（视觉目检、split-mode 冒烟、长稳）按方案顺延。

## 5. 产物索引

- T2 运行产物（日志/截图）：`out/ide-tests/tests/IU-locally-installed-ide/<用例名>/`
- mock 请求流水：`packages/kilo-jetbrains/build/integrationTest-mock-requests.log`
- T3 SSE 记录：`packages/kilo-jetbrains/sse-capture.log`、`sse-review.log`、`sse-nudge.log`
- T3 工作区：`out/t3-workspace/`（09-03 脏构建）；`out/t3-workspace-v2/`（09-04 官方构建，含 15 份审查产物 + `artifacts/sse-p1-repro.log`）
- 环境备注：集成测试 IDE 为本地缓存 IU-2026.1；daemon 会自愈换端口并重写 `server_url`，脚本须先读 `~/.costrict/cs-cloud/server_url`
