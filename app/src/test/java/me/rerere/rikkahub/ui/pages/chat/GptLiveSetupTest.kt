package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GptLiveSetupTest {
    private val gateway = ProviderSetting.OpenAI(
        name = "Vercel AI Gateway",
        baseUrl = "https://ai-gateway.vercel.sh/v1",
        enabled = false,
        apiKey = "saved-key",
    )

    @Test
    fun `upgraded provider with no models can be configured without selecting a chat model`() {
        val text = Model(modelId = "openai/gpt-5")
        val provider = gateway.copy(models = listOf(text), customHeaders = listOf(CustomHeader("X-Team", "team")))
        val before = Settings(providers = listOf(provider), chatModelId = text.id)
        assertEquals(listOf(provider), before.gptLiveProviders())

        val (after, selection) = before.prepareGptLive(provider.id, "  saved-key  ")
        assertTrue(selection.provider.enabled)
        assertEquals("saved-key", selection.provider.apiKey)
        assertEquals(provider.baseUrl, selection.provider.baseUrl)
        assertEquals(provider.customHeaders, selection.provider.customHeaders)
        assertEquals(listOf(text, selection.model), selection.provider.models)
        assertEquals("openai/gpt-live-1", selection.model.modelId)
        assertEquals(text.id, after.chatModelId)
        assertEquals(before.assistants, after.assistants)
        assertFalse(before.providers.single().enabled)
    }

    @Test
    fun `repeat setup reuses existing voice model without overwriting customizations`() {
        val voice = Model(modelId = "gpt-live-1", displayName = "My voice", contextLength = 500)
        val before = Settings(providers = listOf(gateway.copy(models = listOf(voice))))
        val (once, selection) = before.prepareGptLive(gateway.id, "first")
        val (twice, secondSelection) = once.prepareGptLive(gateway.id, "second")
        assertEquals(voice, selection.model)
        assertEquals(voice, secondSelection.model)
        assertEquals(listOf(voice), twice.providers.single().models)
        assertEquals("second", secondSelection.provider.apiKey)
    }

    @Test
    fun `fresh install offers disabled Vercel provider before any keys or models exist`() {
        val before = Settings(providers = listOf(gateway.copy(apiKey = "", models = emptyList())))
        assertEquals(gateway.id, before.gptLiveProviders().single().id)
        val (after, selection) = before.prepareGptLive(gateway.id, "new-key")
        assertEquals(listOf(selection.provider), after.providers)
        assertEquals(1, selection.provider.models.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank credentials cannot silently enable an unconfigured provider`() {
        Settings(providers = listOf(gateway)).prepareGptLive(gateway.id, "   ")
    }

    @Test
    fun `setup does not reuse a model that routes to a different provider`() {
        val overridden = Model(modelId = "gpt-live-1", providerOverwrite = gateway.copy(apiKey = "other-key"))
        val before = Settings(providers = listOf(gateway.copy(models = listOf(overridden))))
        val (_, selection) = before.prepareGptLive(gateway.id, "chosen-key")
        assertEquals(2, selection.provider.models.size)
        assertEquals(null, selection.model.providerOverwrite)
        assertTrue(selection.model.id != overridden.id)
    }
}
