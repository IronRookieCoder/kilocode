package ai.kilocode.client.app

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.testing.FakeAppRpcApi
import ai.kilocode.client.testing.TEST_WAIT_MS
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.CsCloudStartDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.ProfileBalanceDto
import ai.kilocode.rpc.dto.ProfileDto
import ai.kilocode.rpc.dto.ProfileOrganizationDto
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout

/**
 * Service-level tests for [KiloAppService] profile/login/logout/org operations.
 *
 * Uses [FakeAppRpcApi] to avoid RPC/backend involvement.
 */
@Suppress("UnstableApiUsage")
class KiloAppServiceTest : BasePlatformTestCase() {

    private lateinit var scope: CoroutineScope
    private lateinit var rpc: FakeAppRpcApi
    private lateinit var app: KiloAppService

    /** Copy-on-write: the notification subscriber thread races with the test's own iteration. */
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob())
        rpc = FakeAppRpcApi()
        // No-op runner: the service launches the work itself, so the progress task is not needed.
        app = KiloAppService(scope, rpc) { _, _ -> }
        app._state.value = KiloAppStateDto(KiloAppStatusDto.READY)
        notifications.clear()
    }

    override fun tearDown() {
        try {
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    private fun profile(
        email: String = "alice@test.com",
        name: String? = "Alice",
        balance: ProfileBalanceDto? = null,
        orgs: List<ProfileOrganizationDto> = emptyList(),
        currentOrgId: String? = null,
    ) = ProfileDto(email = email, name = name, organizations = orgs, balance = balance, currentOrgId = currentOrgId)

    /**
     * Waits for [done] on the test's own coroutine scheduler.
     *
     * Deliberately no EDT pumping here: the platform test framework runs this class's test bodies
     * on the EDT inside `runBlocking`, so the EDT is parked and any `invokeAndWait`-based pump
     * would deadlock against it.
     */
    private suspend fun waitUntil(done: () -> Boolean) {
        withTimeout(TEST_WAIT_MS) {
            while (!done()) yield()
        }
    }

    /** [waitUntil] for a value that only appears once (or only grows), returning the first non-null. */
    private suspend fun <T : Any> waitUntilValue(poll: () -> T?): T {
        waitUntil { poll() != null }
        return poll()!!
    }

    // ------ installCscAsync notifications ------

    /** Records the notifications the service raises, whatever project (or none) it attaches them to. */
    private fun recordNotifications() {
        val subscriber = object : Notifications {
            override fun notify(notification: Notification) {
                notifications.add(notification)
            }
        }
        val disposable = testRootDisposable
        project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, subscriber)
        ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(Notifications.TOPIC, subscriber)
    }

    private suspend fun runInstallAndAwaitErrors(): Notification {
        recordNotifications()
        app.installCscAsync()
        // The "installing" feedback lives in the background task now, so the only notice is the outcome.
        return waitUntilValue { notifications.firstOrNull { it.type == NotificationType.ERROR } }
    }

    fun `test installCscAsync keeps the install failure title for the install stage`() = runBlocking(Dispatchers.Default) {
        rpc.csCloudInstall = CsCloudStartDto(ok = false, message = "npm install failed", stage = CsCloudStartDto.STAGE_INSTALL)

        val error = runInstallAndAwaitErrors()

        assertEquals(KiloBundle.message("csCloud.install.failed"), error.title)
        assertEquals("npm install failed", error.content)
    }

    fun `test installCscAsync reports the start failure when only cs-cloud fails to start`() = runBlocking(Dispatchers.Default) {
        rpc.csCloudInstall = CsCloudStartDto(ok = false, message = "port 3000 busy", stage = CsCloudStartDto.STAGE_START)

        val error = runInstallAndAwaitErrors()

        assertEquals(KiloBundle.message("csCloud.install.failed.start"), error.title)
        assertEquals("port 3000 busy", error.content)
    }

    fun `test installCscAsync falls back to the install title when the stage is unknown`() = runBlocking(Dispatchers.Default) {
        rpc.csCloudInstall = CsCloudStartDto(ok = false, message = "cs-cloud daemon is not managed by this connection")

        val error = runInstallAndAwaitErrors()

        assertEquals(KiloBundle.message("csCloud.install.failed"), error.title)
        assertEquals("cs-cloud daemon is not managed by this connection", error.content)
    }

    fun `test installCscAsync offers installation docs and the npm page when npm is missing`() = runBlocking(Dispatchers.Default) {
        rpc.csCloudInstall = CsCloudStartDto(
            ok = false,
            message = "no package manager was found",
            code = ConnectionErrorCode.NPM_NOT_FOUND,
            stage = CsCloudStartDto.STAGE_INSTALL,
        )

        val error = runInstallAndAwaitErrors()

        assertEquals(KiloBundle.message("csCloud.install.failed"), error.title)
        assertEquals(KiloBundle.message("csCloud.install.npmMissing.desc"), error.content)
        assertEquals(2, error.actions.size)
        assertEquals(KiloBundle.message("csCloud.install.npmMissing.docs"), error.actions[0].templateText)
        assertEquals(KiloBundle.message("action.Kilo.OpenCscNpm.text"), error.actions[1].templateText)
    }

    fun `test installCscAsync announces success without an error notice`() = runBlocking(Dispatchers.Default) {
        rpc.csCloudInstall = CsCloudStartDto(ok = true, message = "installed")

        recordNotifications()
        app.installCscAsync()
        waitUntil { notifications.isNotEmpty() }

        // Only the success outcome is raised - the progress feedback moved into the background task.
        assertEquals(listOf(KiloBundle.message("csCloud.install.ok")), notifications.map { it.title }.distinct())
        assertTrue(notifications.none { it.type == NotificationType.ERROR })
    }

    fun `test installCscAsync reports a busy install instead of ignoring a second click`() = runBlocking(Dispatchers.Default) {
        var job: Job? = null
        val runner = CsCloudTaskRunner { _, queued -> job = queued }
        val cancellable = KiloAppService(scope, rpc, runner)
        rpc.csCloudInstallGate = CompletableDeferred()
        recordNotifications()
        cancellable.installCscAsync()
        waitUntil { rpc.csCloudInstalls == 1 }

        cancellable.installCscAsync()

        assertEquals(1, rpc.csCloudInstalls) // a second click must not start a second install
        val busy = waitUntilValue { notifications.firstOrNull { it.title == KiloBundle.message("csCloud.install.busy") } }
        assertEquals(NotificationType.INFORMATION, busy.type)

        // The first install must settle (job done = lock released) before the next click: the
        // outcome notification is posted inside the job, so it is not a lock-released signal.
        rpc.csCloudInstallGate!!.complete(Unit)
        job!!.join()
        cancellable.installCscAsync()
        waitUntil { rpc.csCloudInstalls == 2 }
    }

    fun `test installCscAsync releases the lock when the background task is cancelled`() = runBlocking(Dispatchers.Default) {
        var job: Job? = null
        val runner = CsCloudTaskRunner { _, queued -> job = queued }
        val cancellable = KiloAppService(scope, rpc, runner)
        rpc.csCloudInstallGate = CompletableDeferred()
        recordNotifications()

        cancellable.installCscAsync()
        waitUntil { rpc.csCloudInstalls == 1 }
        job!!.cancelAndJoin()

        assertTrue("cancelling must not be reported as a failure", notifications.none { it.type == NotificationType.ERROR })
        // The lock is released, so a fresh install can start right away.
        cancellable.installCscAsync()
        waitUntil { rpc.csCloudInstalls == 2 }
    }

    fun `test installCscAsync releases the lock when cancel lands before the work runs`() = runBlocking(Dispatchers.Default) {
        // Hold the work coroutine back so the cancel below lands before its first dispatch - the
        // body (and any finally inside it) is then skipped entirely.
        val paused = PausedDispatcher()
        val frozenScope = CoroutineScope(SupervisorJob() + paused)
        val cancelling = CsCloudTaskRunner { _, job -> job.cancel() }
        val cancellable = KiloAppService(frozenScope, rpc, cancelling)
        recordNotifications()

        cancellable.installCscAsync()

        assertEquals(0, rpc.csCloudInstalls) // the cancelled run must not reach the backend
        assertTrue("cancel must not be reported as a failure", notifications.none { it.type == NotificationType.ERROR })
        paused.resume()

        // The lock did not leak: a fresh install goes through and reaches the backend.
        cancellable.installCscAsync()
        waitUntil { rpc.csCloudInstalls == 1 }
        frozenScope.cancel()
    }

    fun `test startCsCloudAsync reports a busy start instead of ignoring a second click`() = runBlocking(Dispatchers.Default) {
        var job: Job? = null
        val runner = CsCloudTaskRunner { _, queued -> job = queued }
        val cancellable = KiloAppService(scope, rpc, runner)
        rpc.csCloudStartGate = CompletableDeferred()
        recordNotifications()
        cancellable.startCsCloudAsync()
        waitUntil { rpc.csCloudStarts == 1 }

        cancellable.startCsCloudAsync()

        assertEquals(1, rpc.csCloudStarts) // a second click must not start a second start
        val busy = waitUntilValue { notifications.firstOrNull { it.title == KiloBundle.message("csCloud.start.busy") } }
        assertEquals(NotificationType.INFORMATION, busy.type)

        // The first start must settle (job done = lock released) before the next click: the
        // outcome notification is posted inside the job, so it is not a lock-released signal.
        rpc.csCloudStartGate!!.complete(Unit)
        job!!.join()
        cancellable.startCsCloudAsync()
        waitUntil { rpc.csCloudStarts == 2 }
    }

    fun `test startCsCloudAsync releases the lock when the background task is cancelled`() = runBlocking(Dispatchers.Default) {
        var job: Job? = null
        val runner = CsCloudTaskRunner { _, queued -> job = queued }
        val cancellable = KiloAppService(scope, rpc, runner)
        rpc.csCloudStartGate = CompletableDeferred()
        recordNotifications()

        cancellable.startCsCloudAsync()
        waitUntil { rpc.csCloudStarts == 1 }
        job!!.cancelAndJoin()

        assertTrue("cancelling must not be reported as a failure", notifications.none { it.type == NotificationType.ERROR })
        cancellable.startCsCloudAsync()
        waitUntil { rpc.csCloudStarts == 2 }
    }

    fun `test fetchCoreInfoAsync dedupes in flight requests`() = runBlocking(Dispatchers.Default) {
        rpc.cliInfoGate = CompletableDeferred()
        val seen = mutableListOf<CoreInfo?>()

        app.fetchCoreInfoAsync { seen.add(it) }
        app.fetchCoreInfoAsync { seen.add(it) }
        waitUntil { rpc.cliVersionCalls == 1 }

        assertEquals(1, rpc.cliVersionCalls)
        assertEquals(0, rpc.cliPlatformCalls)
        rpc.cliInfoGate!!.complete(Unit)
        waitUntil { seen.size == 2 }

        assertEquals(1, rpc.cliVersionCalls)
        assertEquals(1, rpc.cliPlatformCalls)
        assertEquals(listOf(CoreInfo("1.0.0", "darwin-arm64"), CoreInfo("1.0.0", "darwin-arm64")), seen)
    }

    fun `test fetchCoreInfoAsync retries after failure`() = runBlocking(Dispatchers.Default) {
        val seen = mutableListOf<CoreInfo?>()
        rpc.cliInfoError = RuntimeException("core failed")

        app.fetchCoreInfoAsync { seen.add(it) }
        waitUntil { seen.size == 1 }

        assertNull(seen.single())
        assertNull(app.core)
        rpc.cliInfoError = null
        app.fetchCoreInfoAsync { seen.add(it) }
        waitUntil { seen.size == 2 }

        assertEquals(CoreInfo("1.0.0", "darwin-arm64"), seen.last())
        assertEquals(2, rpc.cliVersionCalls)
    }

    // ------ refreshProfile ------

    fun `test refreshProfile updates app state profile on success`() = runBlocking(Dispatchers.Default) {
        rpc.fakeProfile = profile()
        val result = app.refreshProfile()
        assertNotNull(result)
        assertEquals("alice@test.com", result!!.email)
        assertEquals("alice@test.com", app.state.value.profile?.email)
    }

    fun `test refreshProfile returns null and leaves existing state on exception`() = runBlocking(Dispatchers.Default) {
        val existing = profile(email = "existing@test.com")
        app._state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = existing)
        rpc.refreshError = RuntimeException("refresh failed")
        val result = app.refreshProfile()
        assertNull(result)
        assertEquals("existing@test.com", app.state.value.profile?.email)
    }

    // ------ completeLogin ------

    fun `test completeLogin updates app state profile on success`() = runBlocking(Dispatchers.Default) {
        rpc.fakeProfile = profile()
        val result = app.completeLogin("/my/dir")
        assertNotNull(result)
        assertEquals("alice@test.com", result!!.email)
        assertEquals("alice@test.com", app.state.value.profile?.email)
        assertEquals(listOf("/my/dir"), rpc.completeDirectories)
    }

    fun `test completeLogin returns null on exception without clearing previous profile`() = runBlocking(Dispatchers.Default) {
        val existing = profile(email = "existing@test.com")
        app._state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = existing)
        rpc.completeError = RuntimeException("complete failed")
        val result = app.completeLogin("/dir")
        assertNull(result)
        assertEquals("existing@test.com", app.state.value.profile?.email)
    }

    // ------ logout ------

    fun `test logout clears profile when rpc returns true`() = runBlocking(Dispatchers.Default) {
        val prof = profile()
        app._state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = prof)
        rpc.fakeProfile = prof
        rpc.logoutResult = true
        val ok = app.logout()
        assertTrue(ok)
        assertNull(app.state.value.profile)
    }

    fun `test logout does not clear profile when rpc returns false`() = runBlocking(Dispatchers.Default) {
        val prof = profile()
        app._state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = prof)
        rpc.logoutResult = false
        val ok = app.logout()
        assertFalse(ok)
        assertEquals("alice@test.com", app.state.value.profile?.email)
    }

    fun `test logout returns false on exception`() = runBlocking(Dispatchers.Default) {
        val prof = profile()
        app._state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = prof)
        rpc.logoutError = RuntimeException("logout failed")
        val ok = app.logout()
        assertFalse(ok)
        // Profile should be unchanged since logout threw
        assertEquals("alice@test.com", app.state.value.profile?.email)
    }

    // ------ setOrganization ------

    fun `test setOrganization updates profile on success for org id`() = runBlocking(Dispatchers.Default) {
        val orgs = listOf(ProfileOrganizationDto(id = "org_1", name = "Acme", role = "ADMIN"))
        val personal = profile(orgs = orgs)
        rpc.fakeProfile = personal
        val org = personal.copy(currentOrgId = "org_1")
        rpc.orgProfiles["org_1"] = org
        val result = app.setOrganization("org_1")
        assertNotNull(result)
        assertEquals("org_1", result!!.currentOrgId)
        assertEquals(listOf<String?>("org_1"), rpc.orgSelections)
        assertEquals("org_1", app.state.value.profile?.currentOrgId)
    }

    fun `test setOrganization updates profile for personal null selection`() = runBlocking(Dispatchers.Default) {
        val orgs = listOf(ProfileOrganizationDto(id = "org_1", name = "Acme", role = "ADMIN"))
        val org = profile(orgs = orgs, currentOrgId = "org_1")
        rpc.fakeProfile = org
        val personal = profile(orgs = orgs, currentOrgId = null)
        rpc.orgProfiles[null] = personal
        app._state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = org)
        val result = app.setOrganization(null)
        assertNotNull(result)
        assertNull(result!!.currentOrgId)
        assertEquals(listOf<String?>(null), rpc.orgSelections)
    }

    fun `test setOrganization returns null on exception without changing profile`() = runBlocking(Dispatchers.Default) {
        val existing = profile(email = "alice@test.com")
        app._state.value = KiloAppStateDto(KiloAppStatusDto.READY, profile = existing)
        rpc.organizationError = RuntimeException("org failed")
        val result = app.setOrganization("org_1")
        assertNull(result)
        assertEquals("alice@test.com", app.state.value.profile?.email)
    }

    // ------ startLogin / completeLogin directory forwarding ------

    fun `test startLogin forwards directory`() = runBlocking(Dispatchers.Default) {
        app.startLogin("/workspace")
        assertEquals(listOf("/workspace"), rpc.startDirectories)
    }

    fun `test completeLogin forwards directory`() = runBlocking(Dispatchers.Default) {
        rpc.fakeProfile = profile()
        app.completeLogin("/workspace")
        assertEquals(listOf("/workspace"), rpc.completeDirectories)
    }

    fun `test startLogin with null directory is forwarded`() = runBlocking(Dispatchers.Default) {
        app.startLogin(null)
        assertEquals(listOf<String?>(null), rpc.startDirectories)
    }
}

/**
 * Coroutine dispatcher that parks dispatched blocks until [resume] is called, so a test can
 * cancel a job before its coroutine is ever dispatched (the body and its `finally` never run).
 */
private class PausedDispatcher : CoroutineDispatcher() {
    private val gate = CountDownLatch(1)
    private val parked = ConcurrentLinkedQueue<Runnable>()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        parked.add(block)
        drainIfResumed()
    }

    /** Unparks the dispatcher and runs everything parked so far (on the calling thread). */
    fun resume() {
        gate.countDown()
        drainIfResumed()
    }

    private fun drainIfResumed() {
        if (gate.count != 0L) return
        while (true) parked.poll()?.run() ?: return
    }
}
