package ai.kilocode.cscloud

import ai.kilocode.backend.app.KiloConnection
import ai.kilocode.backend.app.KiloConnectionProvider
import ai.kilocode.log.KiloLog
import ai.kilocode.stability.Operations
import ai.kilocode.stability.StabilityService
import com.intellij.openapi.components.service
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
        installer = { CscInstaller(EnvironmentUtil.getEnvironmentMap(), log).install() },
        login = { CscLogin(EnvironmentUtil.getEnvironmentMap(), log).login() },
        // 稳定性采集（B2）：采集不可用绝不妨碍连接业务，连接以null观测继续。
        operations = runCatching { service<StabilityService>().operations }.getOrNull(),
    )
}
