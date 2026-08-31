package me.yui.yuihub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class DefaultProvidersTest {
    @Test
    fun `default providers should only include a removable DeepSeek`() {
        assertEquals(1, DEFAULT_PROVIDERS.size)

        val provider = DEFAULT_PROVIDERS.single() as ProviderSetting.OpenAI
        assertEquals("DeepSeek", provider.name)
        assertEquals("https://api.deepseek.com/v1", provider.baseUrl)
        assertFalse(provider.builtIn)
        assertEquals("/user/balance", provider.balanceOption.apiPath)
    }

    @Test
    fun `legacy builtin providers should be stripped from stored lists`() {
        val openai = ProviderSetting.OpenAI(
            id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
            name = "OpenAI",
        )
        val custom = ProviderSetting.OpenAI(name = "My API")
        val kept = listOf(openai, DEFAULT_PROVIDERS.single(), custom)
            .withoutLegacyBuiltinProviders()

        assertEquals(listOf(DEFAULT_PROVIDERS.single().id, custom.id), kept.map { it.id })
    }
}
