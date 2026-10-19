package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMessageQueueTest {
    @Test
    fun `tool approval keeps live replies attached and preserves ordinary voice behavior`() {
        val queue = MessageQueue()
        val live = LiveChatTask("session")
        val ordinaryVoice = CompletableDeferred<String?>()
        queue.enqueue(listOf(UIMessagePart.Text("live")), reply = live.reply, liveTask = live)
        queue.enqueue(listOf(UIMessagePart.Text("voice")), reply = ordinaryVoice)
        queue.failReplyWaiters("approval needed")
        assertFalse(live.reply.isCompleted)
        assertTrue(ordinaryVoice.isCompleted)
        assertEquals(2, queue.state.value.messages.size)
    }

    @Test
    fun `owner cancellation removes only its queued tasks`() = runBlocking {
        val queue = MessageQueue()
        val old = LiveChatTask("old")
        val current = LiveChatTask("current")
        queue.enqueue(listOf(UIMessagePart.Text("old")), reply = old.reply, liveTask = old)
        queue.enqueue(listOf(UIMessagePart.Text("typed")))
        queue.enqueue(listOf(UIMessagePart.Text("current")), reply = current.reply, liveTask = current)
        queue.removeLiveTasks("old")
        assertNull(old.reply.await())
        assertFalse(current.reply.isCompleted)
        assertFalse(queue.state.value.paused)
        assertEquals(
            listOf("typed", "current"),
            queue.state.value.messages.map { (it.parts.single() as UIMessagePart.Text).text }
        )
    }

    @Test
    fun `paused live requests cannot run later without an attached session`() {
        val queue = MessageQueue()
        val live = LiveChatTask("session")
        queue.enqueue(listOf(UIMessagePart.Text("live")), reply = live.reply, liveTask = live)
        queue.enqueue(listOf(UIMessagePart.Text("typed")))
        queue.pause()
        assertTrue(live.reply.isCompleted)
        queue.resume()
        assertEquals("typed", (queue.takeNext()!!.parts.single() as UIMessagePart.Text).text)
        assertNull(queue.takeNext())
    }
}
