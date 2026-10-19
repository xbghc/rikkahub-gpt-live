package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import me.rerere.rikkahub.data.model.GptLiveEndpointPreset
import me.rerere.rikkahub.data.model.GptLiveSettings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GptLiveSettingsTest {
    @Test
    fun `settings debug output does not reveal the live API key`() {
        val secret = "live-api-secret-not-for-logs"
        val settings = Settings(gptLive = GptLiveSettings(apiKey = secret))

        assertFalse(settings.gptLive.toString().contains(secret))
        assertFalse(settings.toString().contains(secret))
    }

    @Test
    fun `existing settings do not enable a live microphone session`() {
        val fromJson = JsonInstant.decodeFromString<Settings>("{}")
        val fromPreferences = emptyPreferences().toSettings()

        assertEquals(GptLiveSettings(), fromJson.gptLive)
        assertEquals(GptLiveSettings(), fromPreferences.gptLive)
        assertFalse(fromPreferences.gptLive.enabled)
        assertEquals("gpt-live-1", fromPreferences.gptLive.model)
        assertEquals("wss://api.openai.com/v1/live/sessions", fromPreferences.gptLive.endpoint)
    }

    @Test
    fun `Vercel preset selects native GPT Live protocol and gateway model slug`() {
        val openai = GptLiveSettings(enabled = true, apiKey = "openai-key")
        val vercel = openai.withEndpointPreset(GptLiveEndpointPreset.VercelAiGateway)

        assertEquals("", vercel.apiKey)
        assertEquals("wss://ai-gateway.vercel.sh/v1/live/sessions", vercel.endpoint)
        assertEquals("openai/gpt-live-1", vercel.model)
        assertEquals(GptLiveEndpointPreset.VercelAiGateway, vercel.endpointPreset())
        assertEquals(vercel, vercel.withEndpointPreset(GptLiveEndpointPreset.VercelAiGateway))

        val withGatewayKey = vercel.copy(apiKey = "vercel-key")
        assertEquals("vercel-key", withGatewayKey.withEndpointPreset(GptLiveEndpointPreset.VercelAiGateway).apiKey)
        val restored = withGatewayKey.withEndpointPreset(GptLiveEndpointPreset.OpenAI)
        assertEquals("", restored.apiKey)
        assertEquals(GptLiveEndpointPreset.OpenAI.endpoint, restored.endpoint)
        assertEquals(GptLiveEndpointPreset.OpenAI.model, restored.model)

        val custom = withGatewayKey.copy(endpoint = "wss://proxy.example.com/v1/live/sessions")
        assertEquals(null, custom.endpointPreset())
        assertEquals(custom, JsonInstant.decodeFromString<GptLiveSettings>(JsonInstant.encodeToString(custom)))
    }

    @Test
    fun `live connection and speaking preferences survive JSON and datastore round trips`() {
        val live = GptLiveSettings(
            enabled = true,
            apiKey = "test-key",
            endpoint = "wss://voice.example.com/v1/live/sessions",
            model = "custom-live-model",
            voice = "custom-voice",
            instructions = "Speak briefly.\nDelegate research to the assistant.",
        )
        val settings = Settings(gptLive = live)
        val preferences = mutablePreferencesOf().apply { putSettings(settings) }

        assertEquals(live, JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(settings)).gptLive)
        assertEquals(live, preferences.toSettings().gptLive)
        assertEquals(live, JsonInstant.decodeFromString<GptLiveSettings>(preferences[SettingsStore.GPT_LIVE]!!))
    }

    @Test
    fun `disabling live preserves configuration and clearing a key persists`() {
        val initial = Settings(gptLive = GptLiveSettings(enabled = true, apiKey = "remove-me", voice = "custom-voice"))
        val preferences = mutablePreferencesOf().apply { putSettings(initial) }
        val disabled = initial.copy(gptLive = initial.gptLive.copy(enabled = false))
        preferences.putSettings(disabled, persisted = initial)

        assertFalse(preferences.toSettings().gptLive.enabled)
        assertEquals("remove-me", preferences.toSettings().gptLive.apiKey)
        assertEquals("custom-voice", preferences.toSettings().gptLive.voice)

        val cleared = disabled.copy(gptLive = disabled.gptLive.copy(apiKey = ""))
        preferences.putSettings(cleared, persisted = disabled)
        assertEquals("", preferences.toSettings().gptLive.apiKey)
        assertEquals("custom-voice", preferences.toSettings().gptLive.voice)
    }
}
