package ai.kilocode.cscloud

import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.CsCloudStartDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Starts the local cs-cloud daemon. */
interface CsCloudStarter {
    suspend fun start(): CsCloudStartDto
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
    override suspend fun start(): CsCloudStartDto = withContext(Dispatchers.IO) {
        val csc = findCsc()
        if (csc == null) {
            log.warn("csc cloud start skipped: csc not found; PATH=${env["PATH"]} extraDirs=$extraDirs")
            return@withContext CsCloudStartDto(false, "csc is not installed or not on the IDE PATH - install it with `npm install -g @costrict/csc` and restart the IDE")
        }
        val pb = ProcessBuilder(csc, "cloud", "start")
        pb.environment().clear()
        pb.environment().putAll(childEnv(csc))
        pb.redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (error: IOException) {
            log.warn("csc cloud start could not run", error)
            return@withContext CsCloudStartDto(false, "csc could not be started - install it with `npm install -g @costrict/csc` and restart the IDE")
        }
        try {
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@withContext CsCloudStartDto(false, "csc cloud start did not finish within ${timeoutSeconds}s")
            }
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (proc.exitValue() == 0) {
                CsCloudStartDto(true, out.takeIf { it.isNotBlank() })
            } else {
                log.warn("csc cloud start failed: $out")
                CsCloudStartDto(false, out.takeIf { it.isNotBlank() } ?: "csc cloud start failed (exit ${proc.exitValue()})")
            }
        } catch (error: Throwable) {
            log.warn("csc cloud start failed", error)
            CsCloudStartDto(false, error.message ?: "csc cloud start failed")
        }
    }

    private fun findCsc(): String? {
        val names = if (File.separatorChar == '\\') listOf("csc.cmd", "csc.exe", "csc") else listOf("csc")
        return dirs()
            .flatMap { dir -> names.map { name -> File(dir, name) } }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun childEnv(csc: String): Map<String, String> {
        val parent = File(csc).parent
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

    private fun dirs(): List<String> {
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

    private companion object {
        fun defaultDirs(): List<String> {
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
    }
}
