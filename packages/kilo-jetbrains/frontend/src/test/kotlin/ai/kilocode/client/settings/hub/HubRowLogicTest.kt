package ai.kilocode.client.settings.hub

import ai.kilocode.rpc.dto.CloudFavoriteItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HubRowLogicTest {
    private fun item(id: String, type: String, status: String, name: String = id) =
        CloudFavoriteItem(id = id, slug = id, name = name, itemType = type, status = status)

    @Test
    fun `orders by section then status rank then name`() {
        val ordered = HubRowLogic.ordered(
            listOf(
                item("z-skill", "skill", "Cloud"),
                item("a-skill", "skill", "Active"),
                item("m-agent", "agent", "Active"),
                item("b-skill", "skill", "Active"),
            ),
        )
        assertEquals(listOf("a-skill", "b-skill", "z-skill", "m-agent"), ordered.map { it.id })
    }

    @Test
    fun `active sorts before downloaded cloud unloaded`() {
        val ordered = HubRowLogic.ordered(
            listOf(
                item("u", "skill", "Unloaded"),
                item("c", "skill", "Cloud"),
                item("d", "skill", "Downloaded"),
                item("a", "skill", "Active"),
            ),
        )
        assertEquals(listOf("a", "d", "c", "u"), ordered.map { it.id })
    }

    @Test
    fun `filters unknown item types`() {
        val ordered = HubRowLogic.ordered(
            listOf(item("ok", "skill", "Active"), item("weird", "prompt", "Active")),
        )
        assertEquals(listOf("ok"), ordered.map { it.id })
    }

    @Test
    fun `enable cell for every non-active status`() {
        for (status in listOf("Cloud", "Downloaded", "Unloaded", "")) {
            assertEquals(HubRowLogic.ENABLE_CELL, HubRowLogic.cellId(status), status)
        }
    }

    @Test
    fun `disable cell only for active`() {
        assertEquals(HubRowLogic.DISABLE_CELL, HubRowLogic.cellId("Active"))
    }
}
