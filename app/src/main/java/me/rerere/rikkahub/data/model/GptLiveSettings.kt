package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/** Native GPT Live endpoints. AI Gateway's /v4 normalized Live protocol is not compatible. */
enum class GptLiveEndpointPreset(val endpoint: String, val model: String) {
    OpenAI("wss://api.openai.com/v1/live/sessions", "gpt-live-1"),
    VercelAiGateway("wss://ai-gateway.vercel.sh/v1/live/sessions", "openai/gpt-live-1"),
}

@Serializable
data class GptLiveSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val endpoint: String = GptLiveEndpointPreset.OpenAI.endpoint,
    val model: String = GptLiveEndpointPreset.OpenAI.model,
    val voice: String = "marin",
    val instructions: String = "Speak naturally in the user's language and keep spoken responses concise. " +
        "Delegate substantive questions and tasks to the client assistant. " +
        "Briefly acknowledge while waiting, then convey the returned result without inventing details.",
) {
    /** Recognize existing saved endpoint settings without a data migration. */
    fun endpointPreset(): GptLiveEndpointPreset? =
        GptLiveEndpointPreset.entries.firstOrNull { it.endpoint == endpoint.trim() }

    /** Changing providers clears the previous provider's API key. Custom endpoints remain editable. */
    fun withEndpointPreset(preset: GptLiveEndpointPreset): GptLiveSettings =
        copy(
            apiKey = if (endpoint.trim() == preset.endpoint) apiKey else "",
            endpoint = preset.endpoint,
            model = preset.model,
        )

    override fun toString(): String =
        "GptLiveSettings(enabled=$enabled, apiKey=<redacted>, model=$model, voice=$voice)"
}
