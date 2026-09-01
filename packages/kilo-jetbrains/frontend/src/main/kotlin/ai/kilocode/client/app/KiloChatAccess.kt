// kilocode_change - new file
package ai.kilocode.client.app

import ai.kilocode.client.session.SessionManager
import com.intellij.openapi.components.Service

/**
 * Project-level access to the chat [SessionManager] created by the Kilo tool window,
 * so actions outside the tool window (editor/project-view popups) can send commands.
 */
@Service(Service.Level.PROJECT)
class KiloChatAccess {
    @Volatile
    var manager: SessionManager? = null

    /** Backend-resolved workspace directory (split-mode safe). */
    @Volatile
    var workspaceDirectory: String? = null
}
