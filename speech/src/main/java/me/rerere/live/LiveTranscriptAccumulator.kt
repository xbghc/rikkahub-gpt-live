package me.rerere.live

/** Live has no turn IDs or final-transcript events. Keep overlapping speakers independent. */
class LiveTranscriptAccumulator {
    private val segments = mutableListOf<LiveTranscript>()

    @Synchronized
    fun append(fragment: LiveTranscript) {
        if (fragment.text.isEmpty()) return
        val index = segments.indexOfLast { it.speaker == fragment.speaker }
        val previous = segments.getOrNull(index)
        val interveningSpeech = previous != null && segments.any {
            it.speaker != fragment.speaker && it.startMs >= previous.endMs && it.endMs <= fragment.startMs
        }
        if (previous != null && !interveningSpeech &&
            fragment.startMs >= previous.startMs && fragment.startMs - previous.endMs <= 1_500
        ) {
            segments[index] = previous.copy(
                text = previous.text + fragment.text,
                endMs = maxOf(previous.endMs, fragment.endMs),
            )
        } else {
            segments.add(fragment)
        }
    }

    @Synchronized
    fun snapshot(): List<LiveTranscript> = segments.sortedBy { it.startMs }.filter { it.text.isNotBlank() }
}
