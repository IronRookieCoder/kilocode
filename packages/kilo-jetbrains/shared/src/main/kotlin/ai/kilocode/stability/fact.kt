package ai.kilocode.stability

import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 不可变事实草稿（设计6.1/6.2）：业务侧只提供采集时已知的name/kind/channel/data等字段，
 * 公共身份字段（event_id、seq、account_epoch、policy_revision等）由recorder在入队时补齐为[Fact]。
 *
 * 安全约定：Draft不接受JWT、路径或业务payload；[Dictionary.validate]对未知键和错误类型
 * 直接拒绝，而不是裁剪后放行。diagnostic的message模板由Faults生成，不收任意异常文本。
 *
 * 入参集合在构造时复制为不可变快照，调用方事后修改传入集合不影响已创建的草稿。
 * 特意不用data class：copy()会绕过快照复制，重新暴露调用方的可变引用。
 */
// 参数列表镜像冻结的字段集；快照复制在companion invoke中统一执行。
@Suppress("LongParameterList")
class Draft private constructor(
    val name: String,
    val kind: String,
    val channel: String,
    val data: JsonObject,
    val context: Map<String, String>,
    val epoch: String?,
    val purposes: Set<String>,
    val schemaVersion: String,
) {
    companion object {
        private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(source))

        /**
         * 工厂保持与字段同名的位置参数调用形态；context、purposes、data一律复制为不可变快照。
         * context的键是闭集（operation_id/attempt_id/fault_id/trace_id/workspace_id），
         * 这里不做内容校验，统一由[Dictionary.validate]判定。
         */
        @Suppress("LongParameterList")
        operator fun invoke(
            name: String,
            kind: String,
            channel: String,
            data: JsonObject,
            context: Map<String, String> = emptyMap(),
            epoch: String? = null,
            purposes: Set<String> = setOf("metrics", "logs"),
            schemaVersion: String = "1.0",
        ): Draft = Draft(
            name = name,
            kind = kind,
            channel = channel,
            data = JsonObject(immutableMap(data)),
            context = immutableMap(context),
            epoch = epoch,
            purposes = Collections.unmodifiableSet(LinkedHashSet(purposes)),
            schemaVersion = schemaVersion,
        )
    }
}

/**
 * 本地事实格式v1的完整wire记录（设计6.1，fact-schema.json的Kotlin孪生）。
 * 字段名刻意使用snake_case：它们是冻结的wire键名，与fact-schema.json逐字一致，
 * 因此抑制命名规则；26个参数来自schema必填字段集，不做拆分或抽象。
 * 32KiB上限由writer以真实UTF-8编码落盘前再检查；这里的模型只承载字段。
 */
@Suppress("ConstructorParameterNaming", "LongParameterList")
@Serializable
data class Fact(
    val schema_version: String = "1.0",
    val event_id: String,
    val timestamp: Long,
    val producer_id: String,
    val run_id: String,
    val channel: String,
    val seq: Long,
    val account_epoch: String,
    val policy_revision: Long,
    val purposes: Set<String>,
    val source: String = "jetbrains-plugin",
    val device_id: String,
    val plugin_version: String,
    val ide_product: String,
    val ide_build: String,
    val ide_build_major: String,
    val os_family: String,
    val arch: String,
    val env: String,
    val mode: String,
    val side: String,
    val connection_provider: String,
    val kind: String,
    val name: String,
    val context: Map<String, String> = emptyMap(),
    val data: JsonObject,
)
