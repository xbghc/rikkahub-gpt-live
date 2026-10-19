package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class GptLiveSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val endpoint: String = "wss://api.openai.com/v1/live/sessions",
    val model: String = "gpt-live-1",
    val voice: String = "marin",
    val instructions: String = "Speak naturally in the user's language and keep spoken responses concise. " +
        "Delegate substantive questions and tasks to the client assistant. " +
        "Briefly acknowledge while waiting, then convey the returned result without inventing details.",
) {
    override fun toString(): String =
        "GptLiveSettings(enabled=$enabled, apiKey=<redacted>, model=$model, voice=$voice)"
}
