# JetBrains Outbox Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the JetBrains stability outbox contain the complete, correlated, high-fidelity information needed to diagnose plugin failures without consulting separate log files.

**Architecture:** Extend the existing stability protocol with structured diagnostic incidents and bounded payload chunks, mirror WARN/ERROR through a recursion-safe bridge, and add explicit capture at RPC/HTTP/SSE boundaries where raw business context exists. Preserve non-blocking producer behavior while giving failure records a loss-resistant queue and retention priority; expose per-reason loss counters so metric quality is measurable.

**Tech Stack:** Kotlin, IntelliJ Platform 2026.2, kotlinx.serialization JSON, Kotlin coroutines, Java NIO, JUnit/Kotlin tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-23-jetbrains-outbox-diagnostics-design.md`

## Global Constraints

- The outbox is the only required diagnostic artifact; acceptance must not depend on `kilo.log` or `idea.log`.
- Capture raw exception messages, complete cause/suppressed chains, complete stack traces, paths, and failure-related business payloads when logs permission is active.
- Always redact credentials: Authorization headers, cookies, passwords, access/refresh/API tokens, JWTs, private keys, cloud credentials, and registered sensitive environment values.
- Keep each NDJSON row at or below 32 KiB; split larger content into reconstructable incident chunks.
- Limit one incident to 1 MiB. Above that, retain the first and last 512 KiB, original byte length, SHA-256, and `truncated=true`.
- Raise the scope-file budget from 10 MiB to 50 MiB and retain complete failure incidents before critical operations and periodic samples.
- Never perform file IO, compression, or blocking lock acquisition on the EDT or a business request thread.
- Preserve independent metrics/logs consent and policy expiry semantics.
- Emit schema v2 diagnostics only after the control contract advertises fact schema major 2; deploy and verify the consumer before releasing the plugin writer.
- Changes remain under `packages/kilo-jetbrains/`; no `kilocode_change` markers are required.
- Use single-word identifiers for new locals and parameters unless ambiguity requires a compound name.
- Use real implementations and temporary files in tests; avoid mocks.
- Run focused JetBrains tests and `./gradlew typecheck`; do not run root `bun test`.

---

### Task 1: Freeze the v2 diagnostic wire contract and rollout gate

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/test/resources/stability/fact-schema.json`
- Modify: `packages/kilo-jetbrains/shared/src/test/resources/stability/control-schema.json`
- Modify: `packages/kilo-jetbrains/shared/src/test/resources/stability/contract.json`
- Modify: `packages/kilo-jetbrains/shared/src/test/resources/stability/output-vectors.json`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/fact.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/dictionary.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/policy.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/contract-test.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/dictionary-sweep-test.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/policy-test.kt`

**Interfaces:**
- Consumes: existing `Fact`, `Draft`, `Dictionary.validate`, and schema v1.0.
- Produces: schema v2.0 names `diagnostic.reported`, `diagnostic.payload`, and `diagnostic.redaction_failed`; `incident_id` as an allowed context key; exact diagnostic and chunk field sets; a consumer capability gate for fact schema major 2.

- [ ] **Step 1: Write failing contract tests for the new records**

Add fixtures that validate a diagnostic parent and two payload chunks:

```kotlin
val parent = Draft(
    name = "diagnostic.reported",
    kind = "diagnostic",
    channel = "diagnostic",
    context = mapOf("incident_id" to "inc-1", "operation_id" to "op-1"),
    purposes = setOf("logs"),
    data = buildJsonObject {
        put("severity", "error")
        put("component", "backend.rpc")
        put("code", "json_decode_failed")
        put("message", "Expected object at $.projectID")
        put("thread_name", "DefaultDispatcher-worker-1")
        put("thread_id", 42)
        putJsonArray("payload_refs") { add("response") }
        put("truncated", false)
    },
)
assertTrue(Dictionary.validate(parent))
```

Add negative cases for an unknown severity, missing incident ID, duplicate/out-of-range chunk indexes, invalid encoding, and diagnostic details requested with metrics-only purposes.

- [ ] **Step 2: Run the contract tests and confirm they fail**

Run from `packages/kilo-jetbrains/`:

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.ContractTest" --tests "ai.kilocode.stability.DictionarySweepTest"
```

Expected: FAIL because the new names, context key, data fields, and consumer capability are not registered.

- [ ] **Step 3: Implement the v2 dictionary and schema**

Add field types for non-negative integers and bounded arbitrary text. Register these exact chunk fields:

```kotlin
private val PAYLOAD_FIELDS = setOf(
    "incident_id", "payload_kind", "chunk_index", "chunk_count", "encoding",
    "content", "original_bytes", "sha256", "truncated",
)
```

Keep the existing `critical` and `diagnostic` wire channels. Add `incident_id` to `CONTEXT_KEYS`. Add `schemaVersion: String = "1.0"` to `Draft`, copy it into `Fact.schema_version`, and construct all new diagnostic drafts with `schemaVersion = "2.0"`; existing metric facts remain v1 until their v2 representation is required.

Extend the control schema and policy parser with `accepted_fact_schema_majors`, an array of unique positive integers. Missing means `{1}`. High-fidelity diagnostics are admitted only when the array contains `2`; ordinary v1 metrics continue unchanged. Update `contract.json` with `fact_schema_v2_verified=false`; the release gate may change it to true only after a real consumer has parsed, acknowledged, and reconstructed the v2 output vectors.

- [ ] **Step 4: Add cross-version output vectors and rerun tests**

Add one v1.0 operation, one v2.0 diagnostic parent, and two v2.0 chunks to `output-vectors.json`. Assert mixed-version files are decoded row by row, v1 remains readable, and v2 round-trips without content changes. Add a negative gate test proving that a v1-only control policy suppresses v2 diagnostics without suppressing v1 metrics.

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.ContractTest" --tests "ai.kilocode.stability.DictionarySweepTest"
```

Expected: PASS.

- [ ] **Step 5: Commit the wire contract**

```powershell
git add packages/kilo-jetbrains/shared/src/test/resources/stability packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/fact.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/dictionary.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/policy.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/contract-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/dictionary-sweep-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/policy-test.kt
git commit -m "feat(jetbrains): define v2 diagnostic facts"
```

### Task 2: Redact credentials and split large diagnostic content

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostic-redactor.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostic-payload.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/diagnostic-redactor-test.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/diagnostic-payload-test.kt`

**Interfaces:**
- Produces: `DiagnosticRedactor.clean(text: String): Redacted`, `DiagnosticPayload.parts(incident: String, kind: String, bytes: ByteArray): PayloadResult`.
- `Redacted` contains `text: String` and `changed: Boolean`.
- `PayloadResult` contains `drafts: List<Draft>`, `bytes: Long`, `hash: String`, and `truncated: Boolean`.

- [ ] **Step 1: Write credential-redaction tests**

Test credentials in plain messages, JSON, headers, URLs, stack messages, PEM blocks, Windows paths, and multiline payloads. Paths and ordinary business values must survive unchanged.

```kotlin
val input = "path=C:\\work\\a.kt Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.a.b password=hunter2"
val out = DiagnosticRedactor.clean(input)
assertTrue("C:\\work\\a.kt" in out.text)
assertFalse("eyJhbGciOiJIUzI1NiJ9.a.b" in out.text)
assertFalse("hunter2" in out.text)
assertTrue(out.changed)
```

- [ ] **Step 2: Write payload chunking tests**

Cover UTF-8 boundaries, Base64 binary data, exact 32 KiB wire rows, deterministic SHA-256, ordered indexes, 1 MiB truncation, and reconstruction of untruncated input.

```kotlin
val raw = "界".repeat(30_000).encodeToByteArray()
val result = DiagnosticPayload.parts("inc-1", "response", raw)
val restored = result.drafts.joinToString("") { it.data.getValue("content").jsonPrimitive.content }
assertEquals(raw.decodeToString(), restored)
assertTrue(result.drafts.all { encodedSize(it) <= 32 * 1024 })
```

- [ ] **Step 3: Run both tests and confirm missing implementations fail**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.DiagnosticRedactorTest" --tests "ai.kilocode.stability.DiagnosticPayloadTest"
```

- [ ] **Step 4: Implement deterministic redaction and chunking**

Use ordered, precompiled patterns and replace matched values with typed constants such as `<redacted:authorization>` and `<redacted:private-key>`. Process text before chunking so secrets cannot straddle chunk boundaries. Compute SHA-256 from the original bytes, then apply the 1 MiB head/tail rule and choose UTF-8 or Base64 encoding.

If redaction throws, return no payload drafts; the caller must emit `diagnostic.redaction_failed` containing only incident ID, component, code, original length, and hash.

- [ ] **Step 5: Rerun focused tests and commit**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.DiagnosticRedactorTest" --tests "ai.kilocode.stability.DiagnosticPayloadTest"
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostic-redactor.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostic-payload.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/diagnostic-redactor-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/diagnostic-payload-test.kt
git commit -m "feat(jetbrains): preserve diagnostic payloads safely"
```

### Task 3: Make failure delivery loss-resistant and expose metric quality

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/queue.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/recorder.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/health.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/writer.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/queue-test.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/health-test.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/writer-test.kt`

**Interfaces:**
- Consumes: v2 diagnostic names from Task 1 while preserving the two existing wire channels.
- Produces: failure/critical/sample priority, `Recorder.recordBatch(drafts: List<Draft>): Admission`, per-reason counters, `quality=good|degraded`, and atomic retention of incident parent plus chunks.

- [ ] **Step 1: Add failing saturation and health tests**

Fill the queue with sample facts, enqueue 100 failure incidents, and assert all failure parents and chunks are claimable while samples are evicted. Add concurrent producers and assert `droppedFailure == 0`. Verify health reports each loss reason independently and becomes degraded if `drop_failure > 0` or an operation end is lost.

- [ ] **Step 2: Add failing writer retention tests**

Create a real temporary 50 MiB-limited writer fixture with old samples, old operations, and two multi-chunk incidents. Force rewrite and assert no incident is partially retained and the newest failures survive before critical and sample facts.

- [ ] **Step 3: Run focused queue/writer tests**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.QueueTest" --tests "ai.kilocode.stability.HealthTest" --tests "ai.kilocode.stability.WriterTest"
```

Expected: FAIL under the current two-channel queue, aggregate drop field, and line-by-line tail rewrite.

- [ ] **Step 4: Implement priority and loss accounting**

Represent internal priority explicitly rather than adding a new wire channel:

```kotlin
internal enum class Priority { FAILURE, CRITICAL, SAMPLE }
```

Use a `ConcurrentLinkedQueue<QueuedGroup>` for failure records plus atomic item/byte reservation, keeping it bounded without acquiring the existing producer lock. Preserve the current lock-protected queues for lower priorities. `recordBatch` validates and reserves the complete parent/chunk group before publishing it; on failure it publishes none of the group. Claim failure first, then merge critical/sample by enqueue time. Add counters named exactly `droppedInvalid`, `droppedContention`, `droppedCapacity`, `droppedPolicy`, `droppedOversize`, `droppedEvicted`, and `droppedFailure`.

Change the writer default to `50L * 1024 * 1024`. During rewrite, group `diagnostic.reported`/`error.*` and `diagnostic.payload` by incident ID, retain or evict the whole group, and never emit orphan chunks.

- [ ] **Step 5: Rerun tests and commit**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.QueueTest" --tests "ai.kilocode.stability.HealthTest" --tests "ai.kilocode.stability.WriterTest"
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/queue.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/recorder.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/health.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/writer.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/queue-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/health-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/writer-test.kt
git commit -m "fix(jetbrains): retain failure diagnostics in outbox"
```

### Task 4: Mirror WARN and ERROR into outbox without recursion

**Files:**
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostic-bridge.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostic-context.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/log/KiloLog.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/stability-service.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/log/KiloLogTest.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/diagnostic-bridge-test.kt`

**Interfaces:**
- Produces: `DiagnosticBridge.install(sink: (DiagnosticInput) -> Unit): AutoCloseable`, `DiagnosticBridge.publish(input: DiagnosticInput)`, and `DiagnosticContextElement` for coroutine propagation.
- `DiagnosticInput` carries severity, component, message, throwable, context, attributes, and payloads.

- [ ] **Step 1: Write failing bridge tests**

Assert one `KiloLog.warn` creates one outbox diagnostic, one `error` preserves its throwable, INFO/DEBUG do not mirror, nested logging from the sink does not recurse, and closing the installation stops capture.

```kotlin
val seen = mutableListOf<DiagnosticInput>()
DiagnosticBridge.install(seen::add).use {
    KiloLog.create(BridgeFixture::class.java).warn("failed", IllegalStateException("raw"))
}
assertEquals(1, seen.size)
assertEquals("raw", seen.single().error?.message)
```

- [ ] **Step 2: Run bridge tests and confirm failure**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.log.KiloLogTest" --tests "ai.kilocode.stability.DiagnosticBridgeTest"
```

- [ ] **Step 3: Implement logger wrapping and lifecycle installation**

Keep `KiloLog.logger(...)` as the raw sink constructor used by existing tests. Wrap the result only in `KiloLog.create(cls)` so one call mirrors once even when release mode delegates to both IntelliJ and file logs. Use a thread-local reentry flag in `DiagnosticBridge.publish` and a non-mirroring logger for collector-internal warnings.

Install the bridge only after `Writer` is active in `StabilityService.activateRun()`. Close it before recorder/writer shutdown. If no active logs permit exists, publishing returns immediately without evaluating payload suppliers.

- [ ] **Step 4: Rerun tests and commit**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.log.KiloLogTest" --tests "ai.kilocode.stability.DiagnosticBridgeTest" --tests "ai.kilocode.stability.ProducerTest"
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/log/KiloLog.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostic-bridge.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostic-context.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/stability-service.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/log/KiloLogTest.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/diagnostic-bridge-test.kt
git commit -m "feat(jetbrains): mirror failures into stability outbox"
```

### Task 5: Persist complete exception graphs and incident payloads

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/fault.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostics.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/fault-test.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/diagnostics-test.kt`

**Interfaces:**
- Replaces the narrow report entry with `Diagnostics.report(input: DiagnosticInput): String`, returning the incident ID.
- Keeps `Faults.report(...)` as a compatibility adapter that delegates to `Diagnostics`.

- [ ] **Step 1: Write failing exception-graph tests**

Build a throwable with a cause, suppressed exception, multiline raw message, platform and plugin frames, and a Windows path. Assert the parent plus chunks reconstruct the complete stack string generated by `printStackTrace`, preserve the path/message, and share one incident/fault ID.

- [ ] **Step 2: Write failing redaction-failure and rate-limit tests**

Inject a redactor that throws. Assert the raw text is absent and exactly one `diagnostic.redaction_failed` record remains. Repeated identical failures must retain the existing per-fingerprint rate limit while incrementing a summary count.

- [ ] **Step 3: Run focused tests**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.FaultTest" --tests "ai.kilocode.stability.DiagnosticsTest"
```

- [ ] **Step 4: Implement the parent/chunk transaction**

Construct all drafts before recording. The parent contains scalar searchable fields; exception message, printed stack, paths, headers, request, response, and event payloads use `DiagnosticPayload.parts`. Submit the parent and all chunks through `Recorder.recordBatch`; mark the parent truncated whenever any payload exceeds its incident budget. A failed batch admission records no partial incident and increments `droppedFailure`.

Do not swallow `CancellationException`; rethrow `VirtualMachineError` and `ThreadDeath` after the minimal uncaught parent has been attempted.

- [ ] **Step 5: Rerun tests and commit**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.FaultTest" --tests "ai.kilocode.stability.DiagnosticsTest"
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/fault.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/diagnostics.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/fault-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/diagnostics-test.kt
git commit -m "feat(jetbrains): persist complete failure incidents"
```

### Task 6: Capture typed RPC, HTTP, SSE, and MCP failure context

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/rpc-observation.kt`
- Create: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/error-classifier.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/rpc-observation-test.kt`
- Create: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/error-classifier-test.kt`
- Modify targeted call sites in: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt`
- Modify targeted call sites in: `packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/telemetry/KiloBackendTelemetry.kt`
- Modify targeted call sites in: `packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudSseClient.kt`
- Modify targeted call sites in: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt`
- Modify associated tests beside each changed implementation.

**Interfaces:**
- Produces: `ErrorClassifier.classify(error: Throwable): ErrorInfo` and explicit `Diagnostics.report` calls carrying route, status, request, response, JSON path, and operation context.
- `ErrorInfo` contains `cause`, `code`, `type`, `status`, `path`, `expected`, and `actual`.

- [ ] **Step 1: Write classifier and RPC regression tests**

Cover HTTP 401/403/404/429/5xx, socket/connect failures, timeout, serialization errors with JSON path, cancellation, linkage errors, and unknown exceptions. Assert `Operations.rpc` uses the classified cause/code and emits a diagnostic with the same operation ID before rethrowing.

- [ ] **Step 2: Add real boundary tests for the observed failures**

Use the existing fake daemon and real serializers to reproduce:

- `projectID` supplied as a string where an object is expected;
- recent-session HTTP 404;
- telemetry capture endpoint HTTP 404;
- MCP bind failure after the real timeout path;
- malformed SSE event data.

Assert each outbox contains the raw error, full stack, route/status or JSON path, failure payload, and matching operation ID. Assertions must read the real temporary JSONL, not a mocked telemetry call.

- [ ] **Step 3: Run the focused tests and confirm current generic output fails**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.RpcObservationTest" --tests "ai.kilocode.stability.ErrorClassifierTest"
.\gradlew.bat :backend:test --tests "ai.kilocode.backend.telemetry.KiloBackendTelemetryTest"
.\gradlew.bat :cs-cloud:test
```

- [ ] **Step 4: Implement classification and explicit payload capture**

Replace the generic RPC failure end:

```kotlin
val info = ErrorClassifier.classify(error)
operation.end("failure", "rpc", info.cause, info.code)
Diagnostics.report(
    DiagnosticInput.error(
        component = "rpc.$group",
        code = info.code,
        error = error,
        context = mapOf("operation_id" to operation.id),
    ),
)
```

At HTTP boundaries pass method, query-free route, filtered headers, request/response bytes and content type. At decoding boundaries pass the exact received payload. Reuse one incident for repeated logging of the same caught exception.

- [ ] **Step 5: Rerun tests and commit**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.RpcObservationTest" --tests "ai.kilocode.stability.ErrorClassifierTest"
.\gradlew.bat :backend:test --tests "ai.kilocode.backend.telemetry.KiloBackendTelemetryTest"
.\gradlew.bat :cs-cloud:test
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendAppService.kt packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/backend/telemetry/KiloBackendTelemetry.kt packages/kilo-jetbrains/cs-cloud/src/main/kotlin/ai/kilocode/cscloud/CsCloudSseClient.kt packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/controller/SessionController.kt packages/kilo-jetbrains/shared/src/test packages/kilo-jetbrains/backend/src/test packages/kilo-jetbrains/cs-cloud/src/test
git commit -m "feat(jetbrains): correlate runtime failures in outbox"
```

### Task 7: Add actionable EDT stall and unclean-run evidence

**Files:**
- Modify: `packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/probe.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/edt-stall.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/unclean.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/operation.kt`
- Modify: `packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/stability/probe-test.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/edt-stall-test.kt`
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/unclean-test.kt`

**Interfaces:**
- Produces: stall incidents containing the captured EDT stack and enhanced `plugin.unclean` records containing last-run and open-operation evidence.

- [ ] **Step 1: Write failing stall tests**

Drive the real probe scheduler so a blocked EDT crosses 2 seconds. Assert the stack was sampled while blocked, includes all captured frames and thread identity, and is linked from the emitted `edt.stall` through incident ID.

- [ ] **Step 2: Write failing unclean tests**

Write a scope file with started operations, completed operations, last seq/timestamp, and no shutdown. Assert restart emits previous run ID, last seq, last fact time, last flush time, and only the still-open operation IDs. Add a case with more than 32 open operations and verify chunk reconstruction.

- [ ] **Step 3: Run focused tests**

```powershell
.\gradlew.bat :frontend:test --tests "ai.kilocode.client.stability.ProbeTest"
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.EdtStallTest" --tests "ai.kilocode.stability.UncleanTest"
```

- [ ] **Step 4: Implement bounded live evidence capture**

Have the background watchdog sample the EDT thread once when delay first crosses the threshold and keep the raw stack in memory until `StallMerger` closes the interval. Extend operation lifecycle bookkeeping with an in-memory map keyed by operation ID; remove on terminal end and expose an immutable snapshot during graceful shutdown/unclean recovery.

- [ ] **Step 5: Rerun tests and commit**

```powershell
.\gradlew.bat :frontend:test --tests "ai.kilocode.client.stability.ProbeTest"
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.EdtStallTest" --tests "ai.kilocode.stability.UncleanTest"
git add packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/stability/probe.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/edt-stall.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/unclean.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/operation.kt packages/kilo-jetbrains/frontend/src/test/kotlin/ai/kilocode/client/stability/probe-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/edt-stall-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/unclean-test.kt
git commit -m "feat(jetbrains): capture stall and unclean evidence"
```

### Task 8: Prove outbox-only diagnosis end to end

**Files:**
- Modify: `packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityE2eTest.kt`
- Modify: `packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityDictionaryE2eTest.kt`
- Modify: `packages/kilo-jetbrains/script/stability-acceptance.ps1`
- Create: `.changeset/jetbrains-outbox-diagnostics.md`

**Interfaces:**
- Consumes: all diagnostic, correlation, priority, and special-evidence behavior from Tasks 1–7.
- Produces: reproducible evidence that a copied outbox alone explains representative plugin failures.

- [ ] **Step 1: Add an outbox-only integration scenario**

Run a sandbox IDE against the fake daemon and trigger JSON type mismatch, HTTP 404, MCP bind failure, EDT stall, and an unclean restart. After shutdown, parse only the copied outbox and assert:

```kotlin
assertIncident("json_decode_failed", operation = "rpc", payload = "response")
assertIncident("http_not_found", status = 404, payload = "response")
assertIncident("ide_capability_bind_failed", operation = "mcp_register")
assertStallWithStack(minimumMs = 2_000)
assertUncleanWithOpenOperations()
assertNoCredential(SECRET_TOKEN, SECRET_COOKIE, SECRET_PASSWORD)
```

The test must not read `kilo.log` or `idea.log`.

- [ ] **Step 2: Add load and metric-quality acceptance**

Generate periodic samples until lower-priority capacity is full, concurrently produce 100 unique failures, then verify every failure incident is complete. Assert health reports sample eviction by reason and `quality=good` when no failure/end is lost. Inject a forced failure admission error and assert `quality=degraded`.

- [ ] **Step 3: Run focused integration and package checks**

From `packages/kilo-jetbrains/`:

```powershell
.\gradlew.bat compileIntegrationTestKotlin
.\gradlew.bat integrationTest --tests "ai.kilocode.jetbrains.StabilityE2eTest"
.\gradlew.bat integrationTest --tests "ai.kilocode.jetbrains.StabilityDictionaryE2eTest"
.\gradlew.bat typecheck
```

Expected: PASS. If integration infrastructure is unavailable, record the exact failed command and do not claim outbox-only diagnosis is complete.

- [ ] **Step 4: Run repository guards**

From the repository root:

```powershell
bun run script/check-md-table-padding.ts docs/superpowers/specs/2026-09-23-jetbrains-outbox-diagnostics-design.md docs/superpowers/plans/2026-09-23-jetbrains-outbox-diagnostics.md
git diff --check
```

Expected: both commands exit successfully.

- [ ] **Step 5: Add the user-facing changeset and commit**

Create `.changeset/jetbrains-outbox-diagnostics.md`:

```markdown
---
"@kilocode/kilo-jetbrains": minor
---

Persist complete, correlated plugin diagnostics in the JetBrains telemetry outbox.
```

Then commit:

```powershell
git add packages/kilo-jetbrains/src/integrationTest packages/kilo-jetbrains/script/stability-acceptance.ps1 .changeset/jetbrains-outbox-diagnostics.md
git commit -m "test(jetbrains): verify outbox-only diagnostics"
```

## Final verification

- [ ] Run the final focused regression set from `packages/kilo-jetbrains/`:

```powershell
.\gradlew.bat typecheck
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.ContractTest" --tests "ai.kilocode.stability.DictionarySweepTest" --tests "ai.kilocode.stability.DiagnosticRedactorTest" --tests "ai.kilocode.stability.DiagnosticPayloadTest" --tests "ai.kilocode.stability.QueueTest" --tests "ai.kilocode.stability.HealthTest" --tests "ai.kilocode.stability.WriterTest" --tests "ai.kilocode.stability.DiagnosticBridgeTest" --tests "ai.kilocode.stability.FaultTest" --tests "ai.kilocode.stability.DiagnosticsTest" --tests "ai.kilocode.stability.RpcObservationTest" --tests "ai.kilocode.stability.ErrorClassifierTest" --tests "ai.kilocode.stability.EdtStallTest" --tests "ai.kilocode.stability.UncleanTest"
.\gradlew.bat :frontend:test --tests "ai.kilocode.client.stability.ProbeTest"
.\gradlew.bat :backend:test --tests "ai.kilocode.backend.telemetry.KiloBackendTelemetryTest"
.\gradlew.bat :cs-cloud:test
.\gradlew.bat integrationTest --tests "ai.kilocode.jetbrains.StabilityE2eTest" --tests "ai.kilocode.jetbrains.StabilityDictionaryE2eTest"
```

- [ ] Inspect the generated JSONL with the acceptance script and confirm every incident can be reconstructed without reading another log file.
- [ ] Search the generated outbox for every injected credential and require zero matches.
- [ ] Confirm the worktree contains no unrelated changes and no `kilocode_change` markers were added under Kilo-owned paths.
