package ai.kilocode.client.session.vfs

import ai.kilocode.client.app.Workspace
import ai.kilocode.client.session.SessionManager
import ai.kilocode.client.session.controller.SessionController
import ai.kilocode.client.session.model.SessionState
import ai.kilocode.log.KiloLog
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Refreshes the IntelliJ VFS after csc writes files directly to the project directory.
 *
 * When a session becomes idle (csc finished its turn) or a `host.file.*` event arrives,
 * this service schedules a VFS refresh so that open editors, the project tree, and the
 * diff viewer pick up the on-disk changes without requiring a manual reload.
 *
 * Undo support relies on JetBrains Local History — the VFS refresh makes the on-disk
 * state the "current" version, and Local History preserves the prior content for revert.
 */
@Service(Service.Level.PROJECT)
class VfsRefreshService(private val project: Project) : Disposable {

    private val watchedDirs = ConcurrentHashMap.newKeySet<String>()
    private var listener: BulkFileListener? = null

    /**
     * Register a project directory for VFS refresh on session idle.
     * Called when a session is opened or a new workspace is added.
     */
    @RequiresEdt
    fun watch(directory: String) {
        val abs = Path.of(directory).toAbsolutePath().normalize().toString()
        if (watchedDirs.add(abs)) {
            LOG.debug("VFS refresh: watching $abs")
        }
    }

    /**
     * Unregister a directory. Called when a session is closed or workspace removed.
     */
    @RequiresEdt
    fun unwatch(directory: String) {
        val abs = Path.of(directory).toAbsolutePath().normalize().toString()
        watchedDirs.remove(abs)
    }

    /**
     * Trigger a VFS refresh for the given directory. This is called when:
     * 1. A session.idle event is received (csc finished writing files)
     * 2. A host.file.* event is received (csc wrote a specific file)
     *
     * The refresh runs on a background thread to avoid blocking the EDT.
     */
    fun refresh(directory: String) {
        val abs = Path.of(directory).toAbsolutePath().normalize().toString()
        LOG.debug("VFS refresh: scheduling refresh for $abs")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val lfs = LocalFileSystem.getInstance()
                val root = lfs.refreshAndFindFileByPath(abs)
                if (root != null) {
                    // Refresh the directory tree recursively so all child files are updated
                    root.refresh(true, true)
                    LOG.debug("VFS refresh: completed for $abs")
                } else {
                    LOG.warn("VFS refresh: directory not found: $abs")
                }
            } catch (e: Exception) {
                LOG.warn("VFS refresh: failed for $abs: ${e.message}", e)
            }
        }
    }

    /**
     * Refresh a specific file path. Used for host.file.* events where
     * csc wrote a single file.
     */
    fun refreshFile(filePath: String) {
        val abs = Path.of(filePath).toAbsolutePath().normalize().toString()
        LOG.debug("VFS refresh: scheduling file refresh for $abs")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val lfs = LocalFileSystem.getInstance()
                val vf = lfs.refreshAndFindFileByPath(abs)
                if (vf != null) {
                    vf.refresh(false, false)
                    LOG.debug("VFS refresh: file refreshed $abs")
                } else {
                    LOG.warn("VFS refresh: file not found: $abs")
                }
            } catch (e: Exception) {
                LOG.warn("VFS refresh: file refresh failed for $abs: ${e.message}", e)
            }
        }
    }

    /**
     * Refresh all watched directories. Called on bulk operations or
     * when the exact directory is unknown.
     */
    fun refreshAll() {
        for (dir in watchedDirs) {
            refresh(dir)
        }
    }

    override fun dispose() {
        watchedDirs.clear()
        listener = null
    }

    companion object {
        private val LOG = KiloLog.forClass(VfsRefreshService::class.java)

        fun getInstance(project: Project): VfsRefreshService =
            project.getService(VfsRefreshService::class.java)
    }
}
