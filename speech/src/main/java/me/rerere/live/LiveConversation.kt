package me.rerere.live

data class LiveSessionConfig(
    val url: String,
    val apiKey: String,
    val model: String,
    val instructions: String = "",
    val headers: Map<String, String> = emptyMap(),
    val context: List<String> = emptyList(),
    val backendEnabled: Boolean = false,
)

enum class LiveSpeaker { User, Assistant }

/** A timestamped transcript fragment, not a completed conversational turn. */
data class LiveTranscript(
    val speaker: LiveSpeaker,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val eventId: String? = null,
)

sealed interface LiveEvent {
    data object Ready : LiveEvent
    data class Transcript(val transcript: LiveTranscript) : LiveEvent
    data class Speaking(val speaking: Boolean) : LiveEvent
    data class Delegation(val id: String, val offsetMs: Double) : LiveEvent
    data class BackendPending(val count: Int) : LiveEvent
}

interface LiveConversation {
    /** Runs until the server closes or the caller cancels. Failures are thrown to the caller. */
    suspend fun run(onEvent: (LiveEvent) -> Unit)
}

interface DelegatingLiveConversation : LiveConversation {
    fun sendDelegationResult(id: String, text: String)
}
