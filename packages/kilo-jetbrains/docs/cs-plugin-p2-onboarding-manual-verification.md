# 专项二（csc/cs-cloud 新用户引导）手工验证清单

本清单对应分支 `feat-cs-plugin-p2-onboarding`。最终代码评审认定：端到端取消链路（Cancel 按钮 → fleet RPC 取消 → 后端 `awaitExitOrTimeout` → terminate 杀进程）在主力 Windows 开发机上没有自动化覆盖——真进程取消/超时测试为 POSIX-only，本机直接跳过；`Task.Backgroundable` 的 UI 接线同样没有自动化测试。因此**合并前需在真实 IDE 中执行本清单一次，并把结果记录到 PR 描述**。

## 前置

- 以 `gradlew buildPlugin` 产出的 zip 安装到沙箱 IDE（或直接 `runIde`）；确认沙箱环境未安装 csc（`~/.costrict/cs-cloud/server_url` 不存在），以复现首装链路。
- 建议同时在 POSIX 环境（WSL 或 CI 的 Ubuntu 腿）跑一遍 `:cs-cloud:test` 中标注 POSIX-only 的真进程取消/超时用例（`CscInstallerTest`/`CscCloudStarterTest`）。

## 核心：取消链路（5 分钟）

1. 工具窗连接失败引导卡点 **Install csc** → 观察后台进度任务出现（标题"正在安装 csc CLI…"）。
2. 在 `npm install` 进行中点进度条上的 **Cancel** → 确认：npm 进程退出（任务管理器/`tasklist` 无残留的 node/npm 直子进程）、无错误通知弹窗、进度任务消失。
3. 再次点 **Install csc** → 任务能正常重新发起（证明锁已释放；若只弹"正在安装中"提示即为锁泄漏回归）。
4. 对 **Start cs-cloud** 重复步骤 2-3。
5. 登录流程（`csc auth login`）验证不受影响：登录中不做取消杀进程（该路径设计上保留进程）。

## 引导面抽查

1. **CSC_NOT_INSTALLED**：banner 红色 summary 不重复卡片标题；卡片主按钮 **Install csc** + 文档链接可达；恢复弹出菜单只有 **Retry** + **Install csc**。
2. **DAEMON_DOWN**（如手动停掉 daemon）：卡片 **Start cs-cloud**；菜单 **Retry** + **Start cs-cloud**。
3. **UNAUTHORIZED**（daemon 运行未登录）：卡片 **Sign in to CoStrict**；菜单 **Retry** + **Sign in to CoStrict**；点击触发浏览器 `csc auth login`。
4. 空会话欢迎页：连接失败时欢迎语与 History 按钮之间出现同款引导区；连接恢复后消失。
5. 气球一次性：CSC_NOT_INSTALLED 首次弹 balloon（含 `npm install -g @costrict/csc` 文本 + **Install** + **不再提示**）；勾选不再提示后重启 IDE 不再弹；同一会话内不重复弹。
6. zh 界面抽查：IDE 中文语言下错误标题/描述、登录卡片、安装/启动进度标题均为中文；"CoStrict" 拼写统一。

## 记录

执行后请把每一节的结果（通过 / 问题 + 截图）贴到 PR 描述；参考实现报告见 `.superpowers/sdd/cs-plugin-legacy-features-and-onboarding-review/task-5-report.md`（该路径为 git-ignored 工作区，若不存在以本文件为准）。
