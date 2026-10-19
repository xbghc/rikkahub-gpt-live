package me.rerere.rikkahub.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.live.LiveCloseResult
import me.rerere.live.LiveEvent
import me.rerere.live.LiveHistoryMessage
import me.rerere.live.LiveSessionConfig
import me.rerere.live.OpenAILiveClient
import me.rerere.live.splitLiveText
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.GptLiveSettings
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.isAwaitingToolApproval
import okhttp3.OkHttpClient
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

enum class LiveVoicePhase { Off, Connecting, Active, Closing, Error }

data class LiveVoiceState(
    val sessionToken: String? = null,
    val conversationId: Uuid? = null,
    val phase: LiveVoicePhase = LiveVoicePhase.Off,
    val muted: Boolean = false,
    val microphoneChanging: Boolean = false,
    val playing: Boolean = false,
    val pendingDelegations: Int = 0,
    val awaitingApproval: Boolean = false,
    val inputTranscript: String = "",
    val outputTranscript: String = "",
    val elapsedSeconds: Double = 0.0,
    val closeConfirmed: Boolean = false,
    val usageConfirmed: Boolean = false,
    val closeReason: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = phase == LiveVoicePhase.Connecting || phase == LiveVoicePhase.Active || phase == LiveVoicePhase.Closing
}

/** Application lifetime, never Activity/ViewModel lifetime. A new connection always has a new owner token. */
class GptLiveSessionManager(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val httpClient: OkHttpClient,
) {
    private class Session(val token: String, val conversationId: Uuid) {
        val foregroundReady = CompletableDeferred<Unit>()
        lateinit var job: Job
        var scope: CoroutineScope? = null
        var client: OpenAILiveClient? = null
        var audio: GptLiveAudio? = null
        var coordinator: LiveDelegationCoordinator? = null
        var stopping = false
        var failure: String? = null
        var apiKey = ""
    }

    private val mutableState = MutableStateFlow(LiveVoiceState())
    val state = mutableState.asStateFlow()
    private var active: Session? = null

    fun start(conversationId: Uuid) {
        appScope.launch {
            if (active != null) return@launch
            val session = Session(Uuid.random().toString(), conversationId)
            active = session
            mutableState.value = LiveVoiceState(
                sessionToken = session.token,
                conversationId = conversationId,
                phase = LiveVoicePhase.Connecting,
            )
            // Enter the try/finally before returning to the event loop. Cancelling a not-yet-entered
            // lazy coroutine would otherwise skip cleanup and leave the foreground service owned.
            session.job = appScope.launch(start = CoroutineStart.UNDISPATCHED) { runSession(session) }
            // A microphone FGS must be requested from the visible user action. The actual audio
            // starts only after the service confirms startForeground(), including on Android 15+.
            if (active === session && !session.stopping &&
                !GptLiveForegroundService.acquire(context, session.token, conversationId)
            ) {
                session.foregroundReady.completeExceptionally(
                    IllegalStateException("Could not start the voice service.")
                )
            }
        }
    }

    fun stop(expectedSessionToken: String? = null) {
        appScope.launch {
            val session = matching(expectedSessionToken) ?: return@launch
            beginClosing(session)
        }
    }

    fun toggleMuted(expectedSessionToken: String? = null) {
        appScope.launch {
            val session = matching(expectedSessionToken) ?: return@launch
            if (state.value.phase != LiveVoicePhase.Active || state.value.microphoneChanging) return@launch
            val muted = !state.value.muted
            mutableState.update { it.copy(microphoneChanging = true) }
            session.scope?.launch {
                try {
                    // Close local capture before muting remotely; confirm remote unmute before reopening capture.
                    if (muted) session.audio?.setMuted(true)
                    checkNotNull(session.client).setInputMuted(muted)
                    if (!muted) session.audio?.setMuted(false)
                    if (active === session && !session.stopping) {
                        mutableState.update { it.copy(muted = muted, microphoneChanging = false) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(session, e.message ?: "Unable to change the microphone state.")
                }
            }
        }
    }

    fun cancelPendingDelegations(expectedSessionToken: String? = null) {
        appScope.launch {
            val session = matching(expectedSessionToken) ?: return@launch
            if (session.stopping) return@launch
            session.coordinator?.cancelPending()
            // Revoke delivery first. Backend cancellation does not stop the independent Live speaker.
            session.client?.appendThinking(
                null,
                "The user cancelled pending backend tasks. Do not describe them as successful."
            )
            chatService.cancelLiveMessages(session.conversationId, session.token)
        }
    }

    fun onForegroundReady(token: String) {
        appScope.launch { matching(token)?.foregroundReady?.complete(Unit) }
    }

    fun onForegroundFailure(token: String, message: String) {
        appScope.launch { matching(token)?.let { fail(it, message) } }
    }

    fun onForegroundDestroyed(token: String) {
        appScope.launch {
            matching(token)?.takeUnless { it.stopping }?.let { fail(it, "The Android voice service stopped.") }
        }
    }

    private suspend fun runSession(session: Session) = coroutineScope {
        session.scope = this
        var acquiredConversation = false
        var closeResult: LiveCloseResult? = null
        try {
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                "Microphone permission is required for GPT Live."
            }
            val settings = settingsStore.awaitLoaded()
            val config = settings.gptLive
            check(config.enabled) { "Enable GPT Live in Speech settings first." }
            check(config.apiKey.isNotBlank()) { "Add your OpenAI Live API key in Speech settings first." }
            session.apiKey = config.apiKey
            chatService.addConversationReference(session.conversationId)
            acquiredConversation = true
            chatService.initializeConversation(session.conversationId)
            val conversation = chatService.getConversationFlow(session.conversationId).value
            check(
                settings.getChatModelOf(conversation) != null
            ) { "Select a chat model for the current assistant first." }
            val queue = chatService.getMessageQueueFlow(session.conversationId).value
            check(!queue.paused || queue.messages.isEmpty()) { "Resume the chat queue before starting GPT Live." }
            val incarnation = chatService.getConversationIncarnation(session.conversationId)
            withTimeout(8_000) { session.foregroundReady.await() }

            val client = OpenAILiveClient(httpClient)
            session.client = client
            val coordinator = LiveDelegationCoordinator(
                scope = this,
                enqueue = { chatService.enqueueLiveMessage(session.conversationId, it, session.token) },
                sendResult = { id, text ->
                    if (active === session && !session.stopping) {
                        val chunks = splitLiveText(text)
                        val spoken = if (chunks.size > 16) {
                            chunks.take(16).joinToString("") + "\nThe full answer is available in the chat."
                        } else {
                            text
                        }
                        if (!client.appendCommentary(
                                id,
                                spoken
                            )) {
                            fail(session, "Could not return the assistant's result to GPT Live.")
                        }
                    }
                },
                onPendingChanged = { count ->
                    if (active === session &&
                        !session.stopping) {
                        mutableState.update { it.copy(pendingDelegations = count) }
                    }
                },
                onError = { message ->
                    if (active === session &&
                        !session.stopping) {
                        mutableState.update { it.copy(error = redact(session, message)) }
                    }
                },
            )
            session.coordinator = coordinator
            val audio = GptLiveAudio(
                context = context,
                scope = this,
                onAudio = { bytes ->
                    if (!client.appendAudio(
                            bytes
                        )) {
                        appScope.launch { fail(session, "The Live audio connection stopped accepting input.") }
                    }
                },
                onError = { message -> appScope.launch { fail(session, message) } },
                onPlayingChanged = { playing ->
                    appScope.launch {
                        if (active === session && !session.stopping) mutableState.update { it.copy(playing = playing) }
                    }
                },
            )
            session.audio = audio
            launchSessionTask(session, start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { event ->
                    if (session.stopping) return@collect
                    when (event) {
                        is LiveEvent.Audio -> if (!audio.enqueue(
                                event.bytes
                            )) {
                            fail(session, "Live audio playback could not keep up.")
                        }
                        is LiveEvent.Transcript -> {
                            if (coordinator.transcript.append(
                                    event.role,
                                    event.delta,
                                    event.eventId,
                                    event.startMs,
                                    event.endMs
                                )) {
                                mutableState.update {
                                    it.copy(
                                        inputTranscript = coordinator.transcript.text("user"),
                                        outputTranscript = coordinator.transcript.text("assistant"),
                                    )
                                }
                            }
                        }
                        is LiveEvent.Delegation -> coordinator.delegate(event.id, event.offsetMs)
                        is LiveEvent.Usage -> mutableState.update { it.copy(elapsedSeconds = event.seconds) }
                        is LiveEvent.Error -> {
                            if (event.fatal) {
                                fail(session, event.message)
                            } else {
                                mutableState.update { it.copy(error = redact(session, event.message)) }
                            }
                        }
                        is LiveEvent.Closed -> {
                            mutableState.update { it.copy(closeReason = event.reason) }
                            if (event.reason !in setOf("close_requested", "remote_hangup")) {
                                session.failure =
                                    "GPT Live ended the session (${event.reason}). Start a new session to continue."
                            }
                            beginClosing(session)
                        }
                        is LiveEvent.Started -> Unit
                    }
                }
            }
            client.connect(
                LiveSessionConfig(
                    apiKey = config.apiKey,
                    endpoint = config.endpoint,
                    model = config.model,
                    voice = config.voice,
                    instructions = config.instructions.ifBlank { GptLiveSettings().instructions },
                    history = conversation.currentMessages.takeLast(32).mapNotNull { message ->
                        val role = when (message.role) {
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "assistant"
                            else -> return@mapNotNull null
                        }
                        message.toText().takeIf { it.isNotBlank() }?.let { LiveHistoryMessage(role, it) }
                    },
                )
            )
            audio.start()
            mutableState.update { it.copy(phase = LiveVoicePhase.Active) }
            val startedAt = TimeSource.Monotonic.markNow()
            launchSessionTask(session) {
                while (isActive) {
                    delay(1_000)
                    mutableState.update {
                        it.copy(
                            elapsedSeconds = startedAt.elapsedNow().inWholeMilliseconds / 1000.0
                        )
                    }
                }
            }
            launchSessionTask(session) {
                var informedApproval = false
                chatService.getConversationFlow(session.conversationId).collect { current ->
                    if (chatService.getConversationIncarnation(session.conversationId) != incarnation ||
                        current.assistantId != conversation.assistantId
                    ) {
                        fail(
                            session,
                            "The conversation was deleted, restored or moved to another assistant. Start a new voice session."
                        )
                    }
                    val awaitingApproval = current.isAwaitingToolApproval
                    mutableState.update { it.copy(awaitingApproval = awaitingApproval) }
                    if (awaitingApproval && !informedApproval) {
                        client.appendCommentary(
                            null,
                            "The assistant needs approval in the chat before using a tool. Ask the user to review it there."
                        )
                    }
                    informedApproval = awaitingApproval
                }
            }
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.failure = redact(session, e.message ?: "Could not start GPT Live.")
        } finally {
            session.stopping = true
            if (active === session) mutableState.update { it.copy(phase = LiveVoicePhase.Closing, playing = false) }
            session.coordinator?.close()
            withContext(NonCancellable) {
                // Request close immediately to stop the voice session while local resources and tasks wind down.
                val closing =
                    async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.client?.close() }.getOrNull() }
                runCatching { withTimeout(5_000) { session.audio?.release() } }
                    .onFailure { session.failure = session.failure ?: "Audio cleanup did not complete normally." }
                runCatching {
                    withTimeout(
                        10_000
                    ) { chatService.cancelLiveMessages(session.conversationId, session.token) }
                }
                    .onFailure {
                        session.failure =
                            session.failure ?: "A backend task is still stopping. Check the chat."
                    }
                closeResult = closing.await()
                session.client?.dispose()
                GptLiveForegroundService.release(context, session.token)
                if (acquiredConversation) chatService.removeConversationReference(session.conversationId)
            }
            session.apiKey = ""
            if (active === session) {
                active = null
                mutableState.update {
                    it.copy(
                        phase = if (session.failure == null) LiveVoicePhase.Off else LiveVoicePhase.Error,
                        pendingDelegations = 0,
                        awaitingApproval = false,
                        playing = false,
                        microphoneChanging = false,
                        elapsedSeconds = closeResult?.seconds ?: it.elapsedSeconds,
                        closeConfirmed = closeResult?.confirmed == true,
                        usageConfirmed = closeResult?.confirmed == true && closeResult?.seconds != null,
                        closeReason = closeResult?.reason ?: it.closeReason,
                        error = session.failure,
                    )
                }
            }
        }
    }

    private fun CoroutineScope.launchSessionTask(
        session: Session,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = launch(start = start) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Record the cause before cancelling the parent. An uncaught child exception would
            // otherwise reach runSession as cancellation and briefly report a normal disconnect.
            fail(session, e.message ?: "The Live session stopped unexpectedly.")
        }
    }

    private fun matching(token: String?): Session? = active?.takeIf { token == null || it.token == token }

    private fun beginClosing(session: Session) {
        if (active !== session || session.stopping) return
        session.stopping = true
        mutableState.update { it.copy(phase = LiveVoicePhase.Closing) }
        session.job.cancel()
    }

    private fun fail(session: Session, message: String) {
        if (active !== session || session.stopping) return
        session.failure = redact(session, message)
        beginClosing(session)
    }

    private fun redact(session: Session, message: String): String =
        if (session.apiKey.isBlank()) message else message.replace(session.apiKey, "<redacted>")
}
