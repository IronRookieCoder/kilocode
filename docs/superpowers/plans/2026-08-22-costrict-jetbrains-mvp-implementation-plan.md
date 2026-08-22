# Costrict JetBrains MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated `cs-cloud` JetBrains module that connects the existing Kilo session UI to a running local cs-cloud daemon and completes the prompt → ReAct → approval → workspace file flow.

**Architecture:** Keep `shared`, `frontend`, and the existing Kilo backend session model intact. Add a backend connection-provider seam and put Costrict endpoint discovery, route translation, API-key handling, health checks, SSE normalization, and reconnect behavior in `packages/kilo-jetbrains/cs-cloud`. Replace port-only URL assumptions with a `ConnectionTarget.base` URL so both the existing Kilo provider and the external cs-cloud provider use the same managers.

**Tech Stack:** Kotlin/JVM 21, Gradle IntelliJ split-mode modules, IntelliJ Platform RPC/services, OkHttp/OkHttp SSE, kotlinx.serialization, JUnit 5, `MockWebServer`, Go `net/http` tests in `cs-cloud`.

**Spec:** `docs/superpowers/specs/2026-08-22-costrict-jetbrains-mvp-design.md`

## Global Constraints

- Connect only to loopback cs-cloud endpoints: `127.0.0.1`, `localhost`, or `::1`.
- Discover the server URL from the cs-cloud `server_url` file and the API key from `CS_BRIDGE_API_KEY`, `CS_CLOUD_API_KEY`, then cs-cloud `config.json`.
- Send the API key only as `Authorization: Bearer <key>` or `X-API-Key`; never put it in URLs or logs.
- Send `X-Workspace-Directory` on every workspace-bound request and keep the workspace within the current JetBrains project root.
- Do not start, stop, download, or reinstall cs-cloud/csc from the plugin.
- Keep Costrict implementation in `packages/kilo-jetbrains/cs-cloud`; keep backend changes limited to the provider seam and URL abstraction.
- Reuse the existing `SessionController`, `SessionModel`, permission UI, question UI, diff UI, and `KiloCliDataParser` event model.
- Do not add event sequence snapshots, prompt idempotency, IDE MCP, browser preview, or remote-development behavior in this plan.
- Use Java 21 and run only affected package tests; never run root `bun test`.

---

### Task 1: Add The Provider Seam And `cs-cloud` Module Wiring

**Files:**
- Create: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloConnectionProvider.kt`
- Create: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/ConnectionTarget.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendConnectionService.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/resources/kilo.jetbrains.backend.xml`
- Modify: `packages/kilo-jetbrains/settings.gradle.kts`
- Modify: `packages/kilo-jetbrains/src/main/resources/META-INF/plugin.xml`
- Create: `packages/kilo-jetbrains/cs-cloud/build.gradle.kts`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/resources/kilo.jetbrains.cs-cloud.xml`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionProvider.kt`
- Test: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/KiloConnectionProviderTest.kt`

**Interfaces:**
- `KiloConnectionProvider` exposes `id: String` and `create(cs: CoroutineScope, reconnect: () -> Unit, log: KiloLog, timeout: Long): KiloConnection`.
- `data class ConnectionTarget(val base: String)` stores a normalized URL without a trailing slash.
- `KiloConnection` exposes `state: StateFlow<ConnectionState>`, `events: SharedFlow<SseEvent>`, `api: DefaultApi?`, `apiClient: OkHttpClient?`, `target: ConnectionTarget?`, `connect()`, `restart()`, `reinstall()`, `shutdownForUnload()`, `shutdownForAppClose()`, and `dispose()`.
- The existing Kilo connection is wrapped by `KiloCliConnectionProvider`; the new module registers `CsCloudConnectionProvider` through an IntelliJ extension point named `ai.kilocode.backend.connectionProvider`.

- [ ] **Step 1: Define the provider and connection contracts.**

  Keep `ConnectionState` and `SseEvent` in the backend module because they are consumed by `DefaultApi` and the existing managers. Define the extension point with `ExtensionPointName.create<KiloConnectionProvider>("ai.kilocode.backend.connectionProvider")`; provider selection must be deterministic by `id`.

- [ ] **Step 2: Write the provider-selection test.**

  Add a fake provider with `id = "test"`, construct `KiloBackendAppService` through its existing test factory, and assert that the selected provider is used for `connect()` while the original Kilo provider remains the fallback when no extension is registered.

- [ ] **Step 3: Register the new Gradle module.**

  Add `include("cs-cloud")`, include the new module in the root `plugin.xml` content, declare the extension point in `kilo.jetbrains.backend.xml`, and register the Costrict implementation in `kilo.jetbrains.cs-cloud.xml`. The new module must depend on the backend module only for the connection contract and on the existing IntelliJ backend/HTTP libraries; it must not depend on `frontend`.

- [ ] **Step 4: Wrap the existing Kilo connection without changing its behavior.**

  Move construction of `KiloConnectionService` behind `KiloCliConnectionProvider`. Forward its state, events, `DefaultApi`, HTTP client, `ConnectionTarget`, restart, reinstall, and shutdown methods unchanged. Keep all existing Kilo connection tests green.

- [ ] **Step 5: Compile the wiring.**

  Run `./gradlew :shared:compileKotlin :backend:compileKotlin :cs-cloud:compileKotlin` from `packages/kilo-jetbrains/`.

- [ ] **Step 6: Commit the seam and module scaffolding.**

  ```bash
  git add packages/kilo-jetbrains/settings.gradle.kts packages/kilo-jetbrains/src/main/resources/META-INF/plugin.xml packages/kilo-jetbrains/backend packages/kilo-jetbrains/cs-cloud
  git commit -m "feat(jetbrains): add Costrict connection provider seam"
  ```

### Task 2: Replace Port-Only URLs With A Connection Target

**Files:**
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendSessionManager.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendChatManager.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendModelStateManager.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/workspace/KiloBackendWorkspaceManager.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/workspace/KiloBackendWorkspace.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/telemetry/KiloBackendTelemetry.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendMigrationManager.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendConnectionService.kt`
- Test: corresponding `KiloBackend*Test.kt` files under `backend/src/test/kotlin`

**Interfaces:**
- Existing Kilo provider creates `ConnectionTarget("http://127.0.0.1:$port")`; the external provider creates it from the discovered cs-cloud URL.
- Every manager `start` method accepts `base: String` instead of an integer port and constructs requests from that base.

- [ ] **Step 1: Add `ConnectionTarget` normalization tests.**

  Assert that `http://127.0.0.1:1234/` becomes `http://127.0.0.1:1234`, an empty URL is rejected, and a URL with a path prefix retains that prefix.

- [ ] **Step 2: Refactor manager start methods.**

  Change `KiloBackendSessionManager.start`, `KiloBackendChatManager.start`, `KiloBackendModelStateManager.start`, and `KiloBackendWorkspaceManager.start` to store `base` and build URLs from it. Change `KiloBackendWorkspace` and `KiloBackendTelemetry` to receive `base` rather than deriving a localhost URL from `port`.

- [ ] **Step 3: Update the app orchestrator.**

  Replace `connection.port` call sites in `KiloBackendAppService` with `connection.target!!.base`. Keep a `port` accessor only where existing telemetry or tests require it, deriving it from the Kilo target and returning `0` for external URLs.

- [ ] **Step 4: Update tests to pass explicit bases.**

  Existing mock-server tests must pass `"http://127.0.0.1:${server.port}"`; add one test using `"http://localhost:${server.port}/api/v1"` to prove managers do not hardcode `127.0.0.1`.

- [ ] **Step 5: Run the affected backend tests.**

  ```bash
  ./gradlew :backend:test --tests "ai.kilocode.backend.app.KiloBackendSessionManagerTest" --tests "ai.kilocode.backend.app.KiloBackendChatManagerTest" --tests "ai.kilocode.backend.workspace.KiloBackendWorkspaceTest" --tests "ai.kilocode.backend.telemetry.KiloBackendTelemetryTest"
  ```

- [ ] **Step 6: Commit the URL abstraction.**

  ```bash
  git add packages/kilo-jetbrains/backend
  git commit -m "refactor(jetbrains): use connection base URLs"
  ```

### Task 3: Implement cs-cloud Endpoint Discovery And HTTP Clients

**Files:**
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudEndpoint.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudEndpointResolver.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudHttpClients.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/build.gradle.kts`
- Test: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudEndpointResolverTest.kt`

**Interfaces:**
- `data class CsCloudEndpoint(val base: String, val key: String)`.
- `class CsCloudEndpointResolver(private val home: Path, private val env: Map<String, String>)` with `fun resolve(): Result<CsCloudEndpoint>`.
- `CsCloudHttpClients.create(endpoint: CsCloudEndpoint): CsCloudClients`, where `CsCloudClients` contains a no-timeout API/SSE client and a bounded health client.

- [ ] **Step 1: Write resolver tests first.**

  Use a temporary directory containing `cs-cloud/server_url` and `cs-cloud/config.json`. Cover API-key precedence (`CS_BRIDGE_API_KEY`, `CS_CLOUD_API_KEY`, config `api_key`), missing URL, missing key, malformed URL, non-loopback URL, and trailing-slash normalization.

- [ ] **Step 2: Implement file and environment resolution.**

  Read `${home}/.costrict/cs-cloud/server_url` as UTF-8 text. Read `${home}/.costrict/cs-cloud/config.json` as JSON with `api_key`. Treat blank values as missing. Validate the URL host before returning it and return typed discovery errors with user-safe messages.

- [ ] **Step 3: Implement HTTP clients and API construction.**

  Build an OkHttp client with an interceptor that adds the API key and a `DefaultApi(basePath = endpoint.base, client = client)`. Use a separate client with a 3-second call timeout for health checks and a no-call-timeout/no-read-timeout client for SSE.

- [ ] **Step 4: Run the resolver tests.**

  ```bash
  ./gradlew :cs-cloud:test --tests "ai.kilocode.cscloud.CsCloudEndpointResolverTest"
  ```

- [ ] **Step 5: Commit discovery and client construction.**

  ```bash
  git add packages/kilo-jetbrains/cs-cloud
  git commit -m "feat(jetbrains): discover local cs-cloud endpoint"
  ```

### Task 4: Add Route, Header, Health, And Error Translation

**Files:**
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudRoute.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudHealth.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudError.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudHttpClients.kt`
- Test: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudRouteTest.kt`
- Test: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudHealthTest.kt`

**Interfaces:**
- `fun rewrite(request: Request): Request` translates Kilo paths and converts the `directory` query parameter into `X-Workspace-Directory`.
- `fun parseHealth(body: String): HealthDto` accepts cs-cloud `{ok,data}` and rejects `{ok:false,error}` with `CsCloudRequestException(code, message, status)`.
- Route mappings must cover `/session`, `/session/{id}/prompt_async`, `/session/{id}/message`, `/global/event`, `/permission`, `/question`, and `/global/health` using the paths in the spec.

- [ ] **Step 1: Write table-driven route tests.**

  Assert method, rewritten path, retained non-directory query parameters, workspace header, and unchanged JSON body for session creation, prompt, messages, permissions, questions, events, and health. Assert that a missing workspace directory is rejected for workspace-bound session requests.

- [ ] **Step 2: Implement the route interceptor.**

  Rewrite the URL path segments centrally, remove only the `directory` query parameter, preserve all other parameters, and add `X-Workspace-Directory` after canonicalizing the directory. Do not log the resulting API key or prompt body.

- [ ] **Step 3: Write health and error tests.**

  Parse a successful health envelope into `HealthDto(healthy = true, version = ...)`; parse 401, 404, and 503 responses into distinct error codes used by diagnostics.

- [ ] **Step 4: Implement response normalization.**

  Add a response interceptor that unwraps only the cs-cloud health envelope and preserves proxied conversation/message JSON unchanged. Convert error envelopes into `CsCloudRequestException` while retaining HTTP status and service error code.

- [ ] **Step 5: Run the transport tests.**

  ```bash
  ./gradlew :cs-cloud:test --tests "ai.kilocode.cscloud.CsCloudRouteTest" --tests "ai.kilocode.cscloud.CsCloudHealthTest"
  ```

- [ ] **Step 6: Commit the protocol adapter.**

  ```bash
  git add packages/kilo-jetbrains/cs-cloud
  git commit -m "feat(jetbrains): adapt cs-cloud control-plane routes"
  ```

### Task 5: Implement External Connection And SSE Lifecycle

**Files:**
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionService.kt`
- Create: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudSseClient.kt`
- Modify: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudConnectionProvider.kt`
- Test: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudConnectionServiceTest.kt`

**Interfaces:**
- `CsCloudConnectionService` implements `KiloConnection` and exposes `target = ConnectionTarget(endpoint.base)` after a successful health check.
- `connect()` resolves the endpoint, checks health, constructs clients, opens `/api/v1/events`, then emits `ConnectionState.Connected`.
- `restart()` closes the stream and repeats discovery/health; `reinstall()` returns a typed unsupported-operation error; shutdown methods only close clients and streams.

- [ ] **Step 1: Add an HTTP/SSE test server.**

  Use `MockWebServer` with a health response, conversation responses, and a controllable SSE stream. Record request headers and paths so tests assert the API key and workspace behavior without mocking the connection implementation.

- [ ] **Step 2: Write lifecycle tests first.**

  Cover successful discovery, missing daemon URL, 401 invalid key, 503 agent unavailable, SSE open, SSE close/reconnect, disposal, and the rule that a submitted prompt is never replayed by the connection service.

- [ ] **Step 3: Implement health-gated connection.**

  Set `Discovering` before resolver work, `Connecting` while health/SSE starts, `Error` with the typed diagnostic on failure, and `Connected` only after the SSE stream opens. Keep reconnect backoff bounded and cancel all jobs in `dispose()`.

- [ ] **Step 4: Normalize SSE frames.**

  Emit `SseEvent(type, data)` for typed frames, infer a type from JSON when the SSE event field is absent, pass `GlobalEvent` payloads to the existing `KiloCliDataParser`, and ignore host events outside the active workspace.

- [ ] **Step 5: Run the lifecycle tests.**

  ```bash
  ./gradlew :cs-cloud:test --tests "ai.kilocode.cscloud.CsCloudConnectionServiceTest"
  ```

- [ ] **Step 6: Commit the external connection implementation.**

  ```bash
  git add packages/kilo-jetbrains/cs-cloud
  git commit -m "feat(jetbrains): connect to cs-cloud SSE"
  ```

### Task 6: Integrate Diagnostics, Session Recovery, And VFS Refresh

**Files:**
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendSessionManager.kt`
- Create: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendWorkspaceRefresh.kt`
- Modify: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloAppRpcApiImpl.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/rpc/dto/KiloAppStateDto.kt` only if a new diagnostic status is required by the existing RPC enum
- Test: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/KiloBackendAppServiceTest.kt`
- Test: `packages/kilo-jetbrains/backend/src/test/kotlin/ai/kilocode/backend/app/KiloBackendWorkspaceRefreshTest.kt`

**Interfaces:**
- `KiloBackendWorkspaceRefresh` accepts `Project` and a canonical workspace root, handles `host.file.*` and idle events, and calls `LocalFileSystem.getInstance().refreshAndFindFileByPath` on the BGT/EDT boundary required by IntelliJ.
- `KiloBackendAppService` maps discovery errors to user-facing `KiloAppState.Error` details: daemon not running, credential missing/invalid, or csc agent unavailable.

- [ ] **Step 1: Add app-state tests for each diagnostic.**

  Use the existing `KiloBackendAppServiceTest` factory and a fake `KiloConnectionProvider` to assert the exact error detail for missing `server_url`, missing/invalid key, and HTTP 503. Assert that `restart()` reconnects without invoking a process manager.

- [ ] **Step 2: Start backend managers with the external base.**

  Pass the selected connection `base` into model, session, chat, workspace, telemetry, and migration managers. Keep the existing `SessionController` and `KiloBackendActivityManager` event flow unchanged.

- [ ] **Step 3: Implement reconnect recovery.**

  After an SSE reconnect, call the existing session history/status/pending-permission/pending-question reads for active sessions before publishing the recovered ready state. Never call `chat.prompt` as part of recovery.

- [ ] **Step 4: Implement VFS refresh.**

  Consume only host file events within the canonical workspace. Schedule filesystem refresh through IntelliJ's supported background/EDT APIs, and keep refresh failures as logged diagnostics rather than session failures.

- [ ] **Step 5: Run focused backend tests.**

  ```bash
  ./gradlew :backend:test --tests "ai.kilocode.backend.app.KiloBackendAppServiceTest" --tests "ai.kilocode.backend.app.KiloBackendWorkspaceRefreshTest"
  ```

- [ ] **Step 6: Commit backend integration.**

  ```bash
  git add packages/kilo-jetbrains/backend packages/kilo-jetbrains/shared
  git commit -m "feat(jetbrains): integrate Costrict sessions and workspace refresh"
  ```

### Task 7: Add cs-cloud Contract Coverage

**Files:**
- Modify: `D:/code/cs-cloud/internal/localserver/middleware_test.go`
- Modify: `D:/code/cs-cloud/internal/localserver/handle_events_test.go`
- Modify: `D:/code/cs-cloud/internal/localserver/integration_test.go`
- Create: `D:/code/cs-cloud/internal/localserver/costrict_jetbrains_contract_test.go`
- Modify: `D:/code/cs-cloud/docs/runtime-control-api.md` if the tested endpoint/header contract is not already documented

**Interfaces:**
- The contract test starts the real `localserver.Server` with a non-empty API key and a test agent proxy, then verifies `/api/v1/runtime/health`, `/api/v1/conversations`, `/api/v1/events`, `/api/v1/permissions`, and `/api/v1/questions`.
- The test asserts `Authorization`/`X-API-Key`, `X-Workspace-Directory`, successful proxy rewrites, workspace event filtering, and the health/error envelope consumed by the Kotlin adapter.

- [ ] **Step 1: Write the failing contract test.**

  Use `httptest` and the existing localserver test setup. Send unauthorized and authorized requests, then assert status code, JSON envelope, rewritten backend path, and SSE event body.

- [ ] **Step 2: Run the focused Go test.**

  ```bash
  go test ./internal/localserver -run 'TestCostrictJetBrainsContract' -count=1
  ```

  The test must fail if any path, header, envelope, or workspace filter differs from the Kotlin adapter contract.

- [ ] **Step 3: Make only contract-preserving cs-cloud fixes.**

  Keep existing routes and csc driver behavior. Any change must preserve the API key middleware, workspace sandbox, and current Kilo/CLI clients.

- [ ] **Step 4: Re-run the focused Go suite.**

  ```bash
  go test ./internal/localserver/... -count=1
  ```

- [ ] **Step 5: Commit cs-cloud contract changes separately.**

  ```bash
  git -C D:/code/cs-cloud add internal/localserver docs/runtime-control-api.md
  git -C D:/code/cs-cloud commit -m "test(localserver): cover JetBrains cs-cloud contract"
  ```

### Task 8: Verify The End-To-End MVP And Release Metadata

**Files:**
- Create: `.changeset/costrict-jetbrains-mvp.md`
- Modify: `packages/kilo-jetbrains/README.md` with local cs-cloud startup, credential discovery, and troubleshooting instructions
- Test: `packages/kilo-jetbrains/cs-cloud/src/test/kotlin/ai/kilocode/cscloud/CsCloudConnectionServiceTest.kt`

**Interfaces:**
- The changeset targets `@kilocode/kilo-jetbrains` with a patch release and describes the user-visible local Costrict connection capability.
- The README documents `cs-cloud start`, the `server_url`/`config.json` discovery locations, API-key errors, and the fact that the plugin does not own daemon lifecycle.

- [ ] **Step 1: Add the changeset and README contract.**

  Use this changeset body:

  ```markdown
  ---
  "@kilocode/kilo-jetbrains": patch
  ---

  Connect JetBrains sessions to a running local Costrict cs-cloud daemon.
  ```

- [ ] **Step 2: Run the JetBrains typecheck and targeted tests.**

  ```bash
  cd packages/kilo-jetbrains
  ./gradlew typecheck
  ./gradlew :cs-cloud:test :backend:test
  ```

- [ ] **Step 3: Run the cs-cloud focused checks.**

  ```bash
  cd D:/code/cs-cloud
  go test ./internal/localserver/... -count=1
  go vet ./internal/localserver/...
  ```

- [ ] **Step 4: Execute the real smoke flow.**

  Start a local cs-cloud daemon with a configured csc agent, open a temporary JetBrains project, send a prompt that creates `index.html`, `style.css`, and `game.js` for a Gomoku game, approve the required file/command permissions, and verify the three files exist on disk and are visible in the IntelliJ project tree. Stop the daemon manually after the run; the plugin must not stop it.

- [ ] **Step 5: Run the IntelliJ API inspection.**

  Run `Plugin DevKit | Code | Frontend and Backend API Usage` for the new module and VFS refresh code. Resolve any internal-API usage before packaging.

- [ ] **Step 6: Commit release metadata and documentation.**

  ```bash
  git add .changeset/costrict-jetbrains-mvp.md packages/kilo-jetbrains/README.md
  git commit -m "docs(jetbrains): document Costrict MVP connection"
  ```
