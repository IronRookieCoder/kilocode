# JetBrains Single Stability File Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every JetBrains IDE installation append stability facts to one `<scope-id>.jsonl` file across restarts and plugin upgrades.

**Architecture:** Keep `scope_id` as the persistent file identity while retaining `producer_id` and `run_id` inside each fact. Reuse the existing single-process `Writer`; normal IntelliJ single-instance behavior remains the concurrency boundary. On activation, discard same-scope files from the debug-only `<scope-id>-<producer-id>.jsonl` layout, inspect the stable scope file for an unclean previous run, and append the new run to that file.

**Tech Stack:** Kotlin, IntelliJ Platform application services, Java NIO, kotlinx.serialization, JUnit/Kotlin test.

**Spec:** `docs/jetbrains-stability-design.md`

## Global Constraints

- The fact file is exactly `~/.costrict/telemetry/outbox/<scope-id>.jsonl`.
- Restarting the IDE or upgrading the plugin must append to the same scope file.
- `producer_id` remains random per JVM and `run_id` remains random per collection run.
- Legacy same-scope `<scope-id>-<producer-id>.jsonl` files are deleted without migration; files belonging to other scopes are untouched.
- Concurrent JVMs sharing one IntelliJ configuration directory are unsupported; do not add inter-process locking.
- Preserve UTF-8 NDJSON, one-write-per-line, flush, capacity rewrite, policy, and revocation semantics.
- Changes stay in Kilo-owned JetBrains paths, so `kilocode_change` markers are not required.
- Run focused JetBrains tests and type checking; do not run the repository-root test command.

---

### Task 1: Pin the stable scope filename and debug-layout cleanup

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/producer-test.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/stability-service.kt`
- Delete: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/retention.kt`
- Delete: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/retention-test.kt`

**Interfaces:**
- Consumes: persisted `scopeId` from `ScopeIdStore` and the existing `Writer(root, fileName, identity, ...)` constructor.
- Produces: private `fileName(): String` returning `"$scopeId.jsonl"` and private `clearLegacy()` deleting only regular same-scope legacy files.

- [ ] **Step 1: Write failing service tests for the filename and cleanup boundary**

Change the existing outbox-layout assertion to require `sc-fixed.jsonl`. Add a test that pre-creates these entries before starting the service:

```kotlin
val outbox = home.resolve("outbox").apply { createDirectories() }
val legacy = outbox.resolve("sc-fixed-pr-old.jsonl").apply { writeText("legacy\n") }
val other = outbox.resolve("sc-other-pr-old.jsonl").apply { writeText("other\n") }
val junk = outbox.resolve("sc-fixed-notes.txt").apply { writeText("notes\n") }

service.start("monolith")
awaitUntil(10_000) { Files.exists(outbox.resolve("sc-fixed.jsonl")) }
assertFalse(Files.exists(legacy))
assertTrue(Files.exists(other))
assertTrue(Files.exists(junk))
```

Also add a two-launch test using the same telemetry home and `ScopeIdStore { "sc-fixed" }`. Stop the first service, start a second service, then assert there is exactly one `sc-fixed.jsonl` containing two distinct `producer_id` values and two distinct `run_id` values.

- [ ] **Step 2: Run the focused tests and confirm the old layout fails**

Run from `packages/kilo-jetbrains/`:

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.ProducerTest"
```

Expected: failure because the service still creates `sc-fixed-pr-<id>.jsonl`, retains legacy files, or produces two files across launches.

- [ ] **Step 3: Implement scope-only naming and one-time legacy deletion**

In `StabilityService`, replace the identity-dependent filename helper with:

```kotlin
private fun fileName(): String = "$scopeId.jsonl"
```

Update writer creation, revocation deletion, and all file resolution call sites to use `fileName()`. Add a private cleanup function that lists only the flat outbox directory, accepts only `Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)`, and deletes names starting with `"$scopeId-pr-"` and ending in `.jsonl`. Invoke it after the new writer has verified/opened the layout but before unclean detection and `plugin.started`. Do not read, merge, or rename legacy data, and do not touch another scope.

Remove `retentionJob`, `retentionIntervalMs`, `retentionLoop()`, `sweepOnce()`, `Retention`, and `RetentionTest`: plugin-side 24-hour cleanup of per-producer files no longer exists in the approved protocol. Before deleting `retention-test.kt`, move its `SweepClock` test clock into `producer-test.kt` beside the service harness; it is used only by that test file after the retention tests are removed.

- [ ] **Step 4: Run the focused service tests**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.ProducerTest"
```

Expected: PASS; two sequential service instances append to one scope file and cleanup is limited to same-scope legacy JSONL files.

- [ ] **Step 5: Commit the layout change**

```powershell
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/stability-service.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/retention.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/producer-test.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/retention-test.kt
git commit -m "fix(jetbrains): reuse one stability file per IDE"
```

### Task 2: Detect an unclean predecessor inside the shared scope file

**Files:**
- Modify: `packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/unclean-test.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/unclean.kt`
- Modify: `packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/stability-service.kt`

**Interfaces:**
- Consumes: the stable scope-file `Path` produced by Task 1 and serialized `Fact` rows.
- Produces: `UncleanDetector(file: Path).detect(): List<Draft>`, returning zero or one `plugin.unclean` draft for the latest prior started run.

- [ ] **Step 1: Rewrite unclean tests around one file containing multiple runs**

Cover these observable cases with real temporary files:

```kotlin
val file = dir.resolve("sc-live.jsonl")
file.writeText(
    factLine(name = "plugin.started", runId = "run-a") +
        factLine(name = "plugin.shutdown", runId = "run-a") +
        factLine(name = "plugin.started", runId = "run-b"),
)
val drafts = UncleanDetector(file).detect()
assertEquals(1, drafts.size)
assertEquals("run-b", drafts.single().data.field("previous_run_id"))
```

Add cases for a latest run with matching shutdown, a missing scope file, malformed complete lines, and an unterminated tail. The detector must consider only the last valid `plugin.started` and must require a later shutdown with the same `run_id`.

- [ ] **Step 2: Run the detector tests and confirm the directory-scanning API fails**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.UncleanTest"
```

Expected: compilation or assertion failure because `UncleanDetector` still expects directory/scope/producer arguments and scans separate predecessor files.

- [ ] **Step 3: Implement single-file unclean detection**

Change the constructor to accept only the scope file. Return `emptyList()` when it is absent or not a regular file. Parse only LF-terminated nonblank rows, skip malformed rows, locate the last `plugin.started`, and emit one draft only when no later `plugin.shutdown` has that started row's `run_id`.

Update `StabilityService.activateRun()` to call:

```kotlin
UncleanDetector(outboxDir().resolve(fileName())).detect()
```

Keep detection before recording the current `plugin.started`.

- [ ] **Step 4: Run unclean and service regression tests**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.UncleanTest" --tests "ai.kilocode.stability.ProducerTest"
```

Expected: PASS, including unclean detection after reopening the same scope file.

- [ ] **Step 5: Commit the lifecycle update**

```powershell
git add packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/unclean.kt packages/kilo-jetbrains/shared/src/main/kotlin/ai/kilocode/stability/stability-service.kt packages/kilo-jetbrains/shared/src/test/kotlin/ai/kilocode/stability/unclean-test.kt
git commit -m "fix(jetbrains): detect unclean runs in the scope file"
```

### Task 3: Update end-to-end guards and manual verification

**Files:**
- Modify: `packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityE2eTest.kt`
- Modify: `packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityDictionaryE2eTest.kt`
- Modify carefully, preserving pre-existing user edits: `docs/jetbrains-stability-manual-test-guide.md`

**Interfaces:**
- Consumes: `<scope-id>.jsonl` layout from Task 1.
- Produces: integration and manual acceptance checks that reject producer-suffixed filenames and verify reuse across restart/upgrade.

- [ ] **Step 1: Change independent integration filename guards**

In both integration tests, change the outbox filename regex to:

```kotlin
private val outboxFileRegex = Regex("^sc-[0-9a-f]{12}\\.jsonl$")
```

Update comments and assertion messages from “producer fact file” to “IDE scope fact file”. Keep all row-level `producer_id` assertions because producer identity remains part of the wire contract.

- [ ] **Step 2: Update manual scenarios without overwriting unrelated working-tree edits**

Review the existing diff first. Update the manual guide so that:

- startup expects `<scope-id>.jsonl`;
- restart/upgrade expects the same filename, a new `producer_id`, and a new `run_id` appended in that file;
- crash recovery expects `plugin.unclean` in the same file;
- capacity is 10 MiB per IDE scope file;
- plugin-side 24-hour same-scope producer-file cleanup is removed;
- multiple IDE installations use different scope files;
- concurrent JVMs sharing one configuration directory are explicitly unsupported.

- [ ] **Step 3: Compile the integration test sources**

```powershell
.\gradlew.bat compileIntegrationTestKotlin
```

Expected: PASS with both regex guards compiled against the new layout.

- [ ] **Step 4: Run documentation guards**

Run from the repository root:

```powershell
bun run script/check-md-table-padding.ts docs/jetbrains-stability-design.md docs/jetbrains-stability-manual-test-guide.md
git diff --check
```

Expected: both commands exit successfully.

- [ ] **Step 5: Commit integration expectations and documentation**

```powershell
git add packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityE2eTest.kt packages/kilo-jetbrains/src/integrationTest/kotlin/ai/kilocode/jetbrains/StabilityDictionaryE2eTest.kt docs/jetbrains-stability-manual-test-guide.md
git commit -m "test(jetbrains): verify one stability file per IDE"
```

### Task 4: Verify the JetBrains stability change

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: all behavior implemented in Tasks 1–3.
- Produces: fresh test, typecheck, and worktree evidence for handoff.

- [ ] **Step 1: Run all shared stability tests**

```powershell
.\gradlew.bat :shared:test --tests "ai.kilocode.stability.*"
```

Expected: PASS.

- [ ] **Step 2: Run JetBrains type checking**

```powershell
.\gradlew.bat typecheck
```

Expected: PASS.

- [ ] **Step 3: Re-run integration source compilation**

```powershell
.\gradlew.bat compileIntegrationTestKotlin
```

Expected: PASS.

- [ ] **Step 4: Inspect the final diff and naming contract**

```powershell
rg -n "scope-id>-<producer-id>|sc-.*-pr-.*jsonl|Retention\(" packages/kilo-jetbrains docs/jetbrains-stability-design.md docs/jetbrains-stability-manual-test-guide.md
git diff --check
git status --short
```

Expected: no production/test/documentation references require producer-suffixed active files; any remaining legacy filename text is explicitly about deletion, and unrelated user changes remain unmodified.
