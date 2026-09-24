@file:Suppress("MatchingDeclarationName") // 保留既有fault.kt兼容入口。

package ai.kilocode.stability

import java.util.UUID

/** 旧调用点的兼容入口；与日志bridge共享同一Diagnostics的去重和限频状态。 */
class Faults(private val diagnostics: Diagnostics) {
    constructor(recorder: Recorder, clock: Clock, maxRateKeys: Int = 1024) :
        this(Diagnostics(recorder, clock, maxRateKeys))

    fun report(error: Throwable, component: String, handled: Boolean, fault: String = UUID.randomUUID().toString()) {
        diagnostics.report(DiagnosticInput(
            severity = DiagnosticSeverity.ERROR,
            component = component,
            message = if (error is VirtualMachineError || error is ThreadDeath) "fatal" else error.message ?: "error",
            error = error,
            context = mapOf("fault_id" to fault),
            handled = handled,
        ))
    }
}
