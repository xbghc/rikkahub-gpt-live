package me.rerere.rikkahub.service

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.live.DelegatingLiveConversation
import me.rerere.live.LiveConversation
import me.rerere.live.LiveEvent
import me.rerere.live.LiveSpeaker

/** Serializes caption/delegation callbacks while audio and the normal assistant queue keep running. */
internal class LiveAssistantConversation(
    private val live: DelegatingLiveConversation,
    private val enqueue: (String) -> Deferred<String?>,
    private val cancelBackend: suspend () -> Unit,
) : LiveConversation {
    override suspend fun run(onEvent: (LiveEvent) -> Unit) = coroutineScope {
        val events = Channel<LiveEvent>(256)
        val coordinator = LiveDelegationCoordinator(
            scope = this,
            enqueue = enqueue,
            sendResult = live::sendDelegationResult,
            onPendingChanged = { onEvent(LiveEvent.BackendPending(it)) },
            // The ordinary chat error UI owns backend details; do not expose credentials to Live.
            onError = {},
        )
        val transport = launch {
            try {
                live.run { event ->
                    // Persist even the final captions delivered during transport cancellation.
                    onEvent(event)
                    if (event is LiveEvent.Transcript || event is LiveEvent.Delegation) {
                        check(events.trySend(event).isSuccess) { "Live assistant event buffer is full." }
                    }
                }
            } finally {
                events.close()
            }
        }
        try {
            for (event in events) {
                when (event) {
                    is LiveEvent.Transcript -> event.transcript.let { fragment ->
                        coordinator.transcript.append(
                            role = if (fragment.speaker == LiveSpeaker.User) "user" else "assistant",
                            text = fragment.text,
                            eventId = fragment.eventId,
                            startMs = fragment.startMs.toDouble(),
                            endMs = fragment.endMs.toDouble(),
                        )
                    }
                    is LiveEvent.Delegation -> coordinator.delegate(event.id, event.offsetMs)
                    else -> Unit
                }
            }
        } finally {
            coordinator.close()
            transport.cancel()
            withContext(NonCancellable) {
                // Revoke and stop owned tasks before saving the final spoken transcript.
                cancelBackend()
                transport.join()
            }
        }
    }
}
