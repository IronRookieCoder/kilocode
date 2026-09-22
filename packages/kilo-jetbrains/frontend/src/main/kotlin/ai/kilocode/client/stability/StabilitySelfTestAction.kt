package ai.kilocode.client.stability

import ai.kilocode.stability.StabilityService
import ai.kilocode.stability.emitDictionarySweep
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * 采集链路自检动作（隐藏入口）：不注册进任何菜单/工具栏组，仅集成测试经
 * `invokeAction("Kilo.StabilitySelfTest")` 触发。执行时对第9章事件字典的全部登记
 * name（除服务级lifecycle单发与health真实快照，见[emitDictionarySweep]）各产出一条
 * 字典合法事实——经真实Recorder/Operations/Faults/Resources入口与准入、队列、封存、
 * outbox落盘全管线，验证"所有类型的指标与日志事实"在真实IDE内可采集落盘。
 *
 * 安全闸门：默认no-op，仅当`-Dcostrict.stability.selftest=true`时执行（E2E沙箱经
 * extraSystemProperties开启）——生产IDE即使经Find Action误触发也不产生任何业务事实，
 * 不污染指标分母。不走旧Telemetry capture链路（同一事实不得双链路重复计数）。
 * actionPerformed在EDT执行：全部采集入口非阻塞（record入内存队列），无文件IO/等待。
 */
class StabilitySelfTestAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        if (System.getProperty(SELFTEST_PROPERTY) != "true") return
        val stability = service<StabilityService>()
        emitDictionarySweep(
            recorder = stability.recorder,
            operations = stability.operations,
            faults = stability.faults,
            resources = stability.resources,
        )
    }

    private companion object {
        const val SELFTEST_PROPERTY = "costrict.stability.selftest"
    }
}
