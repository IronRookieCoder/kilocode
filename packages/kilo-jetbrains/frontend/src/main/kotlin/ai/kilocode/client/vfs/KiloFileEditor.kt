package ai.kilocode.client.vfs

import ai.kilocode.stability.Resources
import ai.kilocode.stability.StabilityService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import javax.swing.JComponent

private const val RESOURCE_EDITOR = "editor"

class KiloFileEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val kilo: KiloVirtualFile,
    private val kind: KiloEditorKind,
    // M24（C5）：editor资源token绑定真实所有者——实际插件editor构造acquire、dispose释放；
    // token的close幂等（首次close才减计数），重复dispose绝不负数。测试经构造参数注入同一实例。
    private val resources: Resources = service<StabilityService>().resources,
) : KiloFileEditorBase() {
    private val ui: JComponent by lazy { kind.createContent(project, kilo, this) }
    private val editorToken = resources.acquire(RESOURCE_EDITOR)

    @RequiresEdt
    override fun getComponent(): JComponent = ui

    override fun getPreferredFocusedComponent(): JComponent? = kind.preferredFocus(ui)
    override fun getName(): String = kind.title(kilo.path.params)
    override fun getFile(): VirtualFile = file
    override fun isValid(): Boolean = super.isValid() && kilo.isValid

    override fun dispose() {
        editorToken.close()
        KiloVirtualFileSystem.getInstance().release(kilo.path)
        super.dispose()
    }
}
