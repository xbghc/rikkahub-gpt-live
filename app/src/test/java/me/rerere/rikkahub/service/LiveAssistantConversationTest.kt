package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.live.DelegatingLiveConversation
import me.rerere.live.LiveEvent
import me.rerere.live.LiveSpeaker
import me.rerere.live.LiveTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAssistantConversationTest {
    private class FakeLive : DelegatingLiveConversation {
        lateinit var emit: (LiveEvent) -> Unit
        val replies = mutableListOf<Pair<String, String>>()
        var released = false

        override suspend fun run(onEvent: (LiveEvent) -> Unit) {
            emit = onEvent
            try {
                onEvent(LiveEvent.Ready)
                awaitCancellation()
            } finally {
                released = true
                onEvent(LiveEvent.Transcript(LiveTranscript(LiveSpeaker.User, "final caption", 100, 200)))
            }
        }

        override fun sendDelegationResult(id: String, text: String) {
            replies.add(id to text)
        }
    }

    @Test
    fun `captions alone never generate and duplicate delegations share one assistant task`() = runTest {
        val live = FakeLive()
        val requests = mutableListOf<String>()
        val reply = CompletableDeferred<String?>()
        val events = mutableListOf<LiveEvent>()
        var cancelled = false
        val session = LiveAssistantConversation(live, {
            requests.add(it)
            reply
        }, { cancelled = true })
        val job = backgroundScope.launch { session.run(events::add) }
        runCurrent()
        live.emit(LiveEvent.Transcript(LiveTranscript(LiveSpeaker.User, "查明天天气", 0, 100, "caption-1")))
        live.emit(LiveEvent.Transcript(LiveTranscript(LiveSpeaker.User, "查明天天气", 0, 100, "caption-1")))
        runCurrent()
        assertTrue(requests.isEmpty())
        live.emit(LiveEvent.Delegation("d1", 100.0))
        live.emit(LiveEvent.Delegation("d1", 100.0))
        live.emit(LiveEvent.Delegation("d2", 100.0))
        advanceTimeBy(601)
        runCurrent()
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("查明天天气"))
        assertTrue(!requests.single().contains("查明天天气查明天天气"))
        // An unresolved assistant reply can represent an outstanding tool approval.
        assertTrue(live.replies.isEmpty())
        live.emit(LiveEvent.Speaking(true))
        assertTrue(events.contains(LiveEvent.Speaking(true)))
        reply.complete("明天有雨。")
        runCurrent()
        assertEquals(listOf("d1" to "明天有雨。", "d2" to "明天有雨。"), live.replies)
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(live.released)
        assertTrue(events.any { it is LiveEvent.Transcript && it.transcript.text == "final caption" })
    }

    @Test
    fun `stopping voice revokes a pending backend result and cancels its owner`() = runTest {
        val live = FakeLive()
        val reply = CompletableDeferred<String?>()
        var cancelled = false
        val session = LiveAssistantConversation(live, { reply }, { cancelled = true })
        val job = backgroundScope.launch { session.run { } }
        runCurrent()
        live.emit(LiveEvent.Transcript(LiveTranscript(LiveSpeaker.User, "任务", 0, 100)))
        live.emit(LiveEvent.Delegation("d1", 100.0))
        advanceTimeBy(601)
        runCurrent()
        job.cancelAndJoin()
        reply.complete("迟到结果")
        runCurrent()
        assertTrue(cancelled)
        assertTrue(live.replies.isEmpty())
    }

    @Test
    fun `backend failures return a neutral message while the voice session stays connected`() = runTest {
        val live = FakeLive()
        val reply = CompletableDeferred<String?>()
        val session = LiveAssistantConversation(live, { reply }, {})
        val job = backgroundScope.launch { session.run { } }
        runCurrent()
        live.emit(LiveEvent.Transcript(LiveTranscript(LiveSpeaker.User, "任务", 0, 100)))
        live.emit(LiveEvent.Delegation("d1", 100.0))
        advanceTimeBy(601)
        runCurrent()
        reply.completeExceptionally(IllegalStateException("private-provider-detail"))
        runCurrent()
        assertTrue(job.isActive)
        assertEquals("d1", live.replies.single().first)
        assertTrue(live.replies.single().second.contains("could not be completed"))
        assertTrue(!live.replies.single().second.contains("private-provider-detail"))
        job.cancelAndJoin()
    }
}
