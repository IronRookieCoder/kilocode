package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.CsCloudStartDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
) {
    suspend fun install(): CsCloudStartDto = withContext(Dispatchers.IO) {
        val tool = findManager()
        if (tool == null) {
            log.warn("csc install skipped: no package manager found; PATH=${env["PATH"]} extraDirs=$extraDirs")
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
            return@withContext CsCloudStartDto(false, "could not run ${tool.name}: ${error.message}", ConnectionErrorCode.NPM_NOT_FOUND)
        }
        try {
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@withContext CsCloudStartDto(false, "csc install did not finish within ${timeoutSeconds}s")
            }
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.exitValue() == 0) {
                CsCloudStartDto(true, "csc installed via ${tool.name}")
            } else {
                log.warn("csc install failed: $out")
                CsCloudStartDto(false, out.takeIf { it.isNotBlank() } ?: "csc install failed (exit ${proc.exitValue()})")
            }
        } catch (error: Throwable) {
            log.warn("csc install failed", error)
            CsCloudStartDto(false, error.message ?: "csc install failed")
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
