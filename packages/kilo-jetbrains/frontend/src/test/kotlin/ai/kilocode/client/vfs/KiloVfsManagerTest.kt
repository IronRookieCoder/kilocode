package ai.kilocode.client.vfs

import ai.kilocode.client.util.edtWait
import ai.kilocode.stability.Resources
import ai.kilocode.stability.StabilityService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.components.BorderLayoutPanel
import java.nio.file.Files
import javax.swing.JComponent

@Suppress("UnstableApiUsage")
class KiloVfsManagerTest : BasePlatformTestCase() {
    private val kind = "test-close"

    override fun setUp() {
        super.setUp()
        service<KiloEditorKindRegistry>().register(TestKind)
    }

    override fun tearDown() {
        try {
            service<KiloEditorKindRegistry>().unregister(kind)
            KiloVirtualFileSystem.getInstance().clear()
        } finally {
            super.tearDown()
        }
    }

    fun `test close closes the open editor and releases the cache`() {
        val params = mapOf("path" to "/repo/wt")
        val vfs = project.service<KiloVfsManager>()
        val manager = FileEditorManager.getInstance(project)

        edtWait { assertTrue(vfs.open(kind, params)) }
        assertTrue(manager.openFiles.any { it is KiloVirtualFile })

        edtWait { vfs.close(kind, params) }

        assertTrue(manager.openFiles.none { it is KiloVirtualFile })
        assertNull(KiloVirtualFileSystem.getInstance().cached(KiloPath(kind, params)))
    }

    fun `test close matches by canonical params after the cache was released`() {
        // Two params so canonicalization sorts them; open and close use different insertion orders.
        val opened = linkedMapOf("path" to "/repo/wt", "extra" to "1")
        val closed = linkedMapOf("extra" to "1", "path" to "/repo/wt")
        val vfs = project.service<KiloVfsManager>()
        val manager = FileEditorManager.getInstance(project)

        edtWait { assertTrue(vfs.open(kind, opened)) }
        assertTrue(manager.openFiles.any { it is KiloVirtualFile })

        // Drop the VFS cache entry so close() must fall back to scanning the open editors.
        KiloVirtualFileSystem.getInstance().release(KiloPath(kind, opened))
        assertNull(KiloVirtualFileSystem.getInstance().cached(KiloPath(kind, opened)))

        edtWait { vfs.close(kind, closed) }

        assertTrue(manager.openFiles.none { it is KiloVirtualFile })
    }

    private object TestKind : KiloEditorKind {
        override val id: String = "test-close"

        override fun title(params: Map<String, String>): String = "Test"

        override fun createContent(project: Project, file: KiloVirtualFile, parent: Disposable): JComponent =
            BorderLayoutPanel()
    }

    // ------ M24（C5）editor资源token：真实编辑器开/关回落基线，重复dispose不负数 ------

    /** 真实临时项目文件：编辑器计数的路径指向真实存在的临时文件（内容不进入事实）。 */
    private fun tempFileParams(n: Int): Map<String, String> {
        val file = Files.createTempFile("kilo-vfs-lifecycle-$n", ".txt")
        Files.writeString(file, "lifecycle $n")
        file.toFile().deleteOnExit()
        return mapOf("path" to file.toString().replace('\\', '/'))
    }

    fun `test open and close editors 100 times returns counts to baseline`() {
        val vfs = project.service<KiloVfsManager>()
        val resources = service<StabilityService>().resources
        val baseline = resources.snapshot()

        repeat(100) { n ->
            val params = tempFileParams(n)
            edtWait { assertTrue(vfs.open(kind, params)) }
            assertEquals(baseline.getValue("editor") + 1, resources.snapshot()["editor"])
            edtWait { vfs.close(kind, params) }
            assertEquals(baseline.getValue("editor"), resources.snapshot()["editor"])
        }
        assertEquals(baseline, resources.snapshot())
    }

    fun `test repeated editor dispose never goes negative`() {
        val resources = Resources()
        val path = KiloPath(kind, mapOf("path" to "/repo/wt"))
        val kilo = KiloVirtualFile(path)
        val editor = KiloFileEditor(project, kilo, kilo, TestKind, resources)
        assertEquals(1L, resources.snapshot()["editor"])

        // token首次close才减计数：重复dispose（与生产Disposer路径同语义）绝不负数。
        editor.dispose()
        editor.dispose()
        assertEquals(0L, resources.snapshot()["editor"])
    }
}
