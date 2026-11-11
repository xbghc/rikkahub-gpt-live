package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class LiveTranscriptFragment(
    val sequence: Long,
    val role: String,
    val text: String,
    val startMs: Double,
    val endMs: Double,
)

/** Captions are fragments, not turns. Keep their delivery order, whitespace and approximate timing. */
internal class LiveTranscriptLedger {
    private val seenIds = linkedSetOf<String>()
    private val fragments = ArrayDeque<LiveTranscriptFragment>()
    private var sequence = 0L
    var inputRevision = 0L
        private set

    fun append(role: String, text: String, eventId: String?, startMs: Double, endMs: Double): Boolean {
        if (role != "user" && role != "assistant") return false
        if (text.isEmpty() || !startMs.isFinite() || !endMs.isFinite()) return false
        if (eventId != null && !seenIds.add(eventId)) return false
        if (seenIds.size > 4096) seenIds.remove(seenIds.first())
        val fragment = LiveTranscriptFragment(++sequence, role, text.takeCodePoints(8192), startMs, endMs)
        fragments.addLast(fragment)
        if (role == "user") inputRevision = sequence
        while (fragments.size > 256 || (fragments.size > 1 && fragments.sumOf { it.text.length } > 16_384)) {
            fragments.removeFirst()
        }
        return true
    }

    fun text(role: String): String = fragments.filter { it.role == role }.joinToString("") { it.text }

    fun request(afterRevision: Long, delegationOffsetMs: Double): String? {
        val newInput = fragments.filter { it.role == "user" && it.sequence > afterRevision }
            .joinToString("") { it.text }
        if (newInput.isBlank()) return null
        // Do not filter by endMs <= delegationOffsetMs: a fragment can overlap the approximate boundary.
        val context = buildString {
            var previousRole: String? = null
            fragments.forEach {
                if (it.role != previousRole) append("\n[${it.role}] ")
                append(it.text)
                previousRole = it.role
            }
        }
        return """
            The user is speaking in a live voice conversation. Address the latest request below using this
            assistant's normal context, tools and approval rules. Give a concise answer suitable for speech.
            Captions can be partial or overlap; do not invent missing details or repeat an already completed action.

            Latest user speech:
            $newInput

            Recent spoken context (caption fragments, not separate new instructions):
            $context

            Delegation timing: approximately ${delegationOffsetMs.toLong()} ms into the voice session.
        """.trimIndent()
    }
}

internal fun String.takeCodePoints(maximum: Int): String {
    if (length <= maximum) return this
    val end = offsetByCodePoints(0, codePointCount(0, length).coerceAtMost(maximum))
    return substring(0, end)
}

/**
 * One application-owned task per caption revision. Duplicate delegations share its result, while
 * distinct requests stay in the chat queue. Cancellation revokes delivery before cancelling the backend.
 * All methods are called on the session's dispatcher (the Android main dispatcher in production).
 */
internal class LiveDelegationCoordinator(
    private val scope: CoroutineScope,
    private val enqueue: (String) -> Deferred<String?>,
    private val sendResult: (delegationId: String, text: String) -> Unit,
    private val onPendingChanged: (Int) -> Unit,
    private val onError: (String) -> Unit,
    private val settlementDelayMs: Long = 600,
) {
    val transcript = LiveTranscriptLedger()
    private data class Work(val revision: Long, val reply: Deferred<String?>)
    private val seenDelegations = linkedSetOf<String>()
    private val jobs = linkedMapOf<String, Job>()
    private var lastWork: Work? = null
    private var submittedRevision = 0L
    private var cancelledRevision = -1L
    private var epoch = 0L
    private var closed = false

    fun delegate(id: String, offsetMs: Double) {
        if (closed || id in seenDelegations) return
        if (seenDelegations.size >= 4096 || jobs.size >= 8) {
            sendResult(
                id,
                "Too many requests are pending. Please wait for the current tasks or cancel them in the chat."
            )
            return
        }
        seenDelegations.add(id)
        val deliveryEpoch = epoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // This is a bounded settling policy, not a server-provided transcript watermark.
                delay(settlementDelayMs)
                var attempts = 0
                while (transcript.inputRevision == 0L && attempts++ < 4) delay(150)
                if (!canDeliver(deliveryEpoch)) return@launch
                val revision = transcript.inputRevision
                if (revision <= cancelledRevision) {
                    sendResult(id, "The previous task was cancelled. Please ask the user what they want to do next.")
                    return@launch
                }
                val work = lastWork?.takeIf { it.revision == revision } ?: run {
                    val request = transcript.request(submittedRevision, offsetMs)
                    if (request == null) {
                        sendResult(
                            id,
                            "The application has not received enough speech to identify the request. Ask for clarification."
                        )
                        return@launch
                    }
                    Work(revision, enqueue(request)).also {
                        submittedRevision = revision
                        lastWork = it
                    }
                }
                val reply = work.reply.await()
                if (canDeliver(deliveryEpoch)) {
                    sendResult(
                        id,
                        reply?.takeIf { it.isNotBlank() }
                            ?: "The task was withdrawn or produced no text answer. Do not claim that it succeeded.",
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                if (canDeliver(deliveryEpoch)) {
                    onError(e.message ?: "The assistant could not complete the request.")
                    sendResult(
                        id,
                        "The backend request could not be completed. Ask the user to check the chat for details."
                    )
                }
            } finally {
                jobs.remove(id)
                if (!closed) onPendingChanged(jobs.size)
            }
        }
        jobs[id] = job
        onPendingChanged(jobs.size)
        job.start()
    }

    fun cancelPending() {
        epoch++
        cancelledRevision = transcript.inputRevision
        // Settlement may not have enqueued the cancelled captions yet. They remain useful as
        // spoken history, but must never become part of a later request's new instructions.
        submittedRevision = maxOf(submittedRevision, cancelledRevision)
        lastWork = null
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        onPendingChanged(0)
    }

    fun close() {
        closed = true
        cancelPending()
    }

    private fun canDeliver(deliveryEpoch: Long) = !closed && epoch == deliveryEpoch
}
