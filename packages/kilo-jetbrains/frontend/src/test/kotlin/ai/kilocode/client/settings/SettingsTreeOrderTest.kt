package ai.kilocode.client.settings

import ai.kilocode.client.plugin.KiloBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * X4 / U5.1 / U8.4: the settings tree stays complete and ordered — root "Costrict", the seven
 * top-level pages in registration order, and the Agent Behavior sub-pages ending in
 * … → Skills → Cloud Hub. Every registration must resolve a branded bundle displayName and a
 * real Configurable class.
 */
class SettingsTreeOrderTest {

    private data class Registration(val parentId: String, val id: String, val key: String, val instance: String)

    private val registrations: List<Registration> by lazy {
        val xml = requireNotNull(javaClass.classLoader.getResourceAsStream("kilo.jetbrains.frontend.xml")) {
            "kilo.jetbrains.frontend.xml not on the test classpath"
        }.bufferedReader().use { it.readText() }
        // Multi-line elements: join each <applicationConfigurable …/> block before parsing attrs.
        Regex("<applicationConfigurable\\b[^>]*?/>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.value.replace(Regex("\\s+"), " ") }
            .map { block ->
                fun attr(name: String) = Regex("$name=\"([^\"]+)\"").find(block)?.groupValues?.get(1)
                    ?: throw AssertionError("applicationConfigurable is missing $name: $block")
                Registration(attr("parentId"), attr("id"), attr("key"), attr("instance"))
            }
            .toList()
    }

    @Test
    fun `root page is registered under Tools with the Costrict brand`() {
        val root = registrations.first { it.parentId == "tools" }
        assertEquals("ai.kilocode.jetbrains.settings", root.id)
        assertEquals("Costrict", KiloBundle.message(root.key))
    }

    @Test
    fun `top level pages keep the documented order`() {
        val expected = listOf(
            "ai.kilocode.jetbrains.settings.profile",
            "ai.kilocode.jetbrains.settings.models",
            "ai.kilocode.jetbrains.settings.providers",
            "ai.kilocode.jetbrains.settings.agentBehavior",
            "ai.kilocode.jetbrains.settings.context",
            "ai.kilocode.jetbrains.settings.autoApprove",
            "ai.kilocode.jetbrains.settings.advanced",
        )
        val actual = registrations.filter { it.parentId == "ai.kilocode.jetbrains.settings" }.map { it.id }
        assertEquals(expected, actual)
    }

    @Test
    fun `agent behavior sub pages end with skills before cloud hub`() {
        val expected = listOf(
            "ai.kilocode.jetbrains.settings.agentBehavior.agents",
            "ai.kilocode.jetbrains.settings.agentBehavior.mcp",
            "ai.kilocode.jetbrains.settings.agentBehavior.skills",
            "ai.kilocode.jetbrains.settings.agentBehavior.cloudHub",
            "ai.kilocode.jetbrains.settings.agentBehavior.workflows",
            "ai.kilocode.jetbrains.settings.agentBehavior.rules",
        )
        val actual = registrations
            .filter { it.parentId == "ai.kilocode.jetbrains.settings.agentBehavior" }
            .map { it.id }
        assertEquals(expected, actual)
        assertTrue(
            actual.indexOf("ai.kilocode.jetbrains.settings.agentBehavior.skills") <
                actual.indexOf("ai.kilocode.jetbrains.settings.agentBehavior.cloudHub"),
        )
    }

    @Test
    fun `every registration resolves a real class and branded display name`() {
        val loader = javaClass.classLoader
        for (registration in registrations) {
            // initialize = false: Configurable companions may need the platform, which a plain
            // unit test does not provide — only the class must exist and be loadable.
            Class.forName(registration.instance, false, loader)
            val display = KiloBundle.message(registration.key)
            assertTrue(display.isNotBlank() && display != registration.key, "displayName missing for ${registration.id}")
        }
    }
}
