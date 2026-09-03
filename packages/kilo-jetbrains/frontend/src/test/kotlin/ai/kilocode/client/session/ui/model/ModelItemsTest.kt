package ai.kilocode.client.session.ui.model

import ai.kilocode.rpc.dto.ModelDto
import ai.kilocode.rpc.dto.ProviderDto
import ai.kilocode.rpc.dto.ProvidersDto
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ModelItemsTest : BasePlatformTestCase() {

    private fun providers(): ProvidersDto = ProvidersDto(
        providers = listOf(
            ProviderDto(
                "kilo", "Kilo",
                models = mapOf(
                    "gpt-5" to ModelDto("gpt-5", "GPT-5", variants = listOf("low", "high"), attachment = true),
                    "auto-small" to ModelDto("auto-small", "Auto Small"),
                ),
            ),
            ProviderDto("openai", "OpenAI", models = mapOf("o3" to ModelDto("o3", "o3"))),
            ProviderDto("anthropic", "Anthropic", models = mapOf("claude" to ModelDto("claude", "Claude"))),
        ),
        connected = listOf("openai"),
        defaults = emptyMap(),
    )

    fun `test drops small models and providers that are not connected`() {
        assertEquals(listOf("kilo/gpt-5", "openai/o3"), modelItems(providers()).map { it.key })
    }

    fun `test keeps small models when requested`() {
        assertEquals(
            setOf("kilo/gpt-5", "kilo/auto-small", "openai/o3"),
            modelItems(providers(), includeSmall = true).map { it.key }.toSet(),
        )
    }

    fun `test carries variants and attachment onto the item`() {
        val gpt = modelItems(providers()).first { it.key == "kilo/gpt-5" }
        assertEquals(listOf("low", "high"), gpt.variants)
        assertTrue(gpt.attachment)
    }

    fun `test carries billing rates onto the item`() {
        val providers = ProvidersDto(
            providers = listOf(
                ProviderDto(
                    "costrict", "Costrict",
                    models = mapOf(
                        "Auto" to ModelDto("Auto", "Auto", creditConsumption = 0.9, creditDiscount = 0.9),
                        "Kimi-K3" to ModelDto("Kimi-K3", "Kimi K3", creditConsumption = 20.0),
                    ),
                ),
            ),
            connected = listOf("costrict"),
            defaults = emptyMap(),
        )

        val auto = modelItems(providers).single { it.key == "costrict/Auto" }
        assertEquals(0.9, auto.creditConsumption)
        assertEquals(0.9, auto.creditDiscount)
        val k3 = modelItems(providers).single { it.key == "costrict/Kimi-K3" }
        assertEquals(20.0, k3.creditConsumption)
        assertNull(k3.creditDiscount)
    }

    fun `test null providers yields no items`() {
        assertTrue(modelItems(null).isEmpty())
    }
}
