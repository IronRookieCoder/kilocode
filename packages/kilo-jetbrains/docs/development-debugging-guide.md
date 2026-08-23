# JetBrains IDE 插件边开发边调试指南

本文说明如何在 IntelliJ IDEA 中开发 Kilo JetBrains 插件，同时运行一个隔离的沙箱 IDE 进行断点调试。日常开发优先使用 Split Mode，因为它同时覆盖本地模式和 JetBrains Remote Development 的前后端边界。

## 1. 理解开发进程

调试时通常会同时存在以下进程：

| 进程 | 用途 |
|---|---|
| 开发 IDE | 打开 Kilo 仓库、编辑 Kotlin 代码、运行 Gradle 配置和查看断点。 |
| Split Mode backend | 加载 `backend/`，负责项目模型、CLI 进程、HTTP/SSE 和 RPC 实现。 |
| Split Mode frontend | 加载 `frontend/`，负责 Swing UI、用户交互和 RPC 调用。 |
| Kilo CLI | 由 backend 启动的 `kilo serve` 子进程。默认使用插件固定版本的已发布 CLI。 |

代码应按职责放置：

- `frontend/`：Swing UI、输入处理和延迟敏感逻辑。
- `backend/`：项目状态、索引、执行、CLI 生命周期和 RPC 实现。
- `shared/`：前后端共享的 RPC 接口及 `@Serializable` 数据。

## 2. 首次准备

在仓库根目录安装依赖：

```powershell
bun install
```

用 IntelliJ IDEA 打开仓库根目录。IDE 通常会自动识别 `packages/kilo-jetbrains/` 下的 Gradle 项目；如果没有识别，打开 **File > Settings > Build Tools > Gradle**，手动关联：

```text
packages/kilo-jetbrains/settings.gradle.kts
```

Gradle 任务需要 JDK 21。不要把 `java -version` 当作固定的启动前检查；只有 Gradle 报告 Java 缺失或版本不兼容时，再检查并切换到 JDK 21。

## 3. 最常用的开发循环

1. 在开发 IDE 中选择仓库自带的 `Run IDE (Split Mode)`。
2. 点击 **Debug** 而不是 **Run**，以便 backend 断点生效。
3. 等待沙箱 frontend 打开，在其中打开测试项目并操作 Kilo 工具窗口。
4. 修改代码后执行最小范围的编译或测试。
5. 对仅修改方法体的代码尝试 HotSwap；涉及类结构、资源或插件注册时，停止并重新启动调试配置。
6. 结合断点、调试控制台和前后端日志定位问题。

命令行等价方式如下，但从命令行启动不会自动获得开发 IDE 的断点调试体验：

```powershell
cd packages/kilo-jetbrains
.\gradlew.bat --no-configuration-cache runIdeSplitMode --purge-old-log-directories `
  -Pkilo.dev.storage.isolated=true `
  -Pkilo.dev.log.level=debug `
  -Pkilo.dev.log.chat.content=off
```

macOS 或 Linux 使用 `./gradlew`，并按当前 shell 的语法换行。

## 4. 选择正确的调试方式

### 同时验证完整插件

使用 `Run IDE (Split Mode)`。它会启动 backend 和 frontend，适合绝大多数功能开发，也能给 backend JVM 附加调试器。

需要注意：该配置启动的 frontend 不会附加 frontend JVM 调试器，因此 frontend 断点不会命中。

### 调试 frontend 断点

分别启动 backend 和 frontend，并让二者使用同一个固定端口：

1. 在 `Run IDE (Backend)` 的 Gradle 参数中把 `-Pkilo.splitModeServerPort=0` 改为一个空闲端口，例如 `-Pkilo.splitModeServerPort=12345`，启动该配置。
2. 在 `Run IDE (Frontend)` 中使用相同端口 `12345`。
3. 用 **Debug** 启动 `Run IDE (Frontend)`，然后在沙箱 frontend 中复现问题。

端口必须一致。`0` 表示让 Gradle 自动选择空闲端口，适合一次性启动完整 Split Mode，但不适合两个独立配置互相连接。

### 调试 backend 断点

使用 **Debug** 启动 `Run IDE (Split Mode)`，或者固定端口后分别启动 `Run IDE (Backend)` 和 `Run IDE (Frontend)`。断点应放在 `backend/src/main/kotlin/` 下的真实执行路径上。

### 只验证单体模式

仅当需要验证非远程、单 JVM 行为时运行：

```powershell
cd packages/kilo-jetbrains
.\gradlew.bat runIde -Pkilo.dev.storage.isolated=true
```

单体模式会在一个进程中加载 `shared`、`frontend` 和 `backend`，但不能替代 Split Mode 验证。

## 5. 修改代码后如何让变更生效

调试器中的 HotSwap 通常只适用于方法体内部的改动。修改后执行 **Build > Build Project**；如果 IDE 提示重新加载已修改类，可以继续当前沙箱会话。

以下变更应直接重启相应运行配置：

- 新增或删除类、方法、字段，或修改方法签名。
- 修改 `plugin.xml` 或 `kilo.jetbrains.{shared,frontend,backend}.xml`。
- 修改 Gradle 配置、依赖或模块关系。
- 修改资源文件、序列化数据结构或 RPC 接口。
- 修改 CLI 版本、CLI 构建产物或 backend 启动参数。
- HotSwap 失败，或运行结果与源码明显不一致。

只改 frontend 时，可保留固定端口的 backend，重启 `Run IDE (Frontend)`；只改 backend 时则重启 backend，并让 frontend 重新连接。跨 `shared/` 或 RPC 边界的改动必须重启两端。

## 6. 使用隔离的开发数据

仓库自带的三个运行配置默认传入：

```text
-Pkilo.dev.storage.isolated=true
```

backend 会让 CLI 使用当前 worktree 下的隔离目录：

```text
.kilo-dev/
  data/
  config/
  state/
  cache/
```

这样不会污染本机正式安装的 Kilo 会话、配置、状态和缓存。只有明确需要复用真实配置时才传入：

```text
-Pkilo.dev.storage.isolated=false
```

排查“正式环境正常、沙箱中缺少登录或配置”时，先确认这是否只是隔离存储带来的预期差异。

## 7. 查看日志

仓库运行配置默认启用 DEBUG 文件日志，并在每次运行时清理旧的沙箱日志目录。常用文件为：

```text
packages/kilo-jetbrains/.intellijPlatform/sandbox/kilo.jetbrains/kilo-frontend/kilo.log
packages/kilo-jetbrains/.intellijPlatform/sandbox/kilo.jetbrains/kilo-backend/kilo.log
```

如果实际沙箱布局不同，可在运行中的沙箱 IDE 打开日志目录，再查找 `kilo-frontend/` 和 `kilo-backend/` 子目录。轮转日志使用 `kilo.log.0`、`kilo.log.1` 等名称。

日志参数通过 Gradle `-P` 传入：

```text
-Pkilo.dev.log.level=debug
-Pkilo.dev.log.chat.content=off
-Pkilo.dev.log.chat.preview.max=160
```

`kilo.dev.log.chat.content` 支持：

| 值 | 用途 |
|---|---|
| `off` | 默认选择，只记录元数据，不记录聊天正文。 |
| `preview` | 记录清洗并截断的正文，适合排查事件内容。 |
| `full` | 记录清洗后的完整正文，仅用于短时间本地复现。 |

优先使用 `off`，需要确认 payload 时再临时改成 `preview`。`full` 会快速增大日志，也更容易写入敏感内容。

排查流式消息时，可从 `packages/kilo-jetbrains/` 运行：

```bash
script/dev/part-update.sh client <session-id>
script/dev/part-update.sh backend <session-id>
```

脚本会按 session ID 汇总 frontend 或 backend 的 `message.part.delta` 文本。Windows 上可通过 Git Bash 或 WSL 运行该 shell 脚本。

## 8. 同时联调本地 CLI

默认情况下，JetBrains 插件使用 `packages/kilo-jetbrains/package.json` 固定的已发布 CLI。仅修改 `packages/opencode/` 不会自动影响沙箱插件。

需要联调本地 CLI 源码时，在仓库根目录执行：

```powershell
bun .kilo/skills/jetbrains-cli-pin/script/cli-pin.ts unpin
cd packages/kilo-jetbrains
.\gradlew.bat :backend:buildRepoCli
```

然后重启 backend 或完整 Split Mode。后续每次修改 CLI 源码，都需要重新构建本地 CLI，并重启使用它的 backend。

完成联调后恢复发布 CLI 模式：

```powershell
cd ../..
bun .kilo/skills/jetbrains-cli-pin/script/cli-pin.ts pin
```

`kilo.cli.pinned=false` 只能用于本地开发，生产构建和发布流程会拒绝该状态。

## 9. 每轮改动后的最小验证

不要依赖启动沙箱来发现所有编译问题。根据改动范围运行最小相关检查。

编译全部 JetBrains Kotlin 源码和测试源码：

```powershell
cd packages/kilo-jetbrains
.\gradlew.bat typecheck
```

运行单个模块或单个测试类：

```powershell
.\gradlew.bat :frontend:test --tests "完整测试类名"
.\gradlew.bat :backend:test --tests "完整测试类名"
```

修改跨模块契约、Gradle 构建或插件装配后，再运行完整的 JetBrains 插件测试：

```powershell
.\gradlew.bat test
```

涉及 Split Mode API 放置时，还应在开发 IDE 中运行检查：

```text
Plugin DevKit | Code | Frontend and Backend API Usage
```

## 10. 常见问题

### frontend 断点不命中

`Run IDE (Split Mode)` 不会给它启动的 frontend 附加 frontend 调试器。改用固定相同端口的 `Run IDE (Backend)` 和 `Run IDE (Frontend)`，并以 Debug 方式启动 frontend。

### frontend 无法连接 backend

确认两个独立运行配置的 `kilo.splitModeServerPort` 是相同的固定端口，并检查该端口是否被旧进程占用。

### backend 启动后很快退出

检查上一次运行遗留的 Java/backend 进程。结束明确属于当前 JetBrains 沙箱的旧进程后，再重新启动配置。

### 改了 CLI 代码但行为没有变化

默认插件使用已发布的固定版本 CLI。切换到本地 CLI 模式，重新执行 `:backend:buildRepoCli`，然后重启 backend。

### 改了 Kotlin 代码但行为仍是旧的

HotSwap 可能不支持该类结构变化。重新编译并重启对应进程；如果改动涉及 `shared/`、RPC 或模块 XML，则重启 frontend 和 backend。

### 沙箱里没有真实账号或历史会话

这是 `kilo.dev.storage.isolated=true` 的预期结果。优先在 `.kilo-dev/` 中配置专用测试状态，不要为了方便长期关闭隔离。

## 推荐日常组合

| 场景 | 推荐方式 |
|---|---|
| 普通功能开发 | Debug `Run IDE (Split Mode)`，用日志观察两端。 |
| frontend UI 与交互断点 | 固定同一端口，先启动 backend，再 Debug frontend。 |
| backend、CLI 或 RPC 实现断点 | Debug Split Mode，或分别启动前后端。 |
| 仅改方法体 | 编译后尝试 HotSwap。 |
| 改类结构、资源、XML 或 RPC | 重启受影响进程；跨边界改动重启两端。 |
| 联调 `packages/opencode/` | 切换本地 CLI、重建 `:backend:buildRepoCli`、重启 backend。 |
