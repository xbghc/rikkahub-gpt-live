package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
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
