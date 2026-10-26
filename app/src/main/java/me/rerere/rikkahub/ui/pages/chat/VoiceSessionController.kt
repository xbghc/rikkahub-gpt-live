package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.asr.ASRController
import me.rerere.asr.ASRStatus
import me.rerere.live.LiveConversation
import me.rerere.live.LiveEvent
import me.rerere.live.LiveSpeaker
import me.rerere.live.LiveTranscript
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueuePausedException

enum class VoicePhase { Off, Connecting, Listening, Transcribing, Waiting, Speaking, Stopping, Error }

data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Off,
    val transcript: String = "",
    val error: String? = null,
    val pendingReplies: Int = 0,
    val isRealtime: Boolean = false,
    val realtimeModelName: String = "GPT Live",
) {
    val isActive: Boolean get() = phase != VoicePhase.Off && phase != VoicePhase.Error
}

/** Capture and generation run independently. Only TTS owns an exclusive microphone pause. */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val getString: (Int) -> String,
    private val enqueueMessage: (String) -> Deferred<String?>,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    private sealed interface Event {
        data class Utterance(val text: String) : Event
        data class Reply(val text: String?) : Event
        data class Failed(val error: Exception) : Event
    }

    /** Live owns audio; explicit delegations use the assistant queue without ordinary TTS. */
    fun startLive(
        createSession: suspend () -> LiveConversation,
        stopSpeaking: () -> Unit,
        assistantLabel: String = "GPT Live",
        runSession: suspend (LiveConversation, (LiveEvent) -> Unit) -> Unit,
    ) {
        if (job?.isCompleted == false) return
        mutableState.value = VoiceSessionState(
            VoicePhase.Connecting,
            isRealtime = true,
            realtimeModelName = assistantLabel,
        )
        job = scope.launch {
            val sessionJob = currentCoroutineContext()[Job]!!
            val captions = mutableMapOf<LiveSpeaker, LiveTranscript>()
            fun updateActive(transform: (VoiceSessionState) -> VoiceSessionState) {
                mutableState.update { if (sessionJob.isActive) transform(it) else it }
            }
            try {
                stopSpeaking()
                delay(200)
                runSession(createSession()) { event ->
                    // The transport may deliver its final transcript while closing after cancellation.
                    // Persistence still receives it, but it must not reopen the voice UI.
                    if (sessionJob.isActive) {
                        when (event) {
                            is LiveEvent.BackendPending -> updateActive { it.copy(pendingReplies = event.count) }
                            is LiveEvent.Delegation -> Unit
                            LiveEvent.Ready -> updateActive { it.copy(phase = VoicePhase.Listening) }
                            is LiveEvent.Speaking -> updateActive {
                                it.copy(
                                    phase = if (event.speaking) VoicePhase.Speaking else VoicePhase.Listening,
                                )
                            }
                            is LiveEvent.Transcript -> {
                                val fragment = event.transcript
                                val caption = synchronized(captions) {
                                    val previous = captions[fragment.speaker]
                                    val text = if (previous != null && fragment.startMs - previous.endMs <= 1_500) {
                                        previous.text + fragment.text
                                    } else {
                                        fragment.text
                                    }
                                    captions[fragment.speaker] = fragment.copy(text = text.takeLast(500))
                                    val label = if (fragment.speaker == LiveSpeaker.User) "你" else assistantLabel
                                    "$label：${text.takeLast(500)}"
                                }
                                updateActive { it.copy(transcript = caption) }
                            }
                        }
                    }
                }
                mutableState.value = VoiceSessionState()
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                mutableState.update {
                    it.copy(phase = VoicePhase.Error, error = e.message ?: getString(R.string.chat_page_voice_failed))
                }
            } finally {
                stopSpeaking()
                if (!sessionJob.isActive) mutableState.value = VoiceSessionState()
            }
        }
    }

    fun start(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
        listenWhileReplying: Boolean = true,
    ) {
        if (job?.isCompleted == false) return
        mutableState.value = VoiceSessionState(VoicePhase.Connecting)
        job = scope.launch {
            try {
                stopSpeaking()
                delay(200)
                runSession(createAsr, speak, listenWhileReplying)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                mutableState.update {
                    it.copy(
                        phase = VoicePhase.Error,
                        error = when (e) {
                            is TimeoutCancellationException -> "Speech recognition timed out. Restart voice mode."
                            is MessageQueuePausedException -> getString(R.string.chat_page_voice_queue_paused)
                            else -> e.message ?: getString(R.string.chat_page_voice_failed)
                        },
                    )
                }
            } finally {
                stopSpeaking()
            }
        }
    }

    private suspend fun runSession(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        listenWhileReplying: Boolean,
    ) = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        val submitted = Channel<Deferred<String?>>(Channel.UNLIMITED)
        val replies = ArrayDeque<String>()
        var asr: ASRController? = null
        var capture: Job? = null

        // Await replies in submission order, without blocking capture. Queue removal returns null.
        launch {
            try {
                for (reply in submitted) events.send(Event.Reply(reply.await()))
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) throw e
                events.send(Event.Failed(e))
            }
        }

        try {
            while (isActive) {
                // Finish any sentence already in progress before giving TTS the microphone pause.
                if (speak != null && replies.isNotEmpty() && asr?.state?.value?.voiceTurn?.itemId == null) {
                    capture?.cancelAndJoin()
                    capture = null
                    asr = null
                    mutableState.update { it.copy(phase = VoicePhase.Speaking) }
                    speak(replies.removeFirst())
                    delay(300) // Let the loudspeaker's tail decay before opening the microphone.
                    continue
                }
                // Turn-based mode keeps the microphone closed until every reply has arrived and been spoken.
                if (capture == null && !listenWhileReplying && state.value.pendingReplies > 0) {
                    mutableState.update { it.copy(phase = VoicePhase.Waiting) }
                } else if (capture == null) {
                    mutableState.update { it.copy(phase = VoicePhase.Connecting, transcript = "") }
                    val recorder = createAsr()
                    asr = recorder
                    capture = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            events.send(Event.Utterance(listen(recorder)))
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            events.send(Event.Failed(e))
                        }
                    }
                }
                when (val event = events.receive()) {
                    is Event.Utterance -> {
                        capture?.join()
                        capture = null
                        asr = null
                        if (event.text.isNotBlank()) {
                            val reply = enqueueMessage(event.text)
                            mutableState.update {
                                it.copy(transcript = event.text, pendingReplies =
                                    it.pendingReplies + 1)
                            }
                            submitted.send(reply)
                        }
                    }
                    is Event.Reply -> {
                        mutableState.update { it.copy(pendingReplies = (it.pendingReplies - 1).coerceAtLeast(0)) }
                        event.text?.takeIf { speak != null && it.isNotBlank() }?.let { replies.addLast(it) }
                    }
                    is Event.Failed -> throw event.error
                }
            }
        } finally {
            capture?.cancel()
            // Submitted messages belong to the chat queue; leaving voice mode only detaches observers.
            submitted.close()
        }
    }

    private suspend fun listen(asr: ASRController): String {
        try {
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also {
                    check(
                        it.status == ASRStatus.Listening || it.voiceTurn.isComplete
                    ) { "Unable to start speech recognition" }
                }
            }
            val ended = withTimeout(120_000) {
                asr.state.onEach {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(
                        it.status == ASRStatus.Listening || it.voiceTurn.isComplete
                    ) { "Speech recognition disconnected" }
                    mutableState.update { current ->
                        current.copy(
                            phase = VoicePhase.Listening,
                            transcript = it.transcript
                        )
                    }
                }.first { it.voiceTurn.speechEnded }
            }
            asr.pauseCapture()
            mutableState.update { it.copy(phase = VoicePhase.Transcribing, transcript = ended.transcript) }
            return withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.voiceTurn.isComplete || it.status == ASRStatus.Listening) {
                        "Speech recognition disconnected before the final transcript was received"
                    }
                    it.voiceTurn.isComplete
                }.voiceTurn.finalText.orEmpty()
            }
        } finally {
            asr.dispose()
        }
    }

    fun stop() {
        val activeJob = job
        val closingLive = state.value.isRealtime && activeJob?.isCompleted == false
        mutableState.value = if (closingLive) {
            state.value.copy(phase = VoicePhase.Stopping)
        } else {
            VoiceSessionState()
        }
        if (closingLive) {
            activeJob?.invokeOnCompletion {
                mutableState.update {
                    if (job === activeJob && it.phase == VoicePhase.Stopping) VoiceSessionState() else it
                }
            }
        }
        activeJob?.cancel()
    }
}
