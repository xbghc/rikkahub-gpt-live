package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

internal const val VOICE_MODE_PREFERENCE = "voice_mode"
internal const val GPT_LIVE_PROVIDER_PREFERENCE = "gpt_live_provider_id"
internal const val GEMINI_LIVE_PROVIDER_PREFERENCE = "gemini_live_provider_id"

internal enum class VoiceConversationMode(val preferenceValue: String, val label: String) {
    GPT("gpt", "GPT Live"),
    GEMINI("gemini", "Gemini Live"),
    LEGACY("legacy", "语音识别与朗读"),
}

internal sealed interface VoiceStartRequest {
    data class Live(val selection: GptLiveSelection) : VoiceStartRequest
    data class Gemini(val selection: GeminiLiveSelection) : VoiceStartRequest
    data object Legacy : VoiceStartRequest
}

/** Existing installations keep their saved mode; provider-only backups can recover a configured mode. */
internal fun Settings.voiceConversationMode(savedMode: String?): VoiceConversationMode =
    VoiceConversationMode.entries.firstOrNull { it.preferenceValue == savedMode }
        ?: if (configuredGptLiveProvider() == null && configuredGeminiLiveProvider() != null) {
            VoiceConversationMode.GEMINI
        } else {
            VoiceConversationMode.GPT
        }

/** Resolve saved settings without writing credentials or changing the text chat model on startup. */
internal fun Settings.configuredVoiceRequest(
    mode: VoiceConversationMode,
    preferredGptProviderId: Uuid? = null,
    preferredGeminiProviderId: Uuid? = null,
): VoiceStartRequest? = when (mode) {
    VoiceConversationMode.GPT -> configuredGptLiveProvider(preferredGptProviderId)?.let { provider ->
        VoiceStartRequest.Live(
            GptLiveSelection(provider, provider.models.first {
                it.isGptLiveModel(provider) &&
                    it.providerOverwrite == null
            })
        )
    }
    VoiceConversationMode.GEMINI -> configuredGeminiLiveProvider(preferredGeminiProviderId)?.let { provider ->
        VoiceStartRequest.Gemini(
            GeminiLiveSelection(provider, provider.models.first {
                it.isGeminiLiveModel(provider) &&
                    it.providerOverwrite == null
            })
        )
    }
    VoiceConversationMode.LEGACY -> VoiceStartRequest.Legacy
}
