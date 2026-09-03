@file:Suppress("UnstableApiUsage")

package ai.kilocode.client.app

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** How often the task thread re-checks whether the user pressed Cancel. */
private const val CANCEL_POLL_MILLIS = 100L

/**
 * Queues the cs-cloud install/start work as a cancellable background progress task.
 *
 * Injectable so tests can run the work directly instead of through the platform's
 * progress machinery (see [BackgroundableCsCloudTask] for the production runner).
 */
internal fun interface CsCloudTaskRunner {
    /** Queues [work] under a progress task titled [title] and returns immediately. */
    fun queue(title: String, work: suspend () -> Unit)
}

/** Production runner: the work shows up as a cancellable [Task.Backgroundable] in the IDE. */
internal class BackgroundableCsCloudTask(private val cs: CoroutineScope) : CsCloudTaskRunner {
    override fun queue(title: String, work: suspend () -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, title, true) {
            override fun run(indicator: ProgressIndicator) {
                runCancellable(cs, { indicator.isCanceled }, work)
            }
        })
    }
}

/**
 * Runs [work] in [cs] and cancels it once [isCancelled] turns true, blocking the caller until
 * the work has fully unwound.
 *
 * Blocking until unwind matters: the work's `finally` releases the `csCloudInstalling`/
 * `csCloudStarting` locks, so the progress task must not be reported finished before that.
 *
 * The work keeps the app scope's dispatcher — the bridge only owns cancellation, it does not
 * move the work onto the (pooled) calling thread.
 */
internal fun runCancellable(cs: CoroutineScope, isCancelled: () -> Boolean, work: suspend () -> Unit) {
    val job = cs.launch { work() }
    runBlocking {
        val watch = launch {
            while (job.isActive) {
                if (isCancelled()) {
                    job.cancel()
                    return@launch
                }
                delay(CANCEL_POLL_MILLIS)
            }
        }
        watch.join()
        job.join()
    }
}
