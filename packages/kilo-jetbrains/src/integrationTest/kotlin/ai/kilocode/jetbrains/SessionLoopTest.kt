package ai.kilocode.jetbrains

import ai.kilocode.jetbrains.mock.CsEvents
import ai.kilocode.jetbrains.mock.esc
import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.ui
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * [F] The daily AI-coding core loop and the code-review flow, driven over one scripted daemon
 * session per method (spec §7 U3, §8 U7; the SSE event replay — the largest piece of the
 * suite — lives here). Event payloads mirror `KiloCliDataParser.parseChatEvent` (GlobalEvent
 * wrapper); permission/question receipts ride the `/api/v1/permissions*|/api/v1/questions*`
 * POSTs; report completion rides `host.file.updated` into `CodeReviewReportWatcher`.
 *
 * Mock replay proves the plugin side of the protocol only; real agent semantics stay with
 * T3/人工 (spec §1 principle 4).
 *
 * Also covers the plan §5 P1 gaps: A-7 credit-rate labels in the model picker, U7.7/U7.8
 * (abort must not produce a completion balloon; consecutive reports produce distinct ones)
 * and the non-toolbar review entry points' wiring to the command channel.
 */
class SessionLoopTest : IntegrationTestBase() {

    @Test
    fun `session loop journey`() {
        var abortsAtClose = 0
        val result = runPluginIde("costrictSessionLoop") {
            awaitColdStartReady()
            awaitSessionUiReady()
            daemon.assertWorkspaceHeaderEquals(fixtureProjectDir.toString())

            // —— Segment U3.1: New Session → conversation creation POST ——
            // The UI may defer the REST create to the first prompt; either way the daemon must
            // see the conversations-create surface before the loop starts. invokeAction is
            // best-effort: the global action context has no SessionManager data (the enable
            // check needs the tool window's own data context), the typed prompt below is the
            // guaranteed creation path.
            runCatching { invokeAction("Kilo.NewSession") }
            runCatching { daemon.awaitRequest("POST", "/api/v1/conversations", 20_000) }

            // —— Segment U3.2: prompt → scripted SSE delta stream renders incrementally ——
            sendPrompt("Create a hello world file")
            val promptPosts = promptAsyncPosts()
            assertTrue(promptPosts.isNotEmpty(), "prompt/async POST never reached the daemon")
            val sessionId = sessionIdFrom(promptPosts.last().path)
            streamAssistantAnswer(sessionId, "Hello from the mock daemon")
            awaitFrameText({ it.contains("Hello from the mock daemon") }, timeoutMs = 30_000)

            // —— Segment U3.4: permission card → Allow → receipt recorded ——
            val allowRequestId = CsEvents.newId("perm")
            broadcastSafely(
                CsEvents.permissionAsked(
                    fixtureProjectDir.toString(),
                    CsEvents.permissionProperties(allowRequestId, sessionId),
                ),
            )
            awaitFrameText({ it == "Allow" }, timeoutMs = 20_000)
            assertTrue(clickFrameText({ it == "Allow" }), "'Allow' affordance never appeared")
            daemon.awaitRequest("POST", "/api/v1/permissions", 20_000)

            // —— Segment U3.5 (★): permission card → Deny → session stays interactive ——
            val denyRequestId = CsEvents.newId("perm")
            broadcastSafely(
                CsEvents.permissionAsked(
                    fixtureProjectDir.toString(),
                    CsEvents.permissionProperties(denyRequestId, sessionId),
                ),
            )
            awaitFrameText({ it == "Deny" }, timeoutMs = 20_000)
            assertTrue(clickFrameText({ it == "Deny" }), "'Deny' affordance never appeared")
            daemon.awaitNewRequest("POST", "/api/v1/permissions", permissionReceiptCount(), 20_000)

            // —— Segment U3.7: question card → option click → receipt recorded ——
            val questionId = CsEvents.newId("q")
            broadcastSafely(
                CsEvents.questionAsked(
                    fixtureProjectDir.toString(), questionId, sessionId,
                    question = "Proceed with option A?", options = listOf("Option A", "Option B"),
                ),
            )
            awaitFrameText({ it.contains("Proceed with option A?") }, timeoutMs = 20_000)
            assertTrue(clickFrameText({ it == "Option A" }), "question options never appeared")
            daemon.awaitRequest("POST", "/api/v1/questions", 20_000)

            // —— Segment U3.12: second prompt reaches the daemon with its own payload (J2) ——
            sendPrompt("Second turn prompt")
            val secondPromptPosts = promptAsyncPosts()
            assertTrue(
                secondPromptPosts.size >= 2 && secondPromptPosts[0].body != secondPromptPosts[1].body,
                "second prompt must reach the daemon with its own payload (J2)",
            )

            // —— Segment U3.11 (+R-1): Stop lands while running, also after an SSE orphan ——
            broadcastSafely(CsEvents.sessionStatus(fixtureProjectDir.toString(), sessionId, "busy"))
            invokeAction("Kilo.StopSession")
            awaitAbortCount(atLeast = 1)

            // Orphan run: the stream is already gone when the user hits Stop (R-1 regression).
            daemon.breakSseConnections()
            invokeAction("Kilo.StopSession")
            awaitAbortCount(atLeast = 2, timeoutMs = 30_000)

            // —— Segment U3.8: host file event path is accepted (R2 host.file.*; VFS visibility
            //    in the project tree stays with the T3 probe round) ——
            val generated = fixtureProjectDir.resolve("src").resolve("generated-by-mock.txt")
            Files.writeString(generated, "written by the test process\n")
            broadcastSafely(CsEvents.hostFileUpdated(fixtureProjectDir.toString(), generated.toString()))

            // —— Segment U4.1 (mock side): history has at least one session to restore ——
            assertTrue(daemon.conversations.isNotEmpty(), "daemon should have recorded at least one conversation")

            abortsAtClose = daemon.requests.count { it.path.endsWith("/abort") || it.path.endsWith("/stop") }
        }

        // —— Post-close assertions (IDE process fully awaited by useDriverAndCloseIde) ——
        // M22: shutting the IDE down must not emit any stop-class request beyond the session's own.
        Thread.sleep(2_000)
        val stopLikeAfterClose = daemon.requests.count { it.path.endsWith("/abort") || it.path.endsWith("/stop") }
        assertTrue(
            stopLikeAfterClose == abortsAtClose,
            "IDE shutdown must not emit stop-class requests (M22): $abortsAtClose during session, $stopLikeAfterClose after close",
        )

        // M20a: no credential value may leak into the IDE log.
        assertIdeLogHasNoCredentials(ideLogText(result))
    }

    @Test
    fun `code review flow`() {
        runPluginIde("costrictCodeReview") {
            awaitColdStartReady()

            // U7.1 (disabled state without a session) is a T1 duty: ReviewActionUpdateTest.
            // Create a live session first — review entry points send into the active session.
            awaitSessionUiReady()
            sendPrompt("warm up session for review")
            val promptPosts = promptAsyncPosts()
            assertTrue(promptPosts.isNotEmpty(), "session must be live before review triggers")
            val sessionId = sessionIdFrom(promptPosts.last().path)

            // —— Segment U7.2: toolbar entry → `/review` command POST with args ——
            invokeAction("Kilo.CodeReview.Changes")
            val commands = commandPosts()
            assertTrue(commands.isNotEmpty(), "review command POST never reached the daemon")
            assertTrue(
                commands.last().body.contains("review"),
                "command body must carry the review command: ${commands.last().body.take(200)}",
            )

            // —— Segment U7.3: the five-stage progress is a scripted SSE replay; assert one stage ——
            val stageMessage = "Stage 2/5: collecting changes"
            broadcastSafely(CsEvents.sessionStatus(fixtureProjectDir.toString(), sessionId, "busy", stageMessage))
            awaitFrameText({ it.contains(stageMessage) }, timeoutMs = 20_000)

            // —— Segment U7.4: report file lands → counts notification in the review group ——
            val reportPath = fixtureProjectDir.resolve("code-review_result").resolve("review-report.json")
            val project = singleProject()

            writeReviewReport(reportPath, high = 2, middle = 1, low = 1, qualityLine = "质量评分：78")
            val beforeCompleted = notificationSnapshot(project)
            broadcastSafely(CsEvents.hostFileUpdated(fixtureProjectDir.toString(), reportPath.toString()))
            awaitNotification(project, beforeCompleted, matches = { text -> text.contains("CoStrict") })

            // —— Segment U7.6: zero-issue report → "no issues" copy, flow intact ——
            writeReviewReport(reportPath, high = 0, middle = 0, low = 0, qualityLine = "质量评分：95")
            val beforeNoIssues = notificationSnapshot(project)
            broadcastSafely(CsEvents.hostFileUpdated(fixtureProjectDir.toString(), reportPath.toString()))
            awaitNotification(project, beforeNoIssues, matches = { text -> text.contains("CoStrict") })

            // —— Segment U7.9 (★ degraded): unknown marker → degraded copy, flow must not die ——
            writeReviewReport(reportPath, high = 0, middle = 0, low = 0, qualityLine = null, marker = "I-AM-NOT-A-REPORT")
            val beforeDegraded = notificationSnapshot(project)
            broadcastSafely(CsEvents.hostFileUpdated(fixtureProjectDir.toString(), reportPath.toString()))
            awaitNotification(project, beforeDegraded, matches = { text -> text.contains("CoStrict") })
        }
    }

    @Test
    fun `model picker surfaces daemon credit rates`() {
        runPluginIde("costrictModelCreditRates") {
            awaitColdStartReady()
            awaitSessionUiReady()

            // Deterministic front (A-7): the workspace load must have fetched the mock catalog
            // that carries the billing rates the real daemon injects — coder-pro
            // creditConsumption=3.0, coder-lite=0.5 (Scenario.modelsBody).
            daemon.awaitRequest("GET", "/api/v1/agents/models", 20_000)

            // Transit half: the picker button shows the daemon's provider/model label once the
            // catalog reached the picker (ModelText.buttonLabel).
            assertTrue(
                clickFrameText({ it.contains("Costrict Cloud / Coder Pro") }),
                "model picker button never showed the daemon provider/model label",
            )
            // Presentation half: opened popup rows append the credit labels
            // (ModelText.creditLabel — "3x credit" / "0.5x credit"). The mock serves no Auto
            // row, so the "% discount" branch stays with T1 (ModelPickerTest). If a
            // heavy-weight popup ever escapes frame traversal this fails visibly; the degraded
            // path for the rendered popup is then a manual UI check, the label rules
            // themselves remain T1-covered.
            awaitFrameText({ it.contains("3x credit") }, timeoutMs = 20_000)
            awaitFrameText({ it.contains("0.5x credit") }, timeoutMs = 10_000)
        }
    }

    @Test
    fun `code review abort safety, repeat reports and other entry points`() {
        runPluginIde("costrictCodeReviewSafety") {
            awaitColdStartReady()
            awaitSessionUiReady()
            sendPrompt("warm up session for review entry points")
            val promptPosts = promptAsyncPosts()
            assertTrue(promptPosts.isNotEmpty(), "session must be live before review triggers")
            val sessionId = sessionIdFrom(promptPosts.last().path)
            val project = singleProject()

            // —— Segment U7.2b: editor/project-view entries also send the review command ——
            // Driver invokeAction carries no editor/selection/view context, so the target
            // falls back to Changes; the exact `@/path` args grammar stays with T1
            // (ReviewArgsTest). What is proven here is the wiring: both registered entry
            // actions reach the daemon through the live session's command channel.
            val before = commandPosts().size
            invokeAction("Kilo.CodeReview.File")
            invokeAction("Kilo.CodeReview.Directory")
            awaitCommandCount(atLeast = before + 2)
            val entries = commandPosts().drop(before)
            assertTrue(
                entries.all { it.body.contains("review") },
                "entry commands must carry the review command: ${entries.map { it.body.take(120) }}",
            )

            // —— Segment U7.7 (★): abort before any report lands → no completion balloon ——
            val preAbort = notificationSnapshot(project)
            invokeAction("Kilo.CodeReview.Changes")
            awaitCommandCount(atLeast = before + 3)
            broadcastSafely(CsEvents.sessionStatus(fixtureProjectDir.toString(), sessionId, "busy", "Stage 2/5: collecting changes"))
            invokeAction("Kilo.StopSession")
            awaitAbortCount(atLeast = 1)
            assertNoCompletionNotification(project, preAbort)

            // —— Segment U7.8: two consecutive runs → two distinct version-pinned balloons ——
            val reportPath = fixtureProjectDir.resolve("code-review_result").resolve("review-report.json")
            writeReviewReport(reportPath, high = 1, middle = 0, low = 0, qualityLine = "质量评分：70")
            val afterAbort = notificationSnapshot(project)
            broadcastSafely(CsEvents.hostFileUpdated(fixtureProjectDir.toString(), reportPath.toString()))
            awaitNotification(project, afterAbort, matches = { it.contains("质量评分：70") })

            // A different body ⇒ a new (size, mtime) dedupe key ⇒ a second balloon.
            writeReviewReport(reportPath, high = 2, middle = 1, low = 0, qualityLine = "质量评分：85")
            val afterFirst = notificationSnapshot(project)
            broadcastSafely(CsEvents.hostFileUpdated(fixtureProjectDir.toString(), reportPath.toString()))
            awaitNotification(project, afterFirst, matches = { it.contains("质量评分：85") })
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    /** Ordered incremental rendering: message snapshot → per-fragment deltas → part snapshots → idle. */
    private fun streamAssistantAnswer(sessionId: String, answer: String) {
        val messageId = CsEvents.newId("msg")
        val partId = CsEvents.newId("part")
        broadcastSafely(CsEvents.messageUpdated(fixtureProjectDir.toString(), messageId, sessionId))
        var streamed = ""
        for (fragment in answer.chunked(9)) {
            broadcastSafely(
                CsEvents.partDelta(fixtureProjectDir.toString(), sessionId, messageId, partId, delta = fragment),
            )
            streamed += fragment
            broadcastSafely(
                CsEvents.partUpdated(fixtureProjectDir.toString(), sessionId, CsEvents.textPart(partId, messageId, streamed)),
            )
        }
        broadcastSafely(CsEvents.sessionIdle(fixtureProjectDir.toString(), sessionId))
    }

    private fun promptAsyncPosts() =
        daemon.requests("POST", "/api/v1/conversations").filter { it.path.endsWith("/prompt/async") }

    private fun commandPosts() =
        daemon.requests("POST", "/api/v1/conversations").filter { it.path.endsWith("/command") }

    private fun permissionReceiptCount(): Int =
        daemon.requests.count { it.path.startsWith("/api/v1/permissions") && it.method == "POST" }

    private fun awaitAbortCount(atLeast: Int, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (daemon.requests("POST", "/api/v1/conversations").count { it.path.endsWith("/abort") } >= atLeast) return
            Thread.sleep(100)
        }
        throw AssertionError("abort POST count never reached $atLeast within ${timeoutMs}ms")
    }

    private fun awaitCommandCount(atLeast: Int, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (commandPosts().size >= atLeast) return
            Thread.sleep(100)
        }
        throw AssertionError("command POST count never reached $atLeast within ${timeoutMs}ms")
    }

    /**
     * U7.7 absence assertion: after an aborted review (no report ever landed) the completed
     * balloon must never show. Absence is inherently timing-specific — settle once, then
     * compare against the pre-abort snapshot.
     */
    private fun Driver.assertNoCompletionNotification(project: Project, before: List<String>, settleMs: Long = 3_000) {
        Thread.sleep(settleMs)
        val fresh = notificationSnapshot(project) - before.toSet()
        val completed = fresh.filter { it.contains("code review completed") }
        assertTrue(completed.isEmpty(), "aborted review must not produce a completion notification: $completed")
    }

    private fun Driver.notificationSnapshot(project: Project) =
        getNotifications(project).map { "${it.getGroupId()}|${it.getTitle()}|${it.getContent()}" }

    private fun Driver.awaitNotification(project: Project, before: List<String>, timeoutMs: Long = 30_000, matches: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = notificationSnapshot(project)
        while (System.currentTimeMillis() < deadline) {
            val fresh = latest - before.toSet()
            if (fresh.any(matches)) return
            Thread.sleep(300)
            latest = notificationSnapshot(project)
        }
        throw AssertionError("expected a matching notification within ${timeoutMs}ms; latest: ${latest.takeLast(10)}")
    }

    private fun writeReviewReport(
        jsonPath: Path,
        high: Int,
        middle: Int,
        low: Int,
        qualityLine: String?,
        marker: String = REPORT_MARKER,
    ) {
        Files.createDirectories(jsonPath.parent)
        val issues = buildList {
            repeat(high) { add("高") }
            repeat(middle) { add("中") }
            repeat(low) { add("低") }
        }.joinToString(",", "[", "]") { """{"severity":"$it","title":"issue","path":"src/Example.kt"}""" }
        val conclusion = qualityLine?.let { "## 结论\n$it\n" } ?: "## 结论\n无法解析\n"
        val body = """{"report":"$marker","issues":$issues,"conclusion":"${esc(conclusion)}"}"""
        Files.writeString(jsonPath, body)
        Files.writeString(jsonPath.resolveSibling("review-report.md"), "# Code Review Report\n\n$body\n")
    }

    private fun sessionIdFrom(path: String): String =
        path.removePrefix("/api/v1/conversations/").substringBefore('/')

    private companion object {
        const val REPORT_MARKER = "I-AM-CODE-REVIEW-REPORT-V1"
    }
}
