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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cancellation bridge between the background task (Cancel button) and the install/start job:
 * the flag flip must cancel the job, and the bridge must not return before the job settled
 * (its completion handlers release the install/start locks).
 */
class CsCloudTaskRunnerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `cancelWhenRequested runs the job to completion when nothing is cancelled`() = runBlocking {
        val done = CompletableDeferred<Unit>()
        val job = scope.launch { done.complete(Unit) }

        val task = async(Dispatchers.IO) { cancelWhenRequested(job, { false }) }

        task.await()
        assertTrue(done.isCompleted)
    }

    @Test
    fun `cancelWhenRequested cancels the job once the cancel flag flips`() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val unwound = CompletableDeferred<Unit>()
        val job = scope.launch {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                unwound.complete(Unit)
            }
        }

        val task = async(Dispatchers.IO) { cancelWhenRequested(job, { cancelled.get() }) }

        started.await()
        // The job is still running, so the bridge must still be blocked.
        assertFalse(task.isCompleted)
        cancelled.set(true)
        unwound.await()
        task.await()
    }

    @Test
    fun `cancelWhenRequested returns only after the job fully settled`() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val order = Collections.synchronizedList(mutableListOf<String>())

        val task = async(Dispatchers.IO) {
            val job = scope.launch {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    // A cancelled coroutine cannot suspend, so record instead of delay - the
                    // completion handlers in the real flow are non-suspending too.
                    order.add("unwound")
                }
            }
            job.invokeOnCompletion { order.add("completed") }
            cancelWhenRequested(job, { cancelled.get() })
            order.add("returned")
        }

        started.await()
        cancelled.set(true)
        task.await()
        assertEquals(listOf("unwound", "completed", "returned"), order, "the bridge must wait for the job to settle")
    }
}
