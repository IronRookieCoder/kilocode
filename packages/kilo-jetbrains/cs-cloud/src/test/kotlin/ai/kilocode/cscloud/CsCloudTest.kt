package ai.kilocode.cscloud

import kotlin.test.Test
import kotlin.test.assertEquals

class CsCloudTest {
    @Test
    fun moduleIdMatchesPluginContentEntry() {
        assertEquals("kilo.jetbrains.cs-cloud", CsCloud.MODULE_ID)
    }
}
