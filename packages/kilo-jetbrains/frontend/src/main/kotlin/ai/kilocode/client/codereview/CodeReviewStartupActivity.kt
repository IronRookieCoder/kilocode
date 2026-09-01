// kilocode_change - new file
package ai.kilocode.client.codereview

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the code-review report notifier when the project opens. */
class CodeReviewStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CodeReviewNotifier>().start()
    }
}
