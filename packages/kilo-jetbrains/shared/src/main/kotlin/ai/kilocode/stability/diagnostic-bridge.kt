package ai.kilocode.stability

import ai.kilocode.log.FileLog
import ai.kilocode.log.IntellijLog
import ai.kilocode.log.KiloLog
import java.util.concurrent.atomic.AtomicBoolean

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
    private class Entry(val sink: (DiagnosticInput) -> Unit)

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private val reentry = ThreadLocal.withInitial { false }
    private val log: KiloLog by lazy {
        KiloLog.logger(
            sandbox = KiloLog.sandbox(),
            intellij = { IntellijLog(DiagnosticBridge::class.java) },
            file = { FileLog(DiagnosticBridge::class.java) },
        )
    }

    fun install(sink: (DiagnosticInput) -> Unit): AutoCloseable {
        val entry = Entry(sink)
        synchronized(lock) { entries += entry }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(lock) { entries.remove(entry) }
        }
    }

    fun publish(input: DiagnosticInput) {
        if (reentry.get()) return
        val sink = synchronized(lock) { entries.lastOrNull()?.sink } ?: return
        reentry.set(true)
        try {
            sink(input)
        } catch (error: Exception) {
            log.warn("Diagnostic bridge sink failed", error)
        } finally {
            reentry.set(false)
        }
    }
}
