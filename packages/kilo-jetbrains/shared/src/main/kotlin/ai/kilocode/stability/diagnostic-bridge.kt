package ai.kilocode.stability

import ai.kilocode.log.FileLog
import ai.kilocode.log.IntellijLog
import ai.kilocode.log.KiloLog
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val MAX_PENDING = 256

enum class DiagnosticSeverity {
    WARN,
    ERROR,
}

/** 原始诊断输入；payload supplier 仅由有 logs 许可的后续持久化消费者求值。 */
class DiagnosticInput(
    val severity: DiagnosticSeverity,
    val component: String,
    val message: String,
    val error: Throwable? = null,
    context: Map<String, String> = emptyMap(),
    attributes: Map<String, String> = emptyMap(),
    payloads: Map<String, () -> String> = emptyMap(),
) {
    val context = context.toMap()
    val attributes = attributes.toMap()
    val payloads = payloads.toMap()

    companion object {
        fun error(
            component: String,
            code: String? = null,
            error: Throwable? = null,
            message: String = error?.message ?: "error",
            context: Map<String, String> = emptyMap(),
            attributes: Map<String, String> = emptyMap(),
            payloads: Map<String, () -> String> = emptyMap(),
        ): DiagnosticInput = DiagnosticInput(
            severity = DiagnosticSeverity.ERROR,
            component = component,
            message = message,
            error = error,
            context = context,
            attributes = code?.let { attributes + ("code" to it) } ?: attributes,
            payloads = payloads,
        )
    }
}

/** 进程内日志镜像；安装按栈恢复，关闭句柄可并发且幂等。 */
object DiagnosticBridge {
    private class Entry(val sink: (DiagnosticInput) -> Unit) {
        val active = AtomicBoolean(true)
        private val queue = ConcurrentLinkedQueue<DiagnosticInput>()
        private val pending = AtomicInteger()
        private val running = AtomicInteger()
        private val scheduled = AtomicBoolean()
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val monitor = java.lang.Object()

        fun offer(input: DiagnosticInput) {
            if (!active.get() || !reserve()) return
            if (!active.get()) {
                pending.decrementAndGet()
                signal()
                return
            }
            queue.add(input)
            schedule()
        }

        fun await() {
            synchronized(monitor) {
                while (pending.get() > 0 || running.get() > 0) monitor.wait()
            }
        }

        private fun reserve(): Boolean {
            while (true) {
                val size = pending.get()
                if (size >= MAX_PENDING) return false
                if (pending.compareAndSet(size, size + 1)) return true
            }
        }

        private fun schedule() {
            if (!scheduled.compareAndSet(false, true)) return
            executor.execute {
                try {
                    drain()
                } finally {
                    scheduled.set(false)
                    signal()
                    if (pending.get() > 0) schedule()
                }
            }
        }

        private fun drain() {
            while (true) {
                val input = queue.poll() ?: return
                begin()
                try {
                    deliver(input)
                } catch (error: Exception) {
                    log.warn("Diagnostic bridge sink failed", error)
                } finally {
                    finish()
                }
            }
        }

        private fun begin() = synchronized(monitor) {
            pending.decrementAndGet()
            running.incrementAndGet()
        }

        private fun finish() = synchronized(monitor) {
            running.decrementAndGet()
            monitor.notifyAll()
        }

        private fun signal() = synchronized(monitor) { monitor.notifyAll() }
    }

    private class Installation(val entry: Entry) : AutoCloseable {
        override fun close() {
            if (!entry.active.compareAndSet(true, false)) return
            deactivate(entry)
            entry.await()
        }
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val current = AtomicReference<Entry?>()
    private val reentry = ThreadLocal.withInitial { false }
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kilo-diagnostic-bridge").apply { isDaemon = true }
    }
    private val log: KiloLog by lazy {
        KiloLog.logger(
            sandbox = KiloLog.sandbox(),
            intellij = { IntellijLog(DiagnosticBridge::class.java) },
            file = { FileLog(DiagnosticBridge::class.java) },
        )
    }

    fun install(sink: (DiagnosticInput) -> Unit): AutoCloseable {
        val entry = Entry(sink)
        synchronized(lock) {
            entries += entry
            current.set(entry)
        }
        return Installation(entry)
    }

    fun publish(input: DiagnosticInput) {
        if (reentry.get()) return
        current.get()?.offer(input)
    }

    internal fun await(bridge: AutoCloseable) {
        (bridge as? Installation)?.entry?.await()
    }

    internal fun await() {
        current.get()?.await()
    }

    private fun deactivate(entry: Entry) {
        synchronized(lock) {
            entries.remove(entry)
            if (current.get() === entry) current.set(entries.lastOrNull { it.active.get() })
        }
    }

    private fun Entry.deliver(input: DiagnosticInput) {
        reentry.set(true)
        try {
            sink.invoke(input)
        } finally {
            reentry.set(false)
        }
    }
}
