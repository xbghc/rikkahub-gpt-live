package me.rerere.rikkahub.ui.pages.chat

import androidx.datastore.preferences.core.mutablePreferencesOf
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.putSettings
import me.rerere.rikkahub.data.datastore.toSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

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

    @Test
    fun `completed setup skips configuration after settings reload with a text chat model selected`() {
        val text = Model(modelId = "openai/gpt-5")
        val before = Settings(providers = listOf(gateway.copy(models = listOf(text))), chatModelId = text.id)
        val (configured, selection) = before.prepareGptLive(gateway.id, "saved-key")
        val restored = mutablePreferencesOf().apply { putSettings(configured) }.toSettings()

        assertEquals(selection.provider, restored.configuredGptLiveProvider())
        assertEquals(text.id, restored.chatModelId)
    }

    @Test
    fun `text credentials alone do not skip first time voice configuration`() {
        val provider = gateway.copy(enabled = true, models = listOf(Model(modelId = "openai/gpt-5")))
        assertNull(Settings(providers = listOf(provider)).configuredGptLiveProvider())
        assertNull(Settings().configuredGptLiveProvider())
    }

    @Test
    fun `disabled incomplete or insecure configurations require setup`() {
        val (_, selection) = Settings(providers = listOf(gateway)).prepareGptLive(gateway.id, "saved-key")
        val ready = selection.provider
        val invalid = listOf(
            ready.copy(enabled = false),
            ready.copy(apiKey = "  "),
            ready.copy(baseUrl = ""),
            ready.copy(baseUrl = "invalid-url"),
            ready.copy(baseUrl = "http://ai-gateway.vercel.sh/v1"),
            ready.copy(models = emptyList()),
            ready.copy(models = listOf(selection.model.copy(providerOverwrite = gateway))),
        )
        invalid.forEach { provider ->
            assertNull(Settings(providers = listOf(provider)).configuredGptLiveProvider())
        }
        assertNull(Settings(init = true, providers = listOf(ready)).configuredGptLiveProvider())
    }

    @Test
    fun `last selected provider wins and missing credentials do not switch to a different account`() {
        val (_, selection) = Settings(providers = listOf(gateway)).prepareGptLive(gateway.id, "saved-key")
        val first = selection.provider
        val chosen = first.copy(id = Uuid.random(), name = "Other account", apiKey = "other-key")
        val settings = Settings(providers = listOf(first, chosen))

        assertEquals(chosen, settings.configuredGptLiveProvider(chosen.id))
        assertNull(settings.configuredGptLiveProvider(Uuid.random()))
        assertNull(
            settings.copy(providers = listOf(first, chosen.copy(apiKey = "")))
                .configuredGptLiveProvider(chosen.id)
        )
    }

    @Test
    fun `existing gateway configuration is preferred while OpenAI and custom endpoints remain supported`() {
        val voice = Model(modelId = "gpt-live-1")
        val openai = ProviderSetting.OpenAI(apiKey = "openai-key", models = listOf(voice))
        val vercel = gateway.copy(enabled = true, models = listOf(voice))
        val custom = openai.copy(id = Uuid.random(), baseUrl = "https://voice.example.com/v1")

        assertEquals(vercel, Settings(providers = listOf(openai, vercel)).configuredGptLiveProvider())
        assertEquals(openai, Settings(providers = listOf(openai)).configuredGptLiveProvider())
        assertEquals(custom, Settings(providers = listOf(custom)).configuredGptLiveProvider())
    }
}
