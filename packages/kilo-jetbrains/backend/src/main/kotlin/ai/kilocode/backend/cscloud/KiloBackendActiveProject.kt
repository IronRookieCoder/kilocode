package ai.kilocode.backend.cscloud

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import java.nio.file.Path

/**
 * Tracks the base path of the most recently opened IntelliJ project, used as
 * the current workspace for the cs-cloud connection (SSE workspace filter and
 * `X-Workspace-Directory` header).
 */
class KiloBackendActiveProject {
    @Volatile
    private var path: Path? = null

    val current: Path?
        get() = path

    private val listener = object : ProjectManagerListener {
        override fun projectOpened(project: Project) = update(project)

        override fun projectClosed(project: Project) {
            if (path == project.basePath?.let(Path::of)) path = null
        }
    }

    fun start() {
        val manager = ProjectManager.getInstance()
        manager.addProjectManagerListener(listener)
        manager.openProjects.lastOrNull()?.let(::update)
    }

    fun stop() {
        ProjectManager.getInstance().removeProjectManagerListener(listener)
    }

    private fun update(project: Project) {
        project.basePath?.let { path = Path.of(it) }
    }
}
