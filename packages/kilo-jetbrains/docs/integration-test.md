# JetBrains 插件集成测试（Starter/Driver）

基于官方文档配置：<https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html>
已在本地完成端到端验证（构建 ZIP → 安装进真实 IDE → 启动 → Driver 连接 → 正常关闭）。

## 架构

集成测试运行在两个进程：

- **测试进程**：JUnit 5 执行 `src/integrationTest` 下的测试代码，通过 Starter 框架管理 IDE 生命周期、通过 Driver（JMX 协议，端口 7777）与 IDE 交互。
- **IDE 进程**：Starter 准备并启动真实 IDE，插件以 `buildPlugin` 产出的 ZIP 解压安装到该 IDE。

与 `frontend/`、`backend/` 里的 `BasePlatformTestCase` 单元测试不同：单元测试在测试进程内加载平台类，集成测试验证的是**完整插件发行包在真实 IDE 中的安装与启动**。

## IDE 来源（重要）

Starter 默认从 JetBrains 数据服务在线下载 IDE，但该下载路径依赖 `withVersion()`/`useRelease()`，且在本网络环境下发布列表滞后无法解析 2026.1。因此配置为**优先使用本地 IDE**：

- Gradle 的 `integrationTest` 任务自动探测 `.intellijPlatform/ides/` 下与 `libs.versions.intellij.platform` 同版本的完整 IDE 安装（由 `verifyPlugin` 任务下载，如 `IU-2026.1`），通过系统属性 `kilo.integrationTest.ideHome` 传给测试。
- 测试通过 Kodein DI 绑定 `IdeInstallerFactory` → `ExistingIdeInstaller` 使用该本地 IDE（复制到 `out/ide-tests/cache/builds/` 后启动）。
- 属性为空（其他机器/CI 无 verifier 缓存）时回退为在线下载最新发布版。
- 注意：`TestCase` 的 `withVersion()`/`useRelease()`/`useEAP()` 会把 `getInstaller` 硬编码为公共下载器、绕过上述 DI 绑定，测试中不要调用。

## 运行

```bash
# 从 packages/kilo-jetbrains/ 目录执行（需要 JAVA_HOME 指向 JDK 21）
./gradlew integrationTest
```

- `integrationTest` 任务由 `intellijPlatformTesting.testIdeUi` 注册，自动依赖 `buildPlugin`，并通过系统属性 `path.to.build.plugin` 把插件 ZIP 路径传给测试。
- **未挂接到 `check`**：需启动真实 IDE（本机缓存命中后单次约 2 分钟），只做显式运行。
- 过滤参数：`./gradlew integrationTest --tests "ai.kilocode.jetbrains.PluginTest"`。
- 测试产物（IDE 日志、截图、崩溃报告）位于仓库根 `out/ide-tests/tests/<IDE>-locally-installed-ide/<testName>/`。

## 编写测试

- 源码放在 `src/integrationTest/kotlin/`，JUnit 5（`org.junit.jupiter`）。
- IDE 进程的异常不会自动传播到测试进程。`PluginTest` 已通过 Kodein DI 覆写 `CIServer`：IDE MessageBus 中收集到的异常会调用 `reportTestFailure` 使测试失败。
- 与 IDE 交互写在 `useDriverAndCloseIde { ... }` 块内（接收者是 Driver，可通过 `@Remote` 接口调用 IDE 服务，或用 driver-sdk 的 UI 查询 API）。
- IDE 产品在 `TestCase(IdeProductProvider.IU, ...)` 指定，需与本地 IDE（`product-info.json`/目录名）的产品代码一致，否则 Starter 在 `newContext` 校验时报 "Product code must be the same"。

## 配置位置（build.gradle.kts）

| 部分 | 说明 |
|---|---|
| `sourceSets.create("integrationTest")` | 新测试源集，classpath 附加 main 输出 |
| `integrationTestImplementation extendsFrom testImplementation` | 与官方文档一致的依赖继承 |
| `testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")` | Starter + Driver 框架（`ide-starter-squashed`、`ide-starter-junit5`、`ide-starter-driver`、`driver-client/sdk/model`，版本跟随平台 build 261.22158.277） |
| `integrationTestImplementation(junit-jupiter / kodein-di-jvm / kotlinx-coroutines-core-jvm)` | Starter 框架要求的三个依赖（实际解析版本被 starter 的 junit-bom 提升至 5.13.4 / 7.26.1） |
| `integrationTestImplementation(kotlin("stdlib"))` | starter 带入的 kotlin-reflect 2.3.x 依赖 2.3.x stdlib，但其元数据未随之提升 stdlib，需显式补齐 |
| `integrationTestRuntimeOnly(junit-platform-launcher)` | Gradle 9 不再从自身发行版注入 launcher，必须显式声明（版本由 starter 带入的 junit-bom 对齐） |
| `intellijPlatformTesting.testIdeUi.registering` | 注册 `integrationTest` 任务（`TestIdeUiTask`，继承 Gradle `Test`），并注入 `kilo.integrationTest.ideHome` |

## 已踩过的坑

1. **Gradle 9 不再注入 JUnit Platform launcher**：缺 `junit-platform-launcher` 时报 "Failed to load JUnit Platform"。
2. **脚本内动态创建的配置没有类型安全访问器**：`integrationTestRuntimeOnly` 需用字符串形式 `"integrationTestRuntimeOnly"(...)`。
3. **JUnit 5.13 的 `Assertions.fail` 全部重载变为泛型** `<V> V fail(...)`：Kotlin 在 Unit 上下文无法推断 V，用 `throw AssertionError(...)` 替代。
4. **starter 携带 kotlin-reflect 2.3.20-RC2 但不提升 stdlib**：stdlib 停在 2.1.20 导致 `NoClassDefFoundError: kotlin/jvm/internal/KotlinGenericDeclaration`，显式加 `kotlin("stdlib")` 解决。
5. **`withVersion()` 系列方法绕过 DI installer 绑定**（见上）。
6. **`Test.systemProperty` 不解包 Provider**：传 Provider 会写入其 `toString()`，需 `.get()` 取值。
