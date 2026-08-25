package ai.kilocode.client.session.ui.mode

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBList
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.ListCellRenderer

@Suppress("UnstableApiUsage")
class ModePickerTest : BasePlatformTestCase() {

    fun `test active item uses check icon`() {
        val item = ModePicker.Item("code", "Code")
        val renderer = ModePickerRenderer({ "code" })

        assertSame(ModePickerRenderer.checked, renderer.icon(item))
    }

    fun `test inactive item reserves icon space`() {
        val item = ModePicker.Item("plan", "Plan")
        val renderer = ModePickerRenderer({ "code" })

        assertSame(ModePickerRenderer.empty, renderer.icon(item))
        assertEquals(AllIcons.Actions.Checked.iconWidth, renderer.icon(item).iconWidth)
    }

    fun `test item order is stable across selection changes`() {
        val picker = ModePicker()
        val items = listOf(
            ModePicker.Item("plan", "Plan"),
            ModePicker.Item("ask", "Ask"),
            ModePicker.Item("code", "Code"),
        )

        picker.setItems(items, "plan")
        val first = picker.itemsForTest().map { it.id }
        picker.setItems(items, "ask")
        val second = picker.itemsForTest().map { it.id }

        assertEquals(listOf("ask", "code", "plan"), first)
        assertEquals(first, second)
    }

    fun `test missing default falls back to first sorted mode`() {
        val picker = ModePicker()

        picker.setItems(listOf(
            ModePicker.Item("plan", "Plan"),
            ModePicker.Item("ask", "Ask"),
        ), "missing")

        assertEquals("Ask ▴", picker.text)
        assertEquals("ask", picker.selectedForTest()?.id)
    }

    fun `test item string includes description for chooser search`() {
        val item = ModePicker.Item("code", "Code", "Build and edit files")

        assertEquals("Code Build and edit files", item.toString())
    }

    fun `test deprecated item renders badge`() {
        val item = ModePicker.Item("old", "Old", "Deprecated mode", deprecated = true)
        val renderer = ModePickerRenderer({ "code" })
        val cell: ListCellRenderer<ModePicker.Item> = renderer
        val list = JBList(listOf(item))

        cell.getListCellRendererComponent(list, item, 0, false, false)

        assertTrue(renderer.badgeVisible())
        assertEquals("deprecated", renderer.badgeText())
    }

    fun `test item without details hides details row`() {
        val item = ModePicker.Item("code", "Code")
        val renderer = ModePickerRenderer({ "code" })
        val cell: ListCellRenderer<ModePicker.Item> = renderer
        val list = JBList(listOf(item))

        cell.getListCellRendererComponent(list, item, 0, false, false)

        assertFalse(renderer.detailsVisible())
    }

    fun `test blank description hides details row`() {
        val item = ModePicker.Item("code", "Code", " ")
        val renderer = ModePickerRenderer({ "code" })
        val cell: ListCellRenderer<ModePicker.Item> = renderer
        val list = JBList(listOf(item))

        cell.getListCellRendererComponent(list, item, 0, false, false)

        assertFalse(renderer.detailsVisible())
    }

    fun `test renderer hides deprecated badge after reused for normal item`() {
        val old = ModePicker.Item("old", "Old", deprecated = true)
        val code = ModePicker.Item("code", "Code")
        val renderer = ModePickerRenderer({ "code" })
        val cell: ListCellRenderer<ModePicker.Item> = renderer
        val list = JBList(listOf(old, code))

        cell.getListCellRendererComponent(list, old, 0, false, false)
        assertTrue(renderer.badgeVisible())

        cell.getListCellRendererComponent(list, code, 1, false, false)
        assertFalse(renderer.badgeVisible())
    }

    fun `test long description keeps list width bounded`() {
        val long = "Plan and execute large multi-file refactors before touching any code. ".repeat(20)
        val item = ModePicker.Item("agent", "Custom Agent", long)
        val cap = JBUI.scale(320)
        val renderer = ModePickerRenderer({ "code" }, { cap })
        val list = JBList(listOf(item))
        list.cellRenderer = renderer

        renderer.getListCellRendererComponent(list, item, 0, false, false)

        assertTrue(
            "renderer width ${renderer.preferredSize.width} must stay within $cap",
            renderer.preferredSize.width <= cap,
        )
        assertTrue(
            "list width ${list.preferredSize.width} must stay within $cap",
            list.preferredSize.width <= cap,
        )
    }

    fun `test short description keeps natural width`() {
        val item = ModePicker.Item("code", "Code", "Build and edit files")
        val cap = JBUI.scale(400)
        val renderer = ModePickerRenderer({ "code" }, { cap })
        val list = JBList(listOf(item))
        list.cellRenderer = renderer

        renderer.getListCellRendererComponent(list, item, 0, false, false)

        val width = renderer.preferredSize.width
        assertTrue("renderer width should be positive", width > 0)
        assertTrue(
            "renderer width $width should stay natural below the cap",
            width < cap,
        )
    }

    fun `test popup width tracks widest sized ancestor`() {
        val picker = ModePicker()
        val row = JPanel(BorderLayout())
        row.add(picker)
        val shell = JPanel(BorderLayout())
        shell.add(row)
        shell.setSize(500, 100)

        assertEquals(500, picker.popupWidth())
    }

    fun `test popup width falls back without sized ancestor`() {
        val picker = ModePicker()

        assertEquals(JBUI.scale(MODE_PICKER_FALLBACK_WIDTH), picker.popupWidth())
    }
}
