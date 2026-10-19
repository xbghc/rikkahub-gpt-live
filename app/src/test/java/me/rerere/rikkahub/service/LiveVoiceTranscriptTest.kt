package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.live.LiveSpeaker
import me.rerere.live.LiveTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceTranscriptTest {
    private val model = Model(modelId = "openai/gpt-live-1", displayName = "GPT Live")

    @Test
    fun `overlapping speech keeps each speakers fragments together`() {
        val transcript = LiveVoiceTranscript()
        transcript.append(LiveTranscript(LiveSpeaker.User, "你好", 0, 400))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "你好，", 300, 700))
        transcript.append(LiveTranscript(LiveSpeaker.User, "，请说中文。", 400, 900))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "可以的。", 700, 1200))

        val messages = transcript.messages(model)
        assertEquals(listOf("你好，请说中文。", "你好，可以的。"), messages.map { it.toText() })
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
        assertNull(messages[0].modelId)
        assertEquals(model.id, messages[1].modelId)
        assertEquals(model.modelId, messages[1].modelSnapshot?.modelId)
    }

    @Test
    fun `a later utterance becomes a new message and silence is discarded`() {
        val transcript = LiveVoiceTranscript()
        transcript.append(LiveTranscript(LiveSpeaker.User, "第一句", 100, 600))
        transcript.append(LiveTranscript(LiveSpeaker.User, "第二句", 3000, 4000))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, " ", 4500, 4500))
        assertEquals(listOf("第一句", "第二句"), transcript.messages(model).map { it.toText() })
        assertTrue(LiveVoiceTranscript().messages(model).isEmpty())
    }

    @Test
    fun `short back and forth replies remain separate messages`() {
        val transcript = LiveVoiceTranscript()
        transcript.append(LiveTranscript(LiveSpeaker.User, "你好", 0, 500))
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "你好", 500, 1000))
        transcript.append(LiveTranscript(LiveSpeaker.User, "谢谢", 1000, 1500))
        assertEquals(listOf("你好", "你好", "谢谢"), transcript.messages(model).map { it.toText() })
    }

    @Test
    fun `transcript delivery order does not reorder the conversation timeline`() {
        val transcript = LiveVoiceTranscript()
        transcript.append(LiveTranscript(LiveSpeaker.Assistant, "回答", 2000, 2500))
        transcript.append(LiveTranscript(LiveSpeaker.User, "问题", 100, 500))
        assertEquals(listOf("问题", "回答"), transcript.messages(model).map { it.toText() })
    }
}
