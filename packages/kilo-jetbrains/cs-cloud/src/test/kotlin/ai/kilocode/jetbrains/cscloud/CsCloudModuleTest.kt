package ai.kilocode.jetbrains.cscloud

import kotlin.test.Test
import kotlin.test.assertEquals

class CsCloudModuleTest {

    @Test
    fun `module id matches plugin descriptor`() {
        assertEquals("kilo.jetbrains.cs-cloud", CsCloudModule.ID)
    }
}
