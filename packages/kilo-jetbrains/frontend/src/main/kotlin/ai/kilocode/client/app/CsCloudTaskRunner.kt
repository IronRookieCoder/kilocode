@file:Suppress("UnstableApiUsage")

package ai.kilocode.client.app

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** How often the task thread re-checks whether the user pressed Cancel. */
private const val CANCEL_POLL_MILLIS = 100L

/**
 * Queues an already-running [Job] as a cancellable background progress task.
 *
 * The caller creates the job (and owns its completion), so the runner only owns the UI and the
 * Cancel button. Injectable so tests can run without the platform's progress machinery.
 */
internal fun interface CsCloudTaskRunner {
    /** Shows [job] under a progress task titled [title] and returns immediately. */
    fun queue(title: String, job: Job)
}

/** Production runner: the job's work shows up as a cancellable [Task.Backgroundable] in the IDE. */
internal class BackgroundableCsCloudTask : CsCloudTaskRunner {
    override fun queue(title: String, job: Job) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, title, true) {
            override fun run(indicator: ProgressIndicator) {
                cancelWhenRequested(job, { indicator.isCanceled })
            }
        })
    }
}

/**
 * Cancels [job] once [isCancelled] turns true, blocking the caller until the job has settled.
 *
 * Blocking until settle means the task never reports finished before the job's completion
 * handlers (the install/start lock release) have run. Polling the indicator is enough: the
 * platform offers no callback for "Cancel was pressed" on the task thread.
 */
internal fun cancelWhenRequested(job: Job, isCancelled: () -> Boolean) {
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
