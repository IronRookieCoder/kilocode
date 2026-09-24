package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.CsCloudStartDto
import ai.kilocode.stability.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** M07 csc.start（brief Step 3）的阶段受控值（metrics 3.2：spawn/exit/health）。 */
internal const val START_STAGE_SPAWN = "spawn"
internal const val START_STAGE_EXIT = "exit"
internal const val START_STAGE_HEALTH = "health"

/** result六值（metrics 1.2）与cause受控词表（metrics 1.3）固定值。 */
private const val RESULT_FAILURE = "failure"
private const val RESULT_TIMEOUT = "timeout"
private const val RESULT_BLOCKED = "blocked"
private const val RESULT_CANCELLED = "cancelled"
private const val CAUSE_USER = "user"
private const val CAUSE_ENVIRONMENT = "environment"
private const val CAUSE_CS_CLOUD = "cs_cloud"
private const val CAUSE_UNKNOWN = "unknown"

/** 启动error_code受控值（metrics 3.2）：none/csc_not_installed/timeout/spawn_failed/
 *  health_failed/other。 */
private const val START_CODE_SPAWN_FAILED = "spawn_failed"
private const val START_CODE_TIMEOUT = "timeout"
private const val START_CODE_OTHER = "other"

/**
 * Starts the local cs-cloud daemon.
 *
 * [operation]是CsCloudConnectionService协调的同一个csc.start operation（brief Step 3）：
 * starter在spawn/exit阶段结算自己可观测的终态，exit 0后保持打开，由服务做健康确认后
 * 才结算success/health_failed。null=采集不可用，启动业务照常。
 */
interface CsCloudStarter {
    suspend fun start(operation: Operation? = null): CsCloudStartDto
}

/**
 * Runs `csc cloud start` so the plugin can bring the cs-cloud daemon up itself.
 *
 * The `csc` executable is resolved manually against the injected PATH plus common
 * npm/nvm/bun global bin locations, because the IDE's inherited PATH often misses
 * the directory the user installed csc into (e.g. nvm-managed node). The resolved
 * directory is prepended to the child PATH so the csc node wrapper can find `node`.
 */
class CscCloudStarter(
    private val env: Map<String, String>,
    private val log: KiloLog,
    private val timeoutSeconds: Long = 180L,
    private val extraDirs: List<String> = defaultDirs(),
) : CsCloudStarter {
    override suspend fun start(operation: Operation?): CsCloudStartDto = withContext(Dispatchers.IO) {
        // M07（brief Step 3）：spawn阶段开始；失败映射启动error_code固定值（spawn_failed/
        // timeout/csc_not_installed/other），绝不解析消息文本。
        operation?.progress(START_STAGE_SPAWN)
        val csc = findCsc()
        if (csc == null) {
            log.warn("csc cloud start skipped: csc not found; PATH=${env["PATH"]} extraDirs=$extraDirs")
            operation?.end(RESULT_BLOCKED, START_STAGE_SPAWN, CAUSE_ENVIRONMENT, ConnectionErrorCode.CSC_NOT_INSTALLED)
            return@withContext CsCloudStartDto(false, "csc is not installed or not on the IDE PATH - install it with `npm install -g @costrict/csc`, then try Start cs-cloud again", ConnectionErrorCode.CSC_NOT_INSTALLED)
        }
        val pb = ProcessBuilder(csc, "cloud", "start")
        pb.environment().clear()
        pb.environment().putAll(toolChildEnv(env, csc))
        pb.redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (error: IOException) {
            log.warn("csc cloud start could not run", error)
            operation?.end(RESULT_FAILURE, START_STAGE_SPAWN, CAUSE_ENVIRONMENT, START_CODE_SPAWN_FAILED)
            return@withContext CsCloudStartDto(false, "csc could not be started - reinstall it with `npm install -g @costrict/csc`, then try Start cs-cloud again", ConnectionErrorCode.CSC_NOT_INSTALLED)
        }
        try {
            if (!proc.awaitExitOrTimeout(timeoutSeconds)) {
                proc.destroyForcibly()
                operation?.end(RESULT_TIMEOUT, START_STAGE_EXIT, CAUSE_ENVIRONMENT, START_CODE_TIMEOUT)
                return@withContext CsCloudStartDto(false, "csc cloud start did not finish within ${timeoutSeconds}s")
            }
            operation?.progress(START_STAGE_EXIT)
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.exitValue() == 0) {
                // exit 0只是健康阶段的入场券：operation保持打开，success由
                // CsCloudConnectionService确认daemon可发现且健康后结算（绝不能只依据Dto.ok）。
                operation?.progress(START_STAGE_HEALTH)
                CsCloudStartDto(true, out.takeIf { it.isNotBlank() })
            } else {
                log.warn("csc cloud start failed: $out")
                operation?.end(RESULT_FAILURE, START_STAGE_EXIT, CAUSE_CS_CLOUD, START_CODE_OTHER)
                CsCloudStartDto(false, out.takeIf { it.isNotBlank() } ?: "csc cloud start failed (exit ${proc.exitValue()})")
            }
        } catch (error: CancellationException) {
            // The user cancelled the background task, which cancelled this coroutine through the
            // backend RPC: stop the daemon start so no stray csc process is left behind.
            operation?.end(RESULT_CANCELLED, START_STAGE_EXIT, CAUSE_USER)
            proc.terminate()
            log.info("csc cloud start cancelled - stopped the csc process")
            throw error
        } catch (error: Throwable) {
            log.warn("csc cloud start failed", error)
            operation?.end(RESULT_FAILURE, START_STAGE_EXIT, CAUSE_UNKNOWN, START_CODE_OTHER)
            CsCloudStartDto(false, error.message ?: "csc cloud start failed")
        }
    }

    private fun findCsc(): String? = findCsc(env, extraDirs)
}

/** Resolve the `csc` executable against [env] PATH plus [extraDirs], or null when missing. */
internal fun findCsc(env: Map<String, String>, extraDirs: List<String>): String? {
    val names = if (File.separatorChar == '\\') listOf("csc.cmd", "csc.exe", "csc") else listOf("csc")
    return toolDirs(env, extraDirs)
        .flatMap { dir -> names.map { name -> File(dir, name) } }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
}

/** Directories to scan for CLI tools beyond the injected PATH, in order. */
internal fun defaultDirs(): List<String> {
    val home = System.getProperty("user.home") ?: return emptyList()
    return listOf(
        "$home/.nvm/versions/node",
        "$home/.bun/bin",
        "$home/.local/bin",
        "$home/.local/share/pnpm",
        "$home/.npm-global/bin",
        "$home/npm/bin",
        "$home/.volta/bin",
        "$home/.costrict/bin",
        "/usr/local/bin",
        "/opt/homebrew/bin",
    )
}

/** Candidate directories for tool lookup: the injected PATH plus [defaultDirs]. */
internal fun toolDirs(env: Map<String, String>, extraDirs: List<String>): List<String> {
    val sep = File.pathSeparatorChar
    val path = buildList {
        addAll(env["PATH"].orEmpty().split(sep))
        addAll(extraDirs)
    }
    return path.flatMap(::expand).distinct().filter { it.isNotBlank() }
}

private fun expand(dir: String): List<String> {
    val file = File(dir)
    if (!file.isDirectory || file.name != "node") return listOf(dir)
    // ~/.nvm/versions/node contains per-version folders such as v22.18.0/bin
    val versions = file.listFiles { it.isDirectory } ?: return listOf(dir)
    return versions.map { File(it, "bin").absolutePath }
}

/** PATH for a tool child process: the tool's own dir first so a node wrapper can find `node`. */
internal fun toolChildEnv(env: Map<String, String>, tool: String): Map<String, String> {
    val parent = File(tool).parent
    val path = buildString {
        if (parent != null) {
            append(parent)
            append(File.pathSeparatorChar)
        }
        append(env["PATH"].orEmpty())
        append(File.pathSeparatorChar)
        append(System.getenv("PATH").orEmpty())
    }
    return env + ("PATH" to path)
}
