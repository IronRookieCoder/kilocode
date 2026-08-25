# Costrict JetBrains IDE MCP Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each active Costrict JetBrains conversation a private, temporary JetBrains MCP connection for IDE analysis, search, read, build, and run capabilities while retaining csc as the only file-writing path.

**Architecture:** Add a conversation-scoped capability contract from the JetBrains backend to the `cs-cloud` provider, with `CsCloudMcpBridge` owning project matching, JetBrains authorization leases, generation replacement, and revocation. cs-cloud validates and forwards capability bindings to the matching csc serve session; csc stages a Streamable HTTP MCP client through its existing child-process control channel, filters tools again, and applies the two approval policies without persisting credentials.

**Tech Stack:** Kotlin/JVM 21, IntelliJ Platform `2026.1`, JetBrains MCP Server, coroutines, OkHttp, kotlinx.serialization, Go 1.25 `net/http`, Bun/TypeScript strict mode, Hono, Model Context Protocol TypeScript SDK.

**Spec:** `docs/superpowers/specs/2026-08-25-costrict-jetbrains-ide-mcp-bridge-design.md`

## Global Constraints

- Implement against JetBrains `idea/2026.1` tag `088fc74da710f5a4dc177b4ca539e11b669959f7`.
- Use `McpServerService.authorizedSession()` over HTTP Stream at `http://127.0.0.1:<port>/stream`; do not require the global MCP Server switch.
- The effective tool set is the fixed Costrict allowlist intersected with the user's current JetBrains Exposed Tools.
- Never expose `create_new_file`, `apply_patch`, `reformat_file`, `rename_refactoring`, `execute_terminal_command`, `execute_tool`, or any unlisted tool.
- Keep file creation, edit, deletion, patching, workspace sandboxing, Diff attribution, and file approval in csc's existing tools.
- Require csc control-plane approval for `build_project`; let JetBrains `ASK` provide the only approval for `execute_run_configuration`.
- Match a conversation directory to exactly one open, non-default, non-disposed Project `basePath`; do not promote child paths to a parent project.
- Keep MCP URLs, tokens, headers, and transport DTOs out of frontend/shared RPC and out of persistent configuration, logs, telemetry, SSE, and diagnostics.
- A zero-tool intersection permits the prompt without an MCP binding. Project mismatch, MCP plugin absence, listener failure, capability-version mismatch, and binding failure block the prompt.
- Preserve a lease across a short SSE disconnect; revoke on authoritative idle, abort, delete, project close, non-recovering provider shutdown, plugin shutdown, or service disposal.
- Do not automatically replay prompts or failed IDE MCP calls.
- Keep shared backend edits minimal and retain `kilocode_change` markers where the repository guard requires them.
- Work in isolated worktrees for `kilocode`, `cs-cloud`, and `csc`; do not include the pre-existing `docs/next.md`, `cs-cloud/README.md`, or `csc/tmp/` changes in commits.

---

### Task 1: Add Conversation IDE Capability Control To csc

**Repository:** `F:/ai-coding/csc`

**Files:**
- Create: `src/server/ideCapability.ts`
- Create: `src/server/routes/ideCapability.ts`
- Create: `src/server/__tests__/ideCapabilityRoutes.test.ts`
- Modify: `src/server/server.ts`
- Modify: `src/server/sessionHandle.ts`
- Modify: `src/entrypoints/sdk/controlSchemas.ts`
- Modify: `src/entrypoints/sdk/controlTypes.ts`
- Modify: `src/cli/print.ts`

**Interfaces:**
- `IdeCapabilityInput` is `{ generation: string; transport: { type: 'streamable_http'; url: string; headers: Record<string, string> }; tools: string[]; approval: { build_project: 'control_plane'; execute_run_configuration: 'jetbrains_ask' } }`.
- `SessionHandle.setIdeCapability(input: IdeCapabilityInput): Promise<IdeCapabilityResult>` sends the `ide_mcp_set` control request.
- `SessionHandle.clearIdeCapability(generation: string): Promise<{ cleared: boolean }>` sends `ide_mcp_clear`.
- csc exposes `PUT /session/:sessionID/capabilities/ide` and `DELETE /session/:sessionID/capabilities/ide?generation=...` only on the local serve API.
- `ide_mcp_set` returns `{ generation, accepted, tools, errors }`; `ide_mcp_clear` returns `{ generation, cleared }`.

- [ ] **Step 1: Write route validation tests.**

  In `ideCapabilityRoutes.test.ts`, build a Hono app around a fake `SessionManager` and cover: missing session, malformed generation, non-HTTP transport, non-loopback host, wrong `/stream` path, redirects encoded in the URL, duplicate tools, invalid approval literals, and missing generation on DELETE. Use these valid fixtures:

  ```ts
  const input = {
    generation: '6c3ce34e-41d7-42f4-9398-c6c08b8c84cc',
    transport: {
      type: 'streamable_http' as const,
      url: 'http://127.0.0.1:64342/stream',
      headers: {
        IJ_MCP_AUTH_TOKEN: 'secret',
        IJ_MCP_SERVER_PROJECT_PATH: 'C:\\work\\demo',
      },
    },
    tools: ['read_file', 'build_project'],
    approval: {
      build_project: 'control_plane' as const,
      execute_run_configuration: 'jetbrains_ask' as const,
    },
  }
  ```

- [ ] **Step 2: Run the new route test and confirm the missing route fails.**

  Run `bun test src/server/__tests__/ideCapabilityRoutes.test.ts`. Expected: failures because `createIdeCapabilityRoutes` and the handle methods do not exist.

- [ ] **Step 3: Define and validate the in-memory wire type.**

  In `ideCapability.ts`, define Zod schemas and inferred types. Parse URLs with `URL`, require `protocol === 'http:'`, accept only `127.0.0.1`, `localhost`, or `[::1]`, require `pathname === '/stream'`, reject username/password/search/hash, require non-empty string header names and values, deduplicate tools, and cap the list at the 17 names below. Do not add this payload to settings or session-index types.

  ```ts
  export const IDE_MCP_TOOLS = new Set([
    'analyze_calls', 'get_file_problems', 'lint_files',
    'get_project_dependencies', 'get_project_modules', 'get_symbol_info',
    'search_file', 'search_regex', 'search_symbol', 'search_text',
    'read_file', 'list_directory_tree', 'get_all_open_file_paths',
    'open_file_in_editor', 'build_project', 'get_run_configurations',
    'execute_run_configuration',
  ])
  ```

- [ ] **Step 4: Extend the child-process control schema.**

  Add these members to `SDKControlRequestInnerSchema` and export their inferred response types through `controlTypes.ts`:

  ```ts
  z.object({ subtype: z.literal('ide_mcp_set'), capability: IdeCapabilitySchema() })
  z.object({ subtype: z.literal('ide_mcp_clear'), generation: z.string().uuid() })
  ```

  The schema must use a shared definition imported from `src/server/ideCapability.ts`, so the HTTP route and stdin control channel cannot drift.

- [ ] **Step 5: Add SessionHandle control methods and Hono routes.**

  `PUT` must call `sessionManager.getSession(id)` and return 404 when no live handle exists; the JetBrains binding is established immediately before a prompt, so it must not resume a history-only session. `DELETE` is idempotent: return `{ generation, cleared: false }` when the handle is absent. Mount `createIdeCapabilityRoutes(sessionManager)` in `server.ts`.

- [ ] **Step 6: Add separate IDE MCP state in the print child.**

  Keep `ideMcpState` separate from user-configured and SDK dynamic MCP state:

  ```ts
  type IdeMcpState = {
    generation?: string
    config?: ScopedMcpServerConfig
    client?: MCPServerConnection
    tools: Tool[]
  }
  ```

  Handle `ide_mcp_set` and `ide_mcp_clear` in the stdin loop. Convert `transport.type === 'streamable_http'` to the existing in-process `{ type: 'http', url, headers }` MCP config, use the fixed internal server name `costrict-jetbrains`, and never write the config to `.mcp.json`, settings, session index, or transcript. Filter the candidate result by `capability.tools` before merging `ideMcpState.tools` into `getCurrentTools()` so unrequested server tools are never available to the model.

- [ ] **Step 7: Stage replacement before closing the prior client.**

  Use `prefetchAllMcpResources({ 'costrict-jetbrains': scopedConfig })` to initialize and list tools for the candidate binding. Reject the control request if the result is not connected. Publish the new state first, then call `cleanup()` and `clearServerCache('costrict-jetbrains', oldConfig)` for the prior generation. If staging fails, close only the candidate and leave the old generation active. An `ide_mcp_clear` whose generation is stale returns `cleared: false` and must not touch current state.

- [ ] **Step 8: Prove replacement and stale deletion behavior.**

  Add tests that use a real local Streamable HTTP test server and two generations. Assert generation two completes initialize and `tools/list` before the first transport closes, stale DELETE leaves generation two connected, matching DELETE closes it, and the auth/project headers appear on initialize and tools requests. Assert response bodies and captured logs never contain the token.

- [ ] **Step 9: Run csc checks and commit.**

  ```bash
  bun test src/server/__tests__/ideCapabilityRoutes.test.ts src/server/__tests__/sessionHandle.test.ts
  bun run typecheck
  bun run lint
  git add src/server src/entrypoints/sdk/controlSchemas.ts src/entrypoints/sdk/controlTypes.ts src/cli/print.ts
  git commit -m "feat: add conversation IDE MCP control"
  ```

### Task 2: Enforce IDE Tool And Approval Policy In csc

**Repository:** `F:/ai-coding/csc`

**Files:**
- Create: `src/services/mcp/idePolicy.ts`
- Create: `src/services/mcp/__tests__/idePolicy.test.ts`
- Modify: `src/services/mcp/client.ts`
- Modify: `src/cli/print.ts`

**Interfaces:**
- `setIdeMcpPolicy(generation: string, tools: ReadonlySet<string>): void` installs only process-local policy for `costrict-jetbrains`.
- `clearIdeMcpPolicy(generation: string): boolean` ignores stale generations.
- `filterIdeMcpTools(server: string, tools: Tool[]): Tool[]` checks `tool.mcpInfo?.toolName` against the binding allowlist.
- `ideMcpPermission(server: string, tool: string): 'allow' | 'control_plane' | undefined` returns special policy only for the fixed server.

- [ ] **Step 1: Write policy tests before changing the MCP client.**

  Assert all 17 allowed names pass, every explicit exclusion and an invented future tool fail, `build_project` maps to `control_plane`, `execute_run_configuration` maps to `allow`, stale clear is ignored, and a new generation replaces the prior tool set.

- [ ] **Step 2: Run the policy test and confirm it fails on missing exports.**

  Run `bun test src/services/mcp/__tests__/idePolicy.test.ts`. Expected: module/export resolution failure.

- [ ] **Step 3: Filter the actual `tools/list` result.**

  In `fetchToolsForClient`, apply `filterIdeMcpTools(client.name, convertedTools)` after sanitization and conversion. Treat `tools/list` as authoritative: missing requested names are absent, not synthesized, and the returned accepted tool list is the filtered `mcpInfo.toolName` set.

- [ ] **Step 4: Make build approval bypass-immune and avoid duplicate run approval.**

  For the IDE `build_project` tool override `requiresUserInteraction()` to return `true` and return an `ask` permission decision. This forces the existing csc control-plane permission request even in brave/bypass modes. For the other IDE tools, including `execute_run_configuration`, return `allow`; JetBrains receives `McpSessionOptions(ASK, ...)` and owns the run confirmation.

- [ ] **Step 5: Disable automatic call replay for the IDE server.**

  In the MCP call loop replace the fixed retry count with:

  ```ts
  const retries = client.name === IDE_MCP_SERVER ? 0 : 1
  ```

  Keep the existing retry behavior for every non-IDE MCP server. Add a test where the JetBrains test server closes during a tool call and assert exactly one `tools/call` request.

- [ ] **Step 6: Verify removed and unavailable tools cannot execute.**

  Add a route/control integration test that requests `read_file` plus `build_project` while the server lists only `read_file`. Assert the accepted set contains only `read_file`, no callable build tool is installed, and a later generation that removes `read_file` removes it from model tools before acknowledgment.

- [ ] **Step 7: Run csc checks and commit.**

  ```bash
  bun test src/services/mcp/__tests__/idePolicy.test.ts src/server/__tests__/ideCapabilityRoutes.test.ts
  bun run typecheck
  bun run lint
  git add src/services/mcp src/cli/print.ts src/server/__tests__/ideCapabilityRoutes.test.ts
  git commit -m "feat: enforce JetBrains MCP tool policy"
  ```

### Task 3: Add The cs-cloud Capability Contract

**Repository:** `F:/ai-coding/cs-cloud`

**Files:**
- Create: `internal/localserver/ide_capability.go`
- Create: `internal/localserver/ide_capability_test.go`
- Modify: `internal/localserver/server.go`
- Modify: `internal/localserver/health_handler.go`
- Modify: `internal/agent/csc/agent.go`
- Modify: `internal/runtime/manager.go`
- Test: `internal/agent/csc/agent_test.go`

**Interfaces:**
- `PUT /api/v1/conversations/{id}/capabilities/ide` accepts the versioned public request from the spec.
- `DELETE /api/v1/conversations/{id}/capabilities/ide?generation=<uuid>` is idempotent and generation-aware.
- `(*csc.Agent).SetIDECapability(ctx context.Context, sessionID string, input IDECapabilityInput) (IDECapabilityResult, error)` forwards to csc's raw local endpoint.
- `(*csc.Agent).ClearIDECapability(ctx context.Context, sessionID, generation string) (bool, error)` clears a matching binding.
- `healthData.Capabilities []string` contains `conversation_ide_capability_v1`.

- [ ] **Step 1: Write HTTP contract tests with a real `httptest.Server`.**

  Cover API authentication, `version == 1`, UUID generation, exact immutable workspace match using `EventBus.GetSessionCwd(id)`, loopback `/stream` validation, required project header equality, fixed tool names, approval literals, same-generation idempotency, same-generation payload conflict with `409 capability_generation_conflict`, staged generation replacement, stale/repeated DELETE, and shutdown cleanup.

- [ ] **Step 2: Run the tests and confirm the endpoints are missing.**

  Run `go test ./internal/localserver ./internal/agent/csc`. Expected: new handler and agent-method compile failures.

- [ ] **Step 3: Add typed request, fingerprint, and memory-only binding state.**

  Define `ideCapabilityStore` with a mutex and `map[string]ideBinding`. Store only generation, SHA-256 fingerprint, canonical workspace, tool names, and the owning csc agent reference; do not retain the request headers after csc acknowledges. Compute the fingerprint from canonical JSON so key ordering cannot create false conflicts.

- [ ] **Step 4: Forward to the exact csc session.**

  Resolve with `s.manager.ResolveCSCAgent(id)`, then call the new agent methods against `a.rawEndpoint + "/session/" + url.PathEscape(id) + "/capabilities/ide"`. Decode csc errors into stable cs-cloud errors without logging the request body. Add an `AgentManager` narrow method so localserver tests can replace this dependency without mocking the HTTP validation itself.

- [ ] **Step 5: Make generation replacement atomic at the cs-cloud layer.**

  Serialize PUT/DELETE per conversation. On a new generation, call csc PUT first; only after it returns `accepted: true` publish the new `ideBinding`. Then best-effort clear the prior generation. A failed candidate leaves the old store entry unchanged. A same-generation/same-fingerprint retry returns the stored result without forwarding again.

- [ ] **Step 6: Advertise capability support and clear on shutdown.**

  Add `Capabilities: []string{"conversation_ide_capability_v1"}` to `/runtime/health`. During `Server.Shutdown`, clear all current bindings before `manager.KillAll()`; continue shutdown if clearing fails, log only conversation/generation hashes and the stable error code, then discard all in-memory state.

- [ ] **Step 7: Run focused Go tests and commit.**

  ```bash
  go test ./internal/localserver ./internal/agent/csc ./internal/runtime
  git add internal/localserver internal/agent/csc internal/runtime
  git commit -m "feat: add conversation IDE capability API"
  ```

### Task 4: Add The JetBrains Backend Capability Seam

**Repository:** `F:/ai-coding/kilocode`

**Files:**
- Create: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloSessionCapabilities.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloConnectionProvider.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`
- Test: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/KiloConnectionProviderTest.kt`

**Interfaces:**

  ```kotlin
  interface KiloSessionCapabilities {
      suspend fun ensure(id: String, directory: String): CapabilityResult
      suspend fun release(id: String, reason: CapabilityReleaseReason)
      suspend fun releaseAll(reason: CapabilityReleaseReason)
  }

  sealed interface CapabilityResult {
      data class Ready(val generation: String, val tools: Set<String>) : CapabilityResult
      data class Unavailable(val reason: String) : CapabilityResult
  }

  enum class CapabilityReleaseReason { IDLE, ABORT, DELETE, PROJECT_CLOSED, DISCONNECT, SHUTDOWN }
  ```

- [ ] **Step 1: Add provider-seam tests.**

  Verify `KiloCliConnection.capabilities == null`, a fake provider's capability object is exposed unchanged through `KiloBackendAppService.sessionCapabilities`, and connection restart does not let backend code inspect provider-specific MCP data.

- [ ] **Step 2: Run the test and confirm the new property is absent.**

  Run `./gradlew :backend:test --tests "ai.kilocode.backend.app.KiloConnectionProviderTest"` from `packages/kilo-jetbrains`.

- [ ] **Step 3: Add the minimal optional seam.**

  Add `val capabilities: KiloSessionCapabilities? get() = null` to `KiloConnection`; expose it from the app service. Do not import `com.intellij.mcpserver`, add shared/frontend DTOs, or alter the Kilo CLI provider behavior.

- [ ] **Step 4: Run backend checks and commit.**

  ```bash
  ./gradlew :backend:typecheck :backend:test --tests "ai.kilocode.backend.app.KiloConnectionProviderTest"
  bun run script/check-opencode-annotations.ts --worktree
  git add packages/kilo-jetbrains/backend
  git commit -m "feat(jetbrains): add session capability seam"
  ```

### Task 5: Implement The Optional JetBrains MCP Factory And Lease Bridge

**Repository:** `F:/ai-coding/kilocode`

**Files:**
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/IdeMcpSessionFactory.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/JetBrainsMcpSessionFactory.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridge.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/mcp/IdeMcpProtocol.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/resources/kilo.jetbrains.cs-cloud-mcp.xml`
- Create: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpBridgeTest.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/resources/kilo.jetbrains.cs-cloud.xml`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionService.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionProvider.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/build.gradle.kts`
- Modify: `packages/kilo-jetbrains/src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- `IdeMcpSessionFactory.enabled(allow: Set<String>): Set<String>` applies JetBrains filter providers to the fixed names.
- `suspend fun IdeMcpSessionFactory.open(tools: Set<String>, ready: suspend (IdeMcpTransport) -> Nothing)` holds an authorization until cancelled.
- `IdeMcpTransport` contains `port`, `authHeader`, and `token`; its `toString()` must be redacted.
- `CsCloudMcpBridge` implements `KiloSessionCapabilities` and keeps `ConcurrentHashMap<String, Lease>` plus a per-conversation `Mutex`.

- [ ] **Step 1: Write bridge tests with a fake factory and `MockWebServer`.**

  Cover exact project matching; child directory rejection; unopened worktree rejection; default/disposed project rejection; all 17 allowlisted names; user-disabled intersection; empty-set `Unavailable("tools_disabled")`; concurrent ensure coalescing; same-state reuse; tool/connection change rotation; candidate failure preserving old lease; stale release; project close; release-all; factory absence; token/header/error redaction.

- [ ] **Step 2: Run the bridge test and confirm missing classes fail compilation.**

  Run `./gradlew :cs-cloud:test --tests "ai.kilocode.cscloud.mcp.CsCloudMcpBridgeTest"`.

- [ ] **Step 3: Register an optional MCP implementation boundary.**

  Declare `ai.kilocode.jetbrains.ideMcpSessionFactory` in `kilo.jetbrains.cs-cloud.xml`. Put the `JetBrainsMcpSessionFactory` registration in `kilo.jetbrains.cs-cloud-mcp.xml`, loaded only when plugin `com.intellij.mcpServer` is present. Add `bundledPlugin("com.intellij.mcpServer")` as a compile/test dependency and the matching optional descriptor dependency; run `verifyPlugin` to validate the exact descriptor syntax on `2026.1`.

- [ ] **Step 4: Reproduce JetBrains's enabled-tool calculation at the adapter boundary.**

  In `JetBrainsMcpSessionFactory.enabled`, gather `McpToolsProvider.EP.extensionList.flatMap { it.getTools() }`, select descriptor names in the Costrict allowlist, then apply `McpToolFilterProvider.EP.extensionList.flatMap { it.getFilters(null).value }` in order using `McpToolFilterContext`. Return the final descriptor-name set. Catch provider failures individually and log only provider class plus error class.

- [ ] **Step 5: Open the real private authorization session.**

  Use the verified `2026.1` signature:

  ```kotlin
  McpServerService.getInstanceAsync().authorizedSession(
      McpServerService.McpSessionOptions(
          McpServerService.AskCommandExecutionMode.ASK,
          McpToolFilter.AllowList(tools),
      ),
  ) { port, header, token ->
      ready(IdeMcpTransport(port, header, token))
  }
  ```

  `ready` must suspend with `awaitCancellation()` after the bridge has consumed the transport. Direct JetBrains MCP references must not appear outside `JetBrainsMcpSessionFactory.kt`.

- [ ] **Step 6: Implement exact project selection and capability negotiation.**

  Canonicalize both input and `Project.basePath` with real-path fallback matching `CsCloudRoute`. Require exactly one equality match. Before opening a lease, call cs-cloud `/api/v1/runtime/health`, unwrap `data.capabilities`, and require `conversation_ide_capability_v1`.

- [ ] **Step 7: Implement two-phase lease publication.**

  Recompute `factory.enabled(COSTRICT_IDE_TOOLS)` on every ensure. If it becomes empty, revoke any prior lease and return `Unavailable("tools_disabled")`. Otherwise generate a UUID, launch `factory.open`, await its transport, and PUT the spec payload with the cs-cloud API client and workspace header. Only after matching generation acknowledgment publish `Lease.Ready`; then DELETE/cancel the prior generation. If PUT fails, cancel the candidate authorization and preserve the old ready lease. Include a monotonically increasing connection epoch from `CsCloudConnectionService` in the reuse key. When the authorization job ends without an explicit release, compare its generation with the current lease and change only that matching lease to `Failed("mcp_listener_failed")`.

- [ ] **Step 8: Expose and dispose the bridge through the provider.**

  `CsCloudConnectionService.capabilities` returns its bridge. A short SSE reconnect keeps the same epoch and leases; a full endpoint/client replacement increments the epoch so the next ensure rotates. `shutdownForUnload`, `shutdownForAppClose`, and `dispose` call `releaseAll(SHUTDOWN)` before HTTP executors stop.

- [ ] **Step 9: Run cs-cloud module checks and commit.**

  ```bash
  ./gradlew :cs-cloud:typecheck :cs-cloud:test :verifyPlugin
  git add packages/kilo-jetbrains/cs-cloud packages/kilo-jetbrains/src/main/resources/META-INF/plugin.xml
  git commit -m "feat(jetbrains): add private MCP lease bridge"
  ```

### Task 6: Wire Prompt, Recovery, And Revocation Lifecycle

**Repository:** `F:/ai-coding/kilocode`

**Files:**
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloSessionRpcApiImpl.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendSessionManager.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`
- Test: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/rpc/KiloSessionRpcApiImplTest.kt`
- Test: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/KiloBackendSessionManagerTest.kt`
- Test: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/mcp/CsCloudMcpLifecycleTest.kt`

**Interfaces:**
- `KiloBackendAppService.ensureCapabilities(id, directory)` delegates only when the active provider supplies capabilities.
- `KiloBackendSessionManager.start(..., capabilities: KiloSessionCapabilities?)` owns status-driven idle release and busy/retry recovery.
- Provider errors use stable codes (`mcp_plugin_unavailable`, `project_not_open`, `mcp_listener_failed`, `ide_capability_unsupported`, `ide_capability_bind_failed`) and flow through the existing RPC/session error handling.

- [ ] **Step 1: Write ordering and failure tests.**

  In RPC tests, capture calls and assert `ensure -> chat.prompt`; an ensure exception means `chat.prompt` has zero calls; `Unavailable("tools_disabled")` permits prompt; abort and delete each invoke matching release even when the downstream operation fails.

- [ ] **Step 2: Write status/recovery cleanup tests.**

  Assert authoritative `idle` releases once, `busy`/`retry` recovery ensures only sessions with known directories, history load does not ensure, SSE completion alone does not release, stop/disconnect releases all, and a late release from generation one cannot affect generation two.

- [ ] **Step 3: Run focused tests and confirm ordering assertions fail.**

  ```bash
  ./gradlew :backend:test --tests "ai.kilocode.backend.rpc.KiloSessionRpcApiImplTest" --tests "ai.kilocode.backend.app.KiloBackendSessionManagerTest"
  ```

- [ ] **Step 4: Gate prompt on ensure.**

  In `KiloSessionRpcApiImpl.prompt`, call `app.ensureCapabilities(id, directory)` immediately after `requireReady()` and before `chat.prompt`. Do not launch it concurrently and do not retry the prompt. Map stable capability exceptions through the existing failure channel without adding frontend MCP DTOs.

- [ ] **Step 5: Add all revocation triggers.**

  Release before/after abort in a non-cancellable cleanup block, release after successful delete and also on a not-found terminal result, release on authoritative idle in the status collector, release all during provider disconnect/shutdown, and listen for project close in the cs-cloud module to release leases whose canonical workspace equals that project.

- [ ] **Step 6: Rebind active sessions after recovery.**

  At the end of `KiloBackendSessionManager.recover`, after status seeding, call `ensure` for known `busy` and `retry` sessions. Log a stable error code and leave the session state recoverable on failure; do not replay its prompt, MCP request, or transcript events.

- [ ] **Step 7: Run relevant JetBrains checks and commit.**

  ```bash
  ./gradlew :backend:typecheck :backend:test --tests "ai.kilocode.backend.rpc.KiloSessionRpcApiImplTest" --tests "ai.kilocode.backend.app.KiloBackendSessionManagerTest"
  ./gradlew :cs-cloud:typecheck :cs-cloud:test --tests "ai.kilocode.cscloud.mcp.CsCloudMcpLifecycleTest"
  git add packages/kilo-jetbrains/backend packages/kilo-jetbrains/cs-cloud
  git commit -m "feat(jetbrains): bind IDE capability to session lifecycle"
  ```

### Task 7: Add Real MCP Integration And End-To-End Verification

**Repositories:** `F:/ai-coding/kilocode`, `F:/ai-coding/cs-cloud`, `F:/ai-coding/csc`

**Files:**
- Create: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/mcp/JetBrainsMcpSessionIntegrationTest.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/mcp/IdeMcpEndToEndTest.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/build.gradle.kts`
- Test: `F:/ai-coding/cs-cloud/internal/localserver/ide_capability_test.go`
- Test: `F:/ai-coding/csc/src/server/__tests__/ideCapabilityRoutes.test.ts`

**Interfaces:**
- The integration test uses the real IntelliJ Application, real `McpServerService.authorizedSession()`, and the MCP TypeScript/HTTP protocol; it does not substitute a mock MCP server.
- The end-to-end fixture starts real cs-cloud and csc processes on loopback and sends a prompt only after capability acknowledgment.

- [ ] **Step 1: Add a real JetBrains MCP integration test.**

  With the global server setting disabled, open a test Project, create two authorized sessions, initialize over `/stream`, call `tools/list`, invoke one read-only tool, verify distinct tokens, verify bad token returns 401, cancel one authorization job, and verify its old token returns 401 while the other remains valid. Assert the resulting tool names are the allowlist/user-settings intersection and `commandExecutionMode` is `ASK`.

- [ ] **Step 2: Add a process-level handshake test.**

  Start csc serve, start cs-cloud configured for csc, create a conversation, bind an authorized JetBrains session through cs-cloud, and verify initialize, `tools/list`, project header propagation, generation replacement, idle DELETE, and post-delete 401. Capture output through a redacting recorder and assert the token does not occur.

- [ ] **Step 3: Verify approval behavior with real events.**

  Invoke `build_project` and assert csc emits one control-plane permission request before the MCP call. Deny it and assert JetBrains sees no call. Invoke `execute_run_configuration` and assert csc emits no permission request while JetBrains's `ASK` path is reached once.

- [ ] **Step 4: Run all affected checks, not repository-wide test suites.**

  ```bash
  # kilocode/packages/kilo-jetbrains
  ./gradlew :backend:typecheck :backend:test :cs-cloud:typecheck :cs-cloud:test :verifyPlugin

  # cs-cloud
  go test ./internal/localserver ./internal/agent/csc ./internal/runtime

  # csc
  bun test src/server/__tests__/ideCapabilityRoutes.test.ts src/services/mcp/__tests__/idePolicy.test.ts
  bun run typecheck
  bun run lint

  # kilocode root guards
  bun run script/check-md-table-padding.ts
  bun run script/check-opencode-annotations.ts --worktree
  ```

- [ ] **Step 5: Perform the manual acceptance scenario from the spec.**

  Use a real project with one compile error and one failing test. Record only redacted conversation/generation hashes, effective tool names, approval event names, build/test result, and the 401 after revocation. Repeat initialize plus one read-only call in split mode and confirm the loopback connection stays on the backend host and no MCP material reaches frontend RPC.

- [ ] **Step 6: Commit integration coverage in each owning repository.**

  ```bash
  # csc
  git add src/server/__tests__/ideCapabilityRoutes.test.ts src/services/mcp/__tests__/idePolicy.test.ts
  git commit -m "test: cover IDE MCP lifecycle"

  # cs-cloud
  git add internal/localserver/ide_capability_test.go internal/agent/csc/agent_test.go
  git commit -m "test: cover IDE capability forwarding"

  # kilocode
  git add packages/kilo-jetbrains/cs-cloud
  git commit -m "test(jetbrains): verify private MCP bridge"
  ```
