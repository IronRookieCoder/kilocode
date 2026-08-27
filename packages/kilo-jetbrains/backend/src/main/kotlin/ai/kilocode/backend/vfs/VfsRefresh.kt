package ai.kilocode.backend.vfs

import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * Refreshes IntelliJ's VFS so editors, project tree and diff views re-read
 * files the agent wrote on disk (design §2.1.6).
 */
object VfsRefresh {

    /** Refreshes the given paths (recursive for directories) and fires VFS events. */
    fun refresh(paths: List<Path>) {
        if (paths.isEmpty()) return
        val files = paths.distinct().map { it.toFile() }.toMutableList()
        LocalFileSystem.getInstance().refreshIoFiles(files, true, true, null)
    }
}
