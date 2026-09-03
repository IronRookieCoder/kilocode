package ai.kilocode.cscloud

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/** How often the waiting coroutine re-checks for cancellation while a child process runs. */
private const val PROCESS_POLL_MILLIS = 200L

/** Grace period a child gets to react to a graceful terminate before it is force-killed. */
internal const val TERMINATE_GRACE_MILLIS = 3_000L

/**
 * Waits for the process to exit, cooperative with coroutine cancellation.
 *
 * The wait is polled instead of one blocking [Process.waitFor] so a cancelled caller is
 * noticed within [PROCESS_POLL_MILLIS] rather than only when a multi-minute `npm`/`csc`
 * run happens to finish.
 *
 * Returns true once the process has exited, false when [timeoutSeconds] elapsed first.
 * Throws [kotlinx.coroutines.CancellationException] when the calling coroutine is cancelled;
 * callers must stop the process on that path (see [Process.terminate]) so no npm/csc child
 * is left behind.
 */
internal suspend fun Process.awaitExitOrTimeout(timeoutSeconds: Long): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    while (true) {
        if (waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS)) return true
        currentCoroutineContext().ensureActive()
        if (System.nanoTime() >= deadline) return false
    }
}

/**
 * Stops the process: a graceful terminate first (lets npm/csc flush and clean up), then a
 * force kill when the child is still alive after [graceMillis].
 */
internal fun Process.terminate(graceMillis: Long = TERMINATE_GRACE_MILLIS) {
    destroy()
    if (!waitFor(graceMillis, TimeUnit.MILLISECONDS)) destroyForcibly()
}
