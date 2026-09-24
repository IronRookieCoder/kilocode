package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.CsCloudStartDto
import ai.kilocode.stability.Operation
import ai.kilocode.stability.Operations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** M08 credentials.ready（brief Step 4）的name与阶段值；deadline=登录进程的真实业务等待。 */
private const val CREDENTIALS_OPERATION = "credentials.ready"
private const val STAGE_PROBE = "probe"
private const val STAGE_WAIT = "wait"

/** result六值（metrics 1.2）与cause受控词表（metrics 1.3）固定值。 */
private const val RESULT_FAILURE = "failure"
private const val RESULT_TIMEOUT = "timeout"
private const val RESULT_BLOCKED = "blocked"
private const val RESULT_UNKNOWN = "unknown"
private const val CAUSE_USER = "user"
private const val CAUSE_ENVIRONMENT = "environment"
private const val CAUSE_CS_CLOUD = "cs_cloud"
private const val CAUSE_UNKNOWN = "unknown"

/** 凭据error_code受控值（metrics 3.2）：none/csc_not_installed/credentials_missing/
 *  credentials_expired/timeout/other。 */
private const val CODE_CSC_NOT_INSTALLED = "csc_not_installed"
private const val CODE_CREDENTIALS_MISSING = "credentials_missing"
private const val CODE_TIMEOUT = "timeout"
private const val CODE_OTHER = "other"

/** 业务deadline（秒）换算毫秒的固定倍率。 */
private const val MILLIS_PER_SECOND = 1000L

/**
 * Runs `csc auth login` so the plugin can start the CoStrict sign-in flow itself.
 *
 * `csc auth login` opens the system browser (which the plugin cannot do directly
 * for the CoStrict provider) and blocks until the OAuth flow completes and writes
 * `~/.costrict/share/auth.json`. The daemon re-reads that file per request, so the
 * next prompt picks up the fresh token without restarting cs-cloud.
 *
 * When the command is still running after [timeoutSeconds] the user is presumably
 * finishing the flow in the browser, so the process is deliberately left running
 * instead of being killed - destroying it would close the localhost callback
 * listener and drop the pending sign-in.
 */
class CscLogin(
    private val env: Map<String, String>,
    private val log: KiloLog,
    private val timeoutSeconds: Long = 300L,
    private val extraDirs: List<String> = defaultDirs(),
    /** 稳定性采集入口（B2同型注入）：null=采集不可用，登录业务照常。 */
    private val operations: Operations? = null,
) {
    suspend fun login(): CsCloudStartDto = withContext(Dispatchers.IO) {
        val csc = findCsc(env, extraDirs)
        // M08（brief Step 4）：在真实需要凭据的认证请求（csc auth login）前begin；
        // deadline=登录进程已有的timeoutSeconds业务等待换算毫秒。G0可信凭据状态
        // （当前业务代际校验）尚未在插件契约成立（契约标记false）——success分支依赖
        // 该外部信号，本期不接；首次登录期间不追补事实（设计观测盲区）。
        val operation = operations?.begin(CREDENTIALS_OPERATION, timeoutSeconds * MILLIS_PER_SECOND)
        if (csc == null) {
            log.warn("csc auth login skipped: csc not found; PATH=${env["PATH"]} extraDirs=$extraDirs")
            operation?.end(RESULT_BLOCKED, STAGE_PROBE, CAUSE_ENVIRONMENT, CODE_CSC_NOT_INSTALLED)
            return@withContext CsCloudStartDto(false, "csc is not installed or not on the IDE PATH - install it with `npm install -g @costrict/csc`, then try Sign in again", ConnectionErrorCode.CSC_NOT_INSTALLED)
        }
        val pb = ProcessBuilder(csc, "auth", "login")
        pb.environment().clear()
        pb.environment().putAll(toolChildEnv(env, csc))
        pb.redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (error: IOException) {
            log.warn("csc auth login could not run", error)
            operation?.end(RESULT_BLOCKED, STAGE_PROBE, CAUSE_ENVIRONMENT, CODE_CSC_NOT_INSTALLED)
            return@withContext CsCloudStartDto(false, "csc auth login could not be started: ${error.message}", ConnectionErrorCode.CSC_NOT_INSTALLED)
        }
        // Drain output on a background thread so a chatty command cannot fill the pipe buffer.
        val out = StringBuilder()
        val drain = Thread {
            proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
        }.apply { isDaemon = true; name = "csc-auth-login-output"; start() }
        try {
            if (proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // The process has exited, so the reader hits EOF; join it before reading the output.
                drain.join()
                val text = out.toString().trim()
                if (proc.exitValue() == 0) {
                    // ok=true只表示登录流程完成，不能映射为凭据就绪（metrics M08解读）。
                    // 无G0可信确认，按观测盲区记unknown，绝不伪造success。
                    operation?.end(RESULT_UNKNOWN, STAGE_WAIT, CAUSE_CS_CLOUD, CODE_OTHER)
                    CsCloudStartDto(true, text.takeIf { it.isNotBlank() })
                } else {
                    log.warn("csc auth login failed: $text")
                    // 明确缺失阻断：登录失败=凭据未就绪（metrics 1.2 blocked语义）。
                    operation?.end(RESULT_BLOCKED, STAGE_WAIT, CAUSE_USER, CODE_CREDENTIALS_MISSING)
                    CsCloudStartDto(false, text.takeIf { it.isNotBlank() } ?: "csc auth login failed (exit ${proc.exitValue()})")
                }
            } else {
                log.info("csc auth login still pending after ${timeoutSeconds}s; leaving it running so the browser sign-in can complete")
                // 登录进程仍运行到截止：记timeout且绝不终止进程（两个分支与上面的就绪/
                // 阻断互斥，不能顺序调用当作正常实现；唯一终态由Operation的CAS保证）。
                operation?.end(RESULT_TIMEOUT, STAGE_WAIT, CAUSE_UNKNOWN, CODE_TIMEOUT)
                watchLateReady(proc, operation)
                CsCloudStartDto(true, "Sign-in opened in your browser - complete it there to connect cs-cloud.")
            }
        } catch (error: Throwable) {
            operation?.end(RESULT_FAILURE, STAGE_WAIT, CAUSE_UNKNOWN, CODE_OTHER)
            proc.destroyForcibly()
            log.warn("csc auth login failed", error)
            CsCloudStartDto(false, error.message ?: "csc auth login failed")
        }
    }

    /**
     * 迟到ready只发安全诊断（brief Step 4）：deadline已结算timeout后进程才退出，这里
     * 只写插件日志，绝不再end（CAS也会丢弃第二条终态）。遥测侧无该形态的登记name。
     */
    private fun watchLateReady(proc: Process, operation: Operation?) {
        if (operation == null) return
        Thread {
            runCatching {
                proc.waitFor()
                if (operation.isSettled && proc.exitValue() == 0) {
                    log.info("csc auth login finished after the deadline; late ready is diagnostic-only")
                }
            }
        }.apply { isDaemon = true; name = "csc-auth-login-late-ready"; start() }
    }
}
