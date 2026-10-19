package me.rerere.live

data class LiveHistoryMessage(val role: String, val text: String)

data class LiveSessionConfig(
    val apiKey: String,
    val endpoint: String = "wss://api.openai.com/v1/live/sessions",
    val model: String = "gpt-live-1",
    val voice: String = "marin",
    val instructions: String,
    val history: List<LiveHistoryMessage> = emptyList(),
) {
    // Configuration can appear in exception/debug output. Do not expose credentials or conversation text.
    override fun toString(): String = "LiveSessionConfig(apiKey=<redacted>)"
}

sealed interface LiveEvent {
    data class Started(val sessionId: String) : LiveEvent

    data class Audio(val bytes: ByteArray) : LiveEvent

    data class Transcript(
        val role: String,
        val delta: String,
        val eventId: String?,
        val startMs: Double,
        val endMs: Double,
    ) : LiveEvent

    data class Delegation(val id: String, val offsetMs: Double) : LiveEvent

    data class Usage(val seconds: Double) : LiveEvent

    /** A real session.closed event. Transport loss is reported as a fatal Error instead. */
    data class Closed(val reason: String, val seconds: Double?) : LiveEvent

    data class Error(
        val message: String,
        val fatal: Boolean,
        val clientEventId: String? = null,
    ) : LiveEvent
}

/** When unconfirmed, seconds is only the last usage snapshot, not final billed usage. */
data class LiveCloseResult(
    val confirmed: Boolean,
    val reason: String,
    val seconds: Double?,
)

class LiveClientException(message: String) : Exception(message)

/**
 * A conservative byte budget for the API's 500-token append limit, not an exact tokenizer.
 * Chunks preserve every character and never split a UTF-16 surrogate pair.
 */
fun splitLiveText(text: String, maxUtf8Bytes: Int = 450): List<String> {
    require(maxUtf8Bytes in 4..450) { "Live text chunks must use a byte budget between 4 and 450" }
    if (text.isEmpty()) return emptyList()

    val chunks = mutableListOf<String>()
    var start = 0
    var offset = 0
    var bytes = 0
    while (offset < text.length) {
        val next = offset + Character.charCount(text.codePointAt(offset))
        val size = text.substring(offset, next).toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxUtf8Bytes) {
            chunks += text.substring(start, offset)
            start = offset
            bytes = 0
        }
        bytes += size
        offset = next
    }
    chunks += text.substring(start)
    return chunks
}
