package ai.kilocode.stability

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val RPC_NAME = "rpc"
private const val RPC_STAGE = "rpc"
private const val RESULT_SUCCESS = "success"
private const val RESULT_CANCELLED = "cancelled"
private const val RESULT_FAILURE = "failure"
private const val CAUSE_USER = "user"
private const val CAUSE_UNKNOWN = "unknown"
private const val CODE_OTHER = "other"

/** rpc观测默认30秒deadline（brief verbatim值）；实际业务deadline存在时经参数传原值。 */
const val RPC_DEFAULT_DEADLINE_MS = 30_000L

/**
 * M19（C2）单次前端RPC尝试（brief verbatim wrapper）：begin("rpc")携带api_group，成功
 * 结算success/rpc；取消不计故障（cancelled/rpc/user）且原样重抛；失败结算
 * failure/rpc/unknown/other后原样重抛。成功RPC仅metrics出口（Dictionary收窄），绝不逐条
 * 成功写logs；失败按日志选择规则经既有出口产生。重试是独立attempt：每次实际调用（包括
 * durable{}重连重试重新执行的最内层lambda）各自开一个operation，用户operation仍由B层
 * 边界负责，两者互不替代。实际业务deadline存在时经[deadline]传原值（如cs-cloud启动/
 * 安装/登录的既有业务等待）；没有真实deadline的调用用默认观测窗口——超时只结算观测，
 * 不取消业务本身（设计6.2）。长寿命Flow订阅（stream/events/state）不包进本wrapper，
 * 绝不把整个订阅时长当30秒RPC。
 */
// catch(Exception)是brief verbatim wrapper的裁决形态：任何业务异常都先结算failure再原样
// 重抛，绝不允许业务异常绕过观测终态；CancellationException已在更早分支单独处理。
@Suppress("TooGenericExceptionCaught")
suspend fun <T> Operations.rpc(
    group: String,
    deadline: Long = RPC_DEFAULT_DEADLINE_MS,
    block: suspend () -> T,
): T {
    val operation = begin(RPC_NAME, deadline, buildJsonObject { put("api_group", group) })
    try {
        val result = block()
        operation.end(RESULT_SUCCESS, RPC_STAGE)
        return result
    } catch (error: CancellationException) {
        operation.end(RESULT_CANCELLED, RPC_STAGE, CAUSE_USER)
        throw error
    } catch (error: Exception) {
        operation.end(RESULT_FAILURE, RPC_STAGE, CAUSE_UNKNOWN, CODE_OTHER)
        throw error
    }
}
