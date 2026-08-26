package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.CsCloudStartDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

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
) {
    suspend fun login(): CsCloudStartDto = withContext(Dispatchers.IO) {
        val csc = findCsc(env, extraDirs)
        if (csc == null) {
            log.warn("csc auth login skipped: csc not found; PATH=${env["PATH"]} extraDirs=$extraDirs")
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
                    CsCloudStartDto(true, text.takeIf { it.isNotBlank() })
                } else {
                    log.warn("csc auth login failed: $text")
                    CsCloudStartDto(false, text.takeIf { it.isNotBlank() } ?: "csc auth login failed (exit ${proc.exitValue()})")
                }
            } else {
                log.info("csc auth login still pending after ${timeoutSeconds}s; leaving it running so the browser sign-in can complete")
                CsCloudStartDto(true, "Sign-in opened in your browser - complete it there to connect cs-cloud.")
            }
        } catch (error: Throwable) {
            proc.destroyForcibly()
            log.warn("csc auth login failed", error)
            CsCloudStartDto(false, error.message ?: "csc auth login failed")
        }
    }
}
