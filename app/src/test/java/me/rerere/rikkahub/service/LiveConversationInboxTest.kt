package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveConversationInboxTest {
    @Test
    fun `tool approval keeps live replies attached and preserves ordinary voice behavior`() {
        val queue = ConversationInbox()
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
        val queue = ConversationInbox()
        val old = LiveChatTask("old")
        val current = LiveChatTask("current")
        queue.enqueue(listOf(UIMessagePart.Text("old")), reply = old.reply, liveTask = old)
        queue.enqueue(listOf(UIMessagePart.Text("typed")))
        queue.enqueue(listOf(UIMessagePart.Text("current")), reply = current.reply, liveTask = current)
        val notice = UIMessagePart.Notice(title = "Command finished", text = "done")
        queue.post("background-command", notice)
        queue.removeLiveTasks("old")
        assertNull(old.reply.await())
        assertFalse(current.reply.isCompleted)
        assertFalse(queue.state.value.paused)
        assertEquals(
            listOf("typed", "current"),
            queue.state.value.messages.map { (it.parts.single() as UIMessagePart.Text).text }
        )
        assertEquals(listOf(notice), queue.takeNotices())
    }

    @Test
    fun `paused live requests cannot run later without an attached session`() {
        val queue = ConversationInbox()
        val live = LiveChatTask("session")
        queue.enqueue(listOf(UIMessagePart.Text("live")), reply = live.reply, liveTask = live)
        queue.enqueue(listOf(UIMessagePart.Text("typed")))
        val notice = UIMessagePart.Notice(title = "Command finished", text = "done")
        queue.post("background-command", notice)
        queue.pause()
        assertTrue(live.reply.isCompleted)
        queue.resume()
        assertEquals("typed", (queue.nextMessage()!!.parts.single() as UIMessagePart.Text).text)
        assertEquals(InboxTurn.Notices, queue.takeNext())
        assertEquals(listOf(notice), queue.takeNotices())
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing a live request retains its owner and reply across inbox dispatch`() {
        val inbox = ConversationInbox()
        val task = LiveChatTask("session")
        inbox.enqueue(listOf(UIMessagePart.Text("before")), reply = task.reply, liveTask = task)
        val id = inbox.state.value.messages.single().id
        inbox.beginEdit(id)
        assertNull(inbox.takeNext())
        inbox.finishEdit(id, listOf(UIMessagePart.Text("after")))
        val message = inbox.nextMessage()!!
        assertTrue(message.liveTask === task)
        assertTrue(message.reply === task.reply)
        assertEquals("after", (message.parts.single() as UIMessagePart.Text).text)
        assertFalse(task.reply.isCompleted)
    }
}
