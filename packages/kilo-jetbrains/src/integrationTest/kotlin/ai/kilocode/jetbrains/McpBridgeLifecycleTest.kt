package ai.kilocode.jetbrains

import ai.kilocode.jetbrains.mock.CsEvents
import ai.kilocode.jetbrains.mock.RecordedRequest
import com.intellij.driver.client.Driver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M 桥（JetBrains IDE MCP capability bridge）生命周期（方案 §3.2 M-1/M-2/M-3/M-8）：
 * prompt 前 `ensureCapability` → `CsCloudMcpBridge.ensure` 的门禁与绑定 → idle 沿撤销 → 凭据不落日志。
 *
 * 两个用例 = 两次 IDE 启动（spec §3.1 "class = one IDE session"）：
 *  - 默认 health 不声明 `conversation_ide_capability_v1`（mock 缺省）→ 门禁：无 PUT，prompt 照发（M-1）；
 *  - `scenario.withIdeCapability()` → PUT 先于 prompt、`IdeMcpCapabilitySpec` 形状、busy→idle 沿触发
 *    DELETE 且 generation 一致（M-2/M-3），随后校验 token/generation 不泄漏进 idea.log（M-8）。
 *
 * 真实 MCP 往返（csc 连 `/stream` 调工具、旧 token 401 失效）超出 mock 能力，归 T3/人工（M-7/M-9）。
 */
class McpBridgeLifecycleTest : IntegrationTestBase() {

    @Test
    fun `prompts flow without ide capability and never bind`() {
        runPluginIde("costrictMcpGate") {
            awaitColdStartReady()
            awaitSessionUiReady()

            // —— M-1: prompt 照常路由到 daemon ——
            val promptsBefore = promptAsyncPosts().size
            sendPrompt("gate check prompt")
            awaitPromptAsync(promptsBefore, timeoutMs = 30_000)

            // daemon 未声明能力 → 不允许出现任何 bind PUT（awaitNoRequest 语义 + settle 窗口）。
            daemon.awaitNoIdeCapabilityRequest("PUT")
        }
    }

    @Test
    fun `ide capability binds before prompt and releases on idle`() {
        // 绑定场景：health 声明 IDE 能力（必须在 IDE 连接前置好；reset 会在下个用例复原）。
        daemon.scenario.withIdeCapability()
        val result = runPluginIde("costrictMcpBindRelease") {
            awaitColdStartReady()
            awaitSessionUiReady()

            val putsBefore = daemon.ideCapabilityRequests("PUT").size
            val promptsBefore = promptAsyncPosts().size
            sendPrompt("bind check prompt")

            // —— M-2: PUT（绑定）先于 prompt/async（ensureCapability 在 chat.prompt 之前）——
            val bind = daemon.awaitIdeCapabilityRequest("PUT", putsBefore, timeoutMs = 60_000)
            val prompt = awaitPromptAsync(promptsBefore, timeoutMs = 30_000)
            val bindIndex = daemon.requests.indexOf(bind)
            val promptIndex = daemon.requests.indexOf(prompt)
            assertTrue(
                bindIndex in 0 until promptIndex,
                "capability PUT must reach the daemon before the prompt POST: bind@$bindIndex vs prompt@$promptIndex",
            )
            assertEquals(
                prompt.header("X-Workspace-Directory"),
                bind.header("X-Workspace-Directory"),
                "bind must carry the same workspace scoping header as the session traffic",
            )
            assertCapabilitySpecShape(bind)

            // —— M-3: busy → idle 沿触发 DELETE，generation 与 PUT 一致 ——
            val sessionId = sessionIdFrom(prompt.path)
            val deletesBefore = daemon.ideCapabilityRequests("DELETE").size
            broadcastSafely(CsEvents.sessionStatus(fixtureProjectDir.toString(), sessionId, "busy"))
            Thread.sleep(500) // 保证 idle 沿（prev != idle）被顺序处理
            broadcastSafely(CsEvents.sessionStatus(fixtureProjectDir.toString(), sessionId, "idle"))
            val release = daemon.awaitIdeCapabilityRequest("DELETE", deletesBefore, timeoutMs = 60_000)
            val generation = GENERATION_REGEX.find(bind.body)?.groupValues[1]
            assertEquals(generation, release.query["generation"], "release must carry the bound generation")
        }

        // —— M-8: 凭据（环境 api key、MCP token）不得落 idea.log ——
        assertIdeLogHasNoCredentials(ideLogText(result))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * `IdeMcpCapabilitySpec` 形状断言（source of truth：
     * `cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/IdeMcpProtocol.kt`，集成测试 classpath
     * 不含生产类，白名单在此保留副本并保持同步）。
     */
    private fun assertCapabilitySpecShape(bind: RecordedRequest) {
        val body = bind.body
        assertTrue(body.contains("\"generation\":\""), "spec must carry a generation: ${body.take(200)}")
        assertTrue(body.contains("\"type\":\"streamable_http\""), "transport must be streamable http: ${body.take(300)}")
        assertTrue(
            TRANSPORT_URL_REGEX.containsMatchIn(body),
            "transport url must be the loopback /stream endpoint: ${body.take(300)}",
        )
        assertTrue(body.contains("\"IJ_MCP_SERVER_PROJECT_PATH\""), "transport headers must carry the project path header")

        val tools = TOOLS_REGEX.find(body)?.groupValues?.get(1)
        checkNotNull(tools) { "spec must carry a tools array: ${body.take(300)}" }
        val listed = tools.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        assertTrue(listed.isNotEmpty(), "tools array must not be empty (empty ⇒ tools_disabled, no bind)")
        assertEquals(listed.size, listed.toSet().size, "tools list must not repeat entries")
        val unexpected = listed - COSTRICT_IDE_TOOLS
        assertTrue(unexpected.isEmpty(), "tools must stay within the Costrict whitelist, offending: $unexpected")
    }

    /** 从 PUT body 的 transport.headers 里取非 workspace 值作为 token 候选（M-8 用）。 */
    private fun tokenCandidates(bind: RecordedRequest): List<String> {
        val headersPart = bind.body.substringAfter("\"headers\":{", "").substringBefore("}")
        return Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(headersPart)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
            .mapNotNull { (name, value) ->
                value.takeIf { name != "IJ_MCP_SERVER_PROJECT_PATH" && value.length >= 8 }
            }
    }

    /** Push one SSE event once a stream is connected; SSE reconnects make this eventually true. */
    private fun broadcastSafely(eventJson: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (daemon.activeSseConnections() > 0) {
                daemon.broadcast(eventJson)
                return
            }
            Thread.sleep(200)
        }
        throw AssertionError("no SSE connection to broadcast into within 20s")
    }

    private fun promptAsyncPosts(): List<RecordedRequest> =
        daemon.requests("POST", "/api/v1/conversations").filter { it.path.endsWith("/prompt/async") }

    private fun awaitPromptAsync(before: Int, timeoutMs: Long): RecordedRequest {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val posts = promptAsyncPosts()
            if (posts.size > before) return posts[before]
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("prompt/async POST never reached the daemon within ${timeoutMs}ms")
            }
            Thread.sleep(50)
        }
    }

    private fun sessionIdFrom(path: String): String =
        path.removePrefix("/api/v1/conversations/").substringBefore('/')

    private companion object {
        /** `IdeMcpProtocol.COSTRICT_IDE_TOOLS` 副本（classpath 隔离，见 [assertCapabilitySpecShape]）。 */
        val COSTRICT_IDE_TOOLS: Set<String> = setOf(
            "analyze_calls", "get_file_problems", "lint_files", "get_project_dependencies",
            "get_project_modules", "get_symbol_info", "search_file", "search_regex", "search_symbol",
            "search_text", "read_file", "list_directory_tree", "get_all_open_file_paths",
            "open_file_in_editor", "build_project", "get_run_configurations", "execute_run_configuration",
        )

        val TRANSPORT_URL_REGEX = Regex("http://127\\.0\\.0\\.1:\\d+/stream")
        val GENERATION_REGEX = Regex("\"generation\":\"([^\"]+)\"")
        val TOOLS_REGEX = Regex("\"tools\":\\[(.*?)]")
    }
}
