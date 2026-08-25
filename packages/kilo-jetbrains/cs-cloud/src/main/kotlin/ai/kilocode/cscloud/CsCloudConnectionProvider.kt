package ai.kilocode.cscloud

import ai.kilocode.backend.app.KiloConnection
import ai.kilocode.backend.app.KiloConnectionProvider
import ai.kilocode.log.KiloLog
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.EnvironmentUtil
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/** Creates connections to the already-running local cs-cloud daemon. */
class CsCloudConnectionProvider : KiloConnectionProvider {
    override val id: String = "cs-cloud"

    override fun create(
        cs: CoroutineScope,
        reconnect: () -> Unit,
        log: KiloLog,
        timeout: Long,
    ): KiloConnection = CsCloudConnectionService(
        cs = cs,
        resolver = CsCloudEndpointResolver(Path.of(System.getProperty("user.home")), System.getenv()),
        log = log,
        timeout = timeout,
        roots = {
            ProjectManager.getInstance().openProjects
                .asSequence()
                .filterNot { it.isDefault || it.isDisposed }
                .mapNotNull { it.basePath }
                .map { Path.of(it).toAbsolutePath().normalize() }
                .toList()
        },
        starter = { CscCloudStarter(EnvironmentUtil.getEnvironmentMap(), log).start() },
    )
}
