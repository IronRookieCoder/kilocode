package ai.kilocode.client

import ai.kilocode.client.plugin.KiloBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * X3 / U8.3: the two notification groups must stay distinct — different ids, different bundle
 * keys, different display names — so code-review events can never leak into the generic
 * Costrict group and vice versa.
 */
class NotificationGroupIsolationTest {

    private val pluginXml: String by lazy {
        requireNotNull(javaClass.classLoader.getResourceAsStream("kilo.jetbrains.frontend.xml")) {
            "kilo.jetbrains.frontend.xml not on the test classpath"
        }.bufferedReader().use { it.readText() }
    }

    private fun registration(id: String): String =
        Regex("<notificationGroup[^>]*id=\"$id\"[^>]*/>").find(pluginXml)?.value
            ?: throw AssertionError("notificationGroup '$id' is not registered in plugin.xml")

    @Test
    fun `generic and code review groups are registered as separate ids`() {
        registration("Costrict")
        registration("Costrict.CodeReview")
        assertTrue("Costrict" != "Costrict.CodeReview")
    }

    @Test
    fun `groups bind to different bundle keys`() {
        val generic = registration("Costrict")
        val review = registration("Costrict.CodeReview")
        val genericKey = Regex("key=\"([^\"]+)\"").find(generic)?.groupValues?.get(1)
        val reviewKey = Regex("key=\"([^\"]+)\"").find(review)?.groupValues?.get(1)
        assertEquals("notification.group.kilo", genericKey, "generic group must bind notification.group.kilo")
        assertEquals("notification.group.codereview", reviewKey, "review group must bind notification.group.codereview")
        assertTrue(genericKey != reviewKey)
    }

    @Test
    fun `group display names differ`() {
        val generic = KiloBundle.message("notification.group.kilo")
        val review = KiloBundle.message("notification.group.codereview")
        assertTrue(generic != review, "group display names must differ, both were '$generic'")
        assertEquals("Costrict", generic)
        assertEquals("CoStrict Code Review", review)
    }
}
