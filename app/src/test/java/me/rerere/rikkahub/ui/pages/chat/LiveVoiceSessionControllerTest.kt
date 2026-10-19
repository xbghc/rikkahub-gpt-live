package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.live.LiveConversation
import me.rerere.live.LiveEvent
import me.rerere.live.LiveSpeaker
import me.rerere.live.LiveTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceSessionControllerTest {
    private class FakeLive : LiveConversation {
        lateinit var emit: (LiveEvent) -> Unit
        var released = false
        var failure: String? = null

        override suspend fun run(onEvent: (LiveEvent) -> Unit) {
            emit = onEvent
            try {
                onEvent(LiveEvent.Ready)
                failure?.let { error(it) }
                awaitCancellation()
            } finally {
                released = true
                onEvent(LiveEvent.Transcript(LiveTranscript(LiveSpeaker.User, "final", 0, 100)))
            }
        }
    }

    @Test
    fun `live audio bypasses queued text generation and releases on stop`() = runTest {
        val live = FakeLive()
        val transcripts = mutableListOf<String>()
        val voice = VoiceSessionController(backgroundScope, Int::toString) { error("Must not enqueue text") }
        voice.startLive({ live }, {}) { session, onEvent ->
            session.run { event ->
                if (event is LiveEvent.Transcript) transcripts.add(event.transcript.text)
                onEvent(event)
            }
        }
        assertTrue(voice.state.value.isRealtime)
        advanceTimeBy(201)
        runCurrent()
        assertEquals(VoicePhase.Listening, voice.state.value.phase)
        live.emit(LiveEvent.Speaking(true))
        assertEquals(VoicePhase.Speaking, voice.state.value.phase)
        live.emit(LiveEvent.Transcript(LiveTranscript(LiveSpeaker.User, "interrupt", 0, 100)))
        assertEquals("你：interrupt", voice.state.value.transcript)
        voice.stop()
        runCurrent()
        assertTrue(live.released)
        assertFalse(voice.state.value.isActive)
        assertEquals(VoicePhase.Off, voice.state.value.phase)
        assertEquals(listOf("interrupt", "final"), transcripts)
    }

    @Test
    fun `live connection errors allow retry without a second active session`() = runTest {
        val failed = FakeLive().apply { failure = "Gateway rejected the key" }
        val voice = VoiceSessionController(backgroundScope, Int::toString) { error("Must not enqueue text") }
        voice.startLive({ failed }, {}) { live, emit -> live.run(emit) }
        advanceTimeBy(201)
        runCurrent()
        assertEquals(VoicePhase.Error, voice.state.value.phase)
        assertEquals("Gateway rejected the key", voice.state.value.error)
        assertTrue(failed.released)

        val retry = FakeLive()
        voice.startLive({ retry }, {}) { live, emit -> live.run(emit) }
        voice.startLive({ error("Duplicate connection") }, {}) { live, emit -> live.run(emit) }
        advanceTimeBy(201)
        runCurrent()
        assertEquals(VoicePhase.Listening, voice.state.value.phase)
        voice.stop()
        runCurrent()
        assertTrue(retry.released)
    }
}
