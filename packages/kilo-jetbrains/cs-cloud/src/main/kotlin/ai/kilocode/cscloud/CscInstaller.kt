package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.CsCloudStartDto
import ai.kilocode.stability.Operation
import ai.kilocode.stability.Operations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** M06 csc.install（brief Step 2）的name与阶段值；deadline=安装命令的真实业务等待。 */
private const val INSTALL_OPERATION = "csc.install"
private const val STAGE_DISCOVER = "discover"
private const val STAGE_SPAWN = "spawn"
private const val STAGE_EXIT = "exit"

/** result六值（metrics 1.2）与cause受控词表（metrics 1.3）固定值。 */
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILURE = "failure"
private const val RESULT_TIMEOUT = "timeout"
private const val RESULT_BLOCKED = "blocked"
private const val RESULT_CANCELLED = "cancelled"
private const val CAUSE_USER = "user"
private const val CAUSE_ENVIRONMENT = "environment"
private const val CAUSE_UNKNOWN = "unknown"

/** 安装error_code受控值（metrics 3.2）：none/npm_not_found/network/disk/permission/other。
 *  插件不采stdout/stderr，无法细分network/disk/permission——命令失败统一归other，
 *  不因文档枚举而宣称可观测（metrics M06解读）。 */
private const val INSTALL_CODE_OTHER = "other"

/** 业务deadline（秒）换算毫秒的固定倍率。 */
private const val MILLIS_PER_SECOND = 1000L

/**
 * Installs the `csc` CLI with the package manager found on the IDE PATH
 * (`npm install -g @costrict/csc`, or the pnpm/bun/yarn equivalent) so first-time
 * users do not have to install it manually.
 */
class CscInstaller(
    private val env: Map<String, String>,
    private val log: KiloLog,
    private val timeoutSeconds: Long = 300L,
    private val extraDirs: List<String> = defaultDirs(),
    /** 稳定性采集入口（B2同型注入）：null=采集不可用，安装业务照常。 */
    private val operations: Operations? = null,
) {
    suspend fun install(): CsCloudStartDto = withContext(Dispatchers.IO) {
        val tool = findManager()
        // M06（brief Step 2）：installer入口begin，携带实际package_manager（只登记实际
        // 支持的发现值）；deadline从安装命令已有的timeoutSeconds换算毫秒。
        val operation = operations?.begin(
            INSTALL_OPERATION,
            timeoutSeconds * MILLIS_PER_SECOND,
            fields = tool
                ?.let { manager -> buildJsonObject { put("package_manager", manager.name) } }
                ?: JsonObject(emptyMap()),
        )
        if (tool == null) {
            log.warn("csc install skipped: no package manager found; PATH=${env["PATH"]} extraDirs=$extraDirs")
            operation?.end(RESULT_BLOCKED, STAGE_DISCOVER, CAUSE_ENVIRONMENT, ConnectionErrorCode.NPM_NOT_FOUND)
            return@withContext CsCloudStartDto(
                false,
                "no package manager (npm/pnpm/bun/yarn) was found - install Node.js first, or open the csc npm page from the notification",
                ConnectionErrorCode.NPM_NOT_FOUND,
            )
        }
        val args = installArgs(tool)
        log.info("csc install via ${args.joinToString(" ")}")
        val pb = ProcessBuilder(args)
        pb.environment().clear()
        pb.environment().putAll(toolChildEnv(env, tool.path))
        pb.redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (error: IOException) {
            log.warn("csc install could not run", error)
            operation?.end(RESULT_FAILURE, STAGE_SPAWN, CAUSE_ENVIRONMENT, ConnectionErrorCode.NPM_NOT_FOUND)
            return@withContext CsCloudStartDto(false, "could not run ${tool.name}: ${error.message}", ConnectionErrorCode.NPM_NOT_FOUND)
        }
        try {
            if (!proc.awaitExitOrTimeout(timeoutSeconds)) {
                proc.destroyForcibly()
                operation?.end(RESULT_TIMEOUT, STAGE_EXIT, CAUSE_ENVIRONMENT, INSTALL_CODE_OTHER)
                return@withContext CsCloudStartDto(false, "csc install did not finish within ${timeoutSeconds}s")
            }
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.exitValue() == 0) {
                settleInstallSuccess(operation)
                // 业务结果不变：installCsc随后的启动失败绝不改记为安装失败。
                CsCloudStartDto(true, "csc installed via ${tool.name}")
            } else {
                log.warn("csc install failed: $out")
                operation?.end(RESULT_FAILURE, STAGE_EXIT, CAUSE_ENVIRONMENT, INSTALL_CODE_OTHER)
                CsCloudStartDto(false, out.takeIf { it.isNotBlank() } ?: "csc install failed (exit ${proc.exitValue()})")
            }
        } catch (error: CancellationException) {
            // The user cancelled the background task, which cancelled this coroutine through the
            // backend RPC: stop the package manager so no half-finished npm run is left behind.
            operation?.end(RESULT_CANCELLED, STAGE_EXIT, CAUSE_USER)
            proc.terminate()
            log.info("csc install cancelled - stopped the ${tool.name} process")
            throw error
        } catch (error: Throwable) {
            log.warn("csc install failed", error)
            operation?.end(RESULT_FAILURE, STAGE_EXIT, CAUSE_UNKNOWN, INSTALL_CODE_OTHER)
            CsCloudStartDto(false, error.message ?: "csc install failed")
        }
    }

    /**
     * M06（brief Step 2）：命令成功≠安装成功——工具按与启动/登录相同的发现路径
     * （findCsc）可确认时才end success；end放在确认可发现的分支，命令完成不是
     * 无条件执行该行。
     */
    private fun settleInstallSuccess(operation: Operation?) {
        val discoverable = findCsc(env, extraDirs) != null
        if (discoverable) {
            operation?.end(RESULT_SUCCESS, STAGE_DISCOVER)
        } else {
            log.warn("csc install exited 0 but csc is not discoverable afterwards")
            operation?.end(RESULT_FAILURE, STAGE_DISCOVER, CAUSE_ENVIRONMENT, INSTALL_CODE_OTHER)
        }
    }

    private fun findManager(): Tool? {
        val suffixes = if (File.separatorChar == '\\') listOf(".cmd", ".exe", "") else listOf("")
        for (name in listOf("npm", "pnpm", "bun", "yarn")) {
            for (suffix in suffixes) {
                val file = toolDirs(env, extraDirs)
                    .map { dir -> File(dir, name + suffix) }
                    .firstOrNull { it.canExecute() }
                if (file != null) return Tool(name, file.absolutePath)
            }
        }
        return null
    }

    private fun installArgs(tool: Tool) = when (tool.name) {
        "npm" -> listOf(tool.path, "install", "-g", "--no-fund", "--no-audit", CSC_PACKAGE)
        "bun" -> listOf(tool.path, "add", "-g", CSC_PACKAGE)
        "pnpm" -> listOf(tool.path, "add", "-g", CSC_PACKAGE)
        else -> listOf(tool.path, "global", "add", CSC_PACKAGE)
    }

    private data class Tool(val name: String, val path: String)

    private companion object {
        const val CSC_PACKAGE = "@costrict/csc"
    }
}
