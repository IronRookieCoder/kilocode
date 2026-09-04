package ai.kilocode.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CostrictBrandTest {

    @Test
    fun `notification group ids match the xml registration`() {
        val xml = javaClass.classLoader.getResourceAsStream("kilo.jetbrains.frontend.xml")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw AssertionError("kilo.jetbrains.frontend.xml not on test classpath")
        // The exact value with closing quote — must not match "Costrict.CodeReview".
        assertTrue(xml.contains("<notificationGroup id=\"Costrict\""), "generic group must be registered as Costrict")
        assertTrue(
            xml.contains("<notificationGroup id=\"Costrict.CodeReview\""),
            "code review group must be registered as Costrict.CodeReview",
        )
        assertEquals("Costrict", CostrictBrand.NOTIFICATION_GROUP)
        assertEquals("Costrict.CodeReview", CostrictBrand.CODE_REVIEW_NOTIFICATION_GROUP)
    }
}
