package ai.kilocode.client.app

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cancellation bridge between the background task (Cancel button) and the install/start
 * coroutine: the flag flip must cancel the work, and the bridge must not return before the
 * work unwound (its `finally` releases the install/start locks).
 */
class CsCloudTaskRunnerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `runCancellable runs the work to completion when nothing is cancelled`() = runBlocking {
        val done = CompletableDeferred<Unit>()

        val task = async(Dispatchers.IO) {
            runCancellable(scope, { false }) { done.complete(Unit) }
        }

        task.await()
        assertTrue(done.isCompleted)
    }

    @Test
    fun `runCancellable cancels the work once the cancel flag flips`() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val unwound = CompletableDeferred<Unit>()

        val task = async(Dispatchers.IO) {
            runCancellable(scope, { cancelled.get() }) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    unwound.complete(Unit)
                }
            }
        }

        started.await()
        // The work is still running, so the bridge must still be blocked.
        assertFalse(task.isCompleted)
        cancelled.set(true)
        unwound.await()
        task.await()
    }

    @Test
    fun `runCancellable returns only after the work fully unwound`() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())

        val task = async(Dispatchers.IO) {
            runCancellable(scope, { cancelled.get() }) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    // A cancelled coroutine cannot suspend, so record instead of delay:
                    // the lock release in the real flow is non-suspending too.
                    order.add("unwound")
                }
            }
            order.add("returned")
        }

        started.await()
        cancelled.set(true)
        task.await()
        assertEquals(listOf("unwound", "returned"), order, "the bridge must wait for the work's finally block")
    }
}
