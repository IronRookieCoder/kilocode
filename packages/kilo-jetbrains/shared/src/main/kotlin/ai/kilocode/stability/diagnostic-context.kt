package ai.kilocode.stability

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/** 协程内的诊断关联信息；仅在该协程的线程切换期间可见。 */
class DiagnosticContextElement(
    context: Map<String, String> = emptyMap(),
    attributes: Map<String, String> = emptyMap(),
    payloads: Map<String, () -> String> = emptyMap(),
) : ThreadContextElement<DiagnosticContextElement?>, AbstractCoroutineContextElement(Key) {
    val context = context.toMap()
    val attributes = attributes.toMap()
    val payloads = payloads.toMap()

    override fun updateThreadContext(context: CoroutineContext): DiagnosticContextElement? {
        val previous = current.get()
        current.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: DiagnosticContextElement?) {
        if (oldState == null) {
            current.remove()
            return
        }
        current.set(oldState)
    }

    companion object Key : CoroutineContext.Key<DiagnosticContextElement> {
        private val current = ThreadLocal<DiagnosticContextElement?>()

        internal fun value(): DiagnosticContextElement? = current.get()
    }
}
