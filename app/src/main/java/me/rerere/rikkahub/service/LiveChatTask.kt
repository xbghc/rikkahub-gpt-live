package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** In-memory ownership follows a Live request through queuing and tool approvals. */
class LiveChatTask(val owner: String) {
    val reply = CompletableDeferred<String?>()
    var previousMessageIds: Set<Uuid> = emptySet()
    var userMessageId: Uuid? = null
    var job: Job? = null
    internal var incarnation: Int? = null
    private val assistantMessageIds = linkedSetOf<Uuid>()

    @Volatile
    internal var isCancelled = false
        private set

    /** Record the message produced by this generation, never infer ownership from the chat tail. */
    @Synchronized
    internal fun recordAssistantMessage(messageId: Uuid) {
        assistantMessageIds.add(messageId)
    }

    @Synchronized
    internal fun isCurrent(conversation: Conversation, currentIncarnation: Int): Boolean {
        if (incarnation != currentIncarnation) return false
        val selectedIds = conversation.currentMessages.mapTo(HashSet()) { it.id }
        return userMessageId in selectedIds &&
            (assistantMessageIds.isEmpty() || assistantMessageIds.last() in selectedIds)
    }

    @Synchronized
    internal fun ownsToolApproval(conversation: Conversation, currentIncarnation: Int, toolCallId: String): Boolean =
        !isCancelled && isCurrent(conversation, currentIncarnation) &&
            conversation.currentMessages.any { message ->
                message.id in assistantMessageIds &&
                    message.getTools().any { it.toolCallId == toolCallId && it.isPending }
            }

    /** A delayed cancellation must not affect another branch, owner or restored conversation. */
    @Synchronized
    internal fun cancelPendingTools(conversation: Conversation, currentIncarnation: Int): Conversation? {
        if (incarnation != currentIncarnation || assistantMessageIds.isEmpty()) return null
        val nodes = conversation.messageNodes.map { node ->
            val messages = node.messages.map message@{ message ->
                if (message.id !in assistantMessageIds) return@message message
                message.finishPendingTools { tool ->
                    tool.copy(output = listOf(UIMessagePart.Text(
                        """{"status":"cancelled","error":"Live voice task cancelled before tool execution completed."}"""
                    )))
                }
            }
            if (messages == node.messages) node else node.copy(messages = messages)
        }
        return if (nodes == conversation.messageNodes) null else conversation.copy(messageNodes = nodes)
    }

    fun cancel() {
        isCancelled = true
        reply.complete(null)
        job?.cancel(LiveTaskCancellationException(this))
    }
}

/** Cancelling a Live task must not pause unrelated messages in the same chat queue. */
internal class LiveTaskCancellationException(
    val task: LiveChatTask
) : CancellationException("Live voice task cancelled")
