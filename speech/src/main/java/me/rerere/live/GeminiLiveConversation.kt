package me.rerere.live

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A Google Cloud Gemini Live audio session. Google performs VAD and model turn-taking. */
class GeminiLiveConversation internal constructor(
    private val webSocketFactory: WebSocket.Factory,
    private val config: LiveSessionConfig,
    private val audioFactory: ((String) -> Unit) -> LiveAudioIO,
    private val setupTimeoutMs: Long = 20_000,
    private val shutdownTimeoutMs: Long = 1_500,
) : LiveConversation {
    constructor(context: Context, httpClient: OkHttpClient, config: LiveSessionConfig) : this(
        webSocketFactory = httpClient,
        config = config,
        audioFactory = { interrupted ->
            LiveAudioDevice(
                context = context.applicationContext,
                inputSampleRate = GeminiLiveProtocol.INPUT_SAMPLE_RATE,
                outputSampleRate = GeminiLiveProtocol.OUTPUT_SAMPLE_RATE,
                onInterrupted = interrupted,
            )
        },
    )

    private val used = AtomicBoolean(false)

    override suspend fun run(onEvent: (LiveEvent) -> Unit): Unit = withContext(Dispatchers.IO) {
        check(used.compareAndSet(false, true)) { "Create a new Gemini Live conversation for each call" }
        require(config.url.startsWith("wss://")) { "Gemini Live requires a secure WebSocket URL" }
        require(config.model.isNotBlank()) { "A Google Cloud Gemini Live model is required" }
        require(config.apiKey.isNotBlank() || config.headers.any { (name, value) ->
            value.isNotBlank() && (name.equals("Authorization", true) || name.equals("x-goog-api-key", true))
        }) { "Google Cloud credentials are required" }

        coroutineScope {
            val ready = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val transportClosed = CompletableDeferred<Unit>()
            val closing = AtomicBoolean(false)
            val acceptingEvents = AtomicBoolean(true)
            val goAwayReceived = AtomicBoolean(false)
            val sendLock = Any()
            val eventLock = Any()
            val output = GeminiLivePlaybackQueue()
            val audio = audioFactory { message -> finished.completeExceptionally(IOException(message)) }
            val startedAt = System.nanoTime()
            var recorder: Job? = null
            var player: Job? = null
            var speakingMonitor: Job? = null
            var goAwayTimer: Job? = null

            fun fail(error: IOException) {
                finished.completeExceptionally(error)
            }

            fun send(socket: WebSocket, message: String) {
                synchronized(sendLock) {
                    if (closing.get()) return
                    if (socket.queueSize() > MAX_OUTBOUND_BYTES) {
                        throw IOException("The network is too slow for Gemini Live")
                    }
                    if (!socket.send(message)) throw IOException("The Gemini Live connection is no longer open")
                }
            }

            fun transcript(speaker: LiveSpeaker, value: GeminiTranscription?) {
                if (value == null) return
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                val start = value.startMs ?: elapsed
                val end = maxOf(start, value.endMs ?: elapsed)
                onEvent(LiveEvent.Transcript(LiveTranscript(speaker, value.text, start, end)))
            }

            val request = try {
                Request.Builder().url(config.url).apply {
                    config.headers.forEach { (name, value) -> header(name, value) }
                    if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
                }.build()
            } catch (_: IllegalArgumentException) {
                throw IOException("The Google Cloud Live connection configuration is invalid")
            }

            val socket = webSocketFactory.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closing.get()) {
                        webSocket.cancel()
                        return
                    }
                    try {
                        send(webSocket, GeminiLiveProtocol.setup(config))
                    } catch (_: Exception) {
                        fail(IOException("Unable to send the Gemini Live session configuration"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(eventLock) {
                        if (!acceptingEvents.get()) return
                        try {
                            GeminiLiveProtocol.parse(text).forEach { event ->
                                when (event) {
                                    GeminiServerEvent.Ready -> ready.complete(Unit)
                                    is GeminiServerEvent.Error -> fail(IOException(
                                        LiveHandshakeDiagnostics.sanitize(config, event.message)
                                    ))
                                    is GeminiServerEvent.GoAway -> {
                                        if (!closing.get() && goAwayReceived.compareAndSet(false, true)) {
                                            // Do not silently resume from an older handle and lose unacknowledged
                                            // speech. Keep delivering this connection's final output until expiry.
                                            goAwayTimer = launch {
                                                delay(event.timeLeftMs.coerceIn(0, 120_000))
                                                fail(IOException(GO_AWAY_MESSAGE))
                                            }
                                        }
                                    }
                                    is GeminiServerEvent.Content -> {
                                        check(ready.isCompleted) { "Content arrived before setupComplete" }
                                        if (event.interrupted && !closing.get()) {
                                            audio.interruptPlayback()
                                            output.clear()
                                            onEvent(LiveEvent.Speaking(false))
                                        }
                                        transcript(LiveSpeaker.User, event.input)
                                        transcript(LiveSpeaker.Assistant, event.output)
                                        // Content accompanying interruption belongs to the interrupted generation.
                                        if (!event.interrupted && !closing.get()) {
                                            event.audio.filter { it.isNotEmpty() }.forEach { pcm ->
                                                if (!output.offer(pcm, audio.playbackGeneration)) {
                                                    throw IOException("Gemini Live audio playback could not keep up")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Serialization exceptions can include the complete JSON/audio payload. Never expose it.
                            fail(IOException("Unable to process the Gemini Live server message or audio"))
                        }
                    }
                }

                private fun closedError(code: Int, reason: String): IOException {
                    if (goAwayReceived.get()) return IOException(GO_AWAY_MESSAGE)
                    val safeReason = LiveHandshakeDiagnostics.sanitize(config, reason, 500)
                    return IOException("Google Cloud Live connection closed (code $code): $safeReason")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, "Live session closed")
                    if (!closing.get()) fail(closedError(code, reason))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    transportClosed.complete(Unit)
                    if (!closing.get()) fail(closedError(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    transportClosed.complete(Unit)
                    if (closing.get()) return
                    fail(LiveHandshakeDiagnostics.failure(
                        config = config,
                        status = response?.code,
                        host = response?.request?.url?.host ?: request.url.host,
                        responseHeaders = response?.header("x-request-id")?.let { mapOf("x-request-id" to it) }
                            ?: emptyMap(),
                        failureMessage = t.message,
                        readBody = response?.let { failedResponse -> { failedResponse.peekGeminiHandshakeError() } },
                    ))
                }
            })

            try {
                try {
                    withTimeout(setupTimeoutMs) {
                        select<Unit> {
                            ready.onAwait { }
                            finished.onAwait { }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    throw IOException("Timed out waiting for Google Cloud Gemini Live to start")
                }
                currentCoroutineContext().ensureActive()
                if (finished.isCompleted) finished.await()
                GeminiLiveProtocol.context(config.context)?.let { send(socket, it) }
                audio.start()
                onEvent(LiveEvent.Ready)
                recorder = launch { audio.capture { frame -> send(socket, GeminiLiveProtocol.audio(frame)) } }
                player = launch {
                    while (true) {
                        val frame = output.receive()
                        audio.play(frame.pcm, frame.generation)
                    }
                }
                speakingMonitor = launch { audio.monitorSpeaking { onEvent(LiveEvent.Speaking(it)) } }
                finished.await()
            } finally {
                synchronized(sendLock) { closing.set(true) }
                audio.stop()
                withContext(NonCancellable) {
                    recorder?.cancelAndJoin()
                    player?.cancelAndJoin()
                    speakingMonitor?.cancelAndJoin()
                    goAwayTimer?.cancelAndJoin()
                    audio.release()
                    output.close()
                    runCatching { onEvent(LiveEvent.Speaking(false)) }
                    try {
                        if (ready.isCompleted && !transportClosed.isCompleted) {
                            socket.send(GeminiLiveProtocol.audioStreamEnd())
                            socket.close(1000, "Live session closed")
                            withTimeoutOrNull(shutdownTimeoutMs) { transportClosed.await() }
                        }
                    } finally {
                        synchronized(eventLock) { acceptingEvents.set(false) }
                        socket.cancel()
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_OUTBOUND_BYTES = 96_000L
        const val GO_AWAY_MESSAGE = "Google Cloud 实时连接已到期，请重新开始语音对话。"
    }
}

internal data class GeminiPlaybackFrame(val pcm: ByteArray, val generation: Long)

/** Byte and frame limits bound queued speech; an interruption also drops queued old generations. */
internal class GeminiLivePlaybackQueue(private val maxBytes: Int = 240_000) {
    private val frames = Channel<GeminiPlaybackFrame>(128)
    private val bytes = AtomicInteger(0)

    fun offer(pcm: ByteArray, generation: Long): Boolean {
        if (bytes.addAndGet(pcm.size) > maxBytes) {
            bytes.addAndGet(-pcm.size)
            return false
        }
        if (frames.trySend(GeminiPlaybackFrame(pcm, generation)).isFailure) {
            bytes.addAndGet(-pcm.size)
            return false
        }
        return true
    }

    suspend fun receive(): GeminiPlaybackFrame = frames.receive().also { bytes.addAndGet(-it.pcm.size) }

    fun clear() {
        while (true) {
            val frame = frames.tryReceive().getOrNull() ?: return
            bytes.addAndGet(-frame.pcm.size)
        }
    }

    fun close() = frames.cancel()
}

private fun Response.peekGeminiHandshakeError(): String {
    val timeout = body.source().timeout()
    val previousTimeout = timeout.timeoutNanos()
    val previousDeadline = if (timeout.hasDeadline()) timeout.deadlineNanoTime() else null
    val budget = TimeUnit.SECONDS.toNanos(1)
    val deadline = System.nanoTime() + budget
    timeout.timeout(if (previousTimeout == 0L) budget else minOf(previousTimeout, budget), TimeUnit.NANOSECONDS)
    timeout.deadlineNanoTime(previousDeadline?.let { minOf(it, deadline) } ?: deadline)
    return try {
        peekBody(LiveHandshakeDiagnostics.MAX_BODY_BYTES).use { it.string() }
    } finally {
        timeout.timeout(previousTimeout, TimeUnit.NANOSECONDS)
        if (previousDeadline == null) timeout.clearDeadline() else timeout.deadlineNanoTime(previousDeadline)
    }
}
