package ai.kilocode.backend.migration

import ai.kilocode.log.KiloLog
import com.intellij.util.EnvironmentUtil
import java.io.File

/**
 * Path and environment resolution for locating legacy Kilo data during
 * migration detection/import.
 *
 * Ports the legacy-data location logic that used to live with the CLI
 * process layer: XDG-style config resolution plus the dev-storage
 * isolation used by runIde configurations.
 */
internal object LegacyMigrationPaths {

    private const val APP = "kilo"

    fun resolve(env: Map<String, String>): File {
        env["KILO_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let { return File(it) }
        env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { return File(it, APP) }
        return File(File(home(env), ".config"), APP)
    }

    fun legacySettingsFile(env: Map<String, String>): File = File(resolve(env), "legacy-settings.json")

    private fun home(env: Map<String, String>): String {
        return env["HOME"]?.takeIf { it.isNotBlank() }
            ?: env["USERPROFILE"]?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
    }
}

/** Base environment plus optional dev storage isolation for legacy-data lookup. */
internal fun migrationEnv(log: KiloLog): Map<String, String> {
    val base = EnvironmentUtil.getEnvironmentMap()
    val dev = devStorageEnv(log) ?: return base
    return base + dev
}

private fun devStorageEnv(log: KiloLog): Map<String, String>? {
    val enabled = System.getProperty("kilo.dev.storage.isolated", "false").toBoolean()
    if (!enabled) return null
    val root = System.getProperty("kilo.dev.worktree.root") ?: run {
        log.warn("kilo.dev.storage.isolated=true but kilo.dev.worktree.root is not set; skipping dev storage isolation")
        return null
    }
    val dev = File(root, ".kilo-dev")
    val data = File(dev, "data")
    val config = File(dev, "config")
    val state = File(dev, "state")
    val cache = File(dev, "cache")
    for (dir in listOf(data, config, state, cache)) {
        if (!dir.mkdirs() && !dir.isDirectory) {
            log.warn("Failed to create dev storage dir ${dir.absolutePath}; skipping dev storage isolation")
            return null
        }
    }
    log.info("Dev storage isolation enabled under ${dev.absolutePath}")
    return mapOf(
        "XDG_DATA_HOME" to data.absolutePath,
        "XDG_CONFIG_HOME" to config.absolutePath,
        "XDG_STATE_HOME" to state.absolutePath,
        "XDG_CACHE_HOME" to cache.absolutePath,
    )
}
