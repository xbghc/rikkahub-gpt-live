package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.live.LiveSpeaker
import me.rerere.live.LiveTranscript
import me.rerere.live.LiveTranscriptAccumulator

/** Convert a completed Live session to ordinary history without triggering text generation. */
internal class LiveVoiceTranscript {
    private val transcript = LiveTranscriptAccumulator()

    fun append(fragment: LiveTranscript) = transcript.append(fragment)

    fun messages(model: Model): List<UIMessage> = transcript.snapshot().map { segment ->
        val assistant = segment.speaker == LiveSpeaker.Assistant
        UIMessage(
            role = if (assistant) MessageRole.ASSISTANT else MessageRole.USER,
            parts = listOf(UIMessagePart.Text(segment.text.trim())),
            modelId = model.id.takeIf { assistant },
            modelSnapshot = if (assistant) ModelSnapshot(model.modelId, model.displayName) else null,
        )
    }
}
