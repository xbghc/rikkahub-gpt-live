package me.rerere.live

data class LiveSessionConfig(
    val url: String,
    val apiKey: String,
    val model: String,
    val instructions: String = "",
    val headers: Map<String, String> = emptyMap(),
    val context: List<String> = emptyList(),
)

enum class LiveSpeaker { User, Assistant }

/** A timestamped transcript fragment, not a completed conversational turn. */
data class LiveTranscript(
    val speaker: LiveSpeaker,
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

sealed interface LiveEvent {
    data object Ready : LiveEvent
    data class Transcript(val transcript: LiveTranscript) : LiveEvent
    data class Speaking(val speaking: Boolean) : LiveEvent
}

interface LiveConversation {
    /** Runs until the server closes or the caller cancels. Failures are thrown to the caller. */
    suspend fun run(onEvent: (LiveEvent) -> Unit)
}
