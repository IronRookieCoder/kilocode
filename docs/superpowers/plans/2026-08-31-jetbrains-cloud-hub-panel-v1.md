# Cloud Hub 面板 v1（JetBrains 端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 kilo-jetbrains 的 Settings 里加一个 Cloud Hub 页面，展示并启停 Costrict 云端收藏（skill/agent/command/mcp 四类），完全复用 cs-cloud daemon 现有 REST facade，后端仓零改动。

**Architecture:** 前端 Swing Settings 页（复用 `SettingsListPanel` 范式）→ split-mode RPC（`KiloAppRpcApi` 三方法穿线）→ `CsCloudConnectionService` 覆写（新增 `favoritesClient`）→ 手写 `CsCloudFavoritesApi` 直调 daemon 的 `/api/v1/agents/favorites*`（`CsCloudRoute` 未映射路径原样透传，无需改路由）。

**Tech Stack:** Kotlin（IntelliJ Platform Plugin）、kotlinx.serialization、OkHttp、kotlin.test + MockWebServer、Swing（禁 JCEF/Compose/UI DSL v2）。

**Spec:** `docs/superpowers/specs/2026-08-31-jetbrains-cloud-hub-panel-design.md`

## Global Constraints

- 只改 `packages/kilo-jetbrains` 单仓；**禁止**改 csc、cs-cloud 仓（spec D2：纯零后端）。
- UI 纯 Swing，禁 JCEF/Compose/Kotlin UI DSL v2（`packages/kilo-jetbrains/AGENTS.md`）。
- daemon 原生路径直调：`/api/v1/agents/favorites`、`/api/v1/agents/favorites/{id}/load|unload`；**列表不带 `?type=`**（spec §3）。
- scope 仅全局，无项目级；页面不实现 `SettingsDraftPage`（无可持久化修改）。
- i18n 只加 en + zh_CN 两个 properties，其余 17 语言靠标准资源束回退。
- 所有命令在 `F:/ai-coding/kilocode/packages/kilo-jetbrains` 目录下执行（`./gradlew`）。
- 状态枚举（daemon 返回，勿改）：`Cloud` / `Downloaded` / `Active` / `Unloaded`；类型枚举：`skill` / `agent` / `command` / `mcp`。

---

### Task 1: DTO + `CsCloudFavoritesApi`（含解析测试）

**Files:**
- Create: `shared/src/main/kotlin/ai/kilocode/rpc/dto/CloudFavoritesDto.kt`
- Create: `cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudFavoritesApi.kt`
- Test: `cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudFavoritesApiTest.kt`

**Interfaces:**
- Consumes: `CsCloudRoute.responseInterceptor()`（非 2xx 抛 `CsCloudRequestException(code, message, status)`，见 `CsCloudError.kt:9-33`）。
- Produces:
  - `@Serializable data class CloudFavoriteItem(id, slug, name, description, itemType, status, localPath)`
  - `@Serializable data class CloudFavoritesResult(ok, items, errorCode, errorMessage)`
  - `@Serializable data class CloudFavoriteActionResult(ok, item, errorCode, errorMessage)`
  - `object CloudFavoritesErrors { UNAUTHORIZED; NOT_FOUND; UNAVAILABLE; INTERNAL }`
  - `class CsCloudFavoritesApi(client: OkHttpClient, base: String)`，方法 `suspend fun list(): CloudFavoritesResult`、`suspend fun load(id: String): CloudFavoriteActionResult`、`suspend fun unload(id: String): CloudFavoriteActionResult`——**永不抛异常**，失败一律返回 `ok=false` DTO。

- [ ] **Step 1: 写失败测试**

```kotlin
package ai.kilocode.cscloud

import ai.kilocode.rpc.dto.CloudFavoritesErrors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsCloudFavoritesApiTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = CsCloudFavoritesApi(
        OkHttpClient.Builder().addInterceptor(CsCloudRoute.responseInterceptor()).build(),
        server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `list parses bare array`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a1","slug":"my-skill","name":"My Skill","description":"d","itemType":"skill",""" +
                    """"status":"Active","localPath":"C:\\s\\my-skill"}]""",
            ),
        )
        val result = api().list()
        assertTrue(result.ok)
        assertEquals(1, result.items.size)
        assertEquals("Active", result.items[0].status)
        assertEquals("/api/v1/agents/favorites", server.takeRequest().path)
    }

    @Test
    fun `list tolerates unknown fields`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a1","slug":"s","name":"n","itemType":"command","status":"Cloud","version":"2"}]""",
            ),
        )
        val result = api().list()
        assertTrue(result.ok)
        assertEquals("Cloud", result.items.single().status)
    }

    @Test
    fun `list maps 401 to UNAUTHORIZED`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"AUTH_REQUIRED","message":"not authenticated"}}"""),
        )
        val result = api().list()
        assertFalse(result.ok)
        assertEquals(CloudFavoritesErrors.UNAUTHORIZED, result.errorCode)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `load parses passthrough action body`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"item":{"id":"a1","slug":"my-skill","name":"My Skill",""" +
                    """"itemType":"skill","status":"Active"}}""",
            ),
        )
        val result = api().load("my-skill")
        assertTrue(result.ok)
        assertEquals("Active", result.item?.status)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/agents/favorites/my-skill/load", request.path)
    }

    @Test
    fun `unload maps 404 to NOT_FOUND`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val result = api().unload("gone")
        assertFalse(result.ok)
        assertEquals(CloudFavoritesErrors.NOT_FOUND, result.errorCode)
        assertNull(result.item)
    }
}
```

- [ ] **Step 2: 运行确认编译失败**

Run: `./gradlew :cs-cloud:test --tests "ai.kilocode.cscloud.CsCloudFavoritesApiTest"`
Expected: 编译 FAIL（`CsCloudFavoritesApi` / DTO 未定义）

- [ ] **Step 3: 写 DTO**

`shared/src/main/kotlin/ai/kilocode/rpc/dto/CloudFavoritesDto.kt`：

```kotlin
package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/** Stable error codes carried by cloud-favorites DTOs. */
object CloudFavoritesErrors {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val NOT_FOUND = "NOT_FOUND"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val INTERNAL = "INTERNAL"
}

/** A Costrict cloud favorite entry (skill/agent/command/mcp) with its local lifecycle status. */
@Serializable
data class CloudFavoriteItem(
    val id: String,
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    val itemType: String = "",
    val status: String = "",
    val localPath: String? = null,
)

/** Result of listing cloud favorites via the cs-cloud daemon. */
@Serializable
data class CloudFavoritesResult(
    val ok: Boolean,
    val items: List<CloudFavoriteItem> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/** Result of a cloud favorite load/unload action. */
@Serializable
data class CloudFavoriteActionResult(
    val ok: Boolean,
    val item: CloudFavoriteItem? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)
```

- [ ] **Step 4: 写 CsCloudFavoritesApi**

`cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudFavoritesApi.kt`：

```kotlin
package ai.kilocode.cscloud

import ai.kilocode.rpc.dto.CloudFavoriteActionResult
import ai.kilocode.rpc.dto.CloudFavoriteItem
import ai.kilocode.rpc.dto.CloudFavoritesErrors
import ai.kilocode.rpc.dto.CloudFavoritesResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Thin client for the daemon favorites facade. Rides a client built with
 * [CsCloudRoute.responseInterceptor], so non-2xx responses surface as
 * [CsCloudRequestException]. Never throws: failures return ok=false DTOs.
 */
class CsCloudFavoritesApi(
    private val client: OkHttpClient,
    private val base: String,
) {
    suspend fun list(): CloudFavoritesResult = withContext(Dispatchers.IO) {
        try {
            val body = execute("$base$LIST_PATH")
            CloudFavoritesResult(ok = true, items = json.decodeFromString(itemList, body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CloudFavoritesResult(ok = false, errorCode = code(e), errorMessage = e.message)
        }
    }

    suspend fun load(id: String): CloudFavoriteActionResult = action(LoadPath.format(validate(id)))

    suspend fun unload(id: String): CloudFavoriteActionResult = action(UnloadPath.format(validate(id)))

    private suspend fun action(path: String): CloudFavoriteActionResult = withContext(Dispatchers.IO) {
        try {
            val body = execute("$base$path", method = "POST")
            val parsed = json.decodeFromString(ActionBody.serializer(), body)
            CloudFavoriteActionResult(ok = parsed.success, item = parsed.item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CloudFavoriteActionResult(ok = false, errorCode = code(e), errorMessage = e.message)
        }
    }

    private fun execute(url: String, method: String = "GET"): String =
        client.newCall(Request.Builder().url(url).method(method, null).build()).execute().use { response ->
            response.body?.string().orEmpty()
        }

    /** Path traversal guard mirroring the daemon's own route validation. */
    private fun validate(id: String): String {
        require('/' !in id && '\\' !in id && id !in setOf(".", "..") && id.isNotBlank() && NUL !in id) {
            "invalid favorite id"
        }
        return id
    }

    private fun code(e: Exception): String = when (e) {
        is CsCloudRequestException -> when (e.status) {
            401, 403 -> CloudFavoritesErrors.UNAUTHORIZED
            404 -> CloudFavoritesErrors.NOT_FOUND
            else -> CloudFavoritesErrors.INTERNAL
        }
        is IllegalArgumentException -> CloudFavoritesErrors.INTERNAL
        else -> CloudFavoritesErrors.UNAVAILABLE
    }

    @Serializable
    private data class ActionBody(
        val success: Boolean = false,
        val item: CloudFavoriteItem? = null,
        val slug: String? = null,
    )

    private companion object {
        const val NUL = ' '
        const val LIST_PATH = "/api/v1/agents/favorites"
        const val LOAD_PATH = "/api/v1/agents/favorites/%s/load"
        const val UNLOAD_PATH = "/api/v1/agents/favorites/%s/unload"
        val json = Json { ignoreUnknownKeys = true }
        val itemList = ListSerializer(CloudFavoriteItem.serializer())
    }
}
```

注意：`NUL` 常量在源文件中必须是字面量 `'\u0000'`（上例展示为空格是排版限制），写成 `const val NUL = ' '` 时使用 `'\u0000'` 转义。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :cs-cloud:test --tests "ai.kilocode.cscloud.CsCloudFavoritesApiTest"`
Expected: PASS（5 个测试全绿）

- [ ] **Step 6: Commit**

```bash
git add shared/src/main/kotlin/ai/kilocode/rpc/dto/CloudFavoritesDto.kt cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudFavoritesApi.kt cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudFavoritesApiTest.kt
git commit -m "feat(jetbrains): add cloud favorites DTOs and daemon facade client"
```

---

### Task 2: `favoritesClient` 接入与连接服务覆写

**Files:**
- Modify: `cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudHttpClients.kt:10-15`（`CsCloudClients` 加字段）、`:37-48`（加 builder + 返回）
- Modify: `cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionService.kt`（`loginCsCloud` 覆写之后加 3 个覆写 + `favoritesApi()`；`closeTransport()` :258-268 加 shutdown）
- Test: `cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudConnectionServiceTest.kt`（追加测试）

**Interfaces:**
- Consumes: Task 1 的 `CsCloudFavoritesApi` / DTO / `CloudFavoritesErrors`。
- Produces: `CsCloudConnectionService` 实现 `cloudFavorites()` / `loadCloudFavorite(id)` / `unloadCloudFavorite(id)`（`KiloConnection` 的三个新方法——Task 3 定义接口签名，本任务先在服务类上实现同名方法；未连接时返回 `UNAVAILABLE`）。

- [ ] **Step 1: 写失败测试（追加到 CsCloudConnectionServiceTest）**

```kotlin
@Test
fun `favorites list and actions route through the daemon`() = runBlocking {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody("""{"ok":true,"data":{"status":"ok","version":"1.0.0"}}"""))
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setSocketPolicy(SocketPolicy.KEEP_OPEN),
    )
    server.enqueue(
        MockResponse().setBody(
            """[{"id":"a1","slug":"my-skill","name":"My Skill","itemType":"skill","status":"Cloud"}]""",
        ),
    )
    server.enqueue(
        MockResponse().setBody(
            """{"success":true,"item":{"id":"a1","slug":"my-skill","name":"My Skill",""" +
                """"itemType":"skill","status":"Active"}}""",
        ),
    )
    server.start()
    val root = Files.createTempDirectory("cs-cloud-favorites")
    Files.createDirectories(root.resolve(".costrict/cs-cloud"))
    Files.writeString(root.resolve(".costrict/cs-cloud/server_url"), server.url("/").newBuilder().host("127.0.0.1").build().toString())
    Files.writeString(root.resolve(".costrict/cs-cloud/config.json"), "{\"api_key\":\"secret\"}")
    val service = CsCloudConnectionService(
        scope,
        CsCloudEndpointResolver(root, emptyMap()),
        TestLog,
        timeout = 5_000,
        workspace = root,
    )
    try {
        service.connect()
        val listed = service.cloudFavorites()
        assertTrue(listed.ok)
        assertEquals(1, listed.items.size)
        assertEquals("Cloud", listed.items[0].status)
        val loaded = service.loadCloudFavorite("my-skill")
        assertTrue(loaded.ok)
        assertEquals("Active", loaded.item?.status)
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization")) // health
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization")) // sse
        assertEquals("/api/v1/agents/favorites", server.takeRequest().path)            // list
        val action = server.takeRequest()
        assertEquals("POST", action.method)
        assertEquals("/api/v1/agents/favorites/my-skill/load", action.path)
        assertEquals("Bearer secret", action.getHeader("Authorization"))
    } finally {
        service.dispose()
        server.shutdown()
    }
}

@Test
fun `favorites degrade to UNAVAILABLE before connect`() = runBlocking {
    val root = Files.createTempDirectory("cs-cloud-favorites-off")
    val service = CsCloudConnectionService(
        scope,
        CsCloudEndpointResolver(root, emptyMap()),
        TestLog,
        timeout = 5_000,
        workspace = root,
    )
    try {
        val listed = service.cloudFavorites()
        assertFalse(listed.ok)
        assertEquals("UNAVAILABLE", listed.errorCode)
    } finally {
        service.dispose()
    }
}
```

（文件顶部按需补 import：`ai.kilocode.rpc.dto.CloudFavoritesResult` 不需要——直接断言字段即可；`kotlin.test.assertEquals/assertTrue/assertFalse` 已有。）

- [ ] **Step 2: 运行确认编译失败**

Run: `./gradlew :cs-cloud:test --tests "ai.kilocode.cscloud.CsCloudConnectionServiceTest"`
Expected: 编译 FAIL（`cloudFavorites` 等方法未定义）

- [ ] **Step 3: 加 favoritesClient**

`CsCloudHttpClients.kt`——`CsCloudClients` 数据类加字段（:14 之后）：

```kotlin
data class CsCloudClients(
    val api: DefaultApi,
    val apiClient: OkHttpClient,
    val sseClient: OkHttpClient,
    val healthClient: OkHttpClient,
    val favoritesClient: OkHttpClient,
)
```

object 内加常量与 builder（`HEALTH_TIMEOUT_SECONDS` 旁）：

```kotlin
private const val FAVORITES_TIMEOUT_SECONDS = 120L
```

`create()` 里 `healthClient` 之后、`return` 之前加：

```kotlin
val favoritesClient = OkHttpClient.Builder()
    .addInterceptor(CsCloudRoute.interceptor(prefix, roots))
    .addInterceptor(CsCloudRoute.responseInterceptor())
    .apply { endpoint.key?.let { addInterceptor(auth(it)) } }
    .callTimeout(FAVORITES_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(FAVORITES_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()
```

`return CsCloudClients(...)` 加 `favoritesClient = favoritesClient,`。

- [ ] **Step 4: 连接服务覆写**

`CsCloudConnectionService.kt`——`loginCsCloud()` 覆写之后追加：

```kotlin
override suspend fun cloudFavorites(): CloudFavoritesResult =
    favoritesApi()?.list()
        ?: CloudFavoritesResult(ok = false, errorCode = CloudFavoritesErrors.UNAVAILABLE, errorMessage = "cs-cloud daemon is not connected")

override suspend fun loadCloudFavorite(id: String): CloudFavoriteActionResult =
    favoritesApi()?.load(id)
        ?: CloudFavoriteActionResult(ok = false, errorCode = CloudFavoritesErrors.UNAVAILABLE, errorMessage = "cs-cloud daemon is not connected")

override suspend fun unloadCloudFavorite(id: String): CloudFavoriteActionResult =
    favoritesApi()?.unload(id)
        ?: CloudFavoriteActionResult(ok = false, errorCode = CloudFavoritesErrors.UNAVAILABLE, errorMessage = "cs-cloud daemon is not connected")

private fun favoritesApi(): CsCloudFavoritesApi? {
    val client = clients?.favoritesClient ?: return null
    val url = endpoint?.base ?: return null
    return CsCloudFavoritesApi(client, url)
}
```

顶部补 import：`ai.kilocode.rpc.dto.CloudFavoriteActionResult`、`ai.kilocode.rpc.dto.CloudFavoritesErrors`、`ai.kilocode.rpc.dto.CloudFavoritesResult`。

`closeTransport()`（:258-268）的 `old?.let { ... }` 块内加一行：

```kotlin
shutdown(it.favoritesClient)
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :cs-cloud:test`
Expected: PASS（含既有全部测试——确认 favoritesClient 字段变更没有破坏 `CsCloudHttpClients` 其他用例）

- [ ] **Step 6: Commit**

```bash
git add cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudHttpClients.kt cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionService.kt cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudConnectionServiceTest.kt
git commit -m "feat(jetbrains): route cloud favorites through cs-cloud connection"
```

---

### Task 3: RPC 穿线（shared 接口 → backend 链 → 测试桩）

**Files:**
- Modify: `shared/src/main/kotlin/ai/kilocode/rpc/KiloAppRpcApi.kt:70`（`loginCsCloud` 之后加 3 个方法声明）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/app/KiloConnectionProvider.kt:49-51`（`KiloConnection` 加 3 个默认方法）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt:236`（`loginCsCloud` 之后加 3 个委托）
- Modify: `backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloAppRpcApiImpl.kt:80`（`loginCsCloud` 之后加 3 个覆写）
- Modify: `frontend/src/test/kotlin/ai/kilocode/client/testing/FakeAppRpcApi.kt`（加 3 个桩）

**Interfaces:**
- Consumes: Task 1 的三个 DTO；Task 2 的 `CsCloudConnectionService` 覆写。
- Produces（后续 Task 4 依赖的确切签名）:
  - `KiloAppRpcApi.cloudFavorites(): CloudFavoritesResult`
  - `KiloAppRpcApi.loadCloudFavorite(id: String): CloudFavoriteActionResult`
  - `KiloAppRpcApi.unloadCloudFavorite(id: String): CloudFavoriteActionResult`
  - `FakeAppRpcApi.cloudFavoritesResult` / `cloudFavoriteActionResult` 可变属性（frontend 测试可注入）

- [ ] **Step 1: 接口声明（先改接口，编译器驱动其余实现）**

`KiloAppRpcApi.kt`——`loginCsCloud()` 声明后加：

```kotlin
/** List Costrict cloud favorites (skills/agents/commands/mcp) from the cs-cloud daemon. */
suspend fun cloudFavorites(): CloudFavoritesResult

/** Enable (install + activate) a Costrict cloud favorite by slug or cloud id. */
suspend fun loadCloudFavorite(id: String): CloudFavoriteActionResult

/** Disable a Costrict cloud favorite by slug or cloud id. */
suspend fun unloadCloudFavorite(id: String): CloudFavoriteActionResult
```

顶部补 import：`ai.kilocode.rpc.dto.CloudFavoriteActionResult`、`ai.kilocode.rpc.dto.CloudFavoritesResult`。

- [ ] **Step 2: 运行确认编译失败**

Run: `./gradlew :backend:compileKotlin :frontend:compileTestKotlin`
Expected: FAIL（`KiloAppRpcApi` 的新方法在 `KiloAppRpcApiImpl` / `FakeAppRpcApi` 未实现）

- [ ] **Step 3: KiloConnection 默认降级**

`KiloConnectionProvider.kt`——`loginCsCloud()` 默认实现（:49-51）之后加：

```kotlin
/** List Costrict cloud favorites; unsupported by the locally managed Kilo CLI provider. */
suspend fun cloudFavorites(): ai.kilocode.rpc.dto.CloudFavoritesResult =
    ai.kilocode.rpc.dto.CloudFavoritesResult(
        ok = false,
        errorCode = ai.kilocode.rpc.dto.CloudFavoritesErrors.UNAVAILABLE,
        errorMessage = "cloud favorites are not managed by this connection",
    )

/** Enable a Costrict cloud favorite; unsupported by the locally managed Kilo CLI provider. */
suspend fun loadCloudFavorite(id: String): ai.kilocode.rpc.dto.CloudFavoriteActionResult =
    ai.kilocode.rpc.dto.CloudFavoriteActionResult(
        ok = false,
        errorCode = ai.kilocode.rpc.dto.CloudFavoritesErrors.UNAVAILABLE,
        errorMessage = "cloud favorites are not managed by this connection",
    )

/** Disable a Costrict cloud favorite; unsupported by the locally managed Kilo CLI provider. */
suspend fun unloadCloudFavorite(id: String): ai.kilocode.rpc.dto.CloudFavoriteActionResult =
    ai.kilocode.rpc.dto.CloudFavoriteActionResult(
        ok = false,
        errorCode = ai.kilocode.rpc.dto.CloudFavoritesErrors.UNAVAILABLE,
        errorMessage = "cloud favorites are not managed by this connection",
    )
```

- [ ] **Step 4: AppService 与 RpcApiImpl 委托**

`KiloBackendAppService.kt`——`loginCsCloud()`（:236）之后加：

```kotlin
/** List Costrict cloud favorites through the active connection. */
suspend fun cloudFavorites(): CloudFavoritesResult = connection.cloudFavorites()

/** Enable a Costrict cloud favorite through the active connection. */
suspend fun loadCloudFavorite(id: String): CloudFavoriteActionResult = connection.loadCloudFavorite(id)

/** Disable a Costrict cloud favorite through the active connection. */
suspend fun unloadCloudFavorite(id: String): CloudFavoriteActionResult = connection.unloadCloudFavorite(id)
```

顶部补 import（该文件已用 `ai.kilocode.rpc.dto.CsCloudStartDto` 风格）：`ai.kilocode.rpc.dto.CloudFavoriteActionResult`、`ai.kilocode.rpc.dto.CloudFavoritesResult`。

`KiloAppRpcApiImpl.kt`——`loginCsCloud()`（:80）之后加：

```kotlin
override suspend fun cloudFavorites(): CloudFavoritesResult = app.cloudFavorites()

override suspend fun loadCloudFavorite(id: String): CloudFavoriteActionResult = app.loadCloudFavorite(id)

override suspend fun unloadCloudFavorite(id: String): CloudFavoriteActionResult = app.unloadCloudFavorite(id)
```

顶部补同样两个 import。

- [ ] **Step 5: FakeAppRpcApi 桩**

`FakeAppRpcApi.kt`——`loginCsCloud` 桩之后加（沿用 `assertNotEdt` 风格）：

```kotlin
var cloudFavoritesResult = CloudFavoritesResult(ok = true)
var cloudFavoriteActionResult = CloudFavoriteActionResult(ok = true)

override suspend fun cloudFavorites(): CloudFavoritesResult {
    assertNotEdt("cloudFavorites")
    return cloudFavoritesResult
}

override suspend fun loadCloudFavorite(id: String): CloudFavoriteActionResult {
    assertNotEdt("loadCloudFavorite")
    return cloudFavoriteActionResult
}

override suspend fun unloadCloudFavorite(id: String): CloudFavoriteActionResult {
    assertNotEdt("unloadCloudFavorite")
    return cloudFavoriteActionResult
}
```

顶部补 import：`ai.kilocode.rpc.dto.CloudFavoriteActionResult`、`ai.kilocode.rpc.dto.CloudFavoritesResult`。

- [ ] **Step 6: 编译 + 全模块回归**

Run: `./gradlew :shared:compileKotlin :backend:compileKotlin :cs-cloud:test :frontend:compileTestKotlin`
Expected: PASS（纯委托无新逻辑；cs-cloud 测试复跑确认无回归）

- [ ] **Step 7: Commit**

```bash
git add shared/src/main/kotlin/ai/kilocode/rpc/KiloAppRpcApi.kt backend/src/main/kotlin/ai/kilocode/backend/app/KiloConnectionProvider.kt backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloAppRpcApiImpl.kt frontend/src/test/kotlin/ai/kilocode/client/testing/FakeAppRpcApi.kt
git commit -m "feat(jetbrains): expose cloud favorites over app RPC"
```

---

### Task 4: Hub 页 UI（`HubRowLogic` + Settings 页）

**Files:**
- Create: `frontend/src/main/kotlin/ai/kilocode/client/settings/hub/CloudHubConfigurable.kt`（含 `CloudHubSettingsUi` 与 `HubRowLogic`）
- Test: `frontend/src/test/kotlin/ai/kilocode/client/settings/hub/HubRowLogicTest.kt`

**Interfaces:**
- Consumes: `SettingsListPanel`（abstract `fetch(): List<ActiveListItem>` / `onCell(key, cellId)`；`mutateAndReload { suspend () -> Boolean }`；`launch` 捕获 `SettingsMessageException` 并 `showError`）；`ActiveListItem`（`key/title/description/section/badges/cells`）；`ActiveListBadge(text, UiStyle.Badge.Primary|Secondary)`；`ActiveListCell(id, label, primary)`；`KiloAppRpcApi.getInstance()`；Task 3 的三个 RPC 方法与 `CloudFavoritesErrors`。
- Produces: `CloudHubConfigurable.ID = "ai.kilocode.jetbrains.settings.agentBehavior.cloudHub"`（Task 5 XML 引用）；`HubRowLogic.ENABLE_CELL = "enable"` / `DISABLE_CELL = "disable"`。

- [ ] **Step 1: 写失败测试（纯逻辑，无 UI 依赖）**

```kotlin
package ai.kilocode.client.settings.hub

import ai.kilocode.rpc.dto.CloudFavoriteItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HubRowLogicTest {
    private fun item(id: String, type: String, status: String, name: String = id) =
        CloudFavoriteItem(id = id, slug = id, name = name, itemType = type, status = status)

    @Test
    fun `orders by section then status rank then name`() {
        val ordered = HubRowLogic.ordered(
            listOf(
                item("z-skill", "skill", "Cloud"),
                item("a-skill", "skill", "Active"),
                item("m-agent", "agent", "Active"),
                item("b-skill", "skill", "Active"),
            ),
        )
        assertEquals(listOf("a-skill", "b-skill", "z-skill", "m-agent"), ordered.map { it.id })
    }

    @Test
    fun `active sorts before downloaded cloud unloaded`() {
        val ordered = HubRowLogic.ordered(
            listOf(
                item("u", "skill", "Unloaded"),
                item("c", "skill", "Cloud"),
                item("d", "skill", "Downloaded"),
                item("a", "skill", "Active"),
            ),
        )
        assertEquals(listOf("a", "d", "c", "u"), ordered.map { it.id })
    }

    @Test
    fun `filters unknown item types`() {
        val ordered = HubRowLogic.ordered(
            listOf(item("ok", "skill", "Active"), item("weird", "prompt", "Active")),
        )
        assertEquals(listOf("ok"), ordered.map { it.id })
    }

    @Test
    fun `enable cell for every non-active status`() {
        for (status in listOf("Cloud", "Downloaded", "Unloaded", "")) {
            assertEquals(HubRowLogic.ENABLE_CELL, HubRowLogic.cellId(status), status)
        }
    }

    @Test
    fun `disable cell only for active`() {
        assertEquals(HubRowLogic.DISABLE_CELL, HubRowLogic.cellId("Active"))
    }
}
```

- [ ] **Step 2: 运行确认编译失败**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.settings.hub.HubRowLogicTest"`
Expected: 编译 FAIL（`HubRowLogic` 未定义）

- [ ] **Step 3: 写 CloudHubConfigurable.kt**

```kotlin
package ai.kilocode.client.settings.hub

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.settings.base.DraftReadyConfigurable
import ai.kilocode.client.settings.base.SettingsListPanel
import ai.kilocode.client.settings.base.SettingsMessageException
import ai.kilocode.client.ui.UiStyle
import ai.kilocode.client.ui.list.ActiveListBadge
import ai.kilocode.client.ui.list.ActiveListCell
import ai.kilocode.client.ui.list.ActiveListConfig
import ai.kilocode.client.ui.list.ActiveListItem
import ai.kilocode.rpc.KiloAppRpcApi
import ai.kilocode.rpc.dto.CloudFavoriteItem
import ai.kilocode.rpc.dto.CloudFavoritesErrors
import kotlinx.coroutines.CoroutineScope
import javax.swing.JComponent

/** Settings page listing Costrict cloud favorites; see docs/superpowers/specs/2026-08-31-jetbrains-cloud-hub-panel-design.md. */
class CloudHubConfigurable : DraftReadyConfigurable<JComponent>() {
    override fun getId(): String = ID
    override fun getDisplayName(): String = KiloBundle.message("settings.agentBehavior.cloudHub.displayName")

    override fun create(cs: CoroutineScope): JComponent = CloudHubSettingsUi(cs)

    override fun onReadyComponentCreated(component: JComponent) {
        (component as? CloudHubSettingsUi)?.reload()
    }

    companion object {
        const val ID = "ai.kilocode.jetbrains.settings.agentBehavior.cloudHub"
    }
}

internal class CloudHubSettingsUi(
    scope: CoroutineScope,
) : SettingsListPanel(scope, ActiveListConfig.Equal.copy(tooltip = false)) {
    private var cache: List<CloudFavoriteItem> = emptyList()

    /** Authoritative row updates from load/unload responses; merged into the next fetch. */
    private val overrides = mutableMapOf<String, CloudFavoriteItem>()

    init {
        start()
    }

    override suspend fun fetch(): List<ActiveListItem> {
        val result = KiloAppRpcApi.getInstance().cloudFavorites()
        if (!result.ok) throw SettingsMessageException(hubError(result.errorCode, result.errorMessage))
        cache = mergeOverrides(result.items)
        return cache.map(::row)
    }

    override fun onCell(key: String, cellId: String) {
        val item = cache.find { it.id == key } ?: return
        when (cellId) {
            HubRowLogic.ENABLE_CELL -> mutateAndReload { act(HubRowLogic.ENABLE_CELL, item) }
            HubRowLogic.DISABLE_CELL -> mutateAndReload { act(HubRowLogic.DISABLE_CELL, item) }
        }
    }

    private suspend fun act(cellId: String, item: CloudFavoriteItem): Boolean {
        val api = KiloAppRpcApi.getInstance()
        val result = if (cellId == HubRowLogic.ENABLE_CELL) api.loadCloudFavorite(item.id) else api.unloadCloudFavorite(item.id)
        if (!result.ok) throw SettingsMessageException(hubError(result.errorCode, result.errorMessage))
        result.item?.let { overrides[it.id] = it }
        return true
    }

    /** csc's list cache is 30s shared, so a row just acted on may come back stale; response items win. */
    private fun mergeOverrides(items: List<CloudFavoriteItem>): List<CloudFavoriteItem> {
        if (overrides.isEmpty()) return items
        val merged = items.map { overrides[it.id] ?: it }
        overrides.clear()
        return merged
    }

    override fun searchPlaceholder() = KiloBundle.message("settings.agentBehavior.cloudHub.search")

    override fun emptyText() = KiloBundle.message("settings.agentBehavior.cloudHub.empty")

    override fun loadingText() = KiloBundle.message("settings.agentBehavior.cloudHub.loading")

    private fun row(item: CloudFavoriteItem): ActiveListItem = object : ActiveListItem {
        override val key = item.id
        override val title = item.name.ifBlank { item.slug.ifBlank { item.id } }
        override val description = item.description
        override val search = "${item.slug} ${item.itemType}"
        override val section = sectionLabel(item.itemType)
        override val badges = listOf(ActiveListBadge(statusLabel(item.status), badgeStyle(item.status)))
        override val cells = listOf(
            ActiveListCell(
                HubRowLogic.cellId(item.status),
                cellLabel(item.status),
                primary = true,
            ),
        )
    }

    private fun badgeStyle(status: String) =
        if (status == HubRowLogic.ACTIVE) UiStyle.Badge.Primary else UiStyle.Badge.Secondary

    private fun sectionLabel(itemType: String) = KiloBundle.message(
        when (itemType) {
            "skill" -> "settings.agentBehavior.cloudHub.section.skills"
            "agent" -> "settings.agentBehavior.cloudHub.section.agents"
            "command" -> "settings.agentBehavior.cloudHub.section.commands"
            else -> "settings.agentBehavior.cloudHub.section.mcp"
        },
    )

    private fun cellLabel(status: String) = KiloBundle.message(
        if (status == HubRowLogic.ACTIVE) {
            "settings.agentBehavior.cloudHub.cell.disable"
        } else {
            "settings.agentBehavior.cloudHub.cell.enable"
        },
    )

    private fun statusLabel(status: String) = when (status) {
        HubRowLogic.ACTIVE -> KiloBundle.message("settings.agentBehavior.cloudHub.badge.active")
        HubRowLogic.DOWNLOADED -> KiloBundle.message("settings.agentBehavior.cloudHub.badge.downloaded")
        HubRowLogic.CLOUD -> KiloBundle.message("settings.agentBehavior.cloudHub.badge.cloud")
        HubRowLogic.UNLOADED -> KiloBundle.message("settings.agentBehavior.cloudHub.badge.unloaded")
        else -> status
    }

    private fun hubError(code: String?, fallback: String?): String = when (code) {
        CloudFavoritesErrors.UNAUTHORIZED -> KiloBundle.message("settings.agentBehavior.cloudHub.error.unauthorized")
        CloudFavoritesErrors.UNAVAILABLE -> KiloBundle.message("settings.agentBehavior.cloudHub.error.unavailable")
        CloudFavoritesErrors.NOT_FOUND -> KiloBundle.message("settings.agentBehavior.cloudHub.error.notfound")
        else -> fallback?.takeIf { it.isNotBlank() } ?: KiloBundle.message("settings.agentBehavior.cloudHub.error.internal")
    }
}

/** Pure presentation logic for the hub list; no platform or bundle dependencies. */
internal object HubRowLogic {
    const val ACTIVE = "Active"
    const val DOWNLOADED = "Downloaded"
    const val CLOUD = "Cloud"
    const val UNLOADED = "Unloaded"
    const val ENABLE_CELL = "enable"
    const val DISABLE_CELL = "disable"
    private val SECTION_ORDER = listOf("skill", "agent", "command", "mcp")

    fun ordered(items: List<CloudFavoriteItem>): List<CloudFavoriteItem> = items
        .filter { it.itemType in SECTION_ORDER }
        .sortedWith(
            compareBy(
                { SECTION_ORDER.indexOf(it.itemType) },
                { statusRank(it.status) },
                { it.name.lowercase() },
            ),
        )

    fun statusRank(status: String): Int = when (status) {
        ACTIVE -> 0
        DOWNLOADED -> 1
        CLOUD -> 2
        UNLOADED -> 3
        else -> 4
    }

    fun cellId(status: String): String = if (status == ACTIVE) DISABLE_CELL else ENABLE_CELL
}
```

（注：此时 bundle key 尚未添加，`KiloBundle.message` 缺 key 会在运行期报错——本任务只跑纯逻辑测试，key 在 Task 5 补齐；编译不受影响。）

- [ ] **Step 4: 运行逻辑测试确认通过**

Run: `./gradlew :frontend:test --tests "ai.kilocode.client.settings.hub.HubRowLogicTest"`
Expected: PASS（5 个测试全绿）

- [ ] **Step 5: 编译整个 frontend 确认无破坏**

Run: `./gradlew :frontend:compileKotlin`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/main/kotlin/ai/kilocode/client/settings/hub/CloudHubConfigurable.kt frontend/src/test/kotlin/ai/kilocode/client/settings/hub/HubRowLogicTest.kt
git commit -m "feat(jetbrains): add cloud hub settings panel UI"
```

---

### Task 5: 注册 + 链接 + i18n

**Files:**
- Modify: `frontend/src/main/resources/kilo.jetbrains.frontend.xml:113`（Skills 块之后插入）
- Modify: `frontend/src/main/kotlin/ai/kilocode/client/settings/agents/AgentBehaviorConfigurable.kt:29`（Skills 行之后加一行）
- Modify: `frontend/src/main/resources/messages/KiloBundle.properties`（agentBehavior 区块，:539 附近）
- Modify: `frontend/src/main/resources/messages/KiloBundle_zh_CN.properties`（对应区块）

**Interfaces:**
- Consumes: Task 4 的 `CloudHubConfigurable`（类名 + `ID`）与全部 bundle key 列表（下方逐条给出）。
- Produces: 设置树中出现 Cloud Hub 页；`KiloBundle.message("settings.agentBehavior.cloudHub.*")` 可用。

- [ ] **Step 1: XML 注册**

`kilo.jetbrains.frontend.xml`——Skills 块（:108-113）之后插入：

```xml
        <applicationConfigurable
                parentId="ai.kilocode.jetbrains.settings.agentBehavior"
                id="ai.kilocode.jetbrains.settings.agentBehavior.cloudHub"
                instance="ai.kilocode.client.settings.hub.CloudHubConfigurable"
                bundle="messages.KiloBundle"
                key="settings.agentBehavior.cloudHub.displayName"/>
```

- [ ] **Step 2: 父页链接**

`AgentBehaviorConfigurable.kt`——链接表 Skills 行（:29）之后加：

```kotlin
            KiloBundle.message("settings.agentBehavior.cloudHub.displayName") to CloudHubConfigurable.ID,
```

顶部补 import：`ai.kilocode.client.settings.hub.CloudHubConfigurable`。

- [ ] **Step 3: 英文文案**

`KiloBundle.properties` 的 agentBehavior 区块（:539 附近）追加：

```properties
settings.agentBehavior.cloudHub.displayName=Cloud Hub
settings.agentBehavior.cloudHub.search=Search favorites
settings.agentBehavior.cloudHub.empty=No favorite items yet. Favorite skills, agents, commands or MCP servers in the Costrict cloud to see them here.
settings.agentBehavior.cloudHub.loading=Loading favorites...
settings.agentBehavior.cloudHub.section.skills=Skills
settings.agentBehavior.cloudHub.section.agents=Agents
settings.agentBehavior.cloudHub.section.commands=Commands
settings.agentBehavior.cloudHub.section.mcp=MCP
settings.agentBehavior.cloudHub.badge.active=Active
settings.agentBehavior.cloudHub.badge.downloaded=Downloaded
settings.agentBehavior.cloudHub.badge.cloud=Cloud
settings.agentBehavior.cloudHub.badge.unloaded=Unloaded
settings.agentBehavior.cloudHub.cell.enable=Enable
settings.agentBehavior.cloudHub.cell.disable=Disable
settings.agentBehavior.cloudHub.error.unauthorized=Sign in to Costrict from the Kilo Code connection panel to use Cloud Hub.
settings.agentBehavior.cloudHub.error.unavailable=The cs-cloud daemon is not running. Start it from the Kilo Code connection panel.
settings.agentBehavior.cloudHub.error.notfound=This favorite no longer exists in the cloud. Refresh to update the list.
settings.agentBehavior.cloudHub.error.internal=Cloud Hub request failed. Try again.
```

- [ ] **Step 4: 中文文案**

`KiloBundle_zh_CN.properties` 对应区块追加：

```properties
settings.agentBehavior.cloudHub.displayName=云端 Hub
settings.agentBehavior.cloudHub.search=搜索收藏
settings.agentBehavior.cloudHub.empty=暂无收藏条目。在 Costrict 云端收藏 skill、agent、command 或 MCP 后即可在此管理。
settings.agentBehavior.cloudHub.loading=正在加载收藏...
settings.agentBehavior.cloudHub.section.skills=Skills
settings.agentBehavior.cloudHub.section.agents=Agents
settings.agentBehavior.cloudHub.section.commands=Commands
settings.agentBehavior.cloudHub.section.mcp=MCP
settings.agentBehavior.cloudHub.badge.active=已启用
settings.agentBehavior.cloudHub.badge.downloaded=已下载
settings.agentBehavior.cloudHub.badge.cloud=云端
settings.agentBehavior.cloudHub.badge.unloaded=已停用
settings.agentBehavior.cloudHub.cell.enable=启用
settings.agentBehavior.cloudHub.cell.disable=停用
settings.agentBehavior.cloudHub.error.unauthorized=尚未登录 Costrict，请从 Kilo Code 连接面板登录后使用云端 Hub。
settings.agentBehavior.cloudHub.error.unavailable=cs-cloud 守护进程未运行，请从 Kilo Code 连接面板启动。
settings.agentBehavior.cloudHub.error.notfound=该收藏条目在云端已不存在，刷新列表即可更新。
settings.agentBehavior.cloudHub.error.internal=云端 Hub 请求失败，请重试。
```

- [ ] **Step 5: 回归**

Run: `./gradlew :frontend:test`
Expected: PASS（含既有 Settings 相关测试——`AgentBehaviorConfigurableTest` 可能断言链接数量，若失败按测试语义更新断言）

- [ ] **Step 6: Commit**

```bash
git add frontend/src/main/resources/kilo.jetbrains.frontend.xml frontend/src/main/kotlin/ai/kilocode/client/settings/agents/AgentBehaviorConfigurable.kt frontend/src/main/resources/messages/KiloBundle.properties frontend/src/main/resources/messages/KiloBundle_zh_CN.properties
git commit -m "feat(jetbrains): register cloud hub settings page with i18n"
```

---

### Task 6: 真机验证 + 用户文档

**Files:**
- Create: `docs/jetbrains-cloud-hub-panel.md`（用户文档，新建于 kilocode 仓 docs/）

**Interfaces:**
- Consumes: 前五个任务的全部产物 + 一个已登录 Costrict 的真实 daemon。
- Produces: 验收结论（写入 PR 描述）与用户文档。

- [ ] **Step 1: 部署前自检（后端前提）**

Run（本机 shell，替换 `{daemon}` 与 `{key}`）:
```bash
curl -s -H "Authorization: Bearer {key}" "{daemon}/api/v1/agents/favorites" | head -c 400
```
Expected: JSON 数组（四类条目带 `status`）。若 404 → 现网 daemon 版本过旧，先升级 csc（spec §10 风险 4）；若 401 → 先 `csc auth login`。

- [ ] **Step 2: 构建 + 沙箱 IDE 验证**

Run: `./gradlew :frontend:buildPlugin`（或按 `docs/development-debugging-guide.md` 的调试沙箱启动）。

按 spec §8 清单逐项验证：
1. 未装 csc → Cloud Hub 页错误态含安装指引
2. daemon 停止 → 错误态含启动指引
3. 未登录 → 错误态含登录指引（401 → UNAUTHORIZED 文案）
4. 已登录 → 四类条目按 Skills/Agents/Commands/MCP 分组渲染，Active 组内置顶
5. 启用一个 skill → 行变 Active 徽标；新会话里该 skill 可用
6. 停用一个 skill → 行变 Unloaded；会话里失效
7. 双项目窗口 → 一边启停，另一边刷新后一致
8. 中英文界面文案正确（IDE 语言切换）

- [ ] **Step 3: 写用户文档**

`docs/jetbrains-cloud-hub-panel.md`：

```markdown
# Cloud Hub（JetBrains 端用户指南）

Cloud Hub 是 Costrict 云端收藏在 JetBrains 端的管理入口。

## 前置条件
- 已安装 csc CLI 并启动 cs-cloud daemon（Kilo Code 连接面板可一键安装/启动）
- 已登录 Costrict（`csc auth login`）

## 使用
1. 打开 Settings → Tools → Kilo Code → Agent Behavior → Cloud Hub
2. 列表按 Skills / Agents / Commands / MCP 分组展示云端收藏
3. 点击条目行的 Enable 安装并启用；点击 Disable 停用
4. 状态徽标：已启用（当前生效）/ 已下载（落盘未激活）/ 云端（未安装）/ 已停用

## 生效方式
启停写入全局 `~/.costrict` 配置世界，由 cs-cloud daemon 单点管理；
条目启用后在新会话中立即可用。版本更新与孤儿清理由 daemon 后台同步自动完成（约 5 分钟节奏）。

## 排障
- 提示未登录：Kilo Code 连接面板 → 登录
- 提示 daemon 未运行：Kilo Code 连接面板 → 启动/安装
```

- [ ] **Step 4: Commit**

```bash
git add docs/jetbrains-cloud-hub-panel.md
git commit -m "docs: add cloud hub panel user guide"
```

---

## 自审记录（Self-Review）

1. **Spec 覆盖**：架构/数据流（Task 1-3）→ UI 交互 §6（Task 4）→ 注册与 i18n §5（Task 5）→ 错误处理 §7（Task 1 错误映射 + Task 4 `hubError`）→ 测试 §8（Task 1/2/4 自动化 + Task 6 手动清单）→ 非目标未实现（正确）。覆盖完整。
2. **占位符扫描**：无 TBD/TODO；所有代码块为完整可编译内容（Task 1 Step 4 的 NUL 字面量已加注意事项）。
3. **类型一致性**：`CloudFavoritesResult`/`CloudFavoriteActionResult`/`CloudFavoriteItem` 字段在 Task 1 定义后，Task 2/3/4 引用一致；`HubRowLogic.ENABLE_CELL/DISABLE_CELL`、`CloudHubConfigurable.ID` 前后一致；`favoritesClient` 字段名在 Task 2 三处（data class、builder、shutdown）一致。
