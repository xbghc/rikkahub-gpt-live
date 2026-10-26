package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.quotaQuery.enabled)
        assertEquals("{{baseUrl}}/credits", provider.quotaQuery.url)
        assertEquals("balance", provider.quotaQuery.resultPath)
        assertEquals("openai/gpt-live-1", provider.models.single().modelId)
    }

    @Test
    fun `refreshing built in providers preserves existing model selections`() {
        val vercel = DEFAULT_PROVIDERS.single { it.name == "Vercel AI Gateway" }
        val saved = vercel.copyProvider(models = emptyList())
        val refreshed = Settings(providers = listOf(saved)).withBuiltInDefaults()
        assertTrue(refreshed.providers.single { it.id == saved.id }.models.isEmpty())
    }
}
